# settlement-reconciliation  (backend)

**Scope:** Net/gross settlement, ZP file recon, invoicing

**Owned WBS work-packages:** 7.1, 7.4, 7.6, 9.8  ·  **Tickets:** 119  ·  **Est:** 85.7h

> Self-contained backlog for this service. Build in its own module against `shared-libs` contracts. Each ticket has a deliverable + acceptance checks.


## WBS 7.1 — Settlement model: net vs gross
### 7.1-T01 — Add settlement_type column to transaction table (migration)  _(30 min)_
**Context:** The settlement model for ZeroPay differs by partner type: LOCAL partners (e.g. GME Remit) use NET settlement (GME retains its share, remits target_payout to ZeroPay); OVERSEAS partners (e.g. SendMN, T-Bank) use GROSS settlement (GME remits full target_payout to ZeroPay, invoices merchant monthly). The transaction table needs a settlement_type field to drive downstream batch logic. Schema: settlement_type CHAR(1) NOT NULL DEFAULT 'N' CHECK (settlement_type IN ('N','G')) -- N=Net domestic, G=Gross international. Also add settlement_batch_id BIGINT FK -> settlement_batch (nullable, set when transaction is included in a batch).
**Steps:** Create Flyway migration V7_1_001__add_settlement_type_to_transaction.sql; Add column: ALTER TABLE transaction ADD COLUMN settlement_type CHAR(1) NOT NULL DEFAULT 'N' CHECK (settlement_type IN ('N', 'G')); Add column: ALTER TABLE transaction ADD COLUMN settlement_batch_id BIGINT REFERENCES settlement_batch(id); Add index: CREATE INDEX idx_transaction_settlement_batch ON transaction(settlement_batch_id); Add index: CREATE INDEX idx_transaction_settlement_type_status ON transaction(settlement_type, status, committed_at) for batch query performance
**Deliverable:** Flyway migration file V7_1_001__add_settlement_type_to_transaction.sql
**Acceptance / logic checks:**
- Migration applies cleanly on an empty schema and on a schema with existing transaction rows (existing rows get settlement_type='N' by default)
- settlement_type accepts only 'N' or 'G'; INSERT with value 'X' raises a constraint violation
- settlement_batch_id can be NULL (unbatched) and FK constraint rejects a non-existent settlement_batch id
- Index idx_transaction_settlement_type_status exists after migration
- Rollback script (if required) drops the two columns without error

### 7.1-T02 — Add net_settlement_amount and merchant_fee_total columns to settlement_batch  _(25 min)_
**Context:** The settlement_batch table (columns: id, scheme_id, file_type, direction, settlement_date, window, status, transaction_count, total_amount, total_amount_ccy, file_checksum, transmitted_at, received_at, reconciled_at, error_detail) needs two additional financial columns to support the net vs gross distinction in ZP0061/ZP0063 file generation. For NET batches: net_settlement_amount = sum(target_payout) - sum(merchant_fee_total) per merchant; for GROSS batches: net_settlement_amount = sum(target_payout) (no fee deduction). merchant_fee_total is the total fee deducted (domestic only; zero for gross). Both are in total_amount_ccy (KRW for ZeroPay).
**Steps:** Create Flyway migration V7_1_002__add_settlement_financial_cols.sql; Add: ALTER TABLE settlement_batch ADD COLUMN net_settlement_amount DECIMAL(20,4); Add: ALTER TABLE settlement_batch ADD COLUMN merchant_fee_total DECIMAL(20,4) NOT NULL DEFAULT 0; Add: ALTER TABLE settlement_batch ADD COLUMN settlement_type CHAR(1) CHECK (settlement_type IN ('N','G')); Apply and verify migration on dev DB
**Deliverable:** Flyway migration file V7_1_002__add_settlement_financial_cols.sql
**Acceptance / logic checks:**
- Migration applies cleanly; existing settlement_batch rows have merchant_fee_total=0 and settlement_type=NULL by default
- net_settlement_amount can be NULL (set when batch is generated) and accepts DECIMAL(20,4)
- merchant_fee_total is NOT NULL and defaults to 0
- settlement_type CHECK constraint rejects values other than 'N', 'G', or NULL
- Column comments or migration notes clarify that merchant_fee_total is 0 for gross batches
**Depends on:** 7.1-T01

### 7.1-T03 — Define SettlementType enum and SettlementClassifier interface  _(30 min)_
**Context:** The Settlement Engine must determine whether each transaction uses NET or GROSS settlement before batch file generation. Rule: if partner.type = 'LOCAL' (e.g. GME Remit, KRW settlement, no prefunding), use NET ('N'); if partner.type = 'OVERSEAS' (e.g. SendMN, T-Bank, USD prefunding), use GROSS ('G'). This must be config-driven (no hardcoded partner names). The partner table has a type column (VARCHAR(10), values LOCAL or OVERSEAS). Create a SettlementType enum (NET, GROSS) and a SettlementClassifier interface with one method: SettlementType classify(Partner partner).
**Steps:** Create enum SettlementType { NET, GROSS } in package com.gmepayplus.settlement.model; Create interface SettlementClassifier with method SettlementType classify(Partner partner); Create class PartnerTypeSettlementClassifier implementing SettlementClassifier: returns NET if partner.getType() == PartnerType.LOCAL, GROSS if OVERSEAS, throws IllegalArgumentException otherwise; Register PartnerTypeSettlementClassifier as a Spring bean; Write a unit test PartnerTypeSettlementClassifierTest covering LOCAL -> NET, OVERSEAS -> GROSS, null partner type -> exception
**Deliverable:** SettlementType.java, SettlementClassifier.java, PartnerTypeSettlementClassifier.java, PartnerTypeSettlementClassifierTest.java
**Acceptance / logic checks:**
- PartnerTypeSettlementClassifier.classify(partnerWithType(LOCAL)) returns SettlementType.NET
- PartnerTypeSettlementClassifier.classify(partnerWithType(OVERSEAS)) returns SettlementType.GROSS
- PartnerTypeSettlementClassifier.classify(partner with null type) throws IllegalArgumentException with a descriptive message
- No partner names (GME Remit, SendMN, T-Bank) are hardcoded in the classifier
- Spring context loads with PartnerTypeSettlementClassifier as the SettlementClassifier bean
**Depends on:** 7.1-T01, 7.1-T02

### 7.1-T04 — Set settlement_type on transaction at commit time  _(45 min)_
**Context:** When the Transaction Orchestrator commits a transaction (state -> APPROVED, rate-lock step), it must record the settlement_type on the transaction row. The classifier rule (7.1-T03): LOCAL partner -> 'N', OVERSEAS partner -> 'G'. The transaction table column settlement_type is CHAR(1) (migration 7.1-T01). This must happen inside the same DB transaction as the rate-lock step to ensure atomicity. The TransactionOrchestrator service has a commitTransaction(txnRef) method that currently copies rate_quote fields to the transaction row.
**Steps:** Inject SettlementClassifier into TransactionOrchestrator; In commitTransaction(), after loading the partner, call settlementClassifier.classify(partner) to obtain SettlementType; Set transaction.setSettlementType(settlementType.getCode()) where getCode() returns 'N' or 'G'; Ensure the settlement_type update is part of the existing JPA save/update call inside the commit DB transaction; Add integration test: commit a LOCAL-partner transaction, assert settlement_type='N'; commit an OVERSEAS-partner transaction, assert settlement_type='G'
**Deliverable:** Updated TransactionOrchestrator.commitTransaction() with settlement_type assignment; integration test TransactionCommitSettlementTypeIT.java
**Acceptance / logic checks:**
- After committing a LOCAL-partner (GME Remit type) transaction, SELECT settlement_type FROM transaction WHERE txn_ref=X returns 'N'
- After committing an OVERSEAS-partner (SendMN type) transaction, SELECT settlement_type FROM transaction WHERE txn_ref=X returns 'G'
- settlement_type is set atomically with the rate-lock: if the commit DB transaction rolls back, settlement_type is not persisted either
- A transaction with settlement_type already set is not overwritten on a duplicate idempotent commit request
- Existing commitTransaction() tests still pass without modification
**Depends on:** 7.1-T03

