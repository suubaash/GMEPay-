> 작업: Audit/Compliance audit / 출처: agent

# GMEPay+ — Compliance / AML-CFT / Privacy / Risk & Auditability Gap Audit
**Auditor lens:** Head of Internal Audit / Compliance / Risk. Date: 2026-07-01.
**Purpose:** What is missing to keep GME **licensed, examinable, and out of regulatory trouble** across Korea (FSC/FIU-KoFIU/BOK/PIPC) and Nepal (NRB/data-protection), with more corridors planned. Money-movement hubs are examined on their *AML/CFT program*, *consumer protection*, *data privacy*, and *demonstrable control* — not just on whether payments clear.

## Scope / lane
I do **not** repeat Finance (ERP/CoA/nostro recon/accruals/SoD-over-money), CTO/DevOps (DR, tracing, supply-chain, image signing), or Ops (dispute UI plumbing) items, except the few where a regulator specifically compels the control. Only genuine gaps.

**Already present (credit where due, but note partiality):**
- Hash-chained tamper-evident audit log; 4-eyes/maker-checker on config & pricing.
- KoFIU CTR/STR — **aggregation + file builders only**; the gov submission channel is a gated stub (nothing has ever been filed).
- BOK FX1014/FX1015, Hometax e-tax-invoices — same pattern (persist at GENERATED; real channel externally blocked).
- KYB + business-registration verification at **partner onboarding** via Octa (`kyb-adapter`) — a *point-in-time entity* check with PASS/FAIL/MANUAL_REVIEW decisioning.
- RBAC + OIDC operator login; a **single per-transaction AML cap** on the payment path.

The through-line of every gap below: **we can compute a number and build a file, but we cannot yet run an AML program, protect a consumer, honor a data-subject, or hand an examiner an evidence pack.** A regulator does not license a calculator; it licenses a *program*.

---

## TOP COMPLIANCE / RISK / AUDITABILITY GAPS

### A. AML/CFT program depth — the biggest license-threatening cluster

1. **Sanctions screening is entity-only and shallow — no end-customer, merchant, or beneficiary screening.** Octa screens the *partner business* at onboarding. Nothing screens the **partner's end-customers, the merchant/beneficiary, or counterparties on the actual transfer** against OFAC/UN/EU/MOFA(KR) lists, and no daily list-refresh rescreen of the existing book. *Consequence:* a sanctioned party can transact through GME undetected — the single fastest route to license revocation, blocking orders, and criminal/secondary-sanctions exposure.

2. **No PEP screening at all.** No politically-exposed-person identification at onboarding or in-flight, no PEP tier, no senior-management sign-off gate for PEP relationships. *Consequence:* FATF/KoFIU require PEP controls; absence is a headline exam finding and a corruption-facilitation risk.

3. **No ongoing / near-real-time transaction monitoring or typology rules.** There is exactly one per-txn cap. There is **no** tunable rule engine (structuring/smurfing, velocity, rapid-in-rapid-out, round-tripping, corridor-risk, unusual-hour, dormant-then-active, sender/beneficiary concentration) evaluating the *pattern* across transactions. *Consequence:* CTR/STR "aggregation" has no detection feeding it — we can only file what a human happens to notice. This is the core of an AML program and it does not exist.

4. **No alert → case-management → SAR/STR workflow.** No alert queue, no analyst investigation case file, no disposition/escalation states, no audit trail of *why* a report was or was not filed, no regulatory filing-timeliness clock. The STR "builder" has no case behind it. *Consequence:* an examiner asks "show me your last 20 alerts and their dispositions" and we have nothing; unfiled/late STRs are per-count penalties.

5. **KYC/CDD/EDD tiers and periodic refresh are absent.** Onboarding is one-shot PASS/FAIL. No risk-rating of a partner/customer (low/med/high), no Enhanced Due Diligence trigger for high-risk corridors/PEPs/high-value, no **periodic KYC refresh** cadence (e.g. high-risk annually). *Consequence:* stale, un-risk-rated relationships — a direct CDD-rule breach.

6. **Cross-border Travel Rule not implemented.** No capture/validation/transmission of **originator and beneficiary** info (name, account/wallet, address/ID) alongside each cross-border transfer, no missing-info rejection/hold logic. *Consequence:* FATF R.16 / KR & Nepal wire-transfer rules are mandatory for a remittance hub; non-compliance blocks correspondent relationships and is a standalone finding.

7. **No managed watchlist / blocklist / internal-negative-list capability.** No system of record for internally-blocked parties, adverse-media hits, or list versioning with maker-checker and effective-dating. *Consequence:* can't operationalize a regulator's "freeze this party" order or a prior STR subject; no provenance of *which list version* cleared a txn.

8. **No AML/fraud rules & model governance.** Thresholds (the AML cap, future rules) have no versioned governance record, no change approval, no back-testing/tuning log, no annual model validation. *Consequence:* regulators now expect documented rule-tuning rationale; "why is the cap set here?" has no answer.

### B. Data privacy — currently unaddressed, and it is statutory

9. **No PIPA / Nepal / GDPR privacy program.** No consent capture & ledger, no lawful-basis record, no **data-subject access/rectification/erasure** workflow, no cross-border transfer legality (PIPA Art.28 / adequacy) for KR↔NP↔third-country PII flows. *Consequence:* PIPA/PIPC fines are turnover-based; moving PII across borders without a legal basis is itself unlawful.