### 7.1-T05 — Implement NetSettlementAmountCalculator for domestic net settlement  _(45 min)_
**Context:** For NET settlement (domestic, LOCAL partners, settlement_type='N'), the Settlement Engine must compute per-merchant batch totals: gross_txn_amount = SUM(target_payout KRW) for approved transactions in the settlement window; merchant_fee_total = SUM(merchant_fee_krw) for those transactions where merchant_fee is applicable (fee rate from scheme's merchant_fee_table, by merchant_type: GENERAL 0.80%-2.00%, FRANCHISE varies); net_settlement_amount = gross_txn_amount - merchant_fee_total. The net_settlement_amount is what GME remits to ZeroPay so ZeroPay can credit the merchant. For domestic transactions, GME retains the service_charge (KRW 500 per transaction, held separately in service_charge field on the transaction row) and the 70% fee share is a monthly aggregate from ZeroPay -- it does NOT affect the per-transaction net amount.
**Steps:** Create class NetSettlementAmountCalculator in package com.gmepayplus.settlement.calculator; Implement method NetSettlementSummary calculate(List<Transaction> transactions, MerchantFeeTable feeTable); For each transaction: look up merchant fee rate by merchant.merchant_type from feeTable; compute merchant_fee_krw = ROUND(target_payout * fee_rate, 0) (KRW has 0 decimal places); accumulate gross_txn_amount, merchant_fee_total, net_settlement_amount; Return NetSettlementSummary {merchantId, settlementDate, grossTxnCount, grossTxnAmount, merchantFeeTotal, netSettlementAmount, settlementType='N'}; Validate that all transactions have settlement_type='N' and status='APPROVED'; throw if any are GROSS or non-APPROVED
**Deliverable:** NetSettlementAmountCalculator.java, NetSettlementSummary.java, NetSettlementAmountCalculatorTest.java
**Acceptance / logic checks:**
- Given 2 transactions for merchant M1 with target_payout 15000 KRW and 20000 KRW, fee_rate=0.008 (0.8%): gross_txn_amount=35000, merchant_fee_total=ROUND(35000*0.008,0)=280, net_settlement_amount=34720
- Given 0 transactions for a merchant, calculate() returns grossTxnCount=0, grossTxnAmount=0, net_settlement_amount=0 without error
- Given a transaction with settlement_type='G' in the input list, calculate() throws IllegalArgumentException
- KRW amounts are rounded to 0 decimal places using HALF_UP rounding
- merchant_fee_total for a FRANCHISE merchant uses the franchise fee rate, not the GENERAL rate
**Depends on:** 7.1-T03

### 7.1-T06 — Implement GrossSettlementAmountCalculator for international gross settlement  _(35 min)_
**Context:** For GROSS settlement (international, OVERSEAS partners, settlement_type='G'): GME remits the FULL target_payout KRW amount to ZeroPay. There is no merchant fee deduction from the settlement amount (GME invoices the merchant separately monthly). ZP0061 field mapping for gross: gross_txn_amount = SUM(target_payout KRW), merchant_fee_total = 0, net_settlement_amount = gross_txn_amount, settlement_type = 'G'. The 0.21% ZeroPay share of the international merchant fee is settled monthly via the tax_invoice cycle, not per transaction. GME retains collection_margin_usd + payout_margin_usd (FX margin) and service_charge from the USD prefunding deduction, which is already done at payment time -- these do not affect the gross settlement amount sent to ZeroPay.
**Steps:** Create class GrossSettlementAmountCalculator in package com.gmepayplus.settlement.calculator; Implement method GrossSettlementSummary calculate(List<Transaction> transactions); For each transaction: accumulate gross_txn_count and gross_txn_amount (= SUM of target_payout KRW); Set merchant_fee_total=0 and net_settlement_amount=gross_txn_amount for every merchant group; Return GrossSettlementSummary {merchantId, settlementDate, grossTxnCount, grossTxnAmount, merchantFeeTotal=0, netSettlementAmount=grossTxnAmount, settlementType='G'}; Validate all transactions have settlement_type='G' and status='APPROVED'
**Deliverable:** GrossSettlementAmountCalculator.java, GrossSettlementSummary.java, GrossSettlementAmountCalculatorTest.java
**Acceptance / logic checks:**
- Given 3 OVERSEAS transactions for merchant M2 with target_payout 50000, 30000, 20000 KRW: gross_txn_amount=100000, merchant_fee_total=0, net_settlement_amount=100000
- service_charge and FX margin fields (collection_margin_usd, payout_margin_usd) from the transaction row are NOT included in or subtracted from gross_txn_amount
- Given a transaction with settlement_type='N' in the input list, calculate() throws IllegalArgumentException
- Given 0 transactions, calculate() returns grossTxnCount=0, all amounts 0
- net_settlement_amount == gross_txn_amount for every output record (no fee deduction)
**Depends on:** 7.1-T03

### 7.1-T07 — Implement SettlementWindowQueryService to select transactions for a batch window  _(50 min)_
**Context:** The Settlement Engine must query the correct set of APPROVED transactions for each ZP0061 (morning, deadline ~05:00 KST) and ZP0063 (afternoon, deadline ~14:00 KST) batch. Morning window scope: all transactions with status=APPROVED AND settlement_batch_id IS NULL AND completed_at < cutoff_morning (configurable, default 04:30 KST on settlement_date). Afternoon window scope: same filter but completed_at < cutoff_afternoon (default 13:30 KST) AND NOT already in morning batch. The transaction table has: status, settlement_batch_id (null = unbatched), committed_at, completed_at, settlement_type. Query must lock selected rows (SELECT FOR UPDATE SKIP LOCKED) to prevent concurrent batch runs from double-selecting. Batch jobs are idempotent: if settlement_batch_id is already set for a transaction, it is excluded.
**Steps:** Create SettlementWindowQueryService in package com.gmepayplus.settlement.service; Implement List<Transaction> queryForWindow(LocalDate settlementDate, String window, OffsetDateTime cutoff) using JPA/JPQL with FOR UPDATE SKIP LOCKED; Filter: status = 'APPROVED', settlement_batch_id IS NULL, completed_at < cutoff, scheme_id matches batch scheme; Add method selectAndLockForBatch(Long batchId, List<Long> txnIds) that sets settlement_batch_id = batchId atomically; Add configurable cutoff times: settlement.morning-cutoff (default 04:30 KST), settlement.afternoon-cutoff (default 13:30 KST) in application.yml; Write unit test with mock transactions covering: empty result, mixed status exclusion, already-batched exclusion
**Deliverable:** SettlementWindowQueryService.java with query and lock methods; application.yml cutoff config; SettlementWindowQueryServiceTest.java
**Acceptance / logic checks:**
- A transaction with status=APPROVED, settlement_batch_id=NULL, completed_at=03:00 KST is returned for the morning window with cutoff 04:30 KST
- A transaction with status=APPROVED, settlement_batch_id=NULL, completed_at=05:00 KST is NOT returned for the morning window (after cutoff)
- A transaction with settlement_batch_id already set is excluded regardless of status
- A transaction with status=UNCERTAIN is not returned
- selectAndLockForBatch() sets settlement_batch_id on all supplied txn IDs in a single UPDATE statement
**Depends on:** 7.1-T01, 7.1-T02

### 7.1-T08 — Implement SettlementBatchFactory to create settlement_batch records  _(40 min)_
**Context:** Before generating any ZP006x file, the Settlement Engine must create a settlement_batch row in the DB to track the batch lifecycle. settlement_batch columns: id (PK), scheme_id, file_type (e.g. 'ZP0061'), direction ('GME_TO_ZP'), settlement_date (DATE, KST), window ('MORNING' or 'AFTERNOON' or 'DETAIL'), status (PENDING -> GENERATED -> TRANSMITTED -> RECONCILED | ERROR), transaction_count, total_amount, total_amount_ccy ('KRW'), net_settlement_amount, merchant_fee_total, settlement_type ('N' or 'G'), file_checksum (SHA-256, set after file generation), transmitted_at, received_at, reconciled_at, error_detail. The factory must enforce idempotency: if a batch for (scheme_id, file_type, settlement_date, window) already exists with status != ERROR, return the existing record rather than creating a duplicate.
**Steps:** Create SettlementBatchFactory in package com.gmepayplus.settlement.service; Implement SettlementBatch createOrGet(Long schemeId, String fileType, LocalDate settlementDate, String window, SettlementType settlementType); Inside createOrGet: query for existing batch with matching (scheme_id, file_type, settlement_date, window) and status NOT IN ('ERROR'); if found, return it; else create new with status=PENDING; Set total_amount_ccy='KRW' for ZeroPay batches; Throw DuplicateBatchException if batch with same key exists in GENERATED or later status (do not overwrite); Write unit test covering idempotent create, retrieval of existing, and rejection of duplicate in non-ERROR state
**Deliverable:** SettlementBatchFactory.java, DuplicateBatchException.java, SettlementBatchFactoryTest.java
**Acceptance / logic checks:**
- Calling createOrGet twice with same parameters and first batch in PENDING state returns the same batch id both times
- Calling createOrGet when a batch with same key is in GENERATED status returns that existing batch without creating a duplicate row
- Calling createOrGet when a batch with same key is in ERROR status creates a new batch row (retry allowed)
- Newly created batch has status=PENDING, transaction_count=0, total_amount=0, settlement_type set correctly
- settlement_date is always interpreted as KST (Asia/Seoul timezone)
**Depends on:** 7.1-T02, 7.1-T03

### 7.1-T09 — Implement ZP0061MorningSettlementRequestBuilder (net settlement)  _(55 min)_
**Context:** ZP0061 is the morning settlement request file (GME -> ZeroPay, deadline ~05:00 KST). For NET domestic transactions (settlement_type='N'), the per-merchant record layout is: merchant_id CHAR(10), settlement_date DATE(8) YYYYMMDD, gross_txn_count NUM(6), gross_txn_amount NUM(14) KRW, refund_count NUM(6), refund_amount NUM(14) KRW, merchant_fee_total NUM(12) KRW (fee deducted from merchant), net_settlement_amount NUM(14) KRW (= gross_txn_amount - merchant_fee_total + refunds), settlement_type CHAR(1) = 'N'. net_settlement_amount is what ZeroPay credits to the merchant's KRW bank account. File has a header row (batch date, GME sender ID, record count) and trailer (total net settlement KRW). Use the NetSettlementAmountCalculator (7.1-T05) output to populate these fields. KRW values must be integers (0 decimal places, HALF_UP rounding).
**Steps:** Create ZP0061RecordBuilder in package com.gmepayplus.settlement.file; Implement byte[] build(SettlementBatch batch, List<NetSettlementSummary> summaries, List<RefundSummary> refundSummaries) method; Group summaries by merchant_id; merge payment and refund data per merchant; Format each field to the exact column width (fixed-width or delimiter per ZeroPay spec); use YYYYMMDD for dates; Compute file header with settlement_date, GME sender ID (from scheme config), merchant record count; Compute trailer with SUM(net_settlement_amount) across all merchant records; Compute SHA-256 of the byte array and return both (or update the batch record's file_checksum)
**Deliverable:** ZP0061RecordBuilder.java, ZP0061RecordBuilderTest.java
**Acceptance / logic checks:**
- Given NetSettlementSummary for merchant M1: gross_txn_amount=35000, merchant_fee_total=280, net_settlement_amount=34720; output record has net_settlement_amount=34720 and settlement_type='N'
- Trailer total_net_settlement = SUM of all per-merchant net_settlement_amount values
- A merchant with 0 transactions is not included in the file
- All KRW amounts are integer strings (no decimal point) padded to column width
- File header record count matches the number of merchant data records
**Depends on:** 7.1-T05, 7.1-T08

### 7.1-T10 — Implement ZP0061MorningSettlementRequestBuilder (gross settlement)  _(40 min)_
**Context:** ZP0061 also covers GROSS international transactions (settlement_type='G'). Per-merchant record layout is identical to the net case but: merchant_fee_total = 0, net_settlement_amount = gross_txn_amount (GME pays full amount), settlement_type = 'G'. ZeroPay credits merchant KRW accounts upon receiving this file. GME has no further merchant crediting responsibility. Use GrossSettlementAmountCalculator (7.1-T06) for computation. The same ZP0061RecordBuilder must support both 'N' and 'G' records in a single batch run if a mixed-type batch is needed, but in practice each batch is partitioned by settlement_type (domestic batch and international batch are separate settlement_batch rows with distinct windows).
**Steps:** Extend ZP0061RecordBuilder (7.1-T09) to accept GrossSettlementSummary as well as NetSettlementSummary via a common SettlementSummary interface; Implement the settlement_type='G' record path: merchant_fee_total=0, net_settlement_amount=gross_txn_amount; Add a parameter to ZP0061RecordBuilder.build() specifying the overall batch settlement_type to set the per-file settlement_type marker; Write unit test ZP0061GrossBuilderTest covering gross merchant records; Test that a batch with only GROSS transactions produces a file with merchant_fee_total=0 for every record
**Deliverable:** Updated ZP0061RecordBuilder.java with gross path support; ZP0061GrossBuilderTest.java
**Acceptance / logic checks:**
- Given GrossSettlementSummary for merchant M2: gross_txn_amount=100000; output record has merchant_fee_total=0, net_settlement_amount=100000, settlement_type='G'
- Trailer for a gross-only batch equals SUM(gross_txn_amount) for all merchants
- Mixed-type batch (N and G records in same file) produces distinct settlement_type values per record
- service_charge values from individual transactions do not appear in any ZP0061 field
- A GROSS transaction with FX margin fields (collection_margin_usd non-zero) still produces net_settlement_amount = target_payout (FX margin not subtracted)
**Depends on:** 7.1-T06, 7.1-T09

### 7.1-T11 — Implement ZP0063AfternoonSettlementRequestBuilder  _(40 min)_
**Context:** ZP0063 is the supplementary afternoon settlement request (GME -> ZeroPay, deadline ~14:00 KST). It covers transactions approved after the ZP0061 cutoff (default 04:30 KST) up to the afternoon cutoff (default 13:30 KST) for the same settlement_date. File format is identical to ZP0061 (same record layout, settlement_type 'N' or 'G' per record). The batch window is 'AFTERNOON'. A transaction already in a ZP0061 batch (settlement_batch_id set to morning batch) is excluded from ZP0063. The same NetSettlementAmountCalculator / GrossSettlementAmountCalculator logic applies. Batch must be idempotent (7.1-T08).
**Steps:** Create ZP0063RecordBuilder in package com.gmepayplus.settlement.file, delegating to the shared ZP0061RecordBuilder logic (extract common builder or reuse directly); Implement build(SettlementBatch batch, List<SettlementSummary> summaries) using the same record layout; Set file_type='ZP0063' and window='AFTERNOON' in the settlement_batch row; Use SettlementWindowQueryService (7.1-T07) with the afternoon cutoff to select unbatched transactions; Write unit test verifying that transactions already assigned to the morning batch are excluded
**Deliverable:** ZP0063RecordBuilder.java, ZP0063RecordBuilderTest.java
**Acceptance / logic checks:**
- Transactions with settlement_batch_id pointing to the morning ZP0061 batch are absent from the ZP0063 file
- An OVERSEAS transaction approved at 12:00 KST (after 04:30 cutoff) appears in ZP0063 with settlement_type='G'
- An OVERSEAS transaction approved at 03:00 KST (before 04:30 morning cutoff) does NOT appear in ZP0063 if already batched
- Empty afternoon window produces a valid file with header/trailer and 0 merchant records
- file_type in settlement_batch row is 'ZP0063' not 'ZP0061'
**Depends on:** 7.1-T07, 7.1-T09, 7.1-T10

### 7.1-T12 — Implement ZP0065PaymentDetailFileBuilder  _(50 min)_
**Context:** ZP0065 is the payment detail file (GME -> ZeroPay, deadline ~22:00 KST). It provides line-item transaction detail underlying the ZP0061/ZP0063 totals. Per-record layout: merchant_id CHAR(10), zeropay_txn_ref CHAR(20) (transaction.scheme_ref), txn_date DATE(8) YYYYMMDD, txn_time TIME(6) HHMMSS, payout_amount_krw NUM(12) (= target_payout), merchant_fee_amt NUM(12) (fee for domestic; 0 for international), van_fee_amt NUM(10), partner_type CHAR(1) (D=domestic/NET, I=international/GROSS), settlement_batch_ref CHAR(20) (links back to ZP0061 or ZP0063 batch reference). Scope: all transactions assigned to ZP0061 or ZP0063 batches for the settlement_date. KRW = 0 decimal places.
**Steps:** Create ZP0065RecordBuilder in package com.gmepayplus.settlement.file; Implement byte[] build(LocalDate settlementDate, List<Transaction> transactions) grouped by settlement_batch_ref; For each transaction: map settlement_type='N' -> partner_type='D', settlement_type='G' -> partner_type='I'; Compute merchant_fee_amt per transaction (domestic: target_payout * fee_rate rounded to KRW 0dp; gross: 0); Populate settlement_batch_ref from the linked settlement_batch record (batch reference field or batch id as padded string); Write ZP0065RecordBuilderTest with both D and I records
**Deliverable:** ZP0065RecordBuilder.java, ZP0065RecordBuilderTest.java
**Acceptance / logic checks:**
- A domestic transaction (settlement_type='N') produces a record with partner_type='D' and merchant_fee_amt > 0
- An international transaction (settlement_type='G') produces a record with partner_type='I' and merchant_fee_amt=0
- settlement_batch_ref in the ZP0065 record matches the batch reference of the ZP0061 or ZP0063 batch that contains the transaction
- Total payout_amount_krw across all ZP0065 records for a settlement_date equals SUM(target_payout) for the corresponding ZP0061/ZP0063 totals
- payout_amount_krw field is an integer string with no decimal separator
**Depends on:** 7.1-T09, 7.1-T11

### 7.1-T13 — Implement ZP0066RefundDetailFileBuilder  _(45 min)_
**Context:** ZP0066 is the refund detail file (GME -> ZeroPay, deadline ~22:00 KST). Layout mirrors ZP0065 with refund-specific fields: merchant_id CHAR(10), original_zeropay_txn_ref CHAR(20) (the original payment transaction scheme_ref), refund_date DATE(8), refund_amount_krw NUM(12) (negative of original target_payout for full refund, or partial amount), merchant_fee_adj_amt NUM(12) (fee adjustment for domestic refund; 0 for international gross), settlement_batch_ref CHAR(20) (links to ZP0063 or ZP0061 batch containing the refund). Source: refund table rows with status=APPROVED and settlement_date matching the current date. The refund table has columns: id, txn_id (FK -> transaction), refund_amount, refund_ccy, original_zeropay_txn_ref (or derivable from txn.scheme_ref), status, settlement_batch_id.
**Steps:** Create ZP0066RecordBuilder in package com.gmepayplus.settlement.file; Implement byte[] build(LocalDate settlementDate, List<Refund> refunds) joining to the parent transaction for settlement_type; For domestic refunds: compute merchant_fee_adj_amt = ROUND(refund_amount * fee_rate, 0) (negative, as a fee credit); For international refunds: merchant_fee_adj_amt = 0; Populate settlement_batch_ref from the refund's settlement_batch link; Write ZP0066RecordBuilderTest with one domestic refund and one international refund
**Deliverable:** ZP0066RecordBuilder.java, ZP0066RecordBuilderTest.java
**Acceptance / logic checks:**
- A domestic refund of 15000 KRW with fee_rate=0.008 produces merchant_fee_adj_amt=120 (credit back)
- An international refund produces merchant_fee_adj_amt=0
- original_zeropay_txn_ref in ZP0066 matches scheme_ref of the original payment transaction
- A refund with status != APPROVED is excluded from the file
- File trailer contains SUM(refund_amount_krw) across all records
**Depends on:** 7.1-T12

### 7.1-T14 — Implement SettlementBatchJobService: ZP0061 morning batch orchestrator  _(60 min)_
**Context:** The Settlement Engine cron job for ZP0061 runs at ~02:00 KST (after ZP0011/ZP0012 are confirmed). Orchestration steps: (1) Verify ZP0011 was successfully transmitted and ZP0012 received with no unresolved exceptions; (2) Query morning-window transactions via SettlementWindowQueryService; (3) Split by settlement_type (N/G); (4) Compute summaries using NetSettlementAmountCalculator and GrossSettlementAmountCalculator; (5) Build ZP0061 file via ZP0061RecordBuilder; (6) Create/update settlement_batch record via SettlementBatchFactory; (7) Set settlement_batch_id on all selected transactions; (8) Transmit file via SFTP; (9) Update batch status to TRANSMITTED. All steps run inside a DB transaction except SFTP transmission. Idempotency: if batch already in GENERATED or TRANSMITTED state, skip file generation and re-attempt SFTP only.
**Steps:** Create SettlementBatchJobService in package com.gmepayplus.settlement.job; Implement runZP0061Morning(LocalDate settlementDate) method guarded by @DistributedLock (Redis SETNX key = zp0061:{settlementDate}); Add prerequisite check: ZP0011 batch for same date must have status=TRANSMITTED; ZP0012 must have status=RECEIVED with no UNRESOLVED reconciliation_items; if not met, abort with logged BATCH_PREREQ_FAILED; Execute query, calculate, build, update DB, transmit in order; wrap DB steps in @Transactional; On SFTP failure: retry 3 times with 5-minute delay; after 3 failures set batch status=ERROR and publish BATCH_SFTP_FAILED alert; On success: set status=TRANSMITTED, transmitted_at=NOW()
**Deliverable:** SettlementBatchJobService.java (ZP0061 morning method) with distributed lock and prerequisite guard
**Acceptance / logic checks:**
- Running runZP0061Morning() twice concurrently: second invocation obtains the Redis lock and finds batch in GENERATED state, skips file generation, re-attempts SFTP
- If ZP0012 has unresolved reconciliation_items, runZP0061Morning() logs BATCH_PREREQ_FAILED and returns without creating a batch row
- After successful run: settlement_batch.status='TRANSMITTED', all morning-window transactions have settlement_batch_id set, transaction_count equals the number of processed transactions
- SFTP failure on first attempt triggers retry; after 3 failures batch.status='ERROR' and an ops alert is published
- settlement_batch total_amount = SUM(target_payout KRW) of all included transactions
**Depends on:** 7.1-T07, 7.1-T08, 7.1-T09, 7.1-T10

### 7.1-T15 — Implement SettlementBatchJobService: ZP0063 afternoon batch orchestrator  _(50 min)_
**Context:** The ZP0063 afternoon settlement batch runs at ~14:00 KST. Prerequisite: ZP0061 batch for the same settlement_date must be in TRANSMITTED status (morning cycle complete). Logic mirrors ZP0061 but uses the afternoon window (completed_at between morning cutoff and 13:30 KST) and file_type='ZP0063'. If no transactions fall in the afternoon window, a file with zero records is still generated and transmitted (ZeroPay requires it). Distributed lock key = zp0063:{settlementDate}.
**Steps:** Implement runZP0063Afternoon(LocalDate settlementDate) in SettlementBatchJobService; Add prerequisite check: ZP0061 morning batch must have status=TRANSMITTED; Query afternoon-window transactions using SettlementWindowQueryService with afternoon cutoff; Allow zero-transaction case: generate empty ZP0063 file with valid header/trailer; Follow same DB-then-SFTP pattern as ZP0061, with 3-retry SFTP logic; Write integration test covering zero-transaction afternoon case and normal case
**Deliverable:** SettlementBatchJobService.runZP0063Afternoon() method; integration test ZP0063AfternoonJobIT.java
**Acceptance / logic checks:**
- ZP0063 runs only after ZP0061 morning batch is TRANSMITTED; if morning batch status=PENDING the job aborts
- A zero-transaction afternoon produces a valid ZP0063 file (header + trailer, 0 merchant records) and batch status=TRANSMITTED
- An afternoon transaction (completed_at=12:00 KST for settlement_date) appears in ZP0063 but not ZP0061
- After ZP0063 run, all afternoon-window transactions have settlement_batch_id pointing to the ZP0063 batch row
- Distributed lock prevents concurrent ZP0063 runs for the same settlement_date
**Depends on:** 7.1-T11, 7.1-T14

### 7.1-T16 — Implement SettlementBatchJobService: ZP0065/ZP0066 detail batch orchestrator  _(50 min)_
**Context:** ZP0065 (payment detail) and ZP0066 (refund detail) run at ~22:00 KST. They cover all transactions and refunds for the settlement_date that are linked to ZP0061 or ZP0063 batches. Prerequisite: both ZP0061 and ZP0063 must be in TRANSMITTED status for the date. ZP0065 and ZP0066 are generated independently (two separate settlement_batch rows, file_types='ZP0065' and 'ZP0066'). If there are no refunds, ZP0066 is still generated as an empty file.
**Steps:** Implement runZP0065Detail(LocalDate settlementDate) and runZP0066RefundDetail(LocalDate settlementDate) in SettlementBatchJobService; For ZP0065: query all transactions where settlement_batch_id IN (ZP0061 batch id, ZP0063 batch id) for the date; For ZP0066: query refund rows with settlement_date = settlementDate and associated to batched transactions; Build files via ZP0065RecordBuilder and ZP0066RecordBuilder; transmit via SFTP; Distributed lock keys: zp0065:{settlementDate} and zp0066:{settlementDate}; Write unit test verifying that a transaction not linked to any batch is absent from ZP0065
**Deliverable:** SettlementBatchJobService.runZP0065Detail() and runZP0066RefundDetail() methods
**Acceptance / logic checks:**
- ZP0065 run before ZP0061 TRANSMITTED aborts with BATCH_PREREQ_FAILED
- All transactions with settlement_batch_id pointing to the day's ZP0061 or ZP0063 appear in ZP0065
- A transaction not assigned to any batch is absent from ZP0065
- ZP0066 with no refunds produces an empty-body file (header + trailer) with status=TRANSMITTED
- ZP0065 total payout_amount_krw (sum over all records) equals ZP0061 gross_txn_amount + ZP0063 gross_txn_amount for the same date
**Depends on:** 7.1-T12, 7.1-T13, 7.1-T15

### 7.1-T17 — Implement ZP0062 morning settlement result processor  _(55 min)_
**Context:** ZP0062 is the morning settlement result file (ZeroPay -> GME, expected ~10:00 KST). It confirms ZeroPay's processing of ZP0061 with per-merchant settlement status and the KRW amount credited. Processing steps: (1) Parse ZP0062 file from SFTP inbound directory; (2) For each merchant record, look up the corresponding ZP0061 batch line; (3) Compare ZP0062 credited_amount to ZP0061 net_settlement_amount; (4) If match: update merchant's ZP0061 record to RECONCILED; (5) If mismatch or missing: create reconciliation_item with match_status='DISCREPANCY' or 'MISSING_SCHEME'; (6) Update settlement_batch status to RECEIVED (or RECONCILED if all records match). reconciliation_item table: batch_id, txn_ref, scheme_ref, match_status, gme_amount, scheme_amount, discrepancy_amount, ccy, resolution_status.
**Steps:** Create ZP0062ResultProcessor in package com.gmepayplus.settlement.reconciliation; Implement process(byte[] fileContent, Long zp0061BatchId) method; Parse fixed-width or delimited file; extract merchant_id, credited_amount, status_code; For each record: load merchant's expected net_settlement_amount from batch summary; compare; create reconciliation_item if mismatch; Update settlement_batch.status=RECEIVED; if all records matched set to RECONCILED; If any DISCREPANCY or MISSING_SCHEME: publish alert to ops notification queue and set batch error_detail; Write ZP0062ResultProcessorTest with all-match case, one-discrepancy case, and missing-record case
**Deliverable:** ZP0062ResultProcessor.java, ZP0062ResultProcessorTest.java
**Acceptance / logic checks:**
- When all merchants in ZP0062 match ZP0061 net_settlement_amount, settlement_batch.status becomes RECONCILED and no reconciliation_items are created
- A merchant in ZP0062 with credited_amount 34720 vs expected 34720 produces match_status=MATCHED
- A merchant in ZP0062 with credited_amount 34000 vs expected 34720 produces match_status=DISCREPANCY, discrepancy_amount=720 and an ops alert
- A merchant in ZP0061 that is absent from ZP0062 produces match_status=MISSING_SCHEME
- Processing ZP0062 twice for the same batch_id is idempotent (no duplicate reconciliation_item rows)
**Depends on:** 7.1-T09, 7.1-T08

### 7.1-T18 — Implement ZP0064 afternoon settlement result processor  _(40 min)_
**Context:** ZP0064 (ZeroPay -> GME, expected ~19:00 KST) confirms ZeroPay's processing of ZP0063. Identical logic to ZP0062 (7.1-T17) but applied to the ZP0063 afternoon batch. If the ZP0063 batch had zero transactions (empty file), ZP0064 will also be empty (zero merchant records); this is a valid state and should result in settlement_batch.status=RECONCILED without creating any reconciliation_items.
**Steps:** Create ZP0064ResultProcessor in package com.gmepayplus.settlement.reconciliation delegating to shared reconciliation logic extracted from ZP0062ResultProcessor; Implement process(byte[] fileContent, Long zp0063BatchId); Handle the zero-record case: if file has 0 merchant records and ZP0063 also had 0 transactions, set status=RECONCILED immediately; Reuse reconciliation_item creation and alert logic from the shared base; Write ZP0064ResultProcessorTest: zero-record success, one-match, one-discrepancy
**Deliverable:** ZP0064ResultProcessor.java (with extracted shared reconciliation logic), ZP0064ResultProcessorTest.java
**Acceptance / logic checks:**
- ZP0064 with 0 records matching a ZP0063 batch with 0 transactions sets ZP0063 batch status=RECONCILED
- ZP0064 with a merchant amount mismatch creates a reconciliation_item with match_status=DISCREPANCY and an ops alert
- Processing ZP0064 before ZP0063 batch exists raises BatchNotFoundException
- Shared reconciliation logic is in one class (not duplicated between ZP0062 and ZP0064 processors)
- settlement_batch.reconciled_at is set when status transitions to RECONCILED
**Depends on:** 7.1-T17

### 7.1-T19 — Implement daily revenue record creation for domestic NET transactions  _(40 min)_
**Context:** At settlement_batch confirmation for NET domestic transactions, the Settlement Engine must create a revenue_record per transaction. revenue_record columns: id, txn_id, partner_id, scheme_id, revenue_date (settlement_date), fx_margin_usd (= collection_margin_usd + payout_margin_usd from transaction -- this is 0 for same-currency short-circuit domestic transactions), service_charge_amount (transaction.service_charge e.g. KRW 500), service_charge_ccy ('KRW'), fee_share_pct (70.0 for ZeroPay), estimated_fee_share_usd (NULL -- not determinable per transaction; populated monthly from ZeroPay fee share statement). For domestic LOCAL-partner transactions: fx_margin_usd=0 (same-currency short-circuit, no USD pool). service_charge_amount is the only real-time revenue per transaction.
**Steps:** Create RevenueRecordService in package com.gmepayplus.settlement.revenue; Implement void createDomesticRevenueRecord(Transaction txn, LocalDate settlementDate) method; Assert txn.settlement_type='N'; set fx_margin_usd=0 (domestic never has FX margin); set service_charge_amount=txn.service_charge, service_charge_ccy=txn.collection_ccy; Set fee_share_pct=70.0 (ZeroPay), estimated_fee_share_usd=NULL; Persist revenue_record; throw if a record for txn_id already exists (idempotency guard); Write RevenueRecordServiceTest with a domestic transaction (target_payout=15000 KRW, service_charge=500 KRW)
**Deliverable:** RevenueRecordService.java (domestic method), RevenueRecordServiceTest.java
**Acceptance / logic checks:**
- For a domestic transaction with service_charge=500 KRW, revenue_record.service_charge_amount=500, fx_margin_usd=0
- revenue_record.fee_share_pct=70.0 (ZeroPay config), estimated_fee_share_usd=NULL
- Calling createDomesticRevenueRecord twice for the same txn_id throws DuplicateRevenueRecordException (idempotency)
- revenue_date = settlementDate, not transaction.committed_at
- txn.is_same_ccy_shortcircuit=true does not cause an error; fx_margin_usd=0 is still set correctly
**Depends on:** 7.1-T04, 7.1-T08

### 7.1-T20 — Implement revenue record creation for international GROSS transactions  _(40 min)_
**Context:** For GROSS international transactions (settlement_type='G', OVERSEAS partner), revenue is: fx_margin_usd = collection_margin_usd + payout_margin_usd (from the locked transaction fields, e.g. 1.0204 + 1.0204 = 2.0408 USD for the worked example: target_payout=100 USD, m_a=0.01, m_b=0.01); service_charge_amount = transaction.service_charge in settle_a_ccy (e.g. KRW 500 or USD 0.50); fee_share_pct=70.0; estimated_fee_share_usd=NULL. Revenue is funded from the OVERSEAS partner's prefunding deduction (collection_usd). Pool identity guarantees: collection_usd - collection_margin_usd - payout_margin_usd = payout_usd_cost (within 0.01 USD tolerance). Note: the 0.21% ZeroPay share of merchant fee is a monthly aggregate settled via tax_invoice, not per-transaction.
**Steps:** Implement void createInternationalRevenueRecord(Transaction txn, LocalDate settlementDate) in RevenueRecordService; Assert txn.settlement_type='G'; compute fx_margin_usd = txn.collection_margin_usd + txn.payout_margin_usd; Set service_charge_amount=txn.service_charge, service_charge_ccy=txn.service_charge_ccy (settle_a_ccy); Verify pool identity: |txn.collection_usd - txn.collection_margin_usd - txn.payout_margin_usd - txn.payout_usd_cost| <= 0.01; if fails, log POOL_IDENTITY_VIOLATION and still persist (alert only, do not block); Persist revenue_record with idempotency guard; Write test with worked example: collection_usd=102.0408, m_a=0.01, m_b=0.01, service_charge=0.50 USD
**Deliverable:** RevenueRecordService.java (international method), RevenueRecordServiceTest.java (international cases)
**Acceptance / logic checks:**
- For transaction with collection_margin_usd=1.0204, payout_margin_usd=1.0204: revenue_record.fx_margin_usd=2.0408
- service_charge_amount=0.50, service_charge_ccy='USD' (or KRW if settle_a is KRW)
- Pool identity check: |102.0408 - 1.0204 - 1.0204 - 100.0000| = 0 -- passes with tolerance 0.01
- A transaction with collection_margin_usd=NULL (same-currency short-circuit) passed to createInternationalRevenueRecord raises AssertionError
- Calling createInternationalRevenueRecord twice for same txn_id raises DuplicateRevenueRecordException
**Depends on:** 7.1-T19

### 7.1-T21 — Implement monthly tax_invoice aggregation for international gross settlement  _(55 min)_
**Context:** For GROSS international transactions, GME must invoice Korean merchants monthly (last business day of month) for the merchant fee. tax_invoice table: id, merchant_id, invoice_period (first day of invoice month), invoice_ref (unique), total_transaction_amount_krw (SUM of target_payout for the merchant during the period), fee_rate (applicable merchant fee rate from scheme fee table, e.g. 1.70%-2.20% for cross-border), merchant_fee_krw (= total * fee_rate, rounded to integer KRW), vat_krw (= merchant_fee_krw * 0.10, rounded), invoice_amount_krw (= merchant_fee_krw + vat_krw), zeropay_share_krw (= 0.21% of merchant_fee_krw, i.e. merchant_fee_krw * 0.0021, rounded to integer KRW), status ('DRAFT' -> 'ISSUED' -> 'COLLECTED' | 'FAILED'). Only GROSS (settlement_type='G') transactions are included. Domestic NET transactions are excluded.
**Steps:** Create TaxInvoiceAggregationService in package com.gmepayplus.settlement.invoice; Implement List<TaxInvoice> aggregateForMonth(YearMonth period, Long schemeId) method; Query transactions WHERE settlement_type='G' AND committed_at BETWEEN period.atDay(1) AND period.atEndOfMonth() AND scheme_id=schemeId AND status='APPROVED'; Group by merchant_id; sum target_payout; look up fee_rate by merchant.merchant_type and fee_tier='CROSSBORDER' from scheme fee config; Compute merchant_fee_krw = ROUND(total * fee_rate, 0) HALF_UP; vat_krw = ROUND(merchant_fee_krw * 0.10, 0); invoice_amount_krw = merchant_fee_krw + vat_krw; zeropay_share_krw = ROUND(merchant_fee_krw * 0.0021, 0); Persist each as TaxInvoice with status='DRAFT'; assign invoice_ref (unique, format: YYYYMM-{merchantId}-{sequence})
**Deliverable:** TaxInvoiceAggregationService.java, TaxInvoiceAggregationServiceTest.java
**Acceptance / logic checks:**
- Given merchant M2 with 3 GROSS transactions totalling 500000 KRW and fee_rate=0.017: merchant_fee_krw=8500, vat_krw=850, invoice_amount_krw=9350, zeropay_share_krw=ROUND(8500*0.0021,0)=18
- A LOCAL-partner (settlement_type='N') transaction is excluded from the aggregation
- Running aggregateForMonth() twice for the same period and merchant produces only one DRAFT tax_invoice (idempotency via invoice_period + merchant_id unique key)
- All arithmetic uses BigDecimal with HALF_UP rounding; KRW values are integers
- zeropay_share_krw = ROUND(8500 * 0.0021, 0) = ROUND(17.85, 0) = 18
**Depends on:** 7.1-T04, 7.1-T20

### 7.1-T22 — Implement SETTLEMENT_BATCHED transaction event step  _(35 min)_
**Context:** The transaction 8-step event trail requires a SETTLEMENT_BATCHED event (step 6) when a transaction is included in a settlement batch. The transaction_event table has: id, txn_id (FK -> transaction), step (1-8 INT), event_type (VARCHAR(50)), occurred_at, duration_ms, detail (JSONB). The 8 steps are: (1) RATE_QUOTE_ISSUED, (2) PREFUND_DEDUCTED (OVERSEAS only), (3) SCHEME_SUBMITTED, (4) SCHEME_APPROVED, (5) TRANSACTION_COMMITTED, (6) SETTLEMENT_BATCHED, (7) WEBHOOK_QUEUED, (8) WEBHOOK_DELIVERED. Step 6 detail payload: {batch_id, file_type, settlement_date, settlement_type}.
**Steps:** Create or extend TransactionEventService in package com.gmepayplus.settlement.audit; Implement void recordSettlementBatched(Long txnId, Long batchId, String fileType, LocalDate settlementDate, String settlementType); Persist transaction_event with step=6, event_type='SETTLEMENT_BATCHED', detail={batch_id: X, file_type: 'ZP0061', settlement_date: 'YYYY-MM-DD', settlement_type: 'N'|'G'}; Call this method inside selectAndLockForBatch() (7.1-T07) immediately after settlement_batch_id is set on the transaction; Write unit test verifying the event is persisted with correct fields
**Deliverable:** TransactionEventService.recordSettlementBatched() method; updated SettlementWindowQueryService call site; TransactionEventServiceTest (step 6 cases)
**Acceptance / logic checks:**
- After a transaction is assigned to a ZP0061 batch, a transaction_event row exists with step=6, event_type='SETTLEMENT_BATCHED', detail containing batch_id and file_type='ZP0061'
- settlement_type in the event detail is 'N' for a domestic transaction and 'G' for an international one
- Calling recordSettlementBatched twice for the same txn_id raises DuplicateEventException (one SETTLEMENT_BATCHED per transaction)
- occurred_at is set to the time selectAndLockForBatch() executes (not the batch transmission time)
- A transaction without a SETTLEMENT_BATCHED event can be identified by querying for txn_id absent from transaction_event WHERE event_type='SETTLEMENT_BATCHED'
**Depends on:** 7.1-T07, 7.1-T04

### 7.1-T23 — Implement daily pool identity background reconciliation job  _(45 min)_
**Context:** After settlement batches run, a background reconciliation job must verify the USD pool identity for ALL committed cross-border transactions: collection_usd - collection_margin_usd - payout_margin_usd = payout_usd_cost (tolerance 0.01 USD). This is specified in spec section 13.1: violations should trigger an ops alert. Run daily (e.g. 23:00 KST). Scope: transactions with is_same_ccy_shortcircuit=false AND status='APPROVED' AND reconciliation not yet run (track via a reconciled_at column or a separate flag). Domestic same-currency transactions (is_same_ccy_shortcircuit=true) are skipped. The job must not block or affect settlement batch generation.
**Steps:** Create PoolIdentityReconciliationJob in package com.gmepayplus.settlement.reconciliation; Implement void run(LocalDate forDate) querying all approved cross-border transactions committed on forDate; For each transaction: compute delta = |collection_usd - collection_margin_usd - payout_margin_usd - payout_usd_cost|; If delta > 0.01: create reconciliation_item with match_status='DISCREPANCY', batch_id=NULL, txn_ref=txn.txn_ref, gme_amount=collection_usd, scheme_amount=payout_usd_cost+margins, discrepancy_amount=delta, ccy='USD'; Publish POOL_IDENTITY_VIOLATION alert if any violations found; Write PoolIdentityReconciliationJobTest with one passing and one failing (delta=0.02) transaction
**Deliverable:** PoolIdentityReconciliationJob.java, PoolIdentityReconciliationJobTest.java
**Acceptance / logic checks:**
- Transaction with collection_usd=102.0408, collection_margin_usd=1.0204, payout_margin_usd=1.0204, payout_usd_cost=100.0000: delta=0 -- no violation created
- Transaction with delta=0.02 produces a reconciliation_item with match_status='DISCREPANCY', discrepancy_amount=0.02, ccy='USD' and triggers an ops alert
- Domestic transactions (is_same_ccy_shortcircuit=true) are not processed (skipped in query)
- Running the job twice for the same date does not create duplicate reconciliation_items (idempotency by txn_ref + event type)
- Job completes without error when no cross-border transactions exist for the date
**Depends on:** 7.1-T20, 7.1-T17

### 7.1-T24 — Implement daily aggregate settlement reconciliation against ZP0062/ZP0064  _(50 min)_
**Context:** Spec section 13.2: daily reconciliation must verify (1) SUM(target_payout) for all batched transactions on the settlement_date matches ZP0065 totals confirmed by ZP0062/ZP0064; (2) SUM(collection_usd) for OVERSEAS partners matches prefunding ledger debit movements; (3) count of transactions in ZP0061/ZP0063 matches internal batched count. This is separate from the ZP0062ResultProcessor per-merchant matching (7.1-T17). Create a DailyAggregateReconciliationJob that runs after ZP0064 is received (~20:00 KST).
**Steps:** Create DailyAggregateReconciliationJob in package com.gmepayplus.settlement.reconciliation; Implement void run(LocalDate settlementDate) loading ZP0061 and ZP0063 batches for the date; Compare internal SUM(target_payout) for batched transactions vs ZP0062/ZP0064 confirmed totals (from settlement_batch.total_amount after reconciliation); Compare internal transaction_count vs ZP0061.transaction_count + ZP0063.transaction_count; Compare SUM(collection_usd) for OVERSEAS transactions vs prefunding ledger net debit for the date (prefunding_ledger table debit_amount column); Create reconciliation_items for any aggregate mismatch; publish alert; Write DailyAggregateReconciliationJobTest with fully-matched scenario and one count-mismatch scenario
**Deliverable:** DailyAggregateReconciliationJob.java, DailyAggregateReconciliationJobTest.java
**Acceptance / logic checks:**
- When internal SUM(target_payout)=500000 KRW equals ZP0062/ZP0064 confirmed total=500000 KRW, no reconciliation_item is created
- When internal count=10 but ZP0061 + ZP0063 count=9, a DISCREPANCY reconciliation_item is created and alert fired
- SUM(collection_usd) for OVERSEAS transactions is compared to prefunding_ledger debit sum; delta > 0.01 USD triggers an alert
- Job does not run if ZP0062 or ZP0064 batch is not yet in RECONCILED status (deferred until result files processed)
- All comparisons use Decimal/BigDecimal arithmetic to avoid floating-point error
**Depends on:** 7.1-T17, 7.1-T18, 7.1-T23

### 7.1-T25 — Implement settlement cron scheduler configuration  _(35 min)_
**Context:** The Settlement Engine requires a cron scheduler to trigger batch jobs at the correct KST times. All times are KST (Asia/Seoul, UTC+9). Schedule: ZP0011/ZP0021 outbound at 02:00 KST; ZP0061 morning at 05:00 KST; ZP0063 afternoon at 14:00 KST; ZP0065/ZP0066 detail at 22:00 KST; daily pool identity reconciliation at 23:00 KST; daily aggregate reconciliation at 20:30 KST. The scheduler must publish events to the settlement.batch_trigger message queue (or call job service directly). All jobs must be idempotent and guarded by distributed locks (Redis). Cron expressions must use the Asia/Seoul timezone zone parameter.
**Steps:** Create SettlementCronScheduler in package com.gmepayplus.settlement.scheduler using Spring @Scheduled or Quartz; Define cron expressions with zone=Asia/Seoul for each batch window; Each @Scheduled method calls the corresponding SettlementBatchJobService method with LocalDate.now(ZoneId.of('Asia/Seoul')); Ensure @EnableScheduling is present in the Spring configuration; Add application.yml property settlement.scheduling.enabled=true to allow disabling in tests; Write SettlementCronSchedulerTest verifying cron expression format and that scheduling is disabled in test profile
**Deliverable:** SettlementCronScheduler.java with all 6 cron methods; application.yml config; SettlementCronSchedulerTest.java
**Acceptance / logic checks:**
- Cron expression for ZP0061 job is '0 0 5 * * MON-SAT' (or equivalent) with zone=Asia/Seoul
- Cron expression for ZP0065/ZP0066 job is '0 0 22 * * MON-SAT' with zone=Asia/Seoul
- Setting settlement.scheduling.enabled=false disables all @Scheduled methods (test isolation)
- Each scheduler method invokes the correct SettlementBatchJobService method with the current KST date
- No scheduler method catches and swallows exceptions -- failures propagate to the scheduler's error handler for alerting
**Depends on:** 7.1-T14, 7.1-T15, 7.1-T16, 7.1-T23

### 7.1-T26 — Unit tests: net settlement amount calculations with edge cases  _(40 min)_
**Context:** Comprehensive unit tests for NetSettlementAmountCalculator (7.1-T05). Must cover exact arithmetic, KRW rounding, zero-transaction merchants, and fee rate boundaries. ZeroPay domestic fee range: 0.80% to 2.00% by merchant_type. KRW has 0 decimal places; use HALF_UP rounding. Key invariant: net_settlement_amount = gross_txn_amount - merchant_fee_total (per merchant group). service_charge (e.g. KRW 500) is NOT included in any ZP0061 field -- it is retained by GME and only appears in revenue_record.
**Steps:** Write NetSettlementEdgeCaseTest in package com.gmepayplus.settlement.calculator; Test 1: single transaction target_payout=15000, fee_rate=0.008 -> merchant_fee=120, net=14880; Test 2: transaction at fee boundary: target_payout=10000, fee_rate=0.020 -> merchant_fee=200, net=9800; Test 3: two transactions for same merchant M1: payout=[10000,20001], fee_rate=0.008 -> gross=30001, merchant_fee=ROUND(30001*0.008,0)=240, net=29761; Test 4: zero transactions -> all amounts 0, no error; Test 5: service_charge=500 KRW NOT reflected in any ZP0061 field (i.e. not subtracted from net_settlement_amount)
**Deliverable:** NetSettlementEdgeCaseTest.java (5+ test methods)
**Acceptance / logic checks:**
- Test 1 passes: merchant_fee=ROUND(15000*0.008,0)=120, net_settlement_amount=14880
- Test 3 passes: gross=30001, merchant_fee=ROUND(30001*0.008,0)=ROUND(240.008,0)=240, net=29761
- service_charge=500 KRW does not appear in gross_txn_amount or net_settlement_amount (only in revenue_record)
- merchant_fee_total is always <= gross_txn_amount (no negative net for valid fee rates up to 2%)
- All arithmetic uses BigDecimal; no double or float types in the calculator
**Depends on:** 7.1-T05

### 7.1-T27 — Unit tests: gross settlement amount calculations with edge cases  _(35 min)_
**Context:** Comprehensive unit tests for GrossSettlementAmountCalculator (7.1-T06). Key properties: net_settlement_amount = gross_txn_amount for every merchant (no fee deduction); merchant_fee_total = 0 always; FX margin fields (collection_margin_usd, payout_margin_usd) from the transaction row are not surfaced in ZP0061 fields. Worked example: 3 OVERSEAS transactions for merchant M2 with target_payout 50000, 30000, 20000 KRW -> gross=100000, fee=0, net=100000. Also test: refund reduces gross but leaves fee=0.
**Steps:** Write GrossSettlementEdgeCaseTest in package com.gmepayplus.settlement.calculator; Test 1: 3 transactions target_payout=[50000,30000,20000] KRW -> gross=100000, merchant_fee_total=0, net=100000; Test 2: single transaction with collection_margin_usd=1.02 and payout_margin_usd=1.02 -> those fields are not in output, net=target_payout; Test 3: mixed-merchant: merchant A payout=80000, merchant B payout=50000 -> two separate summaries, A.net=80000, B.net=50000; Test 4: zero transactions -> empty result, no error; Test 5: transaction with settlement_type='N' passed to gross calculator -> throws IllegalArgumentException
**Deliverable:** GrossSettlementEdgeCaseTest.java (5+ test methods)
**Acceptance / logic checks:**
- Test 1: net_settlement_amount=100000, merchant_fee_total=0 for merchant M2
- Test 2: output does not reference collection_margin_usd or payout_margin_usd
- Test 3: produces 2 separate GrossSettlementSummary objects for A and B
- Test 5: IllegalArgumentException thrown with message referencing settlement_type mismatch
- All KRW values in output are integers (BigDecimal scale=0)
**Depends on:** 7.1-T06

### 7.1-T28 — Unit tests: SettlementClassifier and settlement_type assignment  _(30 min)_
**Context:** Unit tests for the SettlementClassifier and the transaction commit path that sets settlement_type. Cover: LOCAL partner produces 'N', OVERSEAS produces 'G', unknown partner type produces error, and the same-currency short-circuit (domestic) does NOT change the settlement_type logic (it is still 'N' for LOCAL). Also test that settlement_type is immutable after commit: a second commit of the same txn_ref returns the existing transaction with the same settlement_type.
**Steps:** Write SettlementTypeAssignmentTest in package com.gmepayplus.settlement; Test LOCAL partner -> classify() returns NET -> transaction.settlement_type='N' after commit; Test OVERSEAS partner -> classify() returns GROSS -> transaction.settlement_type='G' after commit; Test LOCAL partner with is_same_ccy_shortcircuit=true -> settlement_type='N' (short-circuit does not change type); Test idempotent commit: second call with same txn_ref returns existing transaction with unchanged settlement_type='N'; Test null partner type -> IllegalArgumentException from classifier
**Deliverable:** SettlementTypeAssignmentTest.java (5 test methods)
**Acceptance / logic checks:**
- LOCAL partner commit: transaction.settlement_type='N'
- OVERSEAS partner commit: transaction.settlement_type='G'
- Same-currency short-circuit LOCAL: transaction.settlement_type='N', is_same_ccy_shortcircuit=true
- Second idempotent commit returns same transaction row with settlement_type unchanged
- Null partner type raises IllegalArgumentException before any DB write
**Depends on:** 7.1-T04

### 7.1-T29 — Unit tests: ZP0061 file generation for net settlement  _(35 min)_
**Context:** End-to-end unit tests for ZP0061RecordBuilder NET path (7.1-T09). Cover header/trailer correctness, field widths, KRW integer formatting, settlement_type='N' marker, and file SHA-256 checksum consistency. Use the worked example: GME Remit domestic, merchant M1, 2 transactions payout=[15000,20000] KRW, fee_rate=0.008, service_charge=500 KRW each (not in ZP0061 fields).
**Steps:** Write ZP0061NetFileBuilderTest in package com.gmepayplus.settlement.file; Build a ZP0061 file for one merchant (M1) with the above inputs; Assert per-record fields: merchant_id='M1', gross_txn_amount=35000, merchant_fee_total=280, net_settlement_amount=34720, settlement_type='N'; Assert file trailer: total_net_settlement=34720; Assert header: merchant_record_count=1; Assert file_checksum is a valid 64-character hex string (SHA-256); Assert service_charge (500 KRW per transaction) does NOT appear in any ZP0061 field
**Deliverable:** ZP0061NetFileBuilderTest.java
**Acceptance / logic checks:**
- gross_txn_amount field in output = 35000 (integer, padded to NUM(14))
- merchant_fee_total = 280 (ROUND(35000*0.008,0))
- net_settlement_amount = 34720
- settlement_type field = 'N'
- File checksum is a valid SHA-256 hex string (64 chars, lowercase hex)
**Depends on:** 7.1-T09, 7.1-T26

### 7.1-T30 — Unit tests: ZP0061 file generation for gross settlement  _(30 min)_
**Context:** End-to-end unit tests for ZP0061RecordBuilder GROSS path (7.1-T10). Use the worked example: SendMN (OVERSEAS), merchant M2, 3 transactions payout=[50000,30000,20000] KRW. Expected: gross_txn_amount=100000, merchant_fee_total=0, net_settlement_amount=100000, settlement_type='G'. Also test that FX margin fields from the transaction (collection_margin_usd=1.02, payout_margin_usd=1.02) do not affect any ZP0061 field values.
**Steps:** Write ZP0061GrossFileBuilderTest in package com.gmepayplus.settlement.file; Build a ZP0061 file for merchant M2 with the above GROSS inputs; Assert per-record: merchant_fee_total=0, net_settlement_amount=100000, settlement_type='G'; Assert trailer total matches 100000; Assert that transaction.collection_margin_usd and payout_margin_usd have no effect on the file output; Test two-merchant gross file: M2 payout=100000, M3 payout=50000 -> trailer=150000
**Deliverable:** ZP0061GrossFileBuilderTest.java
**Acceptance / logic checks:**
- merchant_fee_total field = 0 (not a positive number)
- net_settlement_amount = gross_txn_amount = 100000
- settlement_type field = 'G'
- Trailer total_net_settlement = 100000 for single-merchant case
- Two-merchant gross file trailer = 150000, both records have settlement_type='G' and merchant_fee_total=0
**Depends on:** 7.1-T10, 7.1-T27

### 7.1-T31 — Unit tests: ZP0062 reconciliation processor (all match, discrepancy, missing)  _(45 min)_
**Context:** Unit tests for ZP0062ResultProcessor (7.1-T17). Test vectors: (A) all merchants match -- status becomes RECONCILED, no reconciliation_items; (B) one merchant credited_amount 34000 vs expected 34720 -- DISCREPANCY, discrepancy_amount=720; (C) merchant in ZP0061 absent from ZP0062 -- MISSING_SCHEME; (D) merchant in ZP0062 not in ZP0061 -- MISSING_GME; (E) idempotent reprocessing -- no duplicates. Also test that after full reconciliation the settlement_batch.reconciled_at timestamp is set.
**Steps:** Write ZP0062ReconciliationProcessorTest in package com.gmepayplus.settlement.reconciliation; Prepare synthetic ZP0062 byte payloads for each test vector; Test A: process with all matching amounts -> verify settlement_batch.status=RECONCILED, reconciliation_item count=0; Test B: one mismatch -> verify reconciliation_item with match_status='DISCREPANCY', discrepancy_amount=720, resolution_status='UNRESOLVED'; Test C: missing merchant in ZP0062 -> match_status='MISSING_SCHEME'; Test D: extra merchant in ZP0062 -> match_status='MISSING_GME'; Test E: call process() twice with same inputs -> reconciliation_item count unchanged (idempotent)
**Deliverable:** ZP0062ReconciliationProcessorTest.java (5 test methods)
**Acceptance / logic checks:**
- Test A: settlement_batch.status=RECONCILED, reconciliation_item count=0 for the batch
- Test B: reconciliation_item.discrepancy_amount=720, match_status=DISCREPANCY, ops alert published
- Test C: reconciliation_item.match_status=MISSING_SCHEME for missing merchant
- Test D: reconciliation_item.match_status=MISSING_GME, resolution_status=UNRESOLVED
- Test E: second process() call for same batch produces identical reconciliation_item count (no duplicates)
**Depends on:** 7.1-T17

### 7.1-T32 — Unit tests: monthly tax invoice aggregation edge cases  _(40 min)_
**Context:** Unit tests for TaxInvoiceAggregationService (7.1-T21). Test vectors: (A) 3 GROSS transactions -> correct fee, VAT, ZeroPay share; (B) 0 GROSS transactions -> no invoices created; (C) domestic NET transactions excluded; (D) idempotent re-run same month -> no duplicate invoices; (E) fee rate boundary: total=1000000 KRW, fee_rate=0.022 -> merchant_fee=22000, vat=2200, invoice=24200, zeropay_share=ROUND(22000*0.0021,0)=46. Verify KRW integer rounding for all fields.
**Steps:** Write TaxInvoiceAggregationServiceTest in package com.gmepayplus.settlement.invoice; Test A: 3 transactions totalling 500000 KRW, fee_rate=0.017 -> merchant_fee=8500, vat=850, invoice=9350, zeropay_share=18; Test B: no GROSS transactions for month -> aggregateForMonth() returns empty list; Test C: mix of GROSS and NET transactions -> only GROSS included in total_transaction_amount_krw; Test D: run aggregateForMonth() twice for same YearMonth and merchant -> still one TaxInvoice with status=DRAFT; Test E: total=1000000, fee_rate=0.022 -> zeropay_share=ROUND(22000*0.0021,0)=46
**Deliverable:** TaxInvoiceAggregationServiceTest.java (5 test methods)
**Acceptance / logic checks:**
- Test A: merchant_fee_krw=8500, vat_krw=850, invoice_amount_krw=9350, zeropay_share_krw=18
- Test B: returns empty List<TaxInvoice>
- Test C: a NET transaction (settlement_type='N') with target_payout=100000 KRW is absent from the aggregation total
- Test D: second call returns existing DRAFT invoice, no new row inserted
- Test E: zeropay_share_krw=46 (ROUND(22000*0.0021,0)=ROUND(46.2,0)=46)
**Depends on:** 7.1-T21

### 7.1-T33 — Integration test: full daily settlement cycle (domestic NET)  _(60 min)_
**Context:** End-to-end integration test for a complete domestic NET settlement day. Scenario: GME Remit (LOCAL, KRW) makes 2 payments to ZeroPay merchant M1 (payout 15000 KRW and 20000 KRW, service_charge=500 each, fee_rate=0.008). Steps: transactions committed with settlement_type='N'; ZP0011 transmitted; ZP0012 received (all match); ZP0061 morning batch generated and transmitted; ZP0062 result received confirming net_settlement_amount=34720; reconciliation passes (RECONCILED); ZP0065 detail file generated; revenue_records created with fx_margin_usd=0 and service_charge=500 each. Verify DB state after each step.
**Steps:** Create DomesticNetSettlementCycleIT in package com.gmepayplus.settlement.integration (using @SpringBootTest with test DB); Set up: insert LOCAL partner, ZeroPay scheme, merchant M1, 2 approved transactions with target_payout=15000 and 20000; Run ZP0061 morning batch job; assert settlement_batch created with transaction_count=2, total_amount=35000, net_settlement_amount=34720, merchant_fee_total=280; Simulate ZP0062 receipt with credited_amount=34720; run ZP0062ResultProcessor; assert batch status=RECONCILED; Run ZP0065 detail batch; assert 2 records in file with partner_type='D'; Run createDomesticRevenueRecord; assert revenue_records: fx_margin_usd=0, service_charge_amount=500 each
**Deliverable:** DomesticNetSettlementCycleIT.java
**Acceptance / logic checks:**
- After ZP0061: settlement_batch.transaction_count=2, net_settlement_amount=34720, merchant_fee_total=280
- After ZP0062: settlement_batch.status=RECONCILED, no reconciliation_items with DISCREPANCY
- After ZP0065: file contains 2 records with partner_type='D', payout_amount_krw=[15000,20000]
- After revenue record creation: 2 revenue_record rows with fx_margin_usd=0, service_charge_amount=500, service_charge_ccy='KRW'
- Both transactions have settlement_batch_id set (not NULL) and SETTLEMENT_BATCHED event in transaction_event
**Depends on:** 7.1-T14, 7.1-T17, 7.1-T16, 7.1-T19

### 7.1-T34 — Integration test: full daily settlement cycle (international GROSS)  _(60 min)_
**Context:** End-to-end integration test for a complete international GROSS settlement day. Scenario: SendMN (OVERSEAS, USD prefunding) makes 3 payments to ZeroPay merchant M2 (payout 50000, 30000, 20000 KRW; FX: cost_rate_pay=1350 KRW/USD; m_a=0.01, m_b=0.01; service_charge=500 KRW). USD prefunding deducted at payment time. Steps: transactions committed with settlement_type='G'; ZP0061 morning batch: merchant_fee_total=0, net_settlement_amount=100000; ZP0062 confirms credited_amount=100000 (RECONCILED); ZP0065 generated with partner_type='I', merchant_fee_amt=0; revenue_records: fx_margin_usd = collection_margin_usd + payout_margin_usd (non-zero).
**Steps:** Create InternationalGrossSettlementCycleIT in package com.gmepayplus.settlement.integration; Set up: insert OVERSEAS partner, ZeroPay scheme, merchant M2 (fee_tier='CROSSBORDER'), 3 approved transactions with above payouts and locked USD pool values; Run ZP0061 morning batch; assert settlement_batch: transaction_count=3, total_amount=100000, merchant_fee_total=0, net_settlement_amount=100000, settlement_type='G'; Simulate ZP0062 receipt with credited_amount=100000; run ZP0062ResultProcessor; assert RECONCILED; Run ZP0065; assert 3 records with partner_type='I', merchant_fee_amt=0; Run createInternationalRevenueRecord for each transaction; assert fx_margin_usd > 0
**Deliverable:** InternationalGrossSettlementCycleIT.java
**Acceptance / logic checks:**
- ZP0061 batch: merchant_fee_total=0, net_settlement_amount=100000, settlement_type='G'
- ZP0062 reconciliation: batch status=RECONCILED, no DISCREPANCY items
- ZP0065 records: all 3 have partner_type='I', merchant_fee_amt=0, payout_amount_krw=[50000,30000,20000]
- Revenue records: fx_margin_usd = collection_margin_usd + payout_margin_usd (non-zero per transaction)
- All 3 transactions have SETTLEMENT_BATCHED event in transaction_event with settlement_type='G' in detail
**Depends on:** 7.1-T15, 7.1-T17, 7.1-T20, 7.1-T33

### 7.1-T35 — Document SettlementEngine service API (Javadoc and README section)  _(40 min)_
**Context:** The Settlement Engine for WBS 7.1 exposes several key service classes. Developers integrating with it need to understand: (1) how settlement_type is determined per transaction; (2) the difference between NET and GROSS settlement amounts; (3) the daily batch schedule (KST times); (4) which classes to extend to add a new settlement model when a new QR scheme is added (config-only, no code change for same net/gross model). Document the public API of: SettlementClassifier, NetSettlementAmountCalculator, GrossSettlementAmountCalculator, SettlementBatchJobService, and TaxInvoiceAggregationService.
**Steps:** Add @since, @param, @return, and @throws Javadoc to all public methods in the 5 classes listed in context; In the existing settlement service README (or SETTLEMENT.md in the service module), add a section '7.1 Net vs Gross Settlement Model' covering: rule summary (LOCAL=NET, OVERSEAS=GROSS), daily batch schedule table (KST times), ZP0061/ZP0063/ZP0065/ZP0066 file responsibilities, and extension guidance; Note that settlement model is driven by partner.type (config); adding a new partner requires no code change; Include a concise worked example for each settlement type (reference existing test data from 7.1-T33 and 7.1-T34); Review that no Javadoc references internal Spring bean names or implementation details that could break consumers
**Deliverable:** Javadoc on 5 public service classes; SETTLEMENT.md section '7.1 Net vs Gross Settlement Model'
**Acceptance / logic checks:**
- SettlementClassifier interface Javadoc explains the LOCAL -> NET, OVERSEAS -> GROSS rule without hardcoding partner names
- SettlementBatchJobService Javadoc for runZP0061Morning() documents the ZP0011/ZP0012 prerequisite check
- SETTLEMENT.md batch schedule table lists ZP0061 at 05:00 KST, ZP0063 at 14:00 KST, ZP0065/ZP0066 at 22:00 KST
- SETTLEMENT.md extension guidance states that adding a new partner with type LOCAL or OVERSEAS requires only partner registry configuration, no code change
- Both NET and GROSS worked examples reference the numeric values from 7.1-T33 and 7.1-T34 test scenarios
**Depends on:** 7.1-T14, 7.1-T15, 7.1-T16, 7.1-T21


## WBS 7.4 — Daily reconciliation engine
### 7.4-T01 — Create settlement_batch and settlement_file DB migration  _(35 min)_
**Context:** WBS 7.4 Daily Reconciliation Engine. settlement_batch tracks one row per daily batch run per scheme/file_type. settlement_file tracks physical SFTP file metadata. settlement_batch columns: id BIGINT PK, scheme_id BIGINT FK qr_scheme, file_type VARCHAR(10) (e.g. ZP0011, ZP0061), direction VARCHAR(20) CHECK IN ('GME_TO_ZP','ZP_TO_GME'), settlement_date DATE (KST business date), window VARCHAR(20) CHECK IN ('MORNING','AFTERNOON','DETAIL','NIGHTLY'), status VARCHAR(20) CHECK IN ('PENDING','GENERATED','TRANSMITTED','RECEIVED','RECONCILED','ERROR'), transaction_count INT DEFAULT 0, total_amount DECIMAL(20,4) DEFAULT 0, total_amount_ccy CHAR(3), file_checksum VARCHAR(64) nullable, transmitted_at TIMESTAMPTZ nullable, received_at TIMESTAMPTZ nullable, reconciled_at TIMESTAMPTZ nullable, error_detail TEXT nullable, created_at/updated_at/created_by/updated_by standard audit. settlement_file columns: id BIGINT PK, batch_id BIGINT FK settlement_batch, filename VARCHAR(255), sftp_path VARCHAR(512), file_size_bytes BIGINT, file_checksum VARCHAR(64), direction VARCHAR(20) CHECK IN ('OUTBOUND','INBOUND'), transmitted_at TIMESTAMPTZ nullable, created_at/updated_at standard. Unique constraint on (scheme_id, file_type, settlement_date, window, direction) in settlement_batch.
**Steps:** Create Flyway/Liquibase migration file V7_4_001__settlement_batch_file.sql; Add CREATE TABLE settlement_batch with all columns and constraints as specified; Add CREATE TABLE settlement_file with FK to settlement_batch; Add UNIQUE constraint on settlement_batch(scheme_id, file_type, settlement_date, window, direction); Add index on settlement_batch(settlement_date, status) and settlement_file(batch_id)
**Deliverable:** Migration file V7_4_001__settlement_batch_file.sql that creates both tables with all constraints
**Acceptance / logic checks:**
- INSERT with duplicate (scheme_id, file_type, settlement_date, window, direction) raises unique violation
- INSERT status='INVALID' into settlement_batch raises check constraint violation
- INSERT direction='SEND' into settlement_file raises check constraint violation
- settlement_file FK references settlement_batch; inserting orphan batch_id raises FK error
- Rollback migration cleanly removes both tables with no orphan objects

### 7.4-T02 — Create reconciliation_item DB migration  _(25 min)_
**Context:** WBS 7.4 Daily Reconciliation Engine. reconciliation_item stores one row per line compared during batch reconciliation. Auto-generated when ZP006x result files are received. Columns: id BIGINT PK, batch_id BIGINT FK settlement_batch, txn_ref VARCHAR(64) nullable (GMEPay+ reference; NULL for records missing from GME side), scheme_ref VARCHAR(128) (ZeroPay reference), match_status VARCHAR(20) CHECK IN ('MATCHED','DISCREPANCY','MISSING_GME','MISSING_SCHEME'), gme_amount DECIMAL(20,4), scheme_amount DECIMAL(20,4), discrepancy_amount DECIMAL(20,4), ccy CHAR(3), resolution_status VARCHAR(20) CHECK IN ('UNRESOLVED','RESOLVED','ESCALATED') DEFAULT 'UNRESOLVED', resolved_by VARCHAR(120) nullable, resolved_at TIMESTAMPTZ nullable, resolution_note TEXT nullable, created_at/updated_at/created_by/updated_by standard audit. Index on (batch_id, match_status) and (txn_ref) for lookup.
**Steps:** Create migration file V7_4_002__reconciliation_item.sql; Add CREATE TABLE reconciliation_item with all columns and check constraints; Add FK to settlement_batch; Add index on (batch_id, match_status) and separate index on txn_ref; Verify resolution_status defaults to UNRESOLVED on INSERT without explicit value
**Deliverable:** Migration file V7_4_002__reconciliation_item.sql
**Acceptance / logic checks:**
- INSERT with match_status='WRONG' raises check constraint violation
- txn_ref nullable: INSERT with NULL txn_ref succeeds for MISSING_GME case
- resolution_status defaults to UNRESOLVED when not supplied on INSERT
- FK to settlement_batch: orphan batch_id insert raises error
- Index on (batch_id, match_status) appears in EXPLAIN plan for query WHERE batch_id=X AND match_status='DISCREPANCY'
**Depends on:** 7.4-T01

### 7.4-T03 — Define ReconciliationBatch and SettlementFile Java domain models  _(40 min)_
**Context:** WBS 7.4 Daily Reconciliation Engine. Java backend. Create JPA entity classes for settlement_batch, settlement_file, and reconciliation_item tables created in T01/T02. Use BigDecimal for all monetary fields (DECIMAL(20,4)). settlement_batch.status is an enum: PENDING, GENERATED, TRANSMITTED, RECEIVED, RECONCILED, ERROR. settlement_batch.window is an enum: MORNING, AFTERNOON, DETAIL, NIGHTLY. reconciliation_item.match_status enum: MATCHED, DISCREPANCY, MISSING_GME, MISSING_SCHEME. resolution_status enum: UNRESOLVED, RESOLVED, ESCALATED. All monetary amounts use BigDecimal; no float or double. Timestamps are OffsetDateTime. String ccy fields are plain String (validated by constraint at DB level).
**Steps:** Create enum BatchStatus {PENDING, GENERATED, TRANSMITTED, RECEIVED, RECONCILED, ERROR}; Create enum BatchWindow {MORNING, AFTERNOON, DETAIL, NIGHTLY}; Create enum MatchStatus {MATCHED, DISCREPANCY, MISSING_GME, MISSING_SCHEME}; Create enum ResolutionStatus {UNRESOLVED, RESOLVED, ESCALATED}; Create @Entity SettlementBatch with JPA annotations mapping to settlement_batch table; Create @Entity SettlementFile with @ManyToOne to SettlementBatch; Create @Entity ReconciliationItem with @ManyToOne to SettlementBatch; BigDecimal for gme_amount, scheme_amount, discrepancy_amount
**Deliverable:** Four enum classes and three @Entity classes with full JPA mappings
**Acceptance / logic checks:**
- SettlementBatch.totalAmount is BigDecimal, not double
- ReconciliationItem.txnRef is nullable (no @Column(nullable=false))
- Enum fields use @Enumerated(EnumType.STRING) so DB stores 'MATCHED' not ordinal
- SettlementFile has @ManyToOne(fetch=LAZY) to SettlementBatch to avoid N+1
- Spring Boot integration test: persist and reload a SettlementBatch; verify field round-trip including BigDecimal scale
**Depends on:** 7.4-T01, 7.4-T02

### 7.4-T04 — Implement PoolIdentityVerifier service  _(35 min)_
**Context:** WBS 7.4 Daily Reconciliation Engine. Pool identity invariant (RATE-04 §5, §13.1): collection_usd - collection_margin_usd - payout_margin_usd = payout_usd_cost must hold within 0.01 USD tolerance for every committed cross-border transaction. Fields are BigDecimal stored on the transaction entity. Same-currency short-circuit transactions have collection_usd = NULL and margins = 0; they are excluded from pool check. Service must: accept a transaction record, compute |collection_usd - collection_margin_usd - payout_margin_usd - payout_usd_cost|, return PASS if < 0.01 USD, return FAIL with delta value if >= 0.01 USD. Used both at transaction commit time and by the daily background recon job (T05). Must raise POOL_IDENTITY_FAILURE for commit-time failures; background job only logs and alerts.
**Steps:** Create PoolIdentityVerifier.java in recon package; Implement verify(Transaction txn): returns PoolIdentityResult{status, delta}; Skip check and return PASS if txn.collectionUsd is null (same-currency short-circuit); Compute delta = abs(collectionUsd.subtract(collectionMarginUsd).subtract(payoutMarginUsd).subtract(payoutUsdCost)); Return FAIL with delta if delta.compareTo(new BigDecimal('0.01')) >= 0; Unit test all branches including boundary at exactly 0.01 USD (edge: returns FAIL) and 0.0099 (PASS)
**Deliverable:** PoolIdentityVerifier.java service class with unit tests
**Acceptance / logic checks:**
- verify with collectionUsd=36.9714, collectionMarginUsd=0.3697, payoutMarginUsd=0.3697, payoutUsdCost=36.2319 returns PASS (delta 0.0001 < 0.01)
- verify with collectionUsd=100.00, marginUsd values=1.0204 each, payoutUsdCost=100.00 returns PASS (identity legs example from RATE-04 §7.1)
- verify with delta=0.01 exactly returns FAIL
- verify with delta=0.0099 returns PASS
- verify on same-currency transaction (collectionUsd=null) returns PASS without NPE
**Depends on:** 7.4-T03

### 7.4-T05 — Implement daily pool-identity background reconciliation job  _(45 min)_
**Context:** WBS 7.4 Daily Reconciliation Engine. RATE-04 §13.1 requires a daily background job to verify pool identity across all committed cross-border transactions for the prior business day. The job queries transaction table for rows with status=APPROVED and rate_locked_at on the prior business date (KST), runs PoolIdentityVerifier on each, accumulates any FAIL results, and emits an alert if violations found. Alert must include txn_ref, delta, and a summary count. Uses PoolIdentityVerifier from T04. Job is a Spring @Scheduled component or a named batch step. KST is UTC+9; compute prior KST business day correctly using ZoneId('Asia/Seoul'). Never modifies transaction rows; read-only scan.
**Steps:** Create DailyPoolIdentityReconJob.java annotated @Component; Inject TransactionRepository and PoolIdentityVerifier; Implement runForDate(LocalDate kstDate) method: query approved cross-border transactions for that KST date, verify each; Collect violations into a list; call AlertService.sendPoolIdentityAlert if list non-empty; Register as @Scheduled(cron='0 30 1 * * *', zone='Asia/Seoul') (01:30 KST daily); Unit test runForDate with 3 clean txns + 1 violation: verify alert fires once with correct txn_ref
**Deliverable:** DailyPoolIdentityReconJob.java with unit test
**Acceptance / logic checks:**
- runForDate with zero violations does not call AlertService
- runForDate with one violation calls AlertService exactly once with violating txn_ref and delta value
- Same-currency transactions (collectionUsd=null) are skipped without error
- Date window query uses KST zone: a txn at 2026-06-04 23:30 UTC (2026-06-05 08:30 KST) appears in the 2026-06-05 KST run
- @Scheduled cron expression resolves to 01:30 KST via zone attribute
**Depends on:** 7.4-T04

### 7.4-T06 — Implement SettlementBatchRepository and batch status state machine  _(40 min)_
**Context:** WBS 7.4 Daily Reconciliation Engine. settlement_batch status lifecycle: PENDING -> GENERATED -> TRANSMITTED -> RECEIVED -> RECONCILED; any state -> ERROR. Only valid transitions are allowed. Service method transitionStatus(Long batchId, BatchStatus target) must enforce: GENERATED only from PENDING, TRANSMITTED only from GENERATED, RECEIVED only from TRANSMITTED, RECONCILED only from RECEIVED, ERROR from any state. Invalid transition throws IllegalStateException with message. Uses JPA SettlementBatch entity from T03. Also provide findBySchemeAndDateAndFileType(Long schemeId, LocalDate date, String fileType) query method.
**Steps:** Create SettlementBatchRepository extending JpaRepository<SettlementBatch, Long>; Add @Query method findBySchemeIdAndSettlementDateAndFileType(Long schemeId, LocalDate date, String fileType); Create SettlementBatchService.java; Implement transitionStatus(Long batchId, BatchStatus target) with valid-transition guard; Implement createBatch(Long schemeId, String fileType, String direction, LocalDate date, BatchWindow window) initialising status=PENDING; Unit test all valid transitions and at least two invalid transitions
**Deliverable:** SettlementBatchRepository interface and SettlementBatchService.java with unit tests
**Acceptance / logic checks:**
- PENDING -> GENERATED succeeds
- PENDING -> RECONCILED throws IllegalStateException
- TRANSMITTED -> ERROR succeeds (any-to-ERROR rule)
- RECONCILED -> GENERATED throws IllegalStateException
- createBatch sets status=PENDING and transaction_count=0, total_amount=0
**Depends on:** 7.4-T03

### 7.4-T07 — Implement ZP0061 morning settlement request file generator  _(55 min)_
**Context:** WBS 7.4 Daily Reconciliation Engine. ZP0061 (GME -> ZeroPay, deadline ~05:00 KST) is the morning settlement request. It contains one summary record per merchant: merchant_id CHAR(10), settlement_date DATE(8) YYYYMMDD, gross_txn_count NUM(6), gross_txn_amount NUM(14) KRW total payout, refund_count NUM(6), refund_amount NUM(14), merchant_fee_total NUM(12), net_settlement_amount NUM(14), settlement_type CHAR(1) N=Net domestic / G=Gross international. File header: file_type=ZP0061, business_date, GME institution code, total record count, total payout KRW. File trailer: total record count (repeated), control sum (sum of all net_settlement_amount). Source data: query transactions table for APPROVED transactions with settlement_date = target KST date whose settlement_batch_status = SETTLEMENT_REGISTERED (confirmed by ZP0012). For domestic (LOCAL partner): net_settlement_amount = gross_txn_amount - merchant_fee_total. For international (OVERSEAS partner): net_settlement_amount = gross_txn_amount (gross; full payout). settlement_type = N for domestic, G for international.
**Steps:** Create Zp0061Generator.java in settlement.batch package; Implement generate(LocalDate kstDate): query eligible transactions grouped by merchant_id and partner_type; Build per-merchant summary records with all required fields; Compute control sum as sum of net_settlement_amount across all records; Assemble header + detail records + trailer into byte[]; Write to file named ZP0061_{YYYYMMDD}_01.dat; store metadata in settlement_file table; update settlement_batch status to GENERATED
**Deliverable:** Zp0061Generator.java producing correct fixed-width records with header/trailer
**Acceptance / logic checks:**
- Domestic merchant with 5 txns at KRW 10000 each, fee KRW 500 each: gross_txn_amount=50000, merchant_fee_total=2500, net_settlement_amount=47500, settlement_type=N
- International merchant with 3 txns at KRW 20000 each: gross_txn_amount=60000, merchant_fee_total=0, net_settlement_amount=60000, settlement_type=G
- File header total_record_count equals number of merchant summary rows
- File trailer control_sum equals sum of all net_settlement_amount values
- Transactions with status != SETTLEMENT_REGISTERED are excluded from the file
**Depends on:** 7.4-T06

### 7.4-T08 — Implement ZP0063 afternoon settlement request file generator  _(45 min)_
**Context:** WBS 7.4 Daily Reconciliation Engine. ZP0063 (GME -> ZeroPay, deadline ~14:00 KST) is the supplementary afternoon settlement request. It covers transactions approved after the ZP0061 cutoff for same-day merchant crediting (RATE-04 §12.5, SCH-06 §7.3). File layout is identical to ZP0061: merchant_id CHAR(10), settlement_date DATE(8), gross_txn_count NUM(6), gross_txn_amount NUM(14), refund_count NUM(6), refund_amount NUM(14), merchant_fee_total NUM(12), net_settlement_amount NUM(14), settlement_type CHAR(1). File is named ZP0063_{YYYYMMDD}_01.dat. May produce empty file (zero detail records) if no supplementary transactions exist. Must not re-include transactions already in ZP0061 for the same date. Scope boundary: transactions approved between ZP0061 cutoff and ZP0063 generation time that have SETTLEMENT_REGISTERED status.
**Steps:** Create Zp0063Generator.java extending or reusing logic from Zp0061Generator; Implement generate(LocalDate kstDate, OffsetDateTime morningCutoff): query SETTLEMENT_REGISTERED transactions approved after morningCutoff; Exclude transactions already included in a ZP0061 batch for the same date; Assemble file in same format as ZP0061 with file_type=ZP0063 in header; Handle zero-record case: produce header + empty body + trailer with count=0 and sum=0; Update settlement_batch record status to GENERATED after writing file
**Deliverable:** Zp0063Generator.java producing correct afternoon settlement request file
**Acceptance / logic checks:**
- Transaction included in ZP0061 batch is excluded from ZP0063 output
- Transactions approved after morning cutoff and SETTLEMENT_REGISTERED appear in ZP0063
- Zero-record case produces valid file with header record count=0 and trailer control_sum=0
- File header file_type field contains ZP0063 not ZP0061
- International transaction in ZP0063 has settlement_type=G
**Depends on:** 7.4-T07

### 7.4-T09 — Implement ZP0065 payment detail file generator  _(45 min)_
**Context:** WBS 7.4 Daily Reconciliation Engine. ZP0065 (GME -> ZeroPay, deadline ~22:00 KST) provides transaction-level detail for all payments in the settlement period. Enables ZeroPay to reconcile individual transactions against merchant totals from ZP0061/ZP0063. Per-record fields: merchant_id CHAR(10), zeropay_txn_ref CHAR(20), txn_date DATE(8), txn_time TIME(6) HHMMSS, payout_amount_krw NUM(12), merchant_fee_amt NUM(12), van_fee_amt NUM(10), partner_type CHAR(1) D=Domestic/I=International, settlement_batch_ref CHAR(20) linking back to ZP0061 or ZP0063 batch. payout_amount_krw maps to transaction.target_payout (KRW, 0 decimals, rounded half-up). File header: file_type=ZP0065, business_date, total_record_count. File trailer: total_record_count, control_sum (sum payout_amount_krw). All amounts are integer KRW (no decimal separator).
**Steps:** Create Zp0065Generator.java; Implement generate(LocalDate kstDate): query all SETTLEMENT_REGISTERED payment transactions for the date; For each transaction, look up the settlement_batch_ref from its ZP0061/ZP0063 batch; Build fixed-width detail record per transaction in specified field order; Compute header and trailer; write to ZP0065_{YYYYMMDD}_01.dat; Update settlement_batch record to GENERATED
**Deliverable:** Zp0065Generator.java producing correct line-item payment detail file
**Acceptance / logic checks:**
- Each detail record's payout_amount_krw matches the transaction's target_payout KRW value (integer, no decimal)
- settlement_batch_ref in each record points to correct ZP0061 or ZP0063 batch id
- partner_type=D for domestic (LOCAL partner) transactions, I for international (OVERSEAS) transactions
- File trailer control_sum equals sum of all payout_amount_krw across all detail records
- Refund transactions are excluded (ZP0065 covers payments only; refunds in ZP0066)
**Depends on:** 7.4-T08

### 7.4-T10 — Implement ZP0066 refund detail file generator  _(40 min)_
**Context:** WBS 7.4 Daily Reconciliation Engine. ZP0066 (GME -> ZeroPay, deadline ~22:00 KST) provides transaction-level detail for all refunds in the settlement period. Mirrors ZP0065 layout with refund-specific fields: merchant_id CHAR(10), original_zeropay_txn_ref CHAR(20), refund_date DATE(8), refund_amount_krw NUM(12), merchant_fee_adj_amt NUM(12), settlement_batch_ref CHAR(20). Only refunds processed after the same business day as the original payment are included (same-day cancels handled via real-time API, not batch). Refund transactions in table have status=REFUNDED and direction ties to the original transaction. File header file_type=ZP0066; trailer control_sum = sum of refund_amount_krw.
**Steps:** Create Zp0066Generator.java; Implement generate(LocalDate kstDate): query REFUNDED transactions where refund_date = kstDate and refund processed after original payment day; Build per-refund records with original_zeropay_txn_ref linked to original transaction; Compute header and trailer; write to ZP0066_{YYYYMMDD}_01.dat; Handle empty refund day: produce file with zero detail records; Update settlement_batch record to GENERATED
**Deliverable:** Zp0066Generator.java producing correct refund detail file
**Acceptance / logic checks:**
- Same-day cancels (CANCELLED status from real-time cancel API) are excluded from ZP0066
- Refund record original_zeropay_txn_ref matches the zeropay_txn_ref of the original payment transaction
- refund_amount_krw is positive integer (refund amount, not negative)
- Zero-refund day produces valid file with record_count=0 in header and trailer
- File trailer control_sum equals sum of all refund_amount_krw values
**Depends on:** 7.4-T07

### 7.4-T11 — Implement SFTP file transmission service with checksum and idempotency  _(55 min)_
**Context:** WBS 7.4 Daily Reconciliation Engine. Outbound SFTP transmission deposits files into /gmepay/outbound/ on the ZeroPay SFTP server. File naming convention: {FILE_ID}_{YYYYMMDD}_{SEQUENCE}.dat (e.g. ZP0061_20261015_01.dat). Retransmissions increment SEQUENCE to 02, 03, etc. SSH key authentication (RSA-4096 or ECDSA P-256); private key sourced from secrets store, never from disk. After successful PUT, compute SHA-256 of file content and store in settlement_file.file_checksum and settlement_batch.file_checksum. On failure, retry up to 3 times with exponential backoff. After 3 failures raise SFTP_TRANSMISSION_FAILURE alert to Ops. Update settlement_batch status to TRANSMITTED on success, ERROR on final failure. Transmission must be idempotent: if settlement_file record already exists for (batch_id, filename), skip re-upload unless force flag set.
**Steps:** Create SftpTransmissionService.java using JSch or Apache MINA SSHD library; Implement transmit(SettlementBatch batch, byte[] fileContent): compute SHA-256, build filename with sequence, PUT to /gmepay/outbound/; On success: create settlement_file record, update settlement_batch.file_checksum and status=TRANSMITTED; On failure: retry 3x with 5s/10s/20s backoff; after 3 failures alert Ops and set batch status=ERROR; Implement idempotency: check for existing settlement_file with same batch_id before transmitting; Unit test: mock SFTP client; verify correct filename for first attempt (seq=01) and retransmit (seq=02)
**Deliverable:** SftpTransmissionService.java with retry logic and settlement_file record creation
**Acceptance / logic checks:**
- First transmission produces filename ZP0061_20261015_01.dat
- Retransmission produces ZP0061_20261015_02.dat with incremented sequence
- SHA-256 checksum stored in both settlement_file.file_checksum and settlement_batch.file_checksum
- After 3 SFTP failures, settlement_batch.status=ERROR and AlertService invoked
- Idempotency: calling transmit when settlement_file already exists for batch_id skips upload and returns existing record
**Depends on:** 7.4-T06

### 7.4-T12 — Implement inbound ZP0062/ZP0064 settlement result file parser  _(55 min)_
**Context:** WBS 7.4 Daily Reconciliation Engine. ZP0062 (~10:00 KST) confirms ZeroPay's processing of ZP0061. ZP0064 (~19:00 KST) confirms ZP0063. Both contain per-merchant settlement status and amount credited. GMEPay+ polls /gmepay/inbound/ SFTP directory for these files. After download: compute SHA-256 and store in settlement_file; parse each merchant record; compare against settlement_batch totals. If per-merchant confirmed amount matches ZP0061/ZP0063 net_settlement_amount, mark batch record SETTLEMENT_CONFIRMED. If mismatch, mark SETTLEMENT_DISCREPANCY and raise exception record. Update settlement_batch status to RECEIVED on file receipt, RECONCILED when all merchants confirmed. Parsing must be idempotent: reprocessing same file must not create duplicate reconciliation_item rows.
**Steps:** Create ZpSettlementResultParser.java handling both ZP0062 and ZP0064; Implement parse(byte[] fileContent, Long batchId): extract per-merchant amount and status from file; For each merchant record: compare confirmed_amount vs expected net_settlement_amount from batch; On match: create reconciliation_item with match_status=MATCHED; on mismatch: match_status=DISCREPANCY with discrepancy_amount=|confirmed-expected|; Update settlement_batch to RECEIVED; if all records MATCHED set RECONCILED; any DISCREPANCY set status=ERROR and alert Ops; Idempotency: use INSERT ... ON CONFLICT DO NOTHING keyed on (batch_id, scheme_ref)
**Deliverable:** ZpSettlementResultParser.java with reconciliation_item creation and batch status update
**Acceptance / logic checks:**
- Confirmed amount matches expected: reconciliation_item.match_status=MATCHED, settlement_batch.status=RECONCILED
- Confirmed amount differs by KRW 1: reconciliation_item.match_status=DISCREPANCY, discrepancy_amount=1, batch.status=ERROR
- Reprocessing same ZP0062 file does not create duplicate reconciliation_item rows
- settlement_file record created with correct SHA-256 checksum on download
- Merchant in ZP0062 absent from GME ZP0061 records: reconciliation_item created with match_status=MISSING_GME
**Depends on:** 7.4-T07, 7.4-T03

### 7.4-T13 — Implement daily settlement aggregate reconciliation against ZP0065  _(50 min)_
**Context:** WBS 7.4 Daily Reconciliation Engine. RATE-04 §13.2 mandates daily check: sum of target_payout for all APPROVED transactions for a settlement day must match ZP0065 totals returned by ZeroPay (via ZP0062/ZP0064). Also: count of transactions in ZP0061/ZP0063 must match GMEPay+ outbound settlement count. Reconciliation logic: (1) Compute GME internal aggregate = SUM(target_payout KRW) and COUNT for each merchant for the date. (2) Parse ZP0065 file received from ZeroPay (or compare against ZP0062/ZP0064 totals). (3) For each merchant: if sum matches within tolerance=0 KRW (zero tolerance), mark MATCHED; if delta > 0, mark DISCREPANCY. (4) Create reconciliation_item rows. (5) If any DISCREPANCY, auto-flag to settlement exception queue (update resolution_status=UNRESOLVED).
**Steps:** Create SettlementAggregateReconService.java; Implement reconcile(LocalDate kstDate): query transaction table for APPROVED transactions on kstDate grouped by merchant_id; Compare GME internal aggregate vs ZP0065-sourced totals per merchant; Create reconciliation_item for each merchant with match_status and discrepancy_amount; For any DISCREPANCY: ensure settlement_batch.status=ERROR and reconciliation_item.resolution_status=UNRESOLVED; Unit test: 2 matching merchants + 1 with KRW 100 discrepancy; verify 3 reconciliation_items created with correct match statuses
**Deliverable:** SettlementAggregateReconService.java with unit tests
**Acceptance / logic checks:**
- Merchant with GME sum KRW 150000 and ZP0065 sum KRW 150000: MATCHED, discrepancy_amount=0
- Merchant with GME sum KRW 150000 and ZP0065 sum KRW 149900: DISCREPANCY, discrepancy_amount=100
- Transaction count mismatch triggers DISCREPANCY even when amounts match
- DISCREPANCY items have resolution_status=UNRESOLVED
- No APPROVED transactions for date: reconciliation completes with zero items created (not an error)
**Depends on:** 7.4-T12, 7.4-T05

### 7.4-T14 — Implement prefunding ledger daily reconciliation job  _(50 min)_
**Context:** WBS 7.4 Daily Reconciliation Engine. RATE-04 §13.3 requires daily reconciliation of OVERSEAS partner prefunding accounts: sum of all collection_usd DEBIT_PAYMENT entries in prefunding_ledger_entry for a partner on a given KST date + any CREDIT_TOPUP entries = net balance change. This net change must equal prefunding_account.balance_after_day - balance_before_day. Any delta > 0.01 USD triggers an ops alert. Tables involved: prefunding_account (balance DECIMAL(20,4)), prefunding_ledger_entry (account_id FK, entry_type VARCHAR(20) in {DEBIT_PAYMENT, DEBIT_REVERSAL, CREDIT_TOPUP, CREDIT_REVERSAL}, amount DECIMAL(20,4) always positive, balance_after DECIMAL(20,4)). Reconciliation: sum(DEBIT_PAYMENT + DEBIT_REVERSAL as negative) + sum(CREDIT_TOPUP + CREDIT_REVERSAL as positive) must equal (last balance_after on date) - (last balance_after before date).
**Steps:** Create PrefundingLedgerReconJob.java; Implement reconcilePartner(Long partnerId, LocalDate kstDate): query all ledger entries for partner on kstDate; sum debits and credits; Compute expected net change; compare to actual balance delta using opening and closing balance_after snapshots; If |delta| > 0.01 USD: call AlertService with partner_id, date, expected, actual values; Schedule as @Scheduled(cron='0 0 3 * * *', zone='Asia/Seoul') to run at 03:00 KST; Unit test: 3 DEBIT_PAYMENT at 36.97 USD + 1 CREDIT_TOPUP at 500 USD; verify net = 500 - 3*36.97 = 389.09 USD
**Deliverable:** PrefundingLedgerReconJob.java with unit tests
**Acceptance / logic checks:**
- 3 DEBIT_PAYMENT entries at 36.97 USD each + 1 CREDIT_TOPUP at 500 USD: computed net change = +389.09 USD; matches balance_after delta within 0.01 USD: no alert
- Delta of 0.01 USD exactly triggers alert (>= threshold)
- Delta of 0.0099 USD does not trigger alert (< threshold)
- LOCAL partners (no prefunding_account) are skipped without error
- Balance_after from last entry of day used as closing balance; last entry before day start used as opening balance
**Depends on:** 7.4-T03

### 7.4-T15 — Implement SFTP inbound file poller for ZP0012/ZP0022/ZP0062/ZP0064  _(55 min)_
**Context:** WBS 7.4 Daily Reconciliation Engine. GMEPay+ must poll /gmepay/inbound/ SFTP directory for expected inbound files. Expected files and windows (KST): ZP0012 ~05:00, ZP0022 ~05:00, ZP0062 ~10:00, ZP0064 ~19:00. Late threshold: expected window + 60 minutes; if file not present, raise BATCH_FILE_LATE alert to Ops. Poller checks every 5 minutes within a window (+/- 90 minutes around expected time) to reduce unnecessary SFTP connections. On file receipt: download, compute SHA-256, create settlement_file record with direction=INBOUND, update settlement_batch to RECEIVED, hand off to the relevant parser. Must be idempotent: if settlement_file record already exists for (filename, batch_id), skip re-download. SFTP connection reuses same SSH key auth as outbound (T11).
**Steps:** Create SftpInboundPoller.java with @Scheduled methods per file window; Implement pollForFile(String filePattern, LocalTime expectedKST, Long batchId): check SFTP inbound dir for matching filename; On file found: download bytes, compute SHA-256, create settlement_file, trigger parser; On late detection: elapsed > 60 min past expected and file not found: call AlertService.sendBatchFileLate(fileType, expectedTime); Implement idempotency check on settlement_file table before download; Unit test: mock SFTP; verify late alert fires after 61-minute overrun; verify idempotency skips re-download
**Deliverable:** SftpInboundPoller.java with scheduling and late-file alerting
**Acceptance / logic checks:**
- ZP0062 expected at 10:00 KST; mock shows file absent at 11:01 KST: AlertService.sendBatchFileLate called with fileType=ZP0062
- ZP0062 present at 10:05 KST: downloaded, SHA-256 computed, settlement_file record created with direction=INBOUND
- Second poll with same filename for same batch_id: idempotency check prevents duplicate download
- settlement_batch.status transitions from GENERATED/TRANSMITTED to RECEIVED on successful file receipt
- Poller does not run outside its activity window (e.g. ZP0062 poller is inactive at 02:00 KST)
**Depends on:** 7.4-T11, 7.4-T12

### 7.4-T16 — Implement ZP0012/ZP0022 registration result reconciliation service  _(55 min)_
**Context:** WBS 7.4 Daily Reconciliation Engine. After receiving ZP0012 (confirms ZP0011 payment registrations) or ZP0022 (confirms ZP0021 refund registrations), perform line-by-line match. ZP0012 match key: zeropay_txn_ref + txn_date. Conditions and actions: (1) result_code=00 and amounts match -> mark transaction SETTLEMENT_REGISTERED; create reconciliation_item MATCHED. (2) result_code non-zero -> mark transaction REGISTRATION_FAILED; reconciliation_item DISCREPANCY; alert P1. (3) GME record absent from ZP0012 -> mark REGISTRATION_UNKNOWN; reconciliation_item MISSING_SCHEME. (4) ZP0012 record absent from GME DB -> reconciliation_item MISSING_GME; alert P1 for investigation. (5) Amount mismatch (registered_amount != submitted payout_amount_krw) -> REGISTRATION_AMOUNT_MISMATCH; reconciliation_item DISCREPANCY. Registration failures blocking ZP0061 generation trigger immediate Ops alert.
**Steps:** Create RegistrationReconService.java handling both ZP0012 and ZP0022 paths; Implement reconcileRegistration(byte[] fileContent, Long batchId, String fileType): parse file; build map keyed by zeropay_txn_ref+txn_date; For each GME outbound record: look up in ZP0012 response map; classify and create reconciliation_item; For ZP0012 records not in GME outbound map: create MISSING_GME reconciliation_item; alert P1; Update transactions to SETTLEMENT_REGISTERED or REGISTRATION_FAILED accordingly; Return count of registration failures blocking settlement; caller (batch orchestrator) gates ZP0061 generation
**Deliverable:** RegistrationReconService.java with reconciliation_item creation and transaction status updates
**Acceptance / logic checks:**
- result_code=00 with matching amount: transaction.batch_status=SETTLEMENT_REGISTERED, reconciliation_item.match_status=MATCHED
- result_code=9002 (amount mismatch from ZeroPay): reconciliation_item.match_status=DISCREPANCY, transaction.batch_status=REGISTRATION_AMOUNT_MISMATCH
- GME record zeropay_txn_ref=TXN_X absent from ZP0012: reconciliation_item match_status=MISSING_SCHEME, no transaction status change
- ZP0012 record not found in GME DB: reconciliation_item match_status=MISSING_GME, AlertService.sendP1 called
- reconcileRegistration on identical file twice (idempotency): no duplicate reconciliation_item rows
**Depends on:** 7.4-T03, 7.4-T06

### 7.4-T17 — Implement daily batch orchestrator with job dependency enforcement  _(55 min)_
**Context:** WBS 7.4 Daily Reconciliation Engine. SCH-06 §8.2 mandates a strict dependency chain: ZP0061 generation requires successful ZP0012 receipt AND successful ZP0011 transmission. ZP0063 depends on morning cycle complete. ZP0065/ZP0066 depend on all settlements transmitted. ZP0061 refund portion requires successful ZP0022. Implement a DailySettlementOrchestrator that: (1) Triggers ZP0011/ZP0021 generation and SFTP transmission at 02:00 KST. (2) Polls for ZP0012/ZP0022; runs RegistrationReconService; gates ZP0061 generation if registration failures exist. (3) Generates and transmits ZP0061 at 05:00 KST only if no blocking failures. (4) At 14:00 KST generates ZP0063; at 22:00 KST generates ZP0065/ZP0066. Uses SettlementBatchService to check batch statuses before proceeding.
**Steps:** Create DailySettlementOrchestrator.java with @Scheduled methods at each key time; Implement step method for each window: nightly (02:00), morning (05:00), afternoon (14:00), detail (22:00); Each step checks prerequisite batch statuses via SettlementBatchRepository before proceeding; If prerequisites not met: log warning, update batch to ERROR, alert Ops; do not proceed; Implement isBlockedByRegistrationFailures(LocalDate date): returns true if any REGISTRATION_FAILED or MISSING_SCHEME reconciliation_items exist unresolved for the date; Unit test: registration failure present; verify ZP0061 generation is skipped and Ops alert fires
**Deliverable:** DailySettlementOrchestrator.java with dependency enforcement and unit tests
**Acceptance / logic checks:**
- Morning step with ZP0012 not yet RECEIVED: ZP0061 generation skipped; batch status set to ERROR; AlertService called
- Morning step with all prerequisites RECONCILED: Zp0061Generator.generate invoked
- ZP0061 generation with unresolved REGISTRATION_FAILED items for any merchant: blocked per-merchant; non-affected merchants proceed
- Afternoon step waits for morning cycle complete: if ZP0061 batch status != TRANSMITTED, ZP0063 generation deferred
- Detail step at 22:00 generates ZP0065 and ZP0066 regardless of afternoon result (detail covers full day)
**Depends on:** 7.4-T07, 7.4-T08, 7.4-T09, 7.4-T10, 7.4-T16

### 7.4-T18 — Implement UNCERTAIN transaction resolution via batch reconciliation  _(50 min)_
**Context:** WBS 7.4 Daily Reconciliation Engine. Transactions may be in UNCERTAIN state when scheme response was not received (timeout). SAD-02 §5.2 states: UNCERTAIN -> APPROVED if ZP0012 registration confirms success; UNCERTAIN -> FAILED if ZP0012 confirms failure; UNCERTAIN window = 24 hours. Prefunding deduction is held during UNCERTAIN state (not reversed). On FAILED confirmation: create a CREDIT_REVERSAL entry in prefunding_ledger_entry to restore the collection_usd amount to the partner's prefunding_account. On APPROVED confirmation: no debit change (already debited). Resolution must happen as part of RegistrationReconService (T16) when ZP0012 is processed. After 24 hours unresolved: escalate to P1 Ops alert.
**Steps:** Extend RegistrationReconService to detect UNCERTAIN transactions in ZP0012 processing; On result_code=00 for UNCERTAIN transaction: transition status to APPROVED; do not modify prefunding ledger (deduction stands); On result_code non-zero for UNCERTAIN transaction: transition to FAILED; create CREDIT_REVERSAL prefunding_ledger_entry for collection_usd amount; Implement UncertainTransactionEscalationJob: query UNCERTAIN transactions older than 24h; send P1 alert with txn_ref list; Unit test resolution scenarios: UNCERTAIN+result_code=00 -> APPROVED; UNCERTAIN+result_code=9001 -> FAILED + reversal ledger entry
**Deliverable:** Extended RegistrationReconService with UNCERTAIN resolution logic and UncertainTransactionEscalationJob
**Acceptance / logic checks:**
- UNCERTAIN transaction with ZP0012 result_code=00: status transitions to APPROVED; no new prefunding_ledger_entry created
- UNCERTAIN transaction with ZP0012 result_code=9001: status transitions to FAILED; CREDIT_REVERSAL entry created with amount = collection_usd; balance_after updated
- After CREDIT_REVERSAL: prefunding_account.balance increases by collection_usd (e.g. 36.9714 USD)
- UNCERTAIN transaction older than 24h triggers P1 alert with txn_ref
- Already-APPROVED transaction receiving duplicate ZP0012 confirmation is idempotent (no duplicate ledger entry)
**Depends on:** 7.4-T16, 7.4-T14

### 7.4-T19 — Implement exception queue persistence and Ops alert routing  _(45 min)_
**Context:** WBS 7.4 Daily Reconciliation Engine. SCH-06 §9.2 and §5.4 define exception categories. All reconciliation discrepancies create reconciliation_item rows with resolution_status=UNRESOLVED. Ops Admin portal (PRD-07) surfaces unresolved items. Recon engine must: (1) On creating a DISCREPANCY or MISSING_GME/MISSING_SCHEME reconciliation_item, also write an entry to the existing audit_log table (entity_type='reconciliation_item', action='CREATE', actor_type='SYSTEM'). (2) For P1 exceptions (registration failure, settlement amount mismatch, UNCERTAIN >24h), call AlertService.sendP1Alert with category, batch_id, details. (3) For P2 exceptions (late file, partial batch), call AlertService.sendP2Alert. AlertService interface already exists in codebase; implement the calls. Provide ExceptionQueueService with method listUnresolved(LocalDate date) returning all UNRESOLVED reconciliation_items for the date.
**Steps:** Create ExceptionQueueService.java with listUnresolved(LocalDate date) querying reconciliation_item WHERE resolution_status=UNRESOLVED AND created_at KST date; In RegistrationReconService and ZpSettlementResultParser: after creating DISCREPANCY item, call AlertService.sendP1Alert; Add audit_log insertion for every reconciliation_item creation (entity_type='reconciliation_item', action='CREATE', actor_id='SYSTEM'); Implement markResolved(Long itemId, String operatorId, String note): set resolution_status=RESOLVED, resolved_by, resolved_at; Unit test listUnresolved: 3 items on date, 1 RESOLVED; returns 2 items
**Deliverable:** ExceptionQueueService.java with listUnresolved, markResolved, and audit log integration
**Acceptance / logic checks:**
- Creating a DISCREPANCY reconciliation_item also creates an audit_log row with entity_type='reconciliation_item' and actor_type='SYSTEM'
- listUnresolved returns only UNRESOLVED items for the given date; RESOLVED items excluded
- P1 alert fires for REGISTRATION_FAILED exception; P2 alert fires for late file (no reconciliation_item for late files)
- markResolved sets resolution_status=RESOLVED, resolved_by=operator_id, resolved_at=now; audit_log updated
- markResolved on already-RESOLVED item is idempotent (no exception, no duplicate audit entry)
**Depends on:** 7.4-T16, 7.4-T12

### 7.4-T20 — Implement monthly revenue reconciliation computation  _(45 min)_
**Context:** WBS 7.4 Daily Reconciliation Engine. RATE-04 §13.4 requires monthly reconciliation: (1) FX margin revenue = SUM(collection_margin_usd + payout_margin_usd) for all APPROVED cross-border transactions in the month. (2) Service fee revenue = SUM(service_charge) converted to USD using cost_rate_coll at rate_locked_at. (3) Scheme fee share = 70% x ZeroPay's reported net merchant fee total for the month (source: external input by Ops). Result must be persisted or returned as a structured summary. All computations use BigDecimal. FX margin sums are straightforward (both fields already USD). Service charge may be in non-USD Settle A ccy; conversion: service_charge_usd = service_charge / cost_rate_coll (where cost_rate_coll is units of Settle A per 1 USD, so dividing converts to USD). For same-currency domestic transactions (collection_margin_usd=0, payout_margin_usd=0), service_charge is KRW; skip USD conversion for domestic (no USD-pool involvement).
**Steps:** Create MonthlyRevenueReconService.java; Implement computeForMonth(YearMonth month): query APPROVED transactions with rate_locked_at in month; Sum collection_margin_usd + payout_margin_usd for cross-border transactions; For each cross-border service charge: compute service_charge_usd = service_charge.divide(cost_rate_coll, 8, HALF_UP); Accept zeroPaySchemeFeeShareKRW parameter from Ops input; compute gmeFeeShare = zeroPaySchemeFeeShareKRW * 0.70; Return RevenueReconciliationResult{totalFxMarginUsd, totalServiceFeeUsd, schemeFeePct, gmeSchemeShareKRW, month}
**Deliverable:** MonthlyRevenueReconService.java with RevenueReconciliationResult DTO and unit tests
**Acceptance / logic checks:**
- 100 cross-border txns each with collection_margin_usd=0.3697 and payout_margin_usd=0.3697: totalFxMarginUsd = 100 * 0.7394 = 73.94 USD
- Service charge USD conversion: service_charge=0.36 USD with cost_rate_coll=1.0 -> service_charge_usd=0.36 (IDENTITY leg)
- Service charge conversion: service_charge=500 KRW with cost_rate_coll=1380: service_charge_usd = 500/1380 = 0.3623 USD (BigDecimal, 8dp)
- Domestic transactions (collection_margin_usd=0) contribute 0 to totalFxMarginUsd
- ZeroPay scheme fee KRW 10,000,000 total: gmeSchemeShareKRW = 7,000,000 (70%)
**Depends on:** 7.4-T05, 7.4-T14

### 7.4-T21 — Unit tests for ZP0061/ZP0062 settlement reconciliation round-trip  _(55 min)_
**Context:** WBS 7.4 Daily Reconciliation Engine. Test the full settlement reconciliation cycle from ZP0061 generation through ZP0062 result processing. Uses in-memory DB (H2 or test PostgreSQL container). Test scenario: 2 domestic merchants (LOCAL partner, net settlement) and 1 international merchant (OVERSEAS partner, gross settlement) with known transaction data. Verify ZP0061 file content, then process synthetic ZP0062 with all-matching amounts, verify RECONCILED outcome. Then test discrepancy: process ZP0062 with KRW 500 mismatch on one merchant; verify DISCREPANCY reconciliation_item created with discrepancy_amount=500 and batch status=ERROR.
**Steps:** Create Zp0061Zp0062ReconIntegrationTest.java using @SpringBootTest with test DB; Seed transactions: merchant M1 (domestic, 3 txns at KRW 10000 each), M2 (domestic, 2 txns), M3 (international, 5 txns at KRW 20000); Run Zp0061Generator.generate for the test date; parse generated file bytes; verify M1 net_settlement=29700 (30000 - 300 fee) and M3 net_settlement=100000 (gross); Process synthetic ZP0062 with all amounts matching; verify all 3 reconciliation_items MATCHED; settlement_batch status=RECONCILED; Process ZP0062 variant with M2 amount short by KRW 500; verify reconciliation_item for M2 DISCREPANCY, discrepancy_amount=500, batch status=ERROR
**Deliverable:** Zp0061Zp0062ReconIntegrationTest.java with 5+ test methods
**Acceptance / logic checks:**
- M1 net_settlement_amount in ZP0061 = gross_txn_amount - merchant_fee_total (net domestic rule)
- M3 net_settlement_amount in ZP0061 = gross_txn_amount (gross international rule)
- All-matching ZP0062: 3 MATCHED reconciliation_items; settlement_batch.status=RECONCILED
- KRW 500 mismatch: DISCREPANCY item with discrepancy_amount=500; resolution_status=UNRESOLVED
- settlement_batch.reconciled_at populated on RECONCILED transition; null on ERROR outcome
**Depends on:** 7.4-T12, 7.4-T07

### 7.4-T22 — Unit tests for prefunding ledger reconciliation with debit/credit vectors  _(45 min)_
**Context:** WBS 7.4 Daily Reconciliation Engine. Test PrefundingLedgerReconJob (T14) with concrete numeric vectors. Scenarios: (A) Clean day: partner starts with balance 1000.00 USD, 3 DEBIT_PAYMENT at 36.97 USD + 1 CREDIT_TOPUP at 200.00 USD -> expected net = 200 - 3*36.97 = 89.09 USD; closing balance = 1089.09 USD; delta = 0. (B) Discrepancy: same scenario but closing balance_after shows 1090.00 USD instead of 1089.09 USD -> delta = 0.91 USD > 0.01 threshold -> alert fires. (C) DEBIT_REVERSAL: UNCERTAIN transaction reversed -> CREDIT_REVERSAL entry 36.97 USD -> net = 200 - 2*36.97 + 36.97 = 163.06 USD net increase. (D) No entries for date: delta=0, no alert.
**Steps:** Create PrefundingLedgerReconJobTest.java; Seed prefunding_account and prefunding_ledger_entry rows for each scenario; Run reconcilePartner for each scenario; Capture AlertService calls using Mockito mock; Assert exact delta values and alert/no-alert outcomes for each vector
**Deliverable:** PrefundingLedgerReconJobTest.java with 4+ test scenarios
**Acceptance / logic checks:**
- Scenario A: delta = abs(89.09 - (1089.09 - 1000.00)) = 0 -> no alert
- Scenario B: delta = 0.91 USD > 0.01 -> AlertService called with partner_id, date, expected=89.09, actual=90.00
- Scenario C: CREDIT_REVERSAL correctly increases net change; delta=0 -> no alert
- Scenario D (no entries): opening and closing balance same -> delta=0 -> no alert
- All amounts use BigDecimal; no floating-point comparison (use compareTo, not ==)
**Depends on:** 7.4-T14

### 7.4-T23 — Unit tests for pool identity verifier with edge-case vectors  _(35 min)_
**Context:** WBS 7.4 Daily Reconciliation Engine. Exhaustive test suite for PoolIdentityVerifier (T04) covering all branches and numeric edge cases from RATE-04. Test vectors: (1) Cross-border inbound SendMN: collectionUsd=36.9714, collectionMarginUsd=0.3697, payoutMarginUsd=0.3697, payoutUsdCost=36.2319 -> delta=0.0001 -> PASS. (2) Identity legs: collectionUsd=102.0408, each margin=1.0204, payoutUsdCost=100.00 -> delta=0 -> PASS. (3) Boundary at 0.01: delta=0.01 exactly -> FAIL. (4) Boundary at 0.0099: delta=0.0099 -> PASS. (5) Same-currency (collectionUsd=null): PASS without NPE. (6) Programmatic error simulation: collectionUsd=100, margins=0.5 each, payoutUsdCost=90 -> delta=10 >> 0.01 -> FAIL with delta=10. All assertions use BigDecimal.compareTo.
**Steps:** Create PoolIdentityVerifierTest.java; Implement test method for each of the 6 vectors listed; Verify PASS/FAIL status and exact delta value using BigDecimal.compareTo(ZERO)==0 for delta in PASS cases; Verify FAIL result includes correct delta value as BigDecimal; Verify no NullPointerException for null collectionUsd input; Run tests; all must pass green
**Deliverable:** PoolIdentityVerifierTest.java with 6 test methods all passing
**Acceptance / logic checks:**
- Vector 1 (SendMN example): PASS, delta=0.0001
- Vector 3 (delta=0.01): FAIL returned, not PASS
- Vector 4 (delta=0.0099): PASS returned
- Vector 5 (same-currency null collectionUsd): PASS returned without NPE
- Vector 6 (delta=10 USD): FAIL returned with delta field = 10.00 (BigDecimal)
**Depends on:** 7.4-T04

### 7.4-T24 — Unit tests for UNCERTAIN transaction resolution via ZP0012  _(45 min)_
**Context:** WBS 7.4 Daily Reconciliation Engine. Test RegistrationReconService (T16/T18) UNCERTAIN resolution paths. Scenarios: (A) UNCERTAIN transaction receives ZP0012 result_code=00: status -> APPROVED, no prefunding reversal, reconciliation_item MATCHED. (B) UNCERTAIN transaction receives ZP0012 result_code=9001: status -> FAILED, CREDIT_REVERSAL prefunding_ledger_entry created for collection_usd=36.9714 USD, reconciliation_item DISCREPANCY. (C) UNCERTAIN >24h old with no ZP0012 record: escalation alert fires. (D) Idempotency: process same ZP0012 confirmation twice for same txn_ref; second call produces no duplicate ledger entry. (E) Already-APPROVED transaction in ZP0012: idempotent, no state change.
**Steps:** Create UncertainResolutionTest.java; Seed UNCERTAIN transactions with known collection_usd values and known created_at timestamps; Build synthetic ZP0012 byte content for each scenario; Call reconcileRegistration and verify outcomes; For scenario C: set txn created_at to 25h ago; run UncertainTransactionEscalationJob; verify P1 alert; For idempotency: call reconcileRegistration twice with same file; verify single ledger entry
**Deliverable:** UncertainResolutionTest.java with 5 test scenarios passing
**Acceptance / logic checks:**
- Scenario A: transaction.status=APPROVED; prefunding_ledger_entry count unchanged
- Scenario B: transaction.status=FAILED; exactly 1 CREDIT_REVERSAL entry with amount=36.9714 USD
- Scenario C: P1 alert contains txn_ref; transaction remains UNCERTAIN until manually resolved
- Scenario D: two calls produce exactly 1 reconciliation_item and 1 prefunding_ledger_entry (no duplicates)
- Scenario E: APPROVED transaction receiving duplicate ZP0012 confirmation does not change status or create ledger entry
**Depends on:** 7.4-T18

### 7.4-T25 — Unit tests for batch orchestrator dependency gate enforcement  _(40 min)_
**Context:** WBS 7.4 Daily Reconciliation Engine. Test DailySettlementOrchestrator (T17) dependency enforcement. Scenarios: (A) Morning step with ZP0012 batch status=RECEIVED but RegistrationReconService shows REGISTRATION_FAILED items: ZP0061 generation blocked; AlertService P1 called. (B) Morning step with all prerequisites RECONCILED and no failures: Zp0061Generator.generate called once. (C) Afternoon step with morning ZP0061 batch status=ERROR: ZP0063 generation deferred; P2 alert. (D) Detail step at 22:00: ZP0065Generator and ZP0066Generator both called regardless of afternoon result status. (E) Simulate missing ZP0012 (batch status=TRANSMITTED not yet RECEIVED at 05:01 KST): ZP0061 generation blocked.
**Steps:** Create DailySettlementOrchestratorTest.java with Mockito mocks for all generators and services; For each scenario: set up mock return values from SettlementBatchRepository and RegistrationReconService; Invoke the relevant orchestrator step method; Verify generator invocation or non-invocation using Mockito verify/verifyNoInteractions; Assert AlertService call count and type (P1 vs P2) for each failure scenario
**Deliverable:** DailySettlementOrchestratorTest.java with 5 test scenarios passing
**Acceptance / logic checks:**
- Scenario A: Zp0061Generator.generate never called; AlertService.sendP1Alert called once
- Scenario B: Zp0061Generator.generate called exactly once with correct kstDate
- Scenario C: Zp0063Generator.generate never called; AlertService.sendP2Alert called
- Scenario D: both Zp0065Generator.generate and Zp0066Generator.generate called regardless of ZP0063 status
- Scenario E: ZP0061 generation blocked when ZP0012 batch status != RECEIVED or RECONCILED
**Depends on:** 7.4-T17

### 7.4-T26 — Implement batch file late-arrival monitoring and alerting  _(45 min)_
**Context:** WBS 7.4 Daily Reconciliation Engine. SCH-06 §9.3: if expected inbound file not arrived within 60 minutes of expected window, raise BATCH_FILE_LATE alert (Ops portal + email/Slack). Dependent batch jobs enter WAITING state (not ERROR). Escalation: if early-window file (ZP0012, ZP0022) not arrived by 08:00 KST, escalate to ZeroPay account contact. Cutoffs (SCH-06 §9.5): ZP0012/ZP0022 inbound wait cutoff 06:00 KST; ZP0062 inbound wait cutoff 12:00 KST; ZP0064 inbound wait cutoff 21:00 KST. Implement BatchLatencyMonitorJob that runs every 5 minutes in each expected window and checks settlement_batch status. If batch in TRANSMITTED status (awaiting response) and now > expected_arrival + 60 min: trigger BATCH_FILE_LATE. If now > cutoff and still not RECEIVED: trigger escalation alert.
**Steps:** Create BatchLatencyMonitorJob.java with @Scheduled(fixedDelay=300000); Implement checkLatency(LocalDate kstDate): query settlement_batch rows with status=TRANSMITTED and direction=ZP_TO_GME; For each: compare current KST time against expected arrival + 60 min; if overdue and no alert yet: call AlertService.sendBatchFileLate; Compare against cutoff time; if past cutoff: call AlertService.sendEscalationAlert; Record alert sent flag (add boolean alert_sent to settlement_batch or use a separate batch_alert table) to avoid duplicate alerts; Unit test: ZP0062 batch TRANSMITTED, current time = 11:05 KST (61 min past ~10:00): alert fires; 11:59 same state: no duplicate alert
**Deliverable:** BatchLatencyMonitorJob.java with alert deduplication and escalation logic
**Acceptance / logic checks:**
- ZP0012 expected 05:00 KST; monitor at 06:01 KST with batch still TRANSMITTED: BATCH_FILE_LATE alert sent
- ZP0012 still TRANSMITTED at 06:01 KST (past 06:00 cutoff): escalation alert also sent
- Alert deduplication: running monitor twice in same overdue state sends alert only once
- ZP0062 batch received at 10:45 KST (within 60 min): no late alert generated
- Batch in RECEIVED status (file arrived): no alert regardless of time
**Depends on:** 7.4-T06, 7.4-T15

### 7.4-T27 — Add settlement and reconciliation Admin API endpoints (read-only)  _(55 min)_
**Context:** WBS 7.4 Daily Reconciliation Engine. PRD-07 Admin portal requires read access to settlement batch status and exception queue. Implement three Spring MVC REST endpoints (internal admin API, not partner-facing): (1) GET /internal/v1/settlement/batches?date={kstDate}&schemeId={id} -> list settlement_batch rows for date/scheme with status and totals. (2) GET /internal/v1/settlement/batches/{batchId}/reconciliation -> list reconciliation_item rows for the batch with match_status and resolution_status. (3) PATCH /internal/v1/settlement/reconciliation/{itemId}/resolve with body {resolved_by, resolution_note} -> calls ExceptionQueueService.markResolved. Auth: require ROLE_OPS (Spring Security). All responses use standard JSON envelope. Use BigDecimal for amount fields; never float.
**Steps:** Create SettlementAdminController.java in admin package; Implement GET /internal/v1/settlement/batches returning list of SettlementBatchDto; Implement GET /internal/v1/settlement/batches/{batchId}/reconciliation returning list of ReconciliationItemDto; Implement PATCH /internal/v1/settlement/reconciliation/{itemId}/resolve calling ExceptionQueueService.markResolved; Secure all endpoints with @PreAuthorize('hasRole(ROLE_OPS)'); Integration test: seed 2 batches + 3 reconciliation items; call each endpoint; verify response structure and data
**Deliverable:** SettlementAdminController.java with 3 endpoints and integration test
**Acceptance / logic checks:**
- GET batches returns correct settlement_date, status, transaction_count, total_amount for each batch
- GET reconciliation returns gme_amount, scheme_amount, discrepancy_amount, match_status, resolution_status for each item
- PATCH resolve with valid itemId and operator: returns 200; item resolution_status=RESOLVED in DB
- GET batches without ROLE_OPS returns HTTP 403
- PATCH resolve with non-existent itemId returns HTTP 404
**Depends on:** 7.4-T19, 7.4-T06

### 7.4-T28 — Write developer docs for reconciliation engine flow and runbook  _(40 min)_
**Context:** WBS 7.4 Daily Reconciliation Engine. Document the daily reconciliation flow for on-call developers: (1) Daily KST batch schedule with file sequence, dependencies, and error states. (2) How to manually re-trigger a failed batch step (retransmit outbound, reprocess inbound). (3) How to resolve a DISCREPANCY reconciliation_item via the Admin API. (4) Pool identity failure alert response: how to identify the offending txn_ref and escalate. (5) Prefunding ledger discrepancy: steps to identify source of delta. Keep to one markdown file in /docs/recon-engine.md within the service repo. Do not describe internal implementation; focus on ops-facing procedures. Include the complete KST batch timeline table from SCH-06.
**Steps:** Create docs/recon-engine.md in the service repository; Section 1: Daily batch timeline table (all ZP00xx files, KST times, direction, dependency); Section 2: Manual retransmission procedure (increment sequence, call SftpTransmissionService, re-run orchestrator step); Section 3: Resolving DISCREPANCY via PATCH /internal/v1/settlement/reconciliation/{itemId}/resolve; Section 4: Pool identity failure response checklist (find txn_ref, check rate-engine fields, escalate to Finance); Section 5: Prefunding ledger discrepancy checklist (query ledger entries for partner+date, compare to balance delta)
**Deliverable:** docs/recon-engine.md with 5 sections and KST timeline table
**Acceptance / logic checks:**
- KST timeline table includes all 10 ZP00xx files with correct times and directions matching SCH-06 §7.3
- Retransmission procedure specifies sequence increment and idempotency note
- DISCREPANCY resolution section references exact API endpoint PATCH /internal/v1/settlement/reconciliation/{itemId}/resolve
- Pool identity failure section identifies POOL_IDENTITY_FAILURE error code and lists collection_usd, payout_usd_cost, collection_margin_usd, payout_margin_usd as fields to inspect
- Document reviewed by another developer; no external spec references required to follow the procedures
**Depends on:** 7.4-T27, 7.4-T26


## WBS 7.6 — Gross settlement & monthly invoicing
### 7.6-T01 — Add gross_settlement_flag and settlement_model column to transaction table  _(30 min)_
**Context:** WBS 7.6 delivers gross settlement for international (OVERSEAS partner) ZeroPay transactions. In gross settlement GMEPay+ remits the full target_payout KRW to ZeroPay and invoices the merchant monthly. Domestic (LOCAL partner) uses net settlement where GME deducts its share first. The transaction table needs a settlement_model column to record which model applied at commit time so batch generation and invoicing queries are unambiguous. settlement_model is derived from partner.partner_type: LOCAL -> NET, OVERSEAS -> GROSS. Values must not change after commit (rate-lock principle applies to settlement model too).
**Steps:** Create migration db/migrations/V7_6_001__transaction_settlement_model.sql.; Add column settlement_model VARCHAR(5) NOT NULL DEFAULT 'NET' CHECK (settlement_model IN ('NET','GROSS')) to the transaction table.; Backfill existing rows: UPDATE transaction SET settlement_model = CASE WHEN partner_type = 'OVERSEAS' THEN 'GROSS' ELSE 'NET' END using a join on the partner table.; Add index on (settlement_model, committed_at::DATE, scheme_id) to support daily batch queries that filter by settlement model and date.; Add a table comment documenting: NET = domestic partners (LOCAL type); GROSS = cross-border partners (OVERSEAS type). Value is locked at commit and never updated.
**Deliverable:** Migration file db/migrations/V7_6_001__transaction_settlement_model.sql applying cleanly and backfilling all existing rows.
**Acceptance / logic checks:**
- Migration applies on a populated schema with no errors; column is NOT NULL after backfill.
- An attempt to INSERT a transaction row with settlement_model = 'HYBRID' is rejected by the CHECK constraint.
- All existing OVERSEAS partner transactions have settlement_model = 'GROSS' and all LOCAL partner transactions have settlement_model = 'NET' after backfill.
- Index on (settlement_model, committed_at::DATE, scheme_id) appears in EXPLAIN ANALYZE for a query filtering both columns.
- Rollback migration removes the column cleanly.

### 7.6-T02 — Create tax_invoice table migration (per DAT-03 schema)  _(35 min)_
**Context:** WBS 7.6 monthly invoicing requires a tax_invoice table (DAT-03 §8.3). One row per merchant per calendar month for cross-border transactions. Fields per spec: id BIGINT PK, merchant_id BIGINT FK->merchant, invoice_period DATE (first day of month), invoice_ref VARCHAR(64) UNIQUE (external tax invoice number, NULL until issued), total_transaction_amount_krw BIGINT (sum of target_payout KRW for the month), fee_rate DECIMAL(6,4) (merchant fee rate applied), merchant_fee_krw BIGINT (= total x fee_rate, BIGINT, floor), vat_krw BIGINT (= merchant_fee_krw x 0.10, floor), invoice_amount_krw BIGINT (= merchant_fee_krw + vat_krw), zeropay_share_krw BIGINT (= floor(merchant_fee_krw x 0.0021), the 0.21% ZP share paid by GME to ZeroPay monthly), status VARCHAR(20) CHECK IN ('DRAFT','ISSUED','COLLECTED','FAILED'), issued_at TIMESTAMPTZ NULL, collected_at TIMESTAMPTZ NULL, created_at/updated_at/created_by/updated_by standard audit.
**Steps:** Create migration db/migrations/V7_6_002__tax_invoice.sql.; Define the tax_invoice table with all columns and types exactly as specified in context.; Add UNIQUE constraint on (merchant_id, invoice_period) to prevent duplicate invoice drafts for the same merchant-month.; Add CHECK constraints: merchant_fee_krw >= 0, vat_krw >= 0, invoice_amount_krw = merchant_fee_krw + vat_krw, zeropay_share_krw >= 0, fee_rate BETWEEN 0.0000 AND 1.0000.; Add index on (invoice_period, status) for batch processing queries; add index on merchant_id for merchant-level lookups.
**Deliverable:** Migration file db/migrations/V7_6_002__tax_invoice.sql creating the tax_invoice table with all constraints.
**Acceptance / logic checks:**
- Migration applies cleanly on top of V7_6_001 with no errors.
- UNIQUE(merchant_id, invoice_period) rejects duplicate inserts for the same merchant-month combination.
- CHECK on invoice_amount_krw rejects a row where invoice_amount_krw != merchant_fee_krw + vat_krw (e.g. 1000+100 stored as 1200 is rejected).
- status column rejects values outside the defined enum (e.g. 'PENDING' is rejected).
- invoice_ref UNIQUE constraint rejects two rows with the same non-NULL invoice reference.
**Depends on:** 7.6-T01

### 7.6-T03 — Create gross_settlement_daily_aggregate view for ZP0061/ZP0063 batch generation  _(40 min)_
**Context:** WBS 7.6 gross settlement batch (ZP0061/ZP0063) must aggregate per-merchant daily totals. For GROSS settlement transactions (settlement_model='GROSS') the ZP0061 record contains: merchant_id, settlement_date, gross_txn_count (count of payments), gross_txn_amount (sum of target_payout KRW), refund_count, refund_amount, merchant_fee_total (0 for gross type), net_settlement_amount (= gross_txn_amount - refund_amount for gross type), settlement_type='G'. This view must only include transactions in state SETTLEMENT_REGISTERED (registration confirmed by ZP0012). Refunds are joined from the refund table. The view is used by the ZP0061/ZP0063 generator service.
**Steps:** Create migration db/migrations/V7_6_003__gross_settlement_daily_view.sql.; Define view gross_settlement_daily_aggregate as: SELECT t.merchant_id, t.committed_at::DATE AS settlement_date, COUNT(*) FILTER (WHERE t.txn_type='PAYMENT') AS gross_txn_count, COALESCE(SUM(t.target_payout_krw) FILTER (WHERE t.txn_type='PAYMENT'),0) AS gross_txn_amount, COUNT(*) FILTER (WHERE t.txn_type='REFUND') AS refund_count, COALESCE(SUM(t.target_payout_krw) FILTER (WHERE t.txn_type='REFUND'),0) AS refund_amount, 0::BIGINT AS merchant_fee_total, (SUM(t.target_payout_krw) FILTER (WHERE t.txn_type='PAYMENT') - COALESCE(SUM(t.target_payout_krw) FILTER (WHERE t.txn_type='REFUND'),0)) AS net_settlement_amount FROM transaction t WHERE t.settlement_model='GROSS' AND t.status='SETTLEMENT_REGISTERED' GROUP BY t.merchant_id, t.committed_at::DATE.; Add scheme_id to the GROUP BY and SELECT to allow per-scheme filtering.; Add a comment on the view explaining: merchant_fee_total is always 0 for GROSS type; GME does not deduct fees in the settlement batch.; Verify the view against the ZP0061 field mapping in context: gross_txn_count maps to gross_txn_count, settlement_type is a computed literal 'G'.
**Deliverable:** Migration V7_6_003__gross_settlement_daily_view.sql creating the gross_settlement_daily_aggregate view.
**Acceptance / logic checks:**
- Querying the view for a settlement_date with 3 GROSS SETTLEMENT_REGISTERED payment transactions and 1 refund returns gross_txn_count=3, refund_count=1, net_settlement_amount = sum(payments) - sum(refunds).
- Transactions with settlement_model='NET' do not appear in the view output.
- Transactions with status != 'SETTLEMENT_REGISTERED' (e.g. 'APPROVED') do not appear in the view.
- merchant_fee_total is always 0 for all rows returned.
- Two merchants on the same settlement_date appear as two distinct rows.
**Depends on:** 7.6-T01

### 7.6-T04 — Implement GrossSettlementCalculator: derive net_settlement_amount for ZP0061/ZP0063  _(30 min)_
**Context:** WBS 7.6 gross settlement math. For international transactions settlement_type='G': net_settlement_amount = gross_txn_amount - refund_amount (both in KRW BIGINT). Unlike net settlement, GME does NOT deduct its fee share from the settlement batch amount - the full target_payout goes to ZeroPay. GME recovers FX margin from prefunding at payment time (collection_usd debit) and invoices the merchant fee separately monthly. merchant_fee_total field in ZP0061 is 0 for gross type. Guard: net_settlement_amount must be >= 0 (if refunds exceed payments for a merchant on a day, hold the negative batch entry and alert Ops - do not submit negative amounts to ZeroPay).
**Steps:** Create class GrossSettlementCalculator in com.gme.gmepay.settlement.gross package.; Implement method GrossSettlementResult calculate(long grossTxnAmountKrw, long refundAmountKrw): returns GrossSettlementResult record with fields grossTxnAmountKrw, refundAmountKrw, netSettlementAmountKrw, merchantFeeTotalKrw (always 0), settlementTypeCode (always 'G').; Guard: if grossTxnAmountKrw < 0 or refundAmountKrw < 0, throw IllegalArgumentException.; Guard: if netSettlementAmountKrw < 0 (refunds exceed payments), throw NegativeSettlementAmountException(merchantId, settlementDate, netAmount) - caller must route to Ops alert.; Add Javadoc: 'For gross settlement (international/OVERSEAS partners), GME remits the full gross amount to ZeroPay. Merchant fee is NOT deducted here; it is invoiced monthly to the merchant via tax_invoice.'
**Deliverable:** GrossSettlementCalculator class and GrossSettlementResult record in com.gme.gmepay.settlement.gross package.
**Acceptance / logic checks:**
- calculate(50000, 0) returns netSettlementAmountKrw=50000, merchantFeeTotalKrw=0, settlementTypeCode='G'.
- calculate(50000, 5000) returns netSettlementAmountKrw=45000.
- calculate(5000, 10000) throws NegativeSettlementAmountException (refunds > payments for the day).
- calculate(-1, 0) throws IllegalArgumentException.
- merchantFeeTotalKrw is always 0 regardless of input amounts.
**Depends on:** 7.6-T01

### 7.6-T05 — Implement MonthlyMerchantFeeAggregator: compute per-merchant monthly invoice inputs  _(45 min)_
**Context:** WBS 7.6 monthly invoicing. At end-of-month (last calendar day KST), for each merchant that had GROSS settlement transactions during the month, aggregate: total_transaction_amount_krw = SUM(target_payout_krw) for all APPROVED/SETTLED transactions with settlement_model='GROSS' in that month. Then compute: merchant_fee_krw = floor(total_transaction_amount_krw x fee_rate); vat_krw = floor(merchant_fee_krw x 0.10); invoice_amount_krw = merchant_fee_krw + vat_krw; zeropay_share_krw = floor(merchant_fee_krw x 0.0021). fee_rate is read from the merchant's applicable scheme_fee_config row (fee_tier='CROSSBORDER', merchant_type from merchant record). The 0.21% ZP share is paid by GME to ZeroPay out of the monthly invoice cycle - it is not in the settlement batch files. Use BigDecimal with ROUND_DOWN throughout. Result is written to tax_invoice as status=DRAFT.
**Steps:** Create class MonthlyMerchantFeeAggregator in com.gme.gmepay.invoicing package.; Implement method List<TaxInvoiceDraft> aggregate(YearMonth period, long schemeId) that queries the transaction table for settlement_model='GROSS' AND committed_at within period AND scheme_id = schemeId, groups by merchant_id, and computes all fields.; For fee_rate resolution call FeeConfigRepository.findEffectiveConfig(schemeId, merchantType, 'CROSSBORDER', period.atEndOfMonth()).; Compute zeropay_share_krw = floor(merchant_fee_krw x BigDecimal('0.0021')).; Return a list of TaxInvoiceDraft value objects (merchant_id, invoice_period, total_transaction_amount_krw, fee_rate, merchant_fee_krw, vat_krw, invoice_amount_krw, zeropay_share_krw); do NOT write to DB in this class (persistence is T06).
**Deliverable:** MonthlyMerchantFeeAggregator class and TaxInvoiceDraft record in com.gme.gmepay.invoicing package.
**Acceptance / logic checks:**
- For merchant with total_transaction_amount_krw=1000000, fee_rate=0.0170: merchant_fee_krw=floor(1000000x0.0170)=17000, vat_krw=floor(17000x0.10)=1700, invoice_amount_krw=18700, zeropay_share_krw=floor(17000x0.0021)=35.
- For total_transaction_amount_krw=0 (no transactions): method returns empty list, not a zero-value draft.
- fee_rate is sourced from FeeConfigRepository using CROSSBORDER fee_tier and the merchant's actual merchant_type (not hardcoded).
- All arithmetic uses BigDecimal ROUND_DOWN; fractional KRW is always truncated, never rounded up.
- Merchants with only NET settlement transactions in the period are excluded from the result list.
**Depends on:** 7.6-T02, 7.2-T04

### 7.6-T06 — Implement TaxInvoicePersistenceService: upsert DRAFT invoices and transition status  _(45 min)_
**Context:** WBS 7.6 invoicing persistence. TaxInvoiceDraft objects produced by T05 must be written to tax_invoice table. Business rules: (1) If a DRAFT row already exists for (merchant_id, invoice_period), update its aggregated values (re-aggregation is idempotent before ISSUED). (2) Once status=ISSUED the row must not be overwritten - throw InvoiceAlreadyIssuedException. (3) Transitions: DRAFT->ISSUED (operator action or stub auto-issue), ISSUED->COLLECTED (payment confirmed), ISSUED->FAILED (collection failure). All transitions are audit-logged (created_by, updated_by, timestamps). Status DRAFT may be re-run for corrections. invoice_ref is set at ISSUED transition and must be UNIQUE and non-NULL.
**Steps:** Create class TaxInvoicePersistenceService in com.gme.gmepay.invoicing with Spring @Transactional.; Method upsertDraft(TaxInvoiceDraft draft, String operatorId): INSERT into tax_invoice with status=DRAFT or UPDATE all aggregation fields if row exists with status=DRAFT; throw InvoiceAlreadyIssuedException if status is ISSUED/COLLECTED/FAILED.; Method issueInvoice(long taxInvoiceId, String invoiceRef, String operatorId): set status=ISSUED, issued_at=now(), invoice_ref=invoiceRef, updated_by=operatorId; throw if already ISSUED.; Method markCollected(long taxInvoiceId, String operatorId): transition ISSUED->COLLECTED, set collected_at=now().; Method markFailed(long taxInvoiceId, String reason, String operatorId): transition ISSUED->FAILED, log reason to audit_log.
**Deliverable:** TaxInvoicePersistenceService class in com.gme.gmepay.invoicing with all five methods, transactional and audit-logged.
**Acceptance / logic checks:**
- upsertDraft called twice for same merchant+period with different total_transaction_amount_krw values updates the DRAFT row (idempotent upsert).
- upsertDraft on a row with status=ISSUED throws InvoiceAlreadyIssuedException; the ISSUED row is not modified.
- issueInvoice sets issued_at to a non-null timestamp and invoice_ref to the provided value.
- markCollected on an ISSUED invoice transitions to COLLECTED; calling it on a DRAFT invoice throws InvalidStateTransitionException.
- All state transitions write to audit_log with operator ID and before/after status values.
**Depends on:** 7.6-T02, 7.6-T05

### 7.6-T07 — Implement MonthlyInvoiceBatchJob: scheduled end-of-month aggregation and DRAFT creation  _(45 min)_
**Context:** WBS 7.6 requires automatic monthly invoice draft generation. The batch job runs on the last calendar day of each month at 23:00 KST (cron: '0 23 L * ?'). It calls MonthlyMerchantFeeAggregator.aggregate(currentMonth, schemeId) for each active ZeroPay scheme, then calls TaxInvoicePersistenceService.upsertDraft for each result. The job must be idempotent - if run twice on the same month it re-aggregates and updates existing DRAFT rows without creating duplicates. Job execution is recorded in a batch_job_log table with job_name='MONTHLY_INVOICE_DRAFT', run_date, status (STARTED/COMPLETED/FAILED), record_count, error_detail. Alert Ops via the monitoring system if job fails.
**Steps:** Create class MonthlyInvoiceBatchJob in com.gme.gmepay.invoicing.batch annotated with @Scheduled.; Set cron expression to run at 23:00 KST on the last day of each month; use ZoneId.of('Asia/Seoul') for KST scheduling.; Fetch all active scheme IDs from SchemeRepository where is_active=true.; For each scheme, call aggregator.aggregate(YearMonth.now(ZoneId.of('Asia/Seoul')), schemeId) and persist each draft via upsertDraft.; Log job start/end/count/errors to batch_job_log table; emit a monitoring alert on failure.
**Deliverable:** MonthlyInvoiceBatchJob Spring @Scheduled component with idempotent execution, KST scheduling, and batch_job_log writes.
**Acceptance / logic checks:**
- Job triggers on the last day of the month at 23:00 KST (verify via unit test with a mock clock set to 2026-01-31T23:00 KST).
- Running the job twice on the same month produces the same number of DRAFT rows as running it once (idempotency: no duplicate rows).
- batch_job_log shows status=COMPLETED and record_count > 0 after a successful run.
- If MonthlyMerchantFeeAggregator throws, the job catches the exception, writes status=FAILED to batch_job_log, and emits an alert without crashing the scheduler thread.
- Job skips merchants whose invoice for the month is already ISSUED (upsertDraft raises InvoiceAlreadyIssuedException which is caught and counted as skipped).
**Depends on:** 7.6-T05, 7.6-T06

### 7.6-T08 — Add gross settlement flag to ZP0061/ZP0063 file generation: settlement_type='G'  _(50 min)_
**Context:** WBS 7.6 requires the settlement batch files ZP0061 (morning, by ~05:00 KST) and ZP0063 (afternoon, by ~14:00 KST) to carry settlement_type='G' for gross records. Each merchant row in ZP0061 has fields: merchant_id CHAR(10), settlement_date DATE(8) YYYYMMDD, gross_txn_count NUM(6), gross_txn_amount NUM(14) KRW, refund_count NUM(6), refund_amount NUM(14) KRW, merchant_fee_total NUM(12) (=0 for gross type), net_settlement_amount NUM(14) KRW, settlement_type CHAR(1) N or G. The ZP0061Generator reads from the gross_settlement_daily_aggregate view (T03) and the net settlement equivalent for domestic transactions. File header contains: file_type ZP0061, business_date, GME institution code, total record count, total net_settlement_amount KRW. File is idempotent: regenerating for the same settlement_date replaces the previously generated file record.
**Steps:** Locate or create Zp0061FileGenerator in com.gme.gmepay.settlement.batch.; Add a method List<Zp0061Record> buildGrossRecords(LocalDate settlementDate, long schemeId) that reads from gross_settlement_daily_aggregate view filtered by settlement_date and scheme_id.; Map each view row to a Zp0061Record with settlement_type='G', merchant_fee_total=0, net_settlement_amount from view.; Merge gross records (type G) and net records (type N, from existing logic) into a single sorted list for the file.; Ensure the file header total_net_settlement_amount = SUM of all net_settlement_amount across both G and N records.
**Deliverable:** Updated Zp0061FileGenerator producing combined N+G records with correct settlement_type per row and accurate file header totals.
**Acceptance / logic checks:**
- A file generated for a date with 2 domestic merchants (NET) and 1 international merchant (GROSS) contains 3 records: 2 with settlement_type='N' and 1 with settlement_type='G'.
- For the GROSS record: merchant_fee_total=0, net_settlement_amount = gross_txn_amount - refund_amount.
- For the NET record: merchant_fee_total = GME fee deducted, net_settlement_amount = gross_txn_amount - refund_amount - merchant_fee_total.
- File header total_net_settlement_amount equals the sum of all individual net_settlement_amount values across N and G records.
- Regenerating the file for the same date produces an identical file (idempotent); no duplicate settlement_batch rows are created.
**Depends on:** 7.6-T03, 7.6-T04

### 7.6-T09 — Add settlement_type='G' support to ZP0065 payment detail file generator  _(45 min)_
**Context:** WBS 7.6 payment detail file ZP0065 (transmitted by ~22:00 KST) provides transaction-level detail underlying ZP0061/ZP0063 totals. Each detail record: merchant_id CHAR(10), zeropay_txn_ref CHAR(20), txn_date DATE(8), txn_time TIME(6), payout_amount_krw NUM(12), merchant_fee_amt NUM(12), van_fee_amt NUM(10), partner_type CHAR(1) D or I, settlement_batch_ref CHAR(20). For GROSS settlement records partner_type='I' (International). merchant_fee_amt and van_fee_amt must be populated from transaction_fee_snapshot (WBS 7.2) joined on txn_id; if no snapshot exists fall back to 0 with a warning log. settlement_batch_ref links back to the ZP0061/ZP0063 batch record.
**Steps:** Locate or create Zp0065FileGenerator in com.gme.gmepay.settlement.batch.; Query transactions with settlement_model='GROSS' AND status='SETTLEMENT_REGISTERED' for the settlement date, joined to transaction_fee_snapshot for merchant_fee_amt (gross_merchant_fee_krw) and van_fee_amt (van_fee_krw).; Set partner_type='I' for all GROSS records; 'D' for NET/domestic records.; Set settlement_batch_ref to the settlement_batch.id of the corresponding ZP0061 or ZP0063 batch record.; Log a WARN for any transaction where transaction_fee_snapshot row is missing; use 0 for fee amounts in that case and add to exception queue.
**Deliverable:** Updated Zp0065FileGenerator emitting partner_type='I' and correct fee amounts for gross settlement transaction records.
**Acceptance / logic checks:**
- A GROSS transaction with fee snapshot: payout_amount_krw=50000, gross_merchant_fee_krw=850, van_fee_krw=85 produces ZP0065 record with merchant_fee_amt=850, van_fee_amt=85, partner_type='I'.
- A domestic (NET) transaction produces partner_type='D' in the same file.
- A GROSS transaction with no fee snapshot produces merchant_fee_amt=0, van_fee_amt=0 and a WARN log entry; transaction is also added to the exception queue.
- settlement_batch_ref matches the id of the ZP0061 or ZP0063 settlement_batch row for that date.
- File record count matches the sum of gross_txn_count across all ZP0061 G-type records for that date.
**Depends on:** 7.6-T08, 7.2-T02

### 7.6-T10 — Implement GrossSettlementService: orchestrate daily gross settlement cycle  _(55 min)_
**Context:** WBS 7.6 orchestration. GrossSettlementService ties together: (1) Read gross_settlement_daily_aggregate view for the target settlement_date; (2) Run GrossSettlementCalculator per merchant; (3) Generate ZP0061 records via Zp0061FileGenerator; (4) Persist settlement_batch row with file_type='ZP0061', window='MORNING', status=GENERATED; (5) Hand off to SFTP uploader. The service is idempotent: if a settlement_batch row already exists for (scheme_id, file_type, settlement_date) with status >= GENERATED, skip re-generation and log a warning. The service is called by the batch scheduler at ~05:00 KST (after ZP0012 is received and processed). If any merchant has NegativeSettlementAmountException, exclude that merchant, create an exception record, alert Ops, and continue with the remaining merchants.
**Steps:** Create class GrossSettlementService in com.gme.gmepay.settlement.gross with @Transactional.; Method runMorningSettlement(LocalDate settlementDate, long schemeId): check idempotency, load aggregate view rows, compute per-merchant results, build Zp0061File, persist settlement_batch, submit to SFTP upload queue.; For each NegativeSettlementAmountException: insert a reconciliation_item with match_status='DISCREPANCY', resolution_status='UNRESOLVED', trigger Ops alert.; After successful SFTP upload, update settlement_batch.status to TRANSMITTED and set transmitted_at=now().; Log total merchant count, total net_settlement_amount KRW, and any excluded merchants to batch_job_log.
**Deliverable:** GrossSettlementService class orchestrating the morning gross settlement cycle with idempotency and exception handling.
**Acceptance / logic checks:**
- Calling runMorningSettlement twice for the same date and scheme produces one settlement_batch row (idempotent; second call logs warning and returns without re-generating).
- A merchant with refunds > payments triggers NegativeSettlementAmountException: the merchant is excluded from ZP0061, a reconciliation_item is created, and remaining merchants are settled normally.
- On successful SFTP upload, settlement_batch.status transitions to TRANSMITTED and transmitted_at is non-null.
- batch_job_log entry records total merchant count and total net_settlement_amount KRW after each run.
- If SFTP upload fails, settlement_batch.status remains GENERATED (not TRANSMITTED) and an error is logged - the job does not silently swallow the failure.
**Depends on:** 7.6-T04, 7.6-T08, 7.6-T03

### 7.6-T11 — Implement ZP0062/ZP0064 result file processor: update gross settlement status  _(50 min)_
**Context:** WBS 7.6 result file processing. ZeroPay returns ZP0062 (morning result, ~10:00 KST) and ZP0064 (afternoon result, ~19:00 KST) confirming merchant settlement status. Each result record contains: merchant_id, settlement_date, result_code CHAR(2) (00=success), credited_amount_krw NUM(14), result_message VARCHAR(100). For each result: if result_code=00 mark corresponding transaction rows as SETTLED; if non-00 flag as SETTLEMENT_FAILED and create reconciliation_item. Reconcile total credited_amount_krw against gross_settlement_daily_aggregate totals - any discrepancy (tolerance = 0 KRW for settlement files) creates a reconciliation_item with match_status='DISCREPANCY'. Update settlement_batch status to RECONCILED on zero-discrepancy, ERROR on any discrepancy.
**Steps:** Create class Zp0062ResultProcessor (reuse for ZP0064) in com.gme.gmepay.settlement.gross.; Method processResult(SettlementResultFile file, long schemeId): parse each merchant record, look up the corresponding settlement_batch by merchant+date+window.; For result_code=00: update all transaction rows for that merchant+date+settlement_model='GROSS' from SETTLEMENT_REGISTERED to SETTLED.; For result_code non-00: update transactions to SETTLEMENT_FAILED; create reconciliation_item; trigger P1 ops alert.; Compare file total credited_amount vs GME aggregate; if delta != 0 create reconciliation_item with DISCREPANCY and set settlement_batch.status=ERROR.
**Deliverable:** Zp0062ResultProcessor class handling ZP0062 and ZP0064 result files with zero-tolerance reconciliation and status updates.
**Acceptance / logic checks:**
- A merchant record with result_code='00' and credited_amount matching GME aggregate: all transactions for that merchant transition to SETTLED, settlement_batch for that merchant transitions to RECONCILED.
- A merchant record with result_code='01' (failure): transactions remain at SETTLEMENT_REGISTERED, a reconciliation_item is created with match_status='DISCREPANCY', Ops alert is emitted.
- A credited_amount that differs from the GME aggregate by even 1 KRW creates a reconciliation_item and sets settlement_batch.status=ERROR.
- Processing ZP0064 updates the AFTERNOON window settlement_batch separately from the MORNING window batch.
- Processing ZP0062 twice (retry scenario) is idempotent: already-SETTLED transactions are not double-updated; no duplicate reconciliation_items.
**Depends on:** 7.6-T10

### 7.6-T12 — Implement ZeropaySharePayableService: compute and record monthly 0.21% ZP share obligation  _(45 min)_
**Context:** WBS 7.6: GME pays ZeroPay 0.21% of the international merchant fee monthly. This is NOT in the settlement batch files (SCH-06 A-12). It is a business-level accounting entry from the monthly invoice cycle. zeropay_share_krw is already computed per invoice in TaxInvoiceDraft (T05) as floor(merchant_fee_krw x 0.0021). This service aggregates the total ZP share owed for a given month across all merchants and creates a payable ledger entry. The payable is recorded in a new table zeropay_monthly_payable: id BIGINT PK, period DATE, scheme_id BIGINT FK->qr_scheme, total_merchant_fee_krw BIGINT, total_zeropay_share_krw BIGINT, status VARCHAR(20) CHECK IN ('PENDING','PAID','DISPUTED'), created_at/updated_at/created_by/updated_by.
**Steps:** Create migration db/migrations/V7_6_004__zeropay_monthly_payable.sql: table zeropay_monthly_payable with columns from context; add UNIQUE(period, scheme_id).; Create class ZeropaySharePayableService in com.gme.gmepay.invoicing.; Method computeAndRecordPayable(YearMonth period, long schemeId, String operatorId): sum zeropay_share_krw and merchant_fee_krw from tax_invoice WHERE invoice_period=period AND scheme_id=schemeId AND status IN ('ISSUED','COLLECTED'); INSERT into zeropay_monthly_payable with status=PENDING (upsert if already PENDING).; Method markPaid(long payableId, String operatorId): transition PENDING->PAID, audit log.; Throw if total_zeropay_share_krw < 0.
**Deliverable:** Migration V7_6_004__zeropay_monthly_payable.sql and ZeropaySharePayableService class computing and recording the monthly ZP 0.21% share obligation.
**Acceptance / logic checks:**
- For 3 merchants with merchant_fee_krw values of 17000, 34000, 51000 (total=102000): total_zeropay_share_krw = floor(102000 x 0.0021) = 214.
- UNIQUE(period, scheme_id) prevents duplicate payable rows for the same month.
- computeAndRecordPayable excludes tax_invoice rows with status='DRAFT' (not yet issued); only ISSUED and COLLECTED rows count.
- markPaid on a PENDING payable transitions to PAID and writes to audit_log.
- markPaid on a non-PENDING payable throws InvalidStateTransitionException.
**Depends on:** 7.6-T06

### 7.6-T13 — Add Admin System API endpoint: GET /v1/admin/invoices?period=&merchant_id=&status=  _(40 min)_
**Context:** WBS 7.6 Admin System integration. Finance operators need to view monthly merchant fee invoices (UC-04-04). Admin System backend endpoint GET /v1/admin/invoices returns a paginated list of tax_invoice rows. Query params: period (YYYY-MM, required), merchant_id (optional), status (optional: DRAFT|ISSUED|COLLECTED|FAILED). Response fields per row: id, merchant_id, invoice_period, total_transaction_amount_krw, fee_rate, merchant_fee_krw, vat_krw, invoice_amount_krw, zeropay_share_krw, status, issued_at, collected_at. Only accessible to roles FINANCE_ANALYST, OPS_OPERATOR, SUPER_ADMIN. Returns HTTP 400 if period format is invalid; 403 if role is ADMIN_VIEWER.
**Steps:** Create AdminInvoiceController in com.gme.gmepay.admin.invoice with GET /v1/admin/invoices.; Parse period as YearMonth from YYYY-MM string; return 400 with error code INVALID_PERIOD_FORMAT if parsing fails.; Query tax_invoice with optional filters; paginate with page/pageSize params (default pageSize=50, max=200).; Apply RBAC: check caller role from JWT; reject ADMIN_VIEWER with 403 INSUFFICIENT_PERMISSIONS.; Return response DTO list with all fields from context; include pagination metadata (totalElements, page, pageSize).
**Deliverable:** AdminInvoiceController GET endpoint with RBAC, pagination, and query filtering, returning tax_invoice data.
**Acceptance / logic checks:**
- GET /v1/admin/invoices?period=2026-01 returns all DRAFT/ISSUED/COLLECTED/FAILED invoices for January 2026 with correct field values.
- period=2026-13 returns HTTP 400 with error code INVALID_PERIOD_FORMAT.
- Request with ADMIN_VIEWER JWT returns HTTP 403 INSUFFICIENT_PERMISSIONS.
- merchant_id filter returns only rows for that merchant; status=DRAFT filter returns only DRAFT rows.
- pageSize=200 returns at most 200 rows; pageSize=201 returns HTTP 400.
**Depends on:** 7.6-T06

### 7.6-T14 — Add Admin System API endpoint: POST /v1/admin/invoices/{id}/issue (stub for OI-02)  _(35 min)_
**Context:** WBS 7.6 invoice issuance. Operator triggers invoice issuance manually (Phase 1 stub per OI-02 - tax API not confirmed). POST /v1/admin/invoices/{id}/issue transitions tax_invoice from DRAFT to ISSUED. Request body: {invoice_ref: string} (the operator-provided external reference). Response: updated tax_invoice row. The endpoint is a stub: it does NOT call any external tax API; it simply sets status=ISSUED, issued_at=now(), invoice_ref from request, and records operator. A feature flag FEATURE_TAX_INVOICE_API (default false) gates future integration; when false, the stub path runs. RBAC: only OPS_OPERATOR or SUPER_ADMIN may issue invoices.
**Steps:** Create POST /v1/admin/invoices/{id}/issue in AdminInvoiceController.; Read feature flag FEATURE_TAX_INVOICE_API from config; if false, proceed with stub path (call TaxInvoicePersistenceService.issueInvoice directly).; If true (future): delegate to external TaxInvoiceApiClient (to be implemented when OI-02 resolved); return 501 NOT_IMPLEMENTED for now.; Validate invoice_ref is non-blank and <= 64 chars; return 400 INVALID_INVOICE_REF if not.; Return 404 if invoice id not found; 409 INVOICE_ALREADY_ISSUED if status != DRAFT.
**Deliverable:** POST /v1/admin/invoices/{id}/issue endpoint with stub issuance, feature flag gate, and RBAC.
**Acceptance / logic checks:**
- POST to a DRAFT invoice with valid invoice_ref returns 200 and the updated invoice with status=ISSUED, issued_at non-null.
- POST to an already ISSUED invoice returns 409 INVOICE_ALREADY_ISSUED.
- POST with ADMIN_VIEWER JWT returns 403.
- invoice_ref longer than 64 characters returns 400 INVALID_INVOICE_REF.
- With FEATURE_TAX_INVOICE_API=false (default), the endpoint succeeds as a stub; field invoice_ref is set from the request body.
**Depends on:** 7.6-T06, 7.6-T13

### 7.6-T15 — Add Admin System API endpoint: GET /v1/admin/settlement/gross?date=&scheme_id= (settlement summary)  _(40 min)_
**Context:** WBS 7.6 Admin System visibility. Finance operators must be able to view gross settlement totals for a given date (PRD-07 §11.2). Endpoint returns per-merchant gross settlement summary from the gross_settlement_daily_aggregate view. Response fields: settlement_date, merchant_id, gross_txn_count, gross_txn_amount_krw, refund_count, refund_amount_krw, net_settlement_amount_krw, settlement_status (from settlement_batch for that merchant+date). Also returns file-level status: ZP0061 status (GENERATED/TRANSMITTED/RECONCILED/ERROR), ZP0062 status (RECEIVED or NOT_RECEIVED). RBAC: FINANCE_ANALYST, OPS_OPERATOR, SUPER_ADMIN only.
**Steps:** Create AdminGrossSettlementController in com.gme.gmepay.admin.settlement.; GET /v1/admin/settlement/gross: parse date (YYYY-MM-DD) and scheme_id (required); return 400 for invalid date.; Query gross_settlement_daily_aggregate view for the given date and scheme_id.; For each merchant, join settlement_batch to get ZP0061 and ZP0062 statuses.; Return response list sorted by merchant_id; include a summary row with totals (total gross_txn_count, total net_settlement_amount_krw).
**Deliverable:** AdminGrossSettlementController GET endpoint returning per-merchant gross settlement summary with file statuses.
**Acceptance / logic checks:**
- GET /v1/admin/settlement/gross?date=2026-01-15&scheme_id=1 returns rows only for GROSS settlement_model transactions on that date.
- Each row includes ZP0061_status and ZP0062_status fields; ZP0062_status is NOT_RECEIVED if the result file has not yet been processed.
- Summary totals row: total_net_settlement_amount_krw = SUM of all individual net_settlement_amount_krw in the response.
- date=2026-01-32 returns HTTP 400 INVALID_DATE.
- Response is empty list (not 404) when no gross transactions exist for the given date.
**Depends on:** 7.6-T03, 7.6-T10

### 7.6-T16 — Add Admin System API endpoint: GET /v1/admin/invoices/summary?period= (monthly ZP share summary)  _(35 min)_
**Context:** WBS 7.6 Finance operator view of the monthly ZeroPay 0.21% share payable. Endpoint aggregates tax_invoice data for the period and shows total_merchant_fee_krw, total_zeropay_share_krw, total_invoice_amount_krw, and status breakdown (count by status). Also returns the zeropay_monthly_payable row for the period if it exists (showing total_zeropay_share_krw due and payment status PENDING/PAID/DISPUTED). This gives Finance a single view of what GME owes ZeroPay for the month. RBAC: FINANCE_ANALYST, OPS_OPERATOR, SUPER_ADMIN.
**Steps:** Add GET /v1/admin/invoices/summary to AdminInvoiceController.; Parse period as YearMonth from YYYY-MM; return 400 for invalid format.; Query tax_invoice aggregate: SUM(merchant_fee_krw), SUM(zeropay_share_krw), SUM(invoice_amount_krw) for the period; GROUP BY status to get status counts.; Query zeropay_monthly_payable for the period+scheme; include payable status and total_zeropay_share_krw.; Return summary DTO: period, total_merchants, total_transaction_amount_krw, total_merchant_fee_krw, total_vat_krw, total_invoice_amount_krw, total_zeropay_share_krw, status_counts map, payable (nullable).
**Deliverable:** GET /v1/admin/invoices/summary endpoint returning monthly aggregation of merchant fee invoices and ZP share payable.
**Acceptance / logic checks:**
- For a period with 3 ISSUED invoices with merchant_fee_krw 17000, 34000, 51000 (total 102000): total_zeropay_share_krw = 214 (floor(102000 x 0.0021)).
- status_counts map shows {DRAFT: 0, ISSUED: 3, COLLECTED: 0, FAILED: 0} for the example above.
- payable field is null if computeAndRecordPayable has not been called yet for the period.
- period=2026-13 returns 400 INVALID_PERIOD_FORMAT.
- ADMIN_VIEWER returns 403 INSUFFICIENT_PERMISSIONS.
**Depends on:** 7.6-T12, 7.6-T13

### 7.6-T17 — Unit tests: GrossSettlementCalculator edge cases  _(40 min)_
**Context:** WBS 7.6 unit testing for GrossSettlementCalculator (T04). Tests must cover: normal case, exact zero refunds, refunds equal payments (net=0), refunds exceed payments (negative net -> exception), zero payment amount, large KRW amounts (up to 10 billion). All via JUnit 5 / AssertJ; no DB or Spring context required.
**Steps:** Create GrossSettlementCalculatorTest in src/test/java/com/gme/gmepay/settlement/gross/.; Test normal: calculate(50000,5000) -> net=45000, merchantFeeTotal=0, type='G'.; Test zero refunds: calculate(50000,0) -> net=50000.; Test net exactly zero: calculate(10000,10000) -> should return net=0 (valid; ZeroPay receives 0 for that merchant that day).; Test negative net: calculate(5000,10000) -> throws NegativeSettlementAmountException; verify exception message contains both amounts.; Test illegal args: calculate(-1,0), calculate(0,-1) each throw IllegalArgumentException.; Test large values: calculate(10_000_000_000L, 1_000_000L) -> net=9_999_000_000L, no overflow.
**Deliverable:** GrossSettlementCalculatorTest with >= 7 test methods achieving 100% branch coverage of GrossSettlementCalculator.
**Acceptance / logic checks:**
- All 7 test methods pass with zero failures.
- NegativeSettlementAmountException is thrown when refundAmountKrw > grossTxnAmountKrw (not just >=).
- calculate(10000,10000) returns netSettlementAmountKrw=0 without throwing (zero is a valid gross settlement amount).
- merchantFeeTotalKrw is always 0 in every test case.
- Large-value test confirms no long overflow (use assertThat(result.netSettlementAmountKrw()).isEqualTo(9_999_000_000L)).
**Depends on:** 7.6-T04

### 7.6-T18 — Unit tests: MonthlyMerchantFeeAggregator calculation correctness  _(40 min)_
**Context:** WBS 7.6 unit testing for MonthlyMerchantFeeAggregator (T05). Tests must cover the fee arithmetic: total_transaction_amount_krw -> merchant_fee_krw -> vat_krw -> invoice_amount_krw -> zeropay_share_krw. Key formula: merchant_fee_krw = floor(total x fee_rate); vat_krw = floor(merchant_fee_krw x 0.10); invoice_amount_krw = merchant_fee_krw + vat_krw; zeropay_share_krw = floor(merchant_fee_krw x 0.0021). All arithmetic must use ROUND_DOWN. Test with fee_rate=0.0170 (ZeroPay crossborder minimum) and fee_rate=0.0220 (maximum). Mock FeeConfigRepository and transaction DB queries.
**Steps:** Create MonthlyMerchantFeeAggregatorTest in src/test/java/com/gme/gmepay/invoicing/.; Mock FeeConfigRepository to return a SchemeFeeConfig with fee_rate=0.0170.; Test vector A: total=1000000, fee_rate=0.0170 -> merchant_fee=17000, vat=1700, invoice_amount=18700, zeropay_share=35 (floor(17000x0.0021)=35).; Test vector B: total=1000000, fee_rate=0.0220 -> merchant_fee=22000, vat=2200, invoice_amount=24200, zeropay_share=46 (floor(22000x0.0021)=46).; Test rounding: total=100001, fee_rate=0.0170 -> merchant_fee=floor(100001x0.0170)=1700, not 1700.017.; Test empty period: if no GROSS transactions in period, aggregate returns empty list.
**Deliverable:** MonthlyMerchantFeeAggregatorTest with >= 6 test methods covering both fee rate extremes and rounding behaviour.
**Acceptance / logic checks:**
- Test vector A produces exact values: merchant_fee_krw=17000, vat_krw=1700, invoice_amount_krw=18700, zeropay_share_krw=35.
- Test vector B produces exact values: merchant_fee_krw=22000, vat_krw=2200, invoice_amount_krw=24200, zeropay_share_krw=46.
- Rounding test confirms floor is applied: total=100001 at fee_rate=0.0170 gives merchant_fee=1700 not 1701.
- Empty period returns Collections.emptyList() not a zero-value TaxInvoiceDraft.
- All mocked FeeConfigRepository calls use feeTier='CROSSBORDER' (not 'DOMESTIC').
**Depends on:** 7.6-T05

### 7.6-T19 — Unit tests: TaxInvoicePersistenceService state transitions  _(45 min)_
**Context:** WBS 7.6 unit testing for TaxInvoicePersistenceService (T06). Tests must verify all valid and invalid state transitions for tax_invoice: DRAFT->DRAFT (re-aggregate updates values), DRAFT->ISSUED, ISSUED->COLLECTED, ISSUED->FAILED, and illegal transitions (DRAFT->COLLECTED, COLLECTED->ISSUED, FAILED->ISSUED). Test that upsertDraft throws InvoiceAlreadyIssuedException if status is ISSUED/COLLECTED/FAILED. Use an in-memory H2 database or a Mockito-mocked repository.
**Steps:** Create TaxInvoicePersistenceServiceTest in src/test/java/com/gme/gmepay/invoicing/.; Test upsertDraft on a new merchant+period: creates DRAFT row with correct values.; Test upsertDraft on an existing DRAFT row: updates aggregation fields (idempotent).; Test upsertDraft on an ISSUED row: throws InvoiceAlreadyIssuedException; row is unchanged.; Test issueInvoice: DRAFT->ISSUED, issued_at is set, invoice_ref is set.; Test markCollected: ISSUED->COLLECTED, collected_at is set.; Test markFailed: ISSUED->FAILED, audit log entry created.; Test illegal: markCollected on a DRAFT throws InvalidStateTransitionException.
**Deliverable:** TaxInvoicePersistenceServiceTest with >= 8 test methods covering all valid transitions and at least 3 illegal transitions.
**Acceptance / logic checks:**
- upsertDraft twice with different total_transaction_amount_krw values leaves exactly one DRAFT row with the second value.
- upsertDraft on ISSUED row throws InvoiceAlreadyIssuedException and does not modify the row.
- After issueInvoice: status=ISSUED, issued_at non-null, invoice_ref matches the provided value.
- markCollected on DRAFT throws InvalidStateTransitionException.
- markFailed creates an audit_log entry with before_status=ISSUED and after_status=FAILED.
**Depends on:** 7.6-T06

### 7.6-T20 — Unit tests: ZeropaySharePayableService 0.21% computation  _(35 min)_
**Context:** WBS 7.6 unit testing for ZeropaySharePayableService (T12). Core assertion: total_zeropay_share_krw = floor(SUM(merchant_fee_krw) x 0.0021) across all ISSUED/COLLECTED invoices for the period. Tests: normal multi-merchant aggregation, single merchant, all-DRAFT invoices (excluded - payable should be 0 or empty), large monthly volume (total_merchant_fee 10000000 KRW -> zeropay_share=21000). Also test markPaid state transitions and UNIQUE constraint on period+scheme.
**Steps:** Create ZeropaySharePayableServiceTest in src/test/java/com/gme/gmepay/invoicing/.; Test 3-merchant case: merchant_fee values 17000+34000+51000=102000, zeropay_share=floor(102000x0.0021)=214.; Test all-DRAFT: computeAndRecordPayable with all invoices in DRAFT status returns payable with total_zeropay_share_krw=0 (no ISSUED/COLLECTED invoices counted).; Test large volume: total_merchant_fee=10000000, zeropay_share=floor(10000000x0.0021)=21000.; Test markPaid: PENDING->PAID valid; PENDING->PAID->PAID throws InvalidStateTransitionException.; Test UNIQUE: calling computeAndRecordPayable twice for same period upserts the same row (no duplicate).
**Deliverable:** ZeropaySharePayableServiceTest with >= 5 test methods covering aggregation correctness, DRAFT exclusion, and state transitions.
**Acceptance / logic checks:**
- 3-merchant test produces total_zeropay_share_krw=214 exactly.
- All-DRAFT invoices excluded: payable total_zeropay_share_krw=0 (or method returns empty/zero payable).
- Large volume: total_merchant_fee=10000000 gives zeropay_share=21000 (floor(10000000x0.0021)=21000).
- Second call to computeAndRecordPayable for same period produces one row, not two (upsert idempotency).
- markPaid on an already-PAID payable throws InvalidStateTransitionException.
**Depends on:** 7.6-T12

### 7.6-T21 — Integration test: full monthly gross settlement and invoicing cycle (ZP0061->ZP0062->tax_invoice)  _(60 min)_
**Context:** WBS 7.6 end-to-end integration test. Scenario: OVERSEAS partner SendMN has 5 KRW transactions in January 2026 at 3 different merchants (merchant A: 3 txns x 50000 KRW; merchant B: 2 txns x 100000 KRW); all APPROVED and SETTLEMENT_REGISTERED. Run: (1) GrossSettlementService.runMorningSettlement produces ZP0061 with 2 G-type merchant rows; (2) Process simulated ZP0062 result with result_code=00 for both merchants -> transactions transition to SETTLED; (3) MonthlyInvoiceBatchJob runs for January 2026 -> creates DRAFT tax_invoice rows for merchants A and B; (4) Operator issues invoice for merchant A (POST /issue); (5) ZeropaySharePayableService.computeAndRecordPayable records ZP share. Verify all state transitions and computed values. Use @SpringBootTest with TestContainers Postgres.
**Steps:** Create GrossSettlementIntegrationTest in src/test/java/com/gme/gmepay/settlement/gross/ with @SpringBootTest and TestContainers.; Seed: 5 transactions (3 for merchant A with target_payout_krw=50000, fee_rate=0.0170; 2 for merchant B with target_payout_krw=100000, fee_rate=0.0170); all settlement_model='GROSS', status='SETTLEMENT_REGISTERED'.; Assert ZP0061 file has 2 records with settlement_type='G'; merchant A net_settlement_amount=150000; merchant B=200000.; Simulate ZP0062 with result_code=00 for both; assert all 5 transactions in status SETTLED.; Assert tax_invoice DRAFT for merchant A: total_transaction_amount=150000, merchant_fee=floor(150000x0.0170)=2550, vat=255, invoice_amount=2805, zeropay_share=floor(2550x0.0021)=5.; Assert tax_invoice DRAFT for merchant B: total_transaction_amount=200000, merchant_fee=3400, vat=340, invoice_amount=3740, zeropay_share=7.; After issuing merchant A invoice: status=ISSUED, issued_at non-null.; Assert ZeropaySharePayableService payable: total_merchant_fee=2550 (ISSUED only), total_zeropay_share=5.
**Deliverable:** GrossSettlementIntegrationTest with >= 8 assertions covering the full cycle from transaction seeding to ZP share payable recording.
**Acceptance / logic checks:**
- ZP0061 merchant A record: gross_txn_count=3, gross_txn_amount=150000, net_settlement_amount=150000, settlement_type='G'.
- After ZP0062 processing: all 5 transactions in status SETTLED (not still SETTLEMENT_REGISTERED).
- tax_invoice DRAFT for merchant A: merchant_fee_krw=2550, vat_krw=255, invoice_amount_krw=2805, zeropay_share_krw=5.
- tax_invoice DRAFT for merchant B: merchant_fee_krw=3400, vat_krw=340, invoice_amount_krw=3740, zeropay_share_krw=7.
- ZeropaySharePayableService payable for January 2026 with merchant A ISSUED: total_zeropay_share_krw=5 (merchant B still DRAFT, excluded).
**Depends on:** 7.6-T10, 7.6-T11, 7.6-T07, 7.6-T12

### 7.6-T22 — Add Admin System settlement dashboard: gross settlement tab with ZP0061/ZP0062 status indicators  _(55 min)_
**Context:** WBS 7.6 Admin System UI (PRD-07 §11.2). The Settlement module needs a Gross Settlement tab showing: settlement_date picker, per-merchant table (merchant_id, gross_txn_count, gross_txn_amount_krw, refund_amount_krw, net_settlement_amount_krw, ZP0061 status, ZP0062 status), and a page-level summary (total net_settlement_amount_krw, file statuses). ZP0061 status chip: grey=PENDING, blue=GENERATED, green=RECONCILED, red=ERROR. ZP0062 status chip: grey=NOT_RECEIVED, green=RECEIVED. Backed by GET /v1/admin/settlement/gross (T15). RBAC: FINANCE_ANALYST read-only; OPS_OPERATOR can trigger manual re-transmission of ZP0061.
**Steps:** Create GrossSettlementTab React component in admin-ui/src/pages/settlement/.; Integrate with GET /v1/admin/settlement/gross API (T15); add date picker and auto-refresh every 5 minutes during batch windows (05:00-10:00 KST and 14:00-19:00 KST).; Render per-merchant table with columns from context; highlight rows with ZP0062 status ERROR in red.; Add summary bar at top: total_net_settlement_amount_krw formatted as KRW integer with comma separators, ZP0061 and ZP0062 status chips.; OPS_OPERATOR users see a Re-transmit button per settlement date; FINANCE_ANALYST sees read-only view; button calls POST /v1/admin/settlement/{date}/retransmit-zp0061 (stub endpoint).
**Deliverable:** GrossSettlementTab React component integrated with the settlement API, with status chips, table, and role-gated re-transmit button.
**Acceptance / logic checks:**
- Component renders per-merchant table rows from the API response with all required columns.
- ZP0061 status chip renders green when status=RECONCILED, red when status=ERROR, blue when GENERATED, grey when PENDING.
- FINANCE_ANALYST user does not see the Re-transmit button; OPS_OPERATOR user sees it.
- Total net_settlement_amount_krw in the summary bar matches the SUM of all per-merchant net_settlement_amount_krw values.
- Component auto-refreshes every 5 minutes without requiring user interaction; refresh does not reset the date picker value.
**Depends on:** 7.6-T15

### 7.6-T23 — Add Admin System invoice module: monthly invoice list and issue-invoice workflow (stub)  _(50 min)_
**Context:** WBS 7.6 Admin System UI for UC-04-04. Finance/Ops operators need an Invoice module under the Finance section. List view backed by GET /v1/admin/invoices (T13): columns merchant_id, invoice_period, total_transaction_amount_krw, merchant_fee_krw, vat_krw, invoice_amount_krw, zeropay_share_krw, status. Period picker defaults to current month. Status filter chips (ALL/DRAFT/ISSUED/COLLECTED/FAILED). Issue action: DRAFT row has Issue button (OPS_OPERATOR only) that opens a modal prompting for invoice_ref, then calls POST /v1/admin/invoices/{id}/issue (T14). On success, row status updates to ISSUED. Stub banner shown: 'Tax invoice API (OI-02) not yet confirmed - issuing manually.' per PRD-07 A-08.
**Steps:** Create InvoiceModule React component in admin-ui/src/pages/finance/invoices/.; Integrate with GET /v1/admin/invoices (T13) with period picker (defaulting to current YearMonth) and status filter.; Render the invoice table with all columns from context; format KRW values as integers with comma separators.; Add Issue button on DRAFT rows (only for OPS_OPERATOR/SUPER_ADMIN JWT roles); clicking opens IssueInvoiceModal with invoice_ref text input.; IssueInvoiceModal calls POST /v1/admin/invoices/{id}/issue; on success updates row to ISSUED and shows a banner 'Manually issued (Tax API OI-02 pending)'; on 409 shows 'Already issued'.
**Deliverable:** InvoiceModule React component with list view, period/status filtering, and stub issue-invoice modal workflow.
**Acceptance / logic checks:**
- Invoice list displays all tax_invoice rows for the selected period with correct KRW-formatted column values.
- Status filter chip DRAFT shows only DRAFT rows; ALL chip shows all statuses.
- FINANCE_ANALYST user sees no Issue button on DRAFT rows; OPS_OPERATOR sees Issue button.
- Successfully issuing an invoice updates the row status in the table from DRAFT to ISSUED without full page reload.
- Stub banner 'Manually issued (Tax API OI-02 pending)' appears on successful issue action.
**Depends on:** 7.6-T13, 7.6-T14

### 7.6-T24 — Add Admin System invoice summary widget: ZP share payable for the current month  _(45 min)_
**Context:** WBS 7.6 Admin System Finance dashboard. Add a ZeroPay Share Payable widget to the Finance dashboard backed by GET /v1/admin/invoices/summary (T16). Widget shows: current month period, total_merchant_fee_krw, total_zeropay_share_krw (formatted as KRW integer), status of the zeropay_monthly_payable (PENDING/PAID/DISPUTED badge), and a breakdown pie chart of invoice statuses (DRAFT/ISSUED/COLLECTED/FAILED counts). Finance Analyst and Ops Operator can view. A 'Mark Paid' button is shown when payable status=PENDING (FINANCE_ANALYST or OPS_OPERATOR); calls POST /v1/admin/zeropay-payable/{id}/mark-paid. Tooltip on zeropay_share_krw: 'GME pays 0.21% of total merchant fees to ZeroPay monthly'.
**Steps:** Create ZeropayShareWidget React component in admin-ui/src/pages/finance/dashboard/.; Integrate with GET /v1/admin/invoices/summary?period=YYYY-MM (T16); auto-update to current month on first load.; Render: period label, total_merchant_fee_krw (KRW format), total_zeropay_share_krw with tooltip, payable status badge.; Add a small donut chart (recharts or equivalent) showing invoice status breakdown from status_counts.; Mark Paid button visible when payable.status=PENDING; calls POST /v1/admin/zeropay-payable/{id}/mark-paid; on success badge changes to PAID.
**Deliverable:** ZeropayShareWidget React component integrated with the summary API, with KRW-formatted totals, status badge, donut chart, and Mark Paid button.
**Acceptance / logic checks:**
- Widget displays total_zeropay_share_krw as a KRW integer with comma separator (e.g. 21,000 not 21000.00).
- Tooltip on zeropay_share_krw contains text about the 0.21% rate.
- Donut chart segments match status_counts from the API (e.g. ISSUED=3 shows a segment proportional to 3).
- Mark Paid button only appears when payable.status=PENDING; after marking paid the badge updates to PAID without page reload.
- ADMIN_VIEWER role does not see the Mark Paid button.
**Depends on:** 7.6-T16


## WBS 9.8 — Reconciliation & exception handling
### 9.8-T01 — Add migration: settlement_batch status column and batch_file_status enum  _(25 min)_
**Context:** WBS 9.8 — Reconciliation and exception handling. The settlement_batch table (DAT-03) has status VARCHAR(20) CHECK IN ('PENDING','GENERATED','TRANSMITTED','RECEIVED','RECONCILED','ERROR'). A settlement_batch_item status must also reflect batch-processing outcomes: SETTLEMENT_REGISTERED, REGISTRATION_FAILED, REGISTRATION_UNKNOWN, REGISTRATION_AMOUNT_MISMATCH, SETTLEMENT_CONFIRMED, SETTLEMENT_DISCREPANCY, EXCEPTION_PENDING, EXCEPTION_RESOLVED. These status values are stored on the transaction record's settlement-linkage field and on reconciliation_item rows. Add DB enum type and confirm settlement_batch.status column matches spec.
**Steps:** Create Flyway migration V9_8_001__settlement_batch_status_enum.sql; Define PostgreSQL enum type batch_file_status with values: PENDING, GENERATED, TRANSMITTED, RECEIVED, RECONCILED, ERROR; Define PostgreSQL enum type batch_item_status with values: SETTLEMENT_REGISTERED, REGISTRATION_FAILED, REGISTRATION_UNKNOWN, REGISTRATION_AMOUNT_MISMATCH, SETTLEMENT_CONFIRMED, SETTLEMENT_DISCREPANCY, EXCEPTION_PENDING, EXCEPTION_RESOLVED; Confirm settlement_batch.status column uses or casts to batch_file_status; Add batch_item_status column to reconciliation_item if not present; default EXCEPTION_PENDING for new rows with mismatch
**Deliverable:** Flyway migration file V9_8_001__settlement_batch_status_enum.sql applied without error on clean schema
**Acceptance / logic checks:**
- Migration applies cleanly on a fresh schema and on a schema with existing settlement_batch rows
- SELECT enum_range(NULL::batch_file_status) returns exactly the 6 expected values
- SELECT enum_range(NULL::batch_item_status) returns exactly the 8 expected values
- settlement_batch.status column rejects any value outside the 6 valid states
- reconciliation_item.batch_item_status column exists and defaults to EXCEPTION_PENDING

### 9.8-T02 — Add migration: exception_record table for batch reconciliation exceptions  _(25 min)_
**Context:** WBS 9.8. The spec requires that every reconciliation discrepancy creates an exception record surfaced in the Ops Admin portal exception queue. There is no dedicated exception_record table defined in DAT-03 — the reconciliation_item table tracks match_status and resolution_status but is for settlement-level reconciliation. A separate exception_record table captures all categories: REGISTRATION_FAILED, REGISTRATION_UNKNOWN, REGISTRATION_AMOUNT_MISMATCH, SETTLEMENT_DISCREPANCY, SFTP transfer failures, partial batch, and rogue records. Fields: id (BIGINT PK), batch_id (FK settlement_batch nullable), txn_ref (VARCHAR(64) nullable), scheme_ref (VARCHAR(128) nullable), exception_type (VARCHAR(50) NOT NULL), priority (CHAR(2) NOT NULL CHECK IN ('P1','P2')), status (VARCHAR(20) NOT NULL DEFAULT 'EXCEPTION_PENDING'), detail (TEXT), resolution_note (TEXT), resolved_by (VARCHAR(120)), resolved_at (TIMESTAMPTZ), created_at (TIMESTAMPTZ NOT NULL DEFAULT NOW()), updated_at, created_by.
**Steps:** Create Flyway migration V9_8_002__exception_record.sql; Define exception_record table with all columns above; Add CHECK constraint on exception_type: values include REGISTRATION_FAILED, REGISTRATION_UNKNOWN, REGISTRATION_AMOUNT_MISMATCH, SFTP_TRANSFER_FAILURE, SFTP_RECEIVE_FAILURE, FILE_LATE, FILE_CORRUPT, PARTIAL_BATCH, SETTLEMENT_DISCREPANCY, ROGUE_RECORD; Add index on (status, priority) for Ops queue queries; Add index on txn_ref for fast lookup by transaction
**Deliverable:** Flyway migration V9_8_002__exception_record.sql that creates the exception_record table with correct constraints and indexes
**Acceptance / logic checks:**
- Migration applies cleanly; table has all 14 columns as specified
- Insert with status='EXCEPTION_PENDING', priority='P1', exception_type='REGISTRATION_FAILED' succeeds
- Insert with priority='P3' is rejected by CHECK constraint
- Index on (status, priority) exists and is used by EXPLAIN for a WHERE status='EXCEPTION_PENDING' AND priority='P1' query
- resolved_at remains NULL when status='EXCEPTION_PENDING'; NOT NULL enforcement is left to application layer
**Depends on:** 9.8-T01

### 9.8-T03 — Add migration: batch_late_alert and retransmission_log tables  _(25 min)_
**Context:** WBS 9.8. SCH-06 §9.3 requires that if an expected inbound file has not arrived by deadline + 60 minutes a BATCH_FILE_LATE alert is raised. SCH-06 §9.4 requires retransmitted outbound files to use an incremented sequence number in the filename (e.g. ZP0011_20261015_02.dat.pgp) and that reprocessing is idempotent. Two tables are needed: batch_late_alert (id BIGINT PK, file_type VARCHAR(10), settlement_date DATE, expected_by TIMESTAMPTZ, alerted_at TIMESTAMPTZ, resolved_at TIMESTAMPTZ nullable, created_at) and retransmission_log (id BIGINT PK, batch_id BIGINT FK settlement_batch, original_filename VARCHAR(255), retransmit_filename VARCHAR(255), sequence_number SMALLINT NOT NULL DEFAULT 2, reason TEXT, transmitted_at TIMESTAMPTZ, created_at).
**Steps:** Create Flyway migration V9_8_003__batch_late_alert_retransmission_log.sql; Define batch_late_alert table as specified; Define retransmission_log table as specified; Add UNIQUE constraint on (batch_id, sequence_number) in retransmission_log to prevent duplicate sequence numbers per batch; Add index on (file_type, settlement_date) in batch_late_alert
**Deliverable:** Flyway migration V9_8_003__batch_late_alert_retransmission_log.sql applied without error
**Acceptance / logic checks:**
- batch_late_alert table exists with expected columns; UNIQUE(file_type, settlement_date, alerted_at) prevents double alerts for same file
- retransmission_log UNIQUE(batch_id, sequence_number) prevents inserting two rows with the same sequence for the same batch
- sequence_number defaults to 2 (first retransmit is sequence 2; original is implicit 01)
- Index on (file_type, settlement_date) is present in batch_late_alert
**Depends on:** 9.8-T01

### 9.8-T04 — Add migration: transaction settlement linkage columns  _(25 min)_
**Context:** WBS 9.8. Per DAT-03 and SCH-06, a transaction record must track which settlement batch files it was included in and its current batch-processing status. Required new columns on the transaction table: settlement_batch_id (BIGINT FK nullable → settlement_batch, set atomically when a batch is generated to enforce exactly-once inclusion), batch_item_status (batch_item_status enum nullable), batch_registered_at (TIMESTAMPTZ nullable, set when SETTLEMENT_REGISTERED), batch_exception_id (BIGINT FK nullable → exception_record). The settlement_batch_id must be set atomically during ZP0061/ZP0063 generation (SELECT FOR UPDATE on the transaction row) to prevent double inclusion.
**Steps:** Create Flyway migration V9_8_004__transaction_settlement_linkage.sql; Add settlement_batch_id BIGINT nullable FK → settlement_batch(id) to transaction table; Add batch_item_status batch_item_status nullable to transaction table; Add batch_registered_at TIMESTAMPTZ nullable to transaction table; Add batch_exception_id BIGINT nullable FK → exception_record(id) to transaction table; Add index on settlement_batch_id for batch membership queries
**Deliverable:** Flyway migration V9_8_004__transaction_settlement_linkage.sql
**Acceptance / logic checks:**
- All four columns added to transaction table without error on a populated DB
- A transaction with settlement_batch_id set cannot be re-assigned to a second batch without an explicit UPDATE (no automatic constraint, but the atomic-generation logic depends on this column being non-null as a guard)
- Index on settlement_batch_id is present
- batch_item_status accepts only values from the batch_item_status enum
- FK to exception_record(id) is present and cascades to SET NULL on exception record deletion
**Depends on:** 9.8-T01, 9.8-T02

### 9.8-T05 — Implement ReconciliationMatchResult value object and ZP0012 line-by-line matcher  _(45 min)_
**Context:** WBS 9.8. After ZP0012 (payment registration result) is received, the batch job must perform a line-by-line match against ZP0011. Per SCH-06 §5.4, four outcomes exist: (1) result_code='00' and amounts match -> SETTLEMENT_REGISTERED; (2) result_code non-zero -> REGISTRATION_FAILED + exception; (3) ZP0011 record absent from ZP0012 -> REGISTRATION_UNKNOWN + exception; (4) ZP0012 record not in ZP0011 -> anomaly log + exception. (5) Amount discrepancy (registered != submitted) -> REGISTRATION_AMOUNT_MISMATCH + exception. Primary match key is zeropay_txn_ref + txn_date (both fields present in ZP0012 record layout). gme_txn_id is echoed in ZP0012 for cross-reference. Amounts are KRW integers (0 decimal places).
**Steps:** Create ReconciliationMatchResult enum: MATCHED, REGISTRATION_FAILED, REGISTRATION_UNKNOWN, AMOUNT_MISMATCH, ROGUE_RECORD; Create ZP0012Matcher class with method match(List<ZP0011Record> submitted, List<ZP0012Record> results): List<ReconciliationLineResult>; Index ZP0011 records by (zeropay_txn_ref, txn_date); index ZP0012 records by (zeropay_txn_ref, txn_date); For each ZP0011 record: look up in ZP0012 index; apply the 5-outcome rules above; For each ZP0012 record not matched to a ZP0011 record: emit ROGUE_RECORD result; Each ReconciliationLineResult carries: zeropay_txn_ref, txn_date, gme_txn_id, match_outcome (enum), submitted_amount, registered_amount, result_code, result_message
**Deliverable:** ZP0012Matcher class with complete outcome logic and ReconciliationLineResult value object
**Acceptance / logic checks:**
- Input: ZP0011 has record (ref=REF001, date=20261015, amount=50000 KRW); ZP0012 has matching record with result_code=00, registered_amount=50000 -> outcome MATCHED
- Input: same ZP0011 record; ZP0012 result_code=9001 -> outcome REGISTRATION_FAILED
- Input: ZP0011 record REF002 not present in ZP0012 at all -> outcome REGISTRATION_UNKNOWN
- Input: ZP0012 has record REF003 with no matching ZP0011 record -> outcome ROGUE_RECORD
- Input: ZP0012 result_code=00 but registered_amount=49000 vs submitted 50000 (KRW integer, difference=1000) -> outcome AMOUNT_MISMATCH
- All 5 outcomes handled; no unhandled cases; pure function with no DB calls
**Depends on:** 9.8-T01

### 9.8-T06 — Implement ZP0022 refund registration matcher (mirrors ZP0012 logic)  _(35 min)_
**Context:** WBS 9.8. SCH-06 §6.4 states refund mismatch handling is identical to payment mismatch handling (§5.4) but applied to refund records. ZP0022 mirrors ZP0012 layout with match key original_zeropay_txn_ref + refund_date. Fields in ZP0022: original_zeropay_txn_ref CHAR(20), gme_refund_id CHAR(20), result_code CHAR(2), result_message VARCHAR(100), registered_refund_amount NUM(12), adjustment_settlement_date DATE(8). The 5 outcomes from 9.8-T05 apply identically. An unresolved refund registration failure blocking settlement is a P1 ops incident.
**Steps:** Create ZP0022Matcher class implementing the same 5-outcome logic as ZP0012Matcher; Use match key (original_zeropay_txn_ref, refund_date) instead of (zeropay_txn_ref, txn_date); Reuse ReconciliationLineResult value object; add a recordType field (PAYMENT or REFUND); Ensure submitted amount from ZP0021 record is refund_amount_krw; registered amount from ZP0022 is registered_refund_amount; Write unit tests alongside the class
**Deliverable:** ZP0022Matcher class with 5-outcome logic
**Acceptance / logic checks:**
- Input: ZP0021 refund record (ref=RREF001, date=20261015, amount=25000 KRW); ZP0022 result_code=00, registered_refund_amount=25000 -> MATCHED
- Input: ZP0022 result_code=9001 for RREF001 -> REGISTRATION_FAILED
- Input: ZP0021 record RREF002 absent from ZP0022 -> REGISTRATION_UNKNOWN
- Input: ZP0022 has RREF003 not in ZP0021 -> ROGUE_RECORD
- Input: ZP0022 result_code=00, registered_refund_amount=24000 vs submitted 25000 -> AMOUNT_MISMATCH
- recordType field is REFUND for all ZP0022 results
**Depends on:** 9.8-T05

### 9.8-T07 — Implement ZP0062/ZP0064 settlement result matcher  _(45 min)_
**Context:** WBS 9.8. After ZP0062 (morning settlement result) or ZP0064 (afternoon settlement result) arrives, GMEPay+ must reconcile each merchant-level total against what was submitted in ZP0061/ZP0063. Tolerance is zero — every merchant total must match exactly (SCH-06 §9.1). ZP0061 contains per-merchant: merchant_id, gross_txn_count, gross_txn_amount (KRW), refund_count, refund_amount (KRW), net_settlement_amount (KRW). ZP0062 confirms the credited amount per merchant. Any discrepancy creates a SETTLEMENT_DISCREPANCY exception (P1, contact KFTC). Match key is merchant_id + settlement_date.
**Steps:** Create SettlementResultMatcher class with method match(List<ZP0061Record> submitted, List<ZP0062Record> results): List<SettlementMatchResult>; Index ZP0061 by merchant_id; index ZP0062 by merchant_id; For each ZP0061 record: look up ZP0062; compare net_settlement_amount; if missing -> MISSING_FROM_RESULT; if amounts differ -> SETTLEMENT_DISCREPANCY; if match -> SETTLEMENT_CONFIRMED; For each ZP0062 record not matched to ZP0061: emit ROGUE_SETTLEMENT_RECORD; Same class handles ZP0063/ZP0064 via a batchType parameter (MORNING or AFTERNOON); Each SettlementMatchResult carries: merchant_id, settlement_date, batch_type, match_status, submitted_amount, confirmed_amount, discrepancy_amount
**Deliverable:** SettlementResultMatcher class with SETTLEMENT_CONFIRMED, SETTLEMENT_DISCREPANCY, MISSING_FROM_RESULT, ROGUE_SETTLEMENT_RECORD outcomes
**Acceptance / logic checks:**
- merchant_id=M001, submitted net=100000 KRW, ZP0062 confirmed=100000 -> SETTLEMENT_CONFIRMED
- merchant_id=M002, submitted net=200000 KRW, ZP0062 confirmed=199000 -> SETTLEMENT_DISCREPANCY, discrepancy_amount=1000
- ZP0061 has M003 but ZP0062 does not -> MISSING_FROM_RESULT
- ZP0062 has M004 not in ZP0061 -> ROGUE_SETTLEMENT_RECORD
- batchType=AFTERNOON processes ZP0063 vs ZP0064 identically
- All amounts are KRW integers (DECIMAL scale 0); no floating-point comparison used
**Depends on:** 9.8-T05

### 9.8-T08 — Implement ZP0065/ZP0066 detail reconciliation against internal ledger  _(45 min)_
**Context:** WBS 9.8. SCH-06 §9.1 defines a third reconciliation level: ZP0065 (payment detail) vs internal ledger and ZP0066 (refund detail) vs internal ledger. Tolerance is zero — every line item must match. ZP0065 contains per-transaction: zeropay_txn_ref, txn_date, payout_amount_krw, merchant_id, partner_type (D/I), settlement_batch_ref. The internal ledger source is the transaction table's target_payout (KRW, scale 0) for transactions in status SETTLEMENT_REGISTERED with the matching settlement_batch_id. Discrepancy creates a reconciliation_item row with match_status=DISCREPANCY plus an exception_record.
**Steps:** Create DetailReconciliationMatcher class with method reconcilePayments(List<ZP0065Record> schemeDetail, List<TransactionLedgerEntry> internalEntries): List<ReconciliationItem>; Match on zeropay_txn_ref + txn_date; compare payout_amount_krw vs transaction.target_payout (both KRW integers); Outcomes: MATCHED, DISCREPANCY (amount mismatch), MISSING_GME (in ZP0065 but not internal), MISSING_SCHEME (in internal but not ZP0065); Add parallel method reconcileRefunds for ZP0066 vs refund records; Map each result to a reconciliation_item row (batch_id, txn_ref, scheme_ref, match_status, gme_amount, scheme_amount, discrepancy_amount, ccy='KRW')
**Deliverable:** DetailReconciliationMatcher class producing reconciliation_item entities
**Acceptance / logic checks:**
- ZP0065 ref=TX001 payout=50000, internal=50000 -> match_status=MATCHED, discrepancy_amount=0
- ZP0065 ref=TX002 payout=50000, internal=49500 -> DISCREPANCY, discrepancy_amount=500
- ZP0065 has TX003, internal has no matching entry -> MISSING_GME
- Internal has TX004, ZP0065 does not -> MISSING_SCHEME
- All reconciliation_item rows have ccy='KRW' and correct batch_id
- refund reconciliation uses the same 4 outcomes applied to refund amounts
**Depends on:** 9.8-T05

### 9.8-T09 — Implement UNCERTAIN transaction resolver triggered by ZP0012/ZP0022  _(55 min)_
**Context:** WBS 9.8. Per SAD-02 and SCH-06, UNCERTAIN transactions (scheme response timed out) must be resolved to APPROVED or FAILED within 24 hours via ZP0012/ZP0022. On receipt of ZP0012, the Settlement Engine looks up all UNCERTAIN transactions for that business date by cross-referencing zeropay_txn_ref in the ZP0012 result. If result_code=00 -> transition to APPROVED and dispatch payment.approved webhook. If result_code non-zero -> transition to FAILED and reverse prefunding deduction for OVERSEAS partners (SELECT FOR UPDATE on prefunding ledger). If ZP0012 has no record for an UNCERTAIN transaction -> the transaction remains UNCERTAIN and is flagged for the next cycle up to 24 h; beyond 24 h it is escalated as a P1 exception.
**Steps:** Create UncertainTransactionResolver class with method resolve(List<ZP0012Record> results, Date businessDate); Query DB for all transactions with status=UNCERTAIN and scheme batch date = businessDate; For each UNCERTAIN transaction: look up zeropay_txn_ref in ZP0012 results; If result_code=00: call TransactionOrchestrator.transitionToApproved(txn_ref); dispatch webhook; If result_code non-zero: call TransactionOrchestrator.transitionToFailed(txn_ref); reverse prefunding deduction (OVERSEAS only) using SELECT FOR UPDATE; If no ZP0012 record found: check if transaction is > 24 h old; if yes, create P1 exception_record with type REGISTRATION_UNKNOWN; otherwise leave UNCERTAIN; Wrap each resolution in a database transaction; ensure atomicity of status update + prefunding reversal
**Deliverable:** UncertainTransactionResolver class with full resolve logic
**Acceptance / logic checks:**
- UNCERTAIN transaction with zeropay_txn_ref present in ZP0012 result_code=00 transitions to APPROVED and webhook dispatched
- UNCERTAIN transaction with ZP0012 result_code=9001 transitions to FAILED; OVERSEAS partner prefunding deduction reversed atomically
- UNCERTAIN transaction absent from ZP0012 and age < 24 h remains UNCERTAIN; no exception raised
- UNCERTAIN transaction absent from ZP0012 and age > 24 h creates exception_record with type=REGISTRATION_UNKNOWN, priority=P1
- Prefunding reversal uses SELECT FOR UPDATE to prevent concurrent modification
- Resolve method is idempotent: reprocessing the same ZP0012 does not double-approve or double-reverse
**Depends on:** 9.8-T04, 9.8-T05

### 9.8-T10 — Implement ExceptionRecordService: create, surface, and resolve exception records  _(50 min)_
**Context:** WBS 9.8. Every reconciliation discrepancy must create an exception_record (see 9.8-T02) and surface it in the Ops Admin portal exception queue. ExceptionRecordService must: (a) create an exception_record from a ReconciliationLineResult or SettlementMatchResult, setting priority P1 for all registration/settlement failures; (b) bulk-query open exceptions by status=EXCEPTION_PENDING ordered by priority then created_at for the Ops queue API; (c) resolve an exception (set status=EXCEPTION_RESOLVED, resolved_by, resolved_at, resolution_note); (d) escalate to ESCALATED when a P1 exception remains unresolved beyond a configurable window (default 60 min for registration failures that block ZP0061 generation).
**Steps:** Create ExceptionRecordService with methods: createFromReconciliationResult(result, batchId), createFromSettlementResult(result, batchId), listOpenExceptions(page, pageSize): Page<ExceptionRecord>, resolveException(id, resolvedBy, note), escalateStaleExceptions(cutoffMinutes); createFrom* methods derive exception_type and priority from the result's match_outcome field; listOpenExceptions returns status=EXCEPTION_PENDING ordered by priority ASC, created_at ASC; resolveException sets status=EXCEPTION_RESOLVED, resolved_at=now(), resolved_by, resolution_note; throws if exception already EXCEPTION_RESOLVED; escalateStaleExceptions queries for EXCEPTION_PENDING + priority=P1 + created_at < now()-cutoffMinutes; transitions to EXCEPTION_ESCALATED (add this value to exception status CHECK constraint)
**Deliverable:** ExceptionRecordService class with full CRUD and escalation logic
**Acceptance / logic checks:**
- createFromReconciliationResult with outcome REGISTRATION_FAILED creates row with exception_type=REGISTRATION_FAILED, priority=P1
- createFromSettlementResult with outcome SETTLEMENT_DISCREPANCY creates row with exception_type=SETTLEMENT_DISCREPANCY, priority=P1
- listOpenExceptions returns P1 items before P2 items; items ordered by created_at within same priority
- resolveException on an already-resolved exception throws IllegalStateException
- escalateStaleExceptions with cutoff 60 min escalates a P1 exception that is 90 min old; leaves a 30-min-old exception untouched
**Depends on:** 9.8-T02, 9.8-T05, 9.8-T07

### 9.8-T11 — Implement settlement-blocking guard: hold ZP0061 generation when exceptions are unresolved  _(50 min)_
**Context:** WBS 9.8. SCH-06 §9.2 and §8.2 state that if ZP0012 or ZP0022 indicate registration failures, settlement generation for affected transactions must be held until the exception is resolved by Ops. Non-affected transactions proceed normally. This guard must check, before including a transaction in ZP0061/ZP0063 batch generation, that the transaction does not have an open exception_record (status=EXCEPTION_PENDING or EXCEPTION_ESCALATED). Affected transactions are excluded from the batch with their batch_item_status set to EXCEPTION_PENDING. The generated batch file covers only non-blocked transactions.
**Steps:** In the ZP0061 batch generation service, add a pre-generation query: SELECT t.id FROM transaction t WHERE t.settlement_date = ? AND t.batch_exception_id IS NOT NULL AND EXISTS (SELECT 1 FROM exception_record e WHERE e.id = t.batch_exception_id AND e.status IN ('EXCEPTION_PENDING','EXCEPTION_ESCALATED')); Exclude these transaction IDs from the ZP0061 batch; set their batch_item_status=EXCEPTION_PENDING; For the remaining transactions, proceed with batch generation and set settlement_batch_id atomically; After batch generation, call ExceptionRecordService.createFromReconciliationResult for any transactions excluded due to existing exceptions if not already recorded; Log the count of blocked vs included transactions in the batch generation audit trail
**Deliverable:** Settlement-blocking guard integrated into ZP0061/ZP0063 batch generation service
**Acceptance / logic checks:**
- Batch generated with 5 transactions, 1 of which has an open P1 exception: ZP0061 contains 4 transactions; the blocked transaction's batch_item_status=EXCEPTION_PENDING
- After Ops resolves the exception (status=EXCEPTION_RESOLVED), re-running generation includes the previously blocked transaction in the next batch
- Batch generation is idempotent: re-running does not add a transaction to two batches (settlement_batch_id uniqueness)
- Non-affected transactions are never delayed by other transactions' exceptions
- Count of blocked transactions is logged with the settlement_batch record
**Depends on:** 9.8-T04, 9.8-T09, 9.8-T10

### 9.8-T12 — Implement late-file monitor: raise BATCH_FILE_LATE alert when inbound deadline + 60 min passes  _(50 min)_
**Context:** WBS 9.8. SCH-06 §9.3: if an expected inbound file has not arrived by deadline + 60 minutes, raise a BATCH_FILE_LATE alert and place dependent batch jobs in WAITING state. Deadlines from SCH-06 §9.5: ZP0012/ZP0022 expected ~05:00 KST, P1 escalation cutoff 06:00 KST; ZP0062 expected ~10:00 KST, cutoff 12:00 KST; ZP0064 expected ~19:00 KST, cutoff 21:00 KST. A scheduled monitor runs every 10 minutes and checks whether expected inbound files have arrived (status=RECEIVED) for today's settlement_date.
**Steps:** Create BatchLateFileMonitor scheduled service running every 10 minutes; For each expected inbound file type (ZP0012, ZP0022, ZP0062, ZP0064): query settlement_batch for today's settlement_date and status != RECEIVED; If current time (KST) > expected_arrival + 60 min and no batch_late_alert row exists for (file_type, settlement_date): insert batch_late_alert and emit ops alert (notification + email); If current time > P1 cutoff: re-alert and set alert priority=P1 in batch_late_alert; Set settlement_batch.status=ERROR and dependent batch job statuses to WAITING when file is late; Dependent jobs resume automatically when the late file is received and processed
**Deliverable:** BatchLateFileMonitor service with 10-minute schedule and alert creation logic
**Acceptance / logic checks:**
- ZP0012 not received by 06:00 KST on a given settlement_date: batch_late_alert row created with alerted_at close to 06:00; settlement_batch.status=ERROR; dependent ZP0061 generation job in WAITING
- Running the monitor twice within 10 minutes does not create a duplicate batch_late_alert for the same (file_type, settlement_date)
- ZP0062 not received by 12:00 KST: alert created with P2 priority
- After ZP0012 arrives and status=RECEIVED: monitor marks batch_late_alert.resolved_at; dependent jobs resume from WAITING
- Monitor uses KST timezone (Asia/Seoul) for all deadline comparisons
**Depends on:** 9.8-T03, 9.8-T10

### 9.8-T13 — Implement outbound file retransmission service with sequence-number increment  _(55 min)_
**Context:** WBS 9.8. SCH-06 §9.4: for outbound retransmission, GMEPay+ retransmits with an incremented sequence number in the filename, e.g. ZP0011_20261015_02.dat.pgp (original was 01). File content is regenerated for the same business date. Retransmitted files are processed identically to originals. DB upsert logic must be idempotent. The retransmission_log table (9.8-T03) records each retransmit event. Trigger: Ops clicks 'Retransmit' in Admin portal, or the batch scheduler triggers after 3 failed SFTP uploads (each 5 minutes apart).
**Steps:** Create RetransmissionService with method retransmit(batchId: Long, reason: String, triggeredBy: String): RetransmissionResult; Load the original settlement_batch row; verify status=ERROR or TRANSMITTED; Determine next sequence number: query retransmission_log for MAX(sequence_number) WHERE batch_id=batchId; default 2 if no prior retransmit; Regenerate file content by calling the same file-generation logic used for the original batch (ensuring idempotency: same transaction set, same data); Construct new filename: {file_type}_{yyyyMMdd}_{seqNum:02d}.dat.pgp; Upload via SFTP adapter; on success insert retransmission_log row; update settlement_batch.status=TRANSMITTED; On SFTP failure after 3 retries: create exception_record with type SFTP_TRANSFER_FAILURE, priority=P1
**Deliverable:** RetransmissionService class with sequence-increment filename logic and retransmission_log persistence
**Acceptance / logic checks:**
- First retransmit of batch ZP0011 for 2026-10-15: filename=ZP0011_20261015_02.dat.pgp, sequence_number=2 in retransmission_log
- Second retransmit of same batch: filename=ZP0011_20261015_03.dat.pgp, sequence_number=3
- Regenerated file content is byte-for-byte identical to original for the same transaction set (idempotent generation)
- SFTP upload failure after 3 retries creates P1 exception_record with type=SFTP_TRANSFER_FAILURE
- UNIQUE(batch_id, sequence_number) in retransmission_log prevents duplicate sequence entries
- Retransmit of a batch already in TRANSMITTED status requires explicit reason; raises error if status=RECONCILED
**Depends on:** 9.8-T03, 9.8-T10

### 9.8-T14 — Implement inbound file idempotency: upsert logic for ZP0012 and ZP0022 reprocessing  _(45 min)_
**Context:** WBS 9.8. SCH-06 §9.4: retransmitted inbound files (ZP0012, ZP0022) are processed identically to originals. DB upsert logic must be idempotent — reprocessing the same records must not create duplicates. The upsert key for ZP0012 is (zeropay_txn_ref, txn_date, batch_settlement_date). The upsert key for ZP0022 is (original_zeropay_txn_ref, refund_date, batch_settlement_date). Reprocessing must overwrite the existing match outcome and exception record if the new result differs (e.g. a retransmit returns result_code=00 where the original returned 9001).
**Steps:** In the ZP0012 ingest service, use INSERT ... ON CONFLICT (zeropay_txn_ref, txn_date, batch_settlement_date) DO UPDATE to upsert match outcomes; If result_code changes from non-zero to 00 on upsert: transition transaction to SETTLEMENT_REGISTERED and close any open exception_record for that txn_ref; If result_code remains non-zero on upsert: update exception_record.detail with new result_message; do not create a duplicate exception_record; Apply identical logic to ZP0022 using (original_zeropay_txn_ref, refund_date, batch_settlement_date) as upsert key; Log retransmit reprocessing event in transaction_event table with step label REPROCESS_ZP0012 or REPROCESS_ZP0022
**Deliverable:** Idempotent upsert logic in ZP0012 and ZP0022 ingest services
**Acceptance / logic checks:**
- Processing ZP0012 twice with identical content: exactly one reconciliation_item row created; no duplicate exception_record
- Processing ZP0012 retransmit where result_code changed from 9001 to 00: transaction transitions to SETTLEMENT_REGISTERED; exception_record.status set to EXCEPTION_RESOLVED
- Processing ZP0012 retransmit where result_code remains 9001: exception_record updated (not duplicated); transaction remains REGISTRATION_FAILED
- ZP0022 upsert uses different composite key than ZP0012 and does not collide
- transaction_event has exactly one REPROCESS_ZP0012 event per retransmit processing run per transaction
**Depends on:** 9.8-T05, 9.8-T06, 9.8-T09

### 9.8-T15 — Implement daily pool identity background reconciliation job  _(45 min)_
**Context:** WBS 9.8 and RATE-04 §13.1. After commit, all locked values in the transaction record (collection_usd, payout_usd_cost, collection_margin_usd, payout_margin_usd) must satisfy the pool identity: collection_usd - collection_margin_usd - payout_margin_usd = payout_usd_cost within 0.01 USD. A background job runs daily, checking all committed transactions (status APPROVED or REFUNDED) for that business date. Any violation creates a P1 exception_record with type=POOL_IDENTITY_VIOLATION. This is distinct from the at-commit guard — it serves as an audit backstop. Same-currency transactions (collection_usd IS NULL) are skipped.
**Steps:** Create PoolIdentityAuditJob scheduled daily (e.g. 03:00 KST) with method auditDate(Date settlementDate); Query all transactions with status IN ('APPROVED','REFUNDED') and rate_locked_at::date = settlementDate and collection_usd IS NOT NULL; For each transaction: compute abs(collection_usd - collection_margin_usd - payout_margin_usd - payout_usd_cost); if > 0.01 USD create P1 exception_record with type=POOL_IDENTITY_VIOLATION and detail including txn_ref, violation_amount; Use DECIMAL arithmetic throughout; never convert to float; Log total checked, pass count, and violation count in a structured log message; Job is idempotent: re-running for same date does not create duplicate exception records (use ON CONFLICT on (txn_ref, exception_type, DATE(created_at)))
**Deliverable:** PoolIdentityAuditJob class with daily schedule and violation reporting
**Acceptance / logic checks:**
- Transaction with collection_usd=1000.00, collection_margin_usd=10.00, payout_margin_usd=10.00, payout_usd_cost=980.00: abs(1000-10-10-980)=0.00 < 0.01 -> no exception
- Transaction with payout_usd_cost=979.99 (violates by 0.01 exactly): 0.01 is NOT > 0.01 -> no exception (strict greater-than)
- Transaction with payout_usd_cost=979.98 (violates by 0.02): creates P1 exception_record type=POOL_IDENTITY_VIOLATION
- Same-currency transaction (collection_usd IS NULL) is skipped; no false positive
- Re-running job for same date does not duplicate exception_record rows
- Job completes within 5 minutes for up to 10,000 transactions
**Depends on:** 9.8-T02, 9.8-T04

### 9.8-T16 — Implement daily aggregate reconciliation job (ZP0065 totals vs internal ledger)  _(55 min)_
**Context:** WBS 9.8 and RATE-04 §13.2. Daily, GMEPay+ must verify: (1) sum of target_payout (KRW) for all SETTLEMENT_REGISTERED transactions in a settlement day matches ZP0065 line-item totals; (2) count of transactions in ZP0061/ZP0063 matches GMEPay+ outbound settlement count; (3) sum of collection_usd debited from each OVERSEAS partner's prefunding account matches the prefunding ledger balance movement for that day. Discrepancies auto-flag to exception queue with P1 priority. Runs after ZP0065/ZP0066 are processed (~22:00 KST).
**Steps:** Create DailyAggregateReconciliationJob scheduled at 22:30 KST with method reconcileDate(Date settlementDate); Query sum(target_payout) from transaction WHERE settlement_date=? AND batch_item_status='SETTLEMENT_REGISTERED' GROUP BY settlement_batch_id; Query sum of ZP0065 payout_amount_krw for the same settlement_batch_id; Compare totals; if delta != 0 KRW create P1 exception_record type=AGGREGATE_PAYOUT_MISMATCH with gme_amount and scheme_amount; Query count of transactions in ZP0061/ZP0063 batch (transaction_count from settlement_batch) vs count of transactions with that settlement_batch_id in transaction table; discrepancy -> P1 exception type=TRANSACTION_COUNT_MISMATCH; Query net prefunding_ledger_entry debit sum for each OVERSEAS partner for settlementDate; compare against sum(collection_usd) for that partner's transactions; delta > 0.01 USD -> P1 exception type=PREFUNDING_LEDGER_MISMATCH
**Deliverable:** DailyAggregateReconciliationJob with three reconciliation checks
**Acceptance / logic checks:**
- 10 transactions sum target_payout=500000 KRW; ZP0065 sum=500000 KRW -> no exception raised
- ZP0065 sum=499000 KRW -> P1 exception type=AGGREGATE_PAYOUT_MISMATCH, gme_amount=500000, scheme_amount=499000, discrepancy=1000
- settlement_batch.transaction_count=10 but only 9 transaction rows reference that batch -> P1 exception type=TRANSACTION_COUNT_MISMATCH
- OVERSEAS partner prefunding debit sum=5000.00 USD, collection_usd sum=5000.00 USD -> no exception
- Prefunding debit sum=4999.90 USD vs collection_usd sum=5000.00 USD (delta=0.10 > 0.01) -> P1 exception type=PREFUNDING_LEDGER_MISMATCH
- Job is idempotent when re-run on same date
**Depends on:** 9.8-T08, 9.8-T10, 9.8-T15

### 9.8-T17 — Implement dispute data-retrieval service (SCH-06 §9.6)  _(45 min)_
**Context:** WBS 9.8. SCH-06 §9.6: ZeroPay disputes are initiated via ZeroPay's own dispute channel. GMEPay+'s responsibilities are: (1) provide transaction records on request by zeropay_txn_ref; (2) provide file-level audit trail (SFTP transfer timestamps, checksums, archival copies); (3) for international transactions, identify the OVERSEAS partner for customer-side evidence; (4) escalate to GME Finance/Compliance for settlement-affecting amounts. GMEPay+ does not handle customer disputes directly. A DisputeDataService exposes the data retrieval API consumed by the Admin portal dispute detail screen.
**Steps:** Create DisputeDataService with method getDisputePackage(zeropayTxnRef: String): DisputePackage; Query transaction by zeropay_txn_ref (hub_txn_ref in transaction table, see DAT-03 A7); load all transaction_event rows; Query settlement_file rows linked to the transaction's settlement_batch_id; include filename, sftp_path, file_checksum, transmitted_at; If transaction partner type=OVERSEAS: include partner.name, partner.alert_email in the package for coordination reference; If any reconciliation_item or exception_record is linked to the transaction: include it in the package; Create method flagForEscalation(zeropayTxnRef: String, escalatedBy: String, amount: Decimal, note: String): create exception_record with type=DISPUTE_ESCALATED, priority=P1
**Deliverable:** DisputeDataService class with getDisputePackage and flagForEscalation methods
**Acceptance / logic checks:**
- getDisputePackage for a known zeropay_txn_ref returns a package with: transaction detail, 8-step event trail, settlement_file records with checksums, and reconciliation_item if present
- getDisputePackage for an OVERSEAS partner includes partner.name and partner.alert_email
- getDisputePackage for an unknown zeropay_txn_ref throws ResourceNotFoundException
- flagForEscalation creates exception_record with type=DISPUTE_ESCALATED, priority=P1, detail includes amount and escalatedBy
- flagForEscalation called twice for same reference creates only one DISPUTE_ESCALATED exception (idempotent via ON CONFLICT)
- settlement_file records include file_checksum (SHA-256) for audit trail integrity
**Depends on:** 9.8-T02, 9.8-T04

### 9.8-T18 — Implement Ops Admin API: GET /internal/exceptions endpoint  _(45 min)_
**Context:** WBS 9.8. The Ops Admin portal exception queue must surface open exception_record rows. Endpoint: GET /internal/exceptions with query params: status (default EXCEPTION_PENDING), priority (optional P1/P2), exception_type (optional), page (default 0), pageSize (default 20, max 100). Response: paginated list of ExceptionRecordDto (id, exception_type, priority, status, txn_ref, scheme_ref, batch_id, detail, created_at, resolved_by, resolved_at). Requires ROLE_OPS_ADMIN or ROLE_FINANCE. Alert SLA: discrepancy alerts must reach Ops within 15 minutes of batch receipt (NFR goal G-5).
**Steps:** Add GET /internal/exceptions handler in ExceptionController; Bind query params to ExceptionSearchCriteria (status, priority, exception_type, page, pageSize); Delegate to ExceptionRecordService.listExceptions(criteria) which builds a JPA/JPQL query; Map exception_record rows to ExceptionRecordDto; include txn_ref, scheme_ref, batch file_type, detail; Enforce authorization: only ROLE_OPS_ADMIN and ROLE_FINANCE may access; Return Page<ExceptionRecordDto> with total count, page, pageSize
**Deliverable:** GET /internal/exceptions endpoint returning paginated exception records
**Acceptance / logic checks:**
- GET /internal/exceptions returns only EXCEPTION_PENDING rows by default; total reflects DB count
- GET /internal/exceptions?priority=P1 returns only P1 rows
- GET /internal/exceptions?exception_type=REGISTRATION_FAILED returns only that type
- pageSize=5 returns at most 5 items; second page returns the next 5
- Request without ROLE_OPS_ADMIN or ROLE_FINANCE returns HTTP 403
- Response time < 500 ms for up to 1000 open exception rows
**Depends on:** 9.8-T10

### 9.8-T19 — Implement Ops Admin API: POST /internal/exceptions/{id}/resolve  _(40 min)_
**Context:** WBS 9.8. Ops must be able to resolve an exception record from the Admin portal. Endpoint: POST /internal/exceptions/{id}/resolve with body {resolution_note: string (required, min 10 chars)}. Marks exception as EXCEPTION_RESOLVED; records resolved_by (from auth principal), resolved_at=now(), resolution_note. After resolution, if the exception was blocking a transaction from settlement (batch_item_status=EXCEPTION_PENDING), the transaction's batch_item_status is reset to SETTLEMENT_REGISTERED (or the appropriate status based on its registration state) so it can be included in the next settlement batch. Returns 200 with updated ExceptionRecordDto. Requires ROLE_OPS_ADMIN.
**Steps:** Add POST /internal/exceptions/{id}/resolve handler; Load exception_record by id; if not found return 404; If status=EXCEPTION_RESOLVED return 409 with message 'Already resolved'; Validate resolution_note length >= 10 chars; Call ExceptionRecordService.resolveException(id, principal.name, resolution_note); If exception_record has a linked txn_ref: update transaction.batch_item_status to SETTLEMENT_REGISTERED and clear batch_exception_id if appropriate; Audit-log the resolution (actor, exception id, resolution_note, timestamp); Return 200 with ExceptionRecordDto
**Deliverable:** POST /internal/exceptions/{id}/resolve endpoint
**Acceptance / logic checks:**
- Resolving an open P1 REGISTRATION_FAILED exception sets status=EXCEPTION_RESOLVED, resolved_by=current user, resolved_at=now()
- Resolving an already-resolved exception returns HTTP 409
- resolution_note with fewer than 10 characters returns HTTP 400 with field validation error
- After resolution, the linked transaction's batch_exception_id is cleared and batch_item_status reflects its actual registration state
- Audit log contains an entry with actor, exception id, and resolution_note within 1 second of the call
- Only ROLE_OPS_ADMIN can resolve; ROLE_FINANCE returns 403
**Depends on:** 9.8-T10, 9.8-T18

### 9.8-T20 — Implement Ops Admin API: POST /internal/batches/{id}/retransmit  _(35 min)_
**Context:** WBS 9.8. Ops must be able to trigger retransmission of a failed outbound batch file from the Admin portal. Endpoint: POST /internal/batches/{id}/retransmit with body {reason: string (required)}. Delegates to RetransmissionService (9.8-T13). Returns 202 Accepted with job reference if SFTP upload is async, or 200 with RetransmissionResult if synchronous. Requires ROLE_OPS_ADMIN. The batch must be in status ERROR or TRANSMITTED. Retransmitting a RECONCILED batch is blocked with 409.
**Steps:** Add POST /internal/batches/{id}/retransmit handler in SettlementBatchController; Load settlement_batch by id; if not found return 404; Check status: if RECONCILED return 409 'Cannot retransmit a reconciled batch'; Validate reason is present; Call RetransmissionService.retransmit(batchId, reason, principal.name); Return 200 with {retransmitFilename, sequenceNumber, transmittedAt}; Audit-log the retransmission trigger (actor, batchId, reason, sequenceNumber)
**Deliverable:** POST /internal/batches/{id}/retransmit endpoint
**Acceptance / logic checks:**
- Retransmitting a batch in status ERROR returns 200 with new filename containing incremented sequence number (e.g. _02.dat.pgp)
- Retransmitting a batch in status RECONCILED returns 409
- Retransmitting with no reason body returns 400
- Each retransmit call increments the sequence number: two calls produce _02 and _03
- Audit log contains actor, batchId, reason, and new sequence number
- Only ROLE_OPS_ADMIN can trigger; ROLE_FINANCE returns 403
**Depends on:** 9.8-T13, 9.8-T18

### 9.8-T21 — Implement Ops Admin API: GET /internal/disputes endpoint and dispute package  _(40 min)_
**Context:** WBS 9.8. Ops must be able to retrieve dispute packages for ZeroPay escalation (SCH-06 §9.6). Endpoint: GET /internal/disputes/{zeropayTxnRef} returns the DisputePackage from DisputeDataService (9.8-T17). Endpoint: POST /internal/disputes/{zeropayTxnRef}/escalate with body {amount: decimal, note: string} calls flagForEscalation. Both endpoints require ROLE_OPS_ADMIN or ROLE_FINANCE. escalate requires ROLE_OPS_ADMIN only.
**Steps:** Add GET /internal/disputes/{zeropayTxnRef} handler in DisputeController; Call DisputeDataService.getDisputePackage(zeropayTxnRef); return 200 with DisputePackageDto; Add POST /internal/disputes/{zeropayTxnRef}/escalate handler; Validate amount > 0 and note is present (min 10 chars); Call DisputeDataService.flagForEscalation(zeropayTxnRef, principal.name, amount, note); Return 200 with created exception_record id and status
**Deliverable:** GET /internal/disputes/{zeropayTxnRef} and POST /internal/disputes/{zeropayTxnRef}/escalate endpoints
**Acceptance / logic checks:**
- GET for known zeropayTxnRef returns transaction detail, event trail, and settlement_file audit trail
- GET for unknown zeropayTxnRef returns 404
- POST /escalate creates exception_record type=DISPUTE_ESCALATED, priority=P1
- POST /escalate with amount=0 returns 400
- POST /escalate with note < 10 chars returns 400
- ROLE_FINANCE on GET /internal/disputes returns 200; ROLE_FINANCE on POST /escalate returns 403
**Depends on:** 9.8-T17, 9.8-T19

### 9.8-T22 — Unit tests: ZP0012Matcher all 5 outcomes with boundary cases  _(40 min)_
**Context:** WBS 9.8. Test ticket for ZP0012Matcher (9.8-T05). Tests must be deterministic, self-contained, no DB calls. Use plain Java/Kotlin objects for ZP0011Record and ZP0012Record. Cover all 5 outcome branches plus boundary cases: empty ZP0011 list, empty ZP0012 list, both empty, large list performance (1000 records).
**Steps:** Create ZP0012MatcherTest class; Write test: MATCHED outcome - single record, exact amount match; Write test: REGISTRATION_FAILED - non-zero result_code (e.g. '9001'); Write test: REGISTRATION_UNKNOWN - ZP0011 record not in ZP0012; Write test: ROGUE_RECORD - ZP0012 record not in ZP0011; Write test: AMOUNT_MISMATCH - result_code='00' but amounts differ by 1 KRW; Write test: empty ZP0011 + non-empty ZP0012 produces all ROGUE_RECORD; Write test: non-empty ZP0011 + empty ZP0012 produces all REGISTRATION_UNKNOWN; Write test: 1000-record list completes in < 200ms (use System.nanoTime assertion)
**Deliverable:** ZP0012MatcherTest class with 8 test cases all passing
**Acceptance / logic checks:**
- All 5 outcome branches covered by at least one test
- AMOUNT_MISMATCH test uses KRW amounts (integers): submitted=50000, registered=49999 (diff=1 KRW) -> AMOUNT_MISMATCH
- Empty-input edge cases produce correct empty or all-one-outcome lists without NPE
- Performance test with 1000 records passes in < 200ms
- Zero external dependencies (no Spring context, no DB)
**Depends on:** 9.8-T05

### 9.8-T23 — Unit tests: SettlementResultMatcher MORNING and AFTERNOON batch scenarios  _(35 min)_
**Context:** WBS 9.8. Test ticket for SettlementResultMatcher (9.8-T07). Tests cover ZP0061 vs ZP0062 (MORNING) and ZP0063 vs ZP0064 (AFTERNOON). Also test multi-merchant scenarios and the rogue-record case.
**Steps:** Create SettlementResultMatcherTest class; Write test: 3 merchants all matching -> 3 SETTLEMENT_CONFIRMED results; Write test: merchant M001 confirmed amount differs by 1 KRW -> SETTLEMENT_DISCREPANCY with discrepancy_amount=1; Write test: merchant M002 in ZP0061 but absent from ZP0062 -> MISSING_FROM_RESULT; Write test: merchant M003 in ZP0062 but absent from ZP0061 -> ROGUE_SETTLEMENT_RECORD; Write test: AFTERNOON batchType with ZP0063/ZP0064 produces same outcomes; Write test: empty inputs produce empty result lists without error
**Deliverable:** SettlementResultMatcherTest class with 6 test cases all passing
**Acceptance / logic checks:**
- SETTLEMENT_DISCREPANCY test: submitted_amount=200000 KRW, confirmed=199999 KRW, discrepancy_amount=1 KRW
- All four match_status outcomes covered
- AFTERNOON batchType processes identically to MORNING (batch_type field in result is AFTERNOON)
- No DB or Spring context dependencies
- All tests pass with zero flakiness
**Depends on:** 9.8-T07

### 9.8-T24 — Unit tests: UncertainTransactionResolver APPROVED, FAILED, and still-UNCERTAIN paths  _(45 min)_
**Context:** WBS 9.8. Test ticket for UncertainTransactionResolver (9.8-T09). Uses an in-memory/mock DB or test doubles for TransactionOrchestrator and PrefundingLedger. Covers: APPROVED transition with webhook, FAILED transition with prefunding reversal for OVERSEAS, FAILED for LOCAL (no reversal), still-UNCERTAIN below 24h, escalation above 24h.
**Steps:** Create UncertainTransactionResolverTest class using Mockito for TransactionOrchestrator and PrefundingLedger; Write test: ZP0012 result_code='00' for UNCERTAIN txn -> transitionToApproved called, webhook dispatched; Write test: ZP0012 result_code='9001' for OVERSEAS UNCERTAIN txn -> transitionToFailed called, prefunding reversal invoked with SELECT FOR UPDATE; Write test: ZP0012 result_code='9001' for LOCAL UNCERTAIN txn -> transitionToFailed called, no prefunding reversal; Write test: UNCERTAIN txn absent from ZP0012, age=12h (below 24h) -> no status change, no exception created; Write test: UNCERTAIN txn absent from ZP0012, age=25h (above 24h) -> P1 exception_record created with type=REGISTRATION_UNKNOWN; Write test: idempotency - resolve called twice with same ZP0012 -> transitionToApproved called exactly once
**Deliverable:** UncertainTransactionResolverTest class with 6 test cases all passing
**Acceptance / logic checks:**
- APPROVED transition test: verify transitionToApproved mock called once with correct txn_ref
- OVERSEAS FAILED test: verify prefunding reversal mock called with correct collection_usd amount
- LOCAL FAILED test: verify prefunding reversal mock NOT called
- 25h-old UNCERTAIN creates exception with priority=P1 and type=REGISTRATION_UNKNOWN
- Idempotency test: second call does not invoke transitionToApproved again
- All tests run without DB (pure unit tests with mocks)
**Depends on:** 9.8-T09

### 9.8-T25 — Unit tests: ExceptionRecordService create, resolve, and escalate logic  _(40 min)_
**Context:** WBS 9.8. Test ticket for ExceptionRecordService (9.8-T10). Uses an in-memory H2 test DB or mocked repository. Covers: createFromReconciliationResult for all exception types, listOpenExceptions ordering, resolveException success and idempotency failure, escalateStaleExceptions cutoff logic.
**Steps:** Create ExceptionRecordServiceTest class; Write test: createFromReconciliationResult with REGISTRATION_FAILED -> exception_record with type=REGISTRATION_FAILED, priority=P1; Write test: createFromSettlementResult with SETTLEMENT_DISCREPANCY -> type=SETTLEMENT_DISCREPANCY, priority=P1; Write test: listOpenExceptions - insert 2 P1 exceptions and 1 P2; verify P1 items first, then P2, all with status=EXCEPTION_PENDING; Write test: resolveException - valid resolution sets status=EXCEPTION_RESOLVED, resolved_by, resolved_at; Write test: resolveException on already-resolved exception throws IllegalStateException; Write test: escalateStaleExceptions with cutoff=60min - P1 exception aged 90 min escalated; P1 exception aged 30 min unchanged
**Deliverable:** ExceptionRecordServiceTest class with 6 test cases all passing
**Acceptance / logic checks:**
- REGISTRATION_FAILED exception has priority=P1 (P2 for file_late type would be set separately)
- listOpenExceptions returns P1 before P2; within same priority, older first
- resolveException test: resolved_by matches principal name; resolved_at is approximately now()
- Already-resolved exception throws IllegalStateException (not just returns 409 — that is the controller's job)
- escalateStaleExceptions does not re-escalate an already-EXCEPTION_ESCALATED record
- All 6 tests pass without external service dependencies
**Depends on:** 9.8-T10

### 9.8-T26 — Unit tests: RetransmissionService sequence-number and idempotency  _(35 min)_
**Context:** WBS 9.8. Test ticket for RetransmissionService (9.8-T13). Covers filename construction, sequence increment across multiple retransmits, regenerated content idempotency, RECONCILED-batch rejection, and SFTP failure exception creation.
**Steps:** Create RetransmissionServiceTest class; Write test: first retransmit of batch ZP0011_20261015 produces filename ZP0011_20261015_02.dat.pgp, sequence_number=2; Write test: second retransmit produces ZP0011_20261015_03.dat.pgp, sequence_number=3; Write test: regenerated file content is identical for same transaction set (mock file-generation to return deterministic bytes); Write test: retransmit of RECONCILED batch throws IllegalStateException; Write test: SFTP failure after 3 retries creates P1 exception_record type=SFTP_TRANSFER_FAILURE; Write test: UNIQUE constraint on (batch_id, sequence_number) prevents duplicate retransmission_log entries
**Deliverable:** RetransmissionServiceTest class with 6 test cases all passing
**Acceptance / logic checks:**
- Filename ZP0011_20261015_02.dat.pgp generated correctly for first retransmit
- Sequence 3 generated correctly for second retransmit without manually knowing previous max
- RECONCILED batch throws with a message containing 'Cannot retransmit'
- SFTP failure test: exception_record.type=SFTP_TRANSFER_FAILURE and priority=P1
- Duplicate sequence_number insert test raises DataIntegrityViolationException
- All tests are pure unit tests with no real SFTP calls
**Depends on:** 9.8-T13

### 9.8-T27 — Unit tests: PoolIdentityAuditJob boundary conditions  _(35 min)_
**Context:** WBS 9.8. Test ticket for PoolIdentityAuditJob (9.8-T15). Tests the 0.01 USD boundary precisely using Decimal arithmetic, same-currency skip, and idempotency.
**Steps:** Create PoolIdentityAuditJobTest class; Write test: delta=0.00 -> no exception (exactly balanced pool); Write test: delta=0.01 exactly -> no exception (tolerance is STRICTLY greater-than 0.01); Write test: delta=0.02 -> P1 exception type=POOL_IDENTITY_VIOLATION created; Write test: same-currency transaction (collection_usd=NULL) -> skipped; no exception; Write test: re-running job for same date when violation already exists -> no duplicate exception_record (upsert semantics); Write test: values use BigDecimal not double; verify no floating-point imprecision for amount 100.00 USD
**Deliverable:** PoolIdentityAuditJobTest class with 5 test cases all passing
**Acceptance / logic checks:**
- delta=0.01 does NOT create exception (strictly greater-than boundary)
- delta=0.0101 creates exception
- NULL collection_usd skipped without NPE
- Second run for same txn does not create a second exception_record row
- All arithmetic uses BigDecimal; test with value 100.00 that would be imprecise as double (e.g. 100.00 = 99.99999... in float)
**Depends on:** 9.8-T15

### 9.8-T28 — Integration test: full ZP0012 reconciliation flow end-to-end  _(60 min)_
**Context:** WBS 9.8. Integration test starting from a set of UNCERTAIN and APPROVED transactions, processing a synthetic ZP0012 file, and verifying all downstream state changes: UNCERTAIN -> APPROVED, UNCERTAIN -> FAILED + prefunding reversal, REGISTRATION_UNKNOWN exception, AMOUNT_MISMATCH exception, and settlement-blocking guard. Uses a real test DB (H2 or Testcontainers PostgreSQL).
**Steps:** Create ReconciliationIntegrationTest using @SpringBootTest with TestContainers PostgreSQL; Seed DB: 5 transactions in UNCERTAIN status, 3 in APPROVED; 1 OVERSEAS, 1 LOCAL among UNCERTAIN; Construct synthetic ZP0012 with: result_code=00 for 3 UNCERTAIN txns; result_code=9001 for 1 UNCERTAIN; 1 UNCERTAIN absent; Invoke reconciliation service with ZP0012; Assert UNCERTAIN txn with code 00 -> APPROVED; confirm webhook event queued; Assert UNCERTAIN txn with code 9001 (OVERSEAS) -> FAILED; prefunding ledger has reversal entry; Assert UNCERTAIN txn absent from ZP0012, age < 24h -> remains UNCERTAIN; no exception; Confirm 3 exception_record rows exist: 1 REGISTRATION_FAILED, and 1 per ROGUE/UNKNOWN if applicable; Confirm settlement-blocking guard excludes REGISTRATION_FAILED transaction from next ZP0061 batch
**Deliverable:** ReconciliationIntegrationTest class with full end-to-end flow verified
**Acceptance / logic checks:**
- Exactly 3 UNCERTAIN transitions to APPROVED after processing
- 1 UNCERTAIN transitions to FAILED; prefunding_ledger_entry of type REVERSAL exists for the OVERSEAS partner
- UNCERTAIN transaction absent from ZP0012 and age < 24h: still UNCERTAIN; exception_record count unchanged for this txn
- exception_record table has exactly the expected rows post-processing
- Re-running the same integration test input is idempotent: no duplicate rows, no double-reversals
- Settlement batch for affected REGISTRATION_FAILED transaction is blocked (batch_item_status=EXCEPTION_PENDING)
**Depends on:** 9.8-T09, 9.8-T11, 9.8-T14

### 9.8-T29 — Integration test: late-file detection and retransmission round-trip  _(55 min)_
**Context:** WBS 9.8. Integration test verifying the late-file monitor creates a batch_late_alert when ZP0012 is overdue, places dependent jobs in WAITING state, and that after arrival the alert is resolved and jobs resume. Also tests retransmission sequence increment round-trip via POST /internal/batches/{id}/retransmit.
**Steps:** Create LateFileAndRetransmissionIntegrationTest using @SpringBootTest with TestContainers; Seed a settlement_batch for ZP0012 with status=PENDING and expected arrival in the past (simulate late by setting expected_by to now()-90min); Invoke BatchLateFileMonitor.checkForLateFiles(); Assert batch_late_alert row created; settlement_batch.status=ERROR; dependent ZP0061 batch in WAITING; Simulate ZP0012 arrival: update settlement_batch.status=RECEIVED and resolved_at in batch_late_alert; Assert dependent ZP0061 batch transitions out of WAITING when monitor runs again; Seed a ZP0011 batch in ERROR status; POST /internal/batches/{id}/retransmit with reason; Assert retransmission_log has sequence_number=2; settlement_batch.status=TRANSMITTED; filename contains _02
**Deliverable:** LateFileAndRetransmissionIntegrationTest class verifying late-file and retransmit flows
**Acceptance / logic checks:**
- batch_late_alert created exactly once for the late ZP0012 (idempotent on second monitor run)
- settlement_batch status=ERROR and dependent batch status=WAITING after late-file detection
- After ZP0012 received: batch_late_alert.resolved_at is set; WAITING batch resumes
- Retransmission endpoint returns 200 with filename containing '_02.dat.pgp'
- Second retransmit call returns filename '_03.dat.pgp'
- RECONCILED batch retransmit returns 409
**Depends on:** 9.8-T12, 9.8-T13, 9.8-T20, 9.8-T28

### 9.8-T30 — Ops Admin portal: exception queue UI wiring (backend DTO and openapi spec)  _(35 min)_
**Context:** WBS 9.8. The Admin portal exception queue screen (PRD-07) needs an OpenAPI specification and DTO layer for the /internal/exceptions and /internal/exceptions/{id}/resolve endpoints so the frontend team can build against a contract. This ticket covers the OpenAPI spec fragment and the ExceptionRecordDto class with all fields. The Admin portal must surface exceptions within 15 minutes of batch receipt (NFR G-5).
**Steps:** Add ExceptionRecordDto with fields: id, exception_type, priority, status, txn_ref, scheme_ref, batch_id, file_type (from settlement_batch), detail, resolution_note, created_at, resolved_by, resolved_at; Add ExceptionListResponseDto with fields: items (List<ExceptionRecordDto>), total, page, pageSize; Write OpenAPI YAML fragment for GET /internal/exceptions and POST /internal/exceptions/{id}/resolve including request/response schemas and error codes (400, 403, 404, 409); Add the openapi fragment to the project's openapi spec file; Verify that the auto-generated client stub (if applicable) compiles
**Deliverable:** ExceptionRecordDto, ExceptionListResponseDto, and OpenAPI spec fragment for exception queue endpoints
**Acceptance / logic checks:**
- ExceptionRecordDto includes all 12 fields listed above; no field is optional that the spec requires as mandatory
- OpenAPI fragment validates against OpenAPI 3.0 schema (run via openapi-generator or similar)
- GET /internal/exceptions response schema matches ExceptionListResponseDto
- POST /internal/exceptions/{id}/resolve request schema requires resolution_note (minLength 10)
- HTTP 409 error response is documented for resolving an already-resolved exception
- Fragment merges without conflict into the master openapi.yaml
**Depends on:** 9.8-T18, 9.8-T19

### 9.8-T31 — Ops Admin portal: settlement batch status screen backend DTO and openapi spec  _(35 min)_
**Context:** WBS 9.8. The settlement batch monitoring screen in the Admin portal (PRD-07) needs a DTO and OpenAPI spec for GET /internal/settlement-batches (list by date) and GET /internal/settlement-batches/{id} (detail with reconciliation_item rows). This is the screen where Ops monitors daily batch status and drills into discrepancies.
**Steps:** Add SettlementBatchDto: id, scheme_id, file_type, direction, settlement_date, window, status, transaction_count, total_amount, total_amount_ccy, file_checksum, transmitted_at, received_at, reconciled_at, error_detail, exception_count (count of open exceptions linked to this batch); Add ReconciliationItemDto: id, batch_id, txn_ref, scheme_ref, match_status, gme_amount, scheme_amount, discrepancy_amount, ccy, resolution_status, resolved_by, resolved_at; Write OpenAPI YAML fragment for GET /internal/settlement-batches?settlement_date=&file_type= and GET /internal/settlement-batches/{id} (includes reconciliation_items array); Add the fragment to the master openapi.yaml
**Deliverable:** SettlementBatchDto, ReconciliationItemDto, and OpenAPI spec fragment for settlement batch monitoring
**Acceptance / logic checks:**
- SettlementBatchDto includes exception_count field computed as a DB aggregate
- GET /internal/settlement-batches?settlement_date=2026-10-15 returns all batches for that date
- GET /internal/settlement-batches/{id} response includes reconciliation_items array
- OpenAPI fragment validates against OpenAPI 3.0 schema
- ReconciliationItemDto match_status is documented as an enum: MATCHED, DISCREPANCY, MISSING_GME, MISSING_SCHEME
- OpenAPI spec includes 401, 403, 404 error responses for both endpoints
**Depends on:** 9.8-T18, 9.8-T20

### 9.8-T32 — Developer documentation: exception handling runbook snippet in code comments and README  _(30 min)_
**Context:** WBS 9.8. The exception handling lifecycle and key decisions must be documented for future developers. This ticket covers: (1) Javadoc/KDoc on each exception type in ExceptionRecordService explaining the priority, blocking behaviour, and resolution path; (2) a short reconciliation-flow comment block in the SettlementEngine class explaining the daily file dependencies (ZP0011 -> ZP0012 -> ZP0061 -> ZP0062 -> ... -> ZP0065/ZP0066 chain); (3) a note in the retransmission service explaining the sequence-number filename convention. No standalone .md files — documentation goes in code comments only.
**Steps:** Add class-level Javadoc/KDoc to ExceptionRecordService describing all 10 exception_type values, their priorities, and resolution paths; Add method-level Javadoc to UncertainTransactionResolver.resolve() describing the 24-hour window and prefunding reversal semantics; Add a comment block at the top of the Settlement Engine's batch-scheduler component describing the 7-step daily file dependency chain; Add a comment on RetransmissionService.retransmit() explaining the filename convention ZP0011_{yyyyMMdd}_{seqNum:02d}.dat.pgp and idempotent regeneration guarantee; Review all comments are accurate against the implementation (no stale references)
**Deliverable:** Inline documentation (Javadoc/KDoc + comment blocks) in ExceptionRecordService, UncertainTransactionResolver, SettlementEngine batch scheduler, and RetransmissionService
**Acceptance / logic checks:**
- ExceptionRecordService class Javadoc lists all 10 exception_type values with priority and resolution path
- UncertainTransactionResolver.resolve() Javadoc explains 24-hour window and prefunding reversal semantics
- Settlement Engine comment block shows the 7-step file chain (ZP0011 -> ZP0012 -> ZP0061 -> ZP0062 -> ZP0063 -> ZP0064 -> ZP0065/ZP0066)
- RetransmissionService comment explains sequence-number filename convention with an example
- No method or class has a TODO referencing reconciliation logic that is actually implemented (clean TODO list)
**Depends on:** 9.8-T09, 9.8-T10, 9.8-T13, 9.8-T16