10. **No retention & deletion schedule with legal hold.** No per-data-class statutory retention (AML records typically 5 yrs post-relationship; tax/FX per KR law) with automated purge and a **legal-hold override** for investigations/litigation. *Consequence:* we simultaneously risk over-retention (privacy breach) and premature deletion (AML/tax record-keeping breach) — you can fail both directions at once.

11. **No breach-notification workflow.** No detect→assess→notify pipeline meeting **PIPA 72-hour** / regulator / data-subject notification timelines. *Consequence:* a breach with no notification runbook turns an incident into a separate, larger penalty.

### C. Licensing, consumer protection & examinability

12. **No regulatory licensing register / scope & conditions monitor.** Nothing tracks *which* license GME holds per country (payment/FX/remittance/EMI/small-remittance-operator), its scope, expiry, and **binding conditions/limits** (e.g. per-customer annual remittance ceilings, permitted corridors, capital/float minima), nor alerts when activity approaches a licensed limit. *Consequence:* operating outside licensed scope or breaching a condition is unlicensed money transmission — the existential risk.

13. **Regulatory-mandated transaction & exposure limits not enforced as controls.** Beyond the single per-txn cap, no per-customer/per-partner **cumulative** limits (daily/monthly/annual remittance ceilings mandated by BOK/NRB), no corridor caps. *Consequence:* silent breach of a licensed limit across many small txns; the cap catches one big txn, not aggregation.

14. **Consumer-protection disclosures & receipts not evidenced.** No enforced pre-transaction disclosure of **total price (FX rate, all fees, amount beneficiary receives, delivery time)**, no retained consumer receipt/confirmation, no cancellation/refund-rights window. *Consequence:* KR e-financial-transaction & remittance consumer rules (and analogues abroad) require transparent, retained disclosures; absence = mis-selling finding + consumer redress.

15. **No complaint-handling & dispute-rights system with regulatory timelines.** No logged complaint intake, SLA clock, escalation, or regulator-reportable complaints register. *Consequence:* complaint-handling is an examined control; missing it fails the "treating customers fairly" limb and hides systemic issues.

16. **No examiner/auditor evidence pack or independent audit-trail export.** The hash-chained log is tamper-evident but there is **no** one-click, read-only, independently-verifiable export for a regulator/external auditor, no control library, and no **periodic control-testing** record (control → owner → test → result → remediation). *Consequence:* "examinable" means producing evidence on demand; today it takes an engineer with DB access, which itself undermines the evidence's integrity.

17. **Filing completeness & timeliness is unproven — and nothing has actually filed.** Filings persist at GENERATED against a **stubbed** gov channel; there is no reconciliation that *every* required CTR/STR/FX/tax report for a period was generated **and accepted** on time, no gap/exception alert. *Consequence:* GME cannot prove to an examiner that its regulatory reporting is complete and on-time — and in reality the submission leg is not live at all.

### D. Enterprise risk governance

18. **No enterprise risk register / risk framework.** No maintained register of AML, sanctions, fraud, privacy, operational, third-party and concentration risks with owners, ratings, appetite and treatment. *Consequence:* regulators expect a board-owned risk framework; its absence signals an immature control environment across the whole exam.

19. **Third-party / vendor & concentration risk is ungoverned.** Heavy reliance on a single KYB vendor (Octa) and, per corridor, effectively a **single scheme** (ZeroPay / Nepal) with no documented vendor due diligence, SLA/exit plan, or concentration limit. *Consequence:* vendor failure or a single-scheme outage is an unmanaged single point of *compliance* failure (screening stops; corridor halts) with no regulator-facing continuity plan.

20. **Fraud risk lacks a governed control layer.** No customer/txn fraud scoring, velocity checks, device/behavior signals or fraud blocklist as a *governed, tunable* control (distinct from AML). *Consequence:* fraud losses and consumer harm, plus an examiner expecting fraud controls alongside AML finds none.

21. **No whistleblower / conflicts-of-interest / independence attestations.** No confidential reporting channel, conflicts register, or periodic SoD/independence attestation by staff and operators. *Consequence:* required governance hygiene under KR financial-consumer & AML governance expectations; absence weakens every other control's credibility.

---

## Prioritization for the examiner-readiness plan
- **License-threatening / fix first:** #1 counterparty sanctions screening, #6 Travel Rule, #12 licensing-scope & conditions monitor, #3+#4 transaction monitoring + case/SAR workflow, #17 real filing channel & completeness.
- **Mandatory program depth:** #2 PEP, #5 CDD/EDD tiers + refresh, #13 cumulative limits, #9 privacy program, #10 retention + legal hold.
- **Examinability & governance:** #16 evidence pack + control testing, #18 risk register, #19 vendor/concentration, #14/#15 consumer disclosures & complaints, #11 breach workflow, #7 watchlist mgmt, #8 rules governance, #20 fraud controls, #21 whistleblower/attestations.

**Bottom line:** GME has built strong *plumbing and calculators* but not yet an *AML/CFT compliance program*, a *privacy program*, or an *examinable control environment*. Of the seven "already present" items, four are aggregation-only with a dead submission channel. Before any volume go-live or a first regulatory exam, at minimum #1, #3, #4, #6, #12 and #17 must move from "computes/builds" to "detects, decides, files, and can be evidenced."
