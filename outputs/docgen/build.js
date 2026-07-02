const fs = require("fs");
const {
  Document, Packer, Paragraph, TextRun, Table, TableRow, TableCell,
  AlignmentType, LevelFormat, HeadingLevel, BorderStyle, WidthType, ShadingType,
  TableOfContents, PageBreak, Header, Footer, PageNumber
} = require("docx");

// ---- numbering: one reusable bullet + many independent numbered refs (restart per list) ----
const numConfigs = [{
  reference: "bullets",
  levels: [{ level: 0, format: LevelFormat.BULLET, text: "•", alignment: AlignmentType.LEFT,
    style: { paragraph: { indent: { left: 480, hanging: 240 } } } }]
}];
for (let i = 1; i <= 40; i++) {
  numConfigs.push({ reference: "n" + i, levels: [{ level: 0, format: LevelFormat.DECIMAL, text: "%1.",
    alignment: AlignmentType.LEFT, style: { paragraph: { indent: { left: 480, hanging: 300 } } } }] });
}

// ---- helpers ----
const H1 = t => new Paragraph({ heading: HeadingLevel.HEADING_1, children: [new TextRun(t)] });
const H2 = t => new Paragraph({ heading: HeadingLevel.HEADING_2, children: [new TextRun(t)] });
const H3 = t => new Paragraph({ heading: HeadingLevel.HEADING_3, children: [new TextRun(t)] });
const P  = t => new Paragraph({ spacing: { after: 120 }, children: [new TextRun(t)] });
const Pi = (label, rest) => new Paragraph({ spacing: { after: 120 }, children: [new TextRun({ text: label, bold: true }), new TextRun(rest)] });
const bullet = t => new Paragraph({ numbering: { reference: "bullets", level: 0 }, children: [new TextRun(t)] });
const bulletB = (label, rest) => new Paragraph({ numbering: { reference: "bullets", level: 0 }, children: [new TextRun({ text: label, bold: true }), new TextRun(rest)] });
// numbered step with a bold lead label
const step = (ref, label, rest) => new Paragraph({ numbering: { reference: ref, level: 0 }, children: [new TextRun({ text: label + " — ", bold: true }), new TextRun(rest)] });
const stepPlain = (ref, text) => new Paragraph({ numbering: { reference: ref, level: 0 }, children: [new TextRun(text)] });
const note = t => new Paragraph({ spacing: { after: 160 }, children: [new TextRun({ text: "Note: ", bold: true, italics: true }), new TextRun({ text: t, italics: true })] });

// ---- microservices table ----
const svc = [
  ["API Gateway", "The front door. Authenticates partner requests (signature/keys), enforces rate limits and replay protection, and routes to internal services."],
  ["Payment Executor", "The orchestrator/brain of a payment. Coordinates rate, merchant check, prefunding, scheme submission and recording in the right order."],
  ["Smart Router", "Decides which partner/scheme should handle a scanned QR, and in what order (failover), based on the QR's network and the country."],
  ["QR Service", "Generates customer-presented QR/tokens and parses/validates scanned QR payloads (EMVCo)."],
  ["Rate-FX", "Produces locked exchange-rate quotes (USD-intermediary), applies margins, and holds the rate for a short window."],
  ["Prefunding", "Manages each partner's prepaid balance (float) with GME and GME's float with schemes: reserve, capture, release, top-up, low-balance alerts, credit limits."],
  ["Transaction Management", "The system of record for every transaction: its state machine, history, idempotency, and the rate-locked details used for reporting."],
  ["Scheme Adapter (ZeroPay)", "Talks to ZeroPay (Korea) in its language: two-phase authorize then commit, plus settlement files and refunds."],
  ["Scheme Adapter (Nepal)", "Talks to the Nepal partner (Khalti/Fonepay): decode, pay (single step), and status lookup."],
  ["Merchant & QR Data", "Holds the mirror of merchant/QR data and validates a scanned QR against a real, active merchant."],
  ["Revenue Ledger", "Books the accounting: double-entry journal entries for FX margin, service fee, fee-share split and rounding."],
  ["Settlement & Reconciliation", "Calculates what is owed (net/gross), matches GME's records against the scheme's settlement files, flags exceptions, and books the residual."],
  ["Reporting & Compliance", "Produces regulatory outputs: BOK FX reports, KoFIU AML (CTR/STR), and Hometax tax invoices."],
  ["Config Registry", "The source of truth for partners, schemes, pricing rules, corridors and credentials, with maker-checker (4-eyes) approval and a tamper-evident audit trail."],
  ["Auth-Identity", "Issues and verifies machine credentials (partner API keys, service tokens) and rotates them."],
  ["KYB Adapter", "Runs Know-Your-Business screening and business-registration verification during onboarding."],
  ["Notification / Webhook", "Delivers payment and settlement results to partners over signed webhooks, with retries and a dead-letter queue."],
  ["Ops/Partner BFF", "The backend that aggregates data from the services for the Admin and Partner web portals."],
  ["Admin & Partner Portals", "The web UIs: operations dashboards, onboarding, settlement/revenue views, a sandbox console, and partner self-service."],
  ["Simulators (sandbox)", "Stand-in mocks of the schemes/wallets (ZeroPay, Nepal QR, rate provider) used to exercise flows without live partners; they record every request/response."],
];
const cellBorder = { style: BorderStyle.SINGLE, size: 1, color: "CCCCCC" };
const borders = { top: cellBorder, bottom: cellBorder, left: cellBorder, right: cellBorder };
function svcRow(a, b, head) {
  const shade = head ? { fill: "D5E8F0", type: ShadingType.CLEAR } : undefined;
  const mk = (txt, w) => new TableCell({ borders, width: { size: w, type: WidthType.DXA }, shading: shade,
    margins: { top: 60, bottom: 60, left: 120, right: 120 },
    children: [new Paragraph({ children: [new TextRun({ text: txt, bold: !!head })] })] });
  return new TableRow({ children: [mk(a, 2600), mk(b, 6760)] });
}
const svcTable = new Table({
  width: { size: 9360, type: WidthType.DXA }, columnWidths: [2600, 6760],
  rows: [svcRow("Microservice", "What it does (in one line)", true), ...svc.map(r => svcRow(r[0], r[1]))]
});

const children = [];

// ---------- TITLE ----------
children.push(new Paragraph({ spacing: { before: 2400, after: 120 }, alignment: AlignmentType.CENTER,
  children: [new TextRun({ text: "GMEPay+", bold: true, size: 72, color: "1F3864" })] }));
children.push(new Paragraph({ alignment: AlignmentType.CENTER, spacing: { after: 80 },
  children: [new TextRun({ text: "End-to-End Feature Specification", bold: true, size: 40 })] }));
children.push(new Paragraph({ alignment: AlignmentType.CENTER, spacing: { after: 400 },
  children: [new TextRun({ text: "What the platform must do — and how the microservices coordinate to do it", italics: true, size: 26, color: "555555" })] }));
children.push(new Paragraph({ alignment: AlignmentType.CENTER, spacing: { after: 80 },
  children: [new TextRun({ text: "Version 1.0  ·  1 July 2026", size: 22 })] }));
children.push(new Paragraph({ alignment: AlignmentType.CENTER, spacing: { after: 40 },
  children: [new TextRun({ text: "Grounded in a full review of the GMEPay+ repository: the Hub-Core PRD, Business", size: 20, color: "777777" })] }));
children.push(new Paragraph({ alignment: AlignmentType.CENTER,
  children: [new TextRun({ text: "Scenario & Use-Case document, Specification, Service Map, inter-service contracts, the Settlement Flow Spec, and all 16 ADRs.", size: 20, color: "777777" })] }));
children.push(new Paragraph({ children: [new PageBreak()] }));

// ---------- TOC ----------
children.push(H1("Contents"));
children.push(new TableOfContents("Contents", { hyperlink: true, headingStyleRange: "1-2" }));
children.push(new Paragraph({ children: [new PageBreak()] }));

// ---------- 1. WHAT IT IS ----------
children.push(H1("1. What GMEPay+ Is"));
children.push(P("GMEPay+ is GME's cross-border QR-payment hub. It sits in the middle of three parties and lets a wallet/remittance partner's own customer pay a merchant by scanning a QR code — across borders and currencies — while GME handles the routing, foreign exchange, funding, settlement, accounting and regulatory reporting."));
children.push(H2("Who is involved"));
children.push(bulletB("Wallet / remittance partners (“API clients”)", " — e.g. GME Remit and SendMN today, more later. They own the end customer and their wallet."));
children.push(bulletB("Their customers", " — the people who scan and pay. GME never charges them directly; only the partner debits its own customer."));
children.push(bulletB("QR scheme providers", " — the local rails: ZeroPay in Korea; Khalti/Fonepay in Nepal; later Alipay, WeChat, QRIS, KHQR and others."));
children.push(bulletB("Merchants", " — the shops that receive the money and always get the full payout."));
children.push(bulletB("GME (the hub)", " — parses the QR, routes it, prices the FX, funds the payment, settles with everyone, and reports to regulators."));
children.push(bulletB("Regulators", " — Bank of Korea (FX reporting), KoFIU (anti-money-laundering), and the tax authority (Hometax invoices)."));
children.push(H2("What GME provides"));
children.push(P("QR parsing, smart routing, payment orchestration, multi-currency FX, prefunding (float) management, settlement & reconciliation, revenue accounting, regulatory reporting, partner onboarding, and admin/partner web portals."));
children.push(H2("How GME earns"));
children.push(P("Three revenue streams: (1) an FX margin on cross-border payments, (2) a service fee charged to the partner, and (3) a conditional share of the merchant fee (MDR) where applicable. Every partner × scheme × direction can be priced independently."));
children.push(H2("Phasing"));
children.push(bulletB("Phase 1", " — GME Remit (domestic KRW) and SendMN (overseas inbound) over ZeroPay."));
children.push(bulletB("Phase 2 and beyond", " — more partners and more schemes (incl. Nepal/Khalti), with automatic QR-based routing and failover across multiple partners per country."));
children.push(new Paragraph({ children: [new PageBreak()] }));

// ---------- 2. KEY CONCEPTS ----------
children.push(H1("2. Key Concepts (plain-language glossary)"));
children.push(bulletB("Partner vs customer vs merchant", " — the partner is GME's client; the customer is the partner's user who pays; the merchant is who gets paid."));
children.push(bulletB("MPM vs CPM", " — MPM (Merchant-Presented Mode): the customer scans the merchant's QR. CPM (Customer-Presented Mode): the merchant scans a QR/token from the customer's wallet."));
children.push(bulletB("Static vs dynamic QR", " — a static merchant QR carries no amount (the customer types it in); a dynamic QR already contains the amount."));
children.push(bulletB("Inbound vs outbound", " — inbound = a foreign customer pays a Korean merchant; outbound = a customer pays a merchant in another country (e.g. Nepal)."));
children.push(bulletB("Domestic vs cross-border", " — domestic has no FX (flat KRW fee); cross-border applies an FX margin."));
children.push(bulletB("Prefunding / float (double prefund)", " — partners keep a prepaid balance with GME, and GME keeps a prepaid balance with each scheme. Both are drawn down per transaction and topped up in bulk."));
children.push(bulletB("Authorize → Confirm (two-phase)", " — the payment is first reserved (nothing irreversible), then confirmed; GME only submits to the scheme after the partner has charged its customer."));
children.push(bulletB("Net vs gross settlement", " — domestic settles net; overseas settles gross with a separate monthly merchant-fee invoice."));
children.push(new Paragraph({ children: [new PageBreak()] }));

// ---------- 3. SERVICES AT A GLANCE ----------
children.push(H1("3. The Microservices at a Glance"));
children.push(P("The platform is built as independent microservices, each owning its own database and talking to the others only through APIs or events (never a shared database). The features below are delivered by these services coordinating with each other."));
children.push(svcTable);
children.push(new Paragraph({ children: [new PageBreak()] }));

// ---------- 4. END-TO-END FEATURE FLOWS ----------
children.push(H1("4. End-to-End Feature Flows"));
children.push(P("Each feature below is described as a coordinated sequence across the microservices — what each step does and what it triggers next."));

// F1 inbound
children.push(H2("4.1  Make a payment — inbound (foreign customer pays a Korean merchant, via ZeroPay)"));
children.push(P("This is the flagship two-phase flow. The guiding rules: only the partner charges its own customer, and GME submits to the scheme last (the only irreversible step)."));
children.push(step("n1", "Quote the rate", "The partner asks Rate-FX for a locked exchange-rate quote. Rate-FX returns a USD-based quote that is valid for a short window (about 15 minutes)."));
children.push(step("n1", "Start the payment (authorize)", "The partner sends the request to the Payment Executor, echoing the quoted amount. The Executor re-reads the quote from Rate-FX and checks the amount still matches."));
children.push(step("n1", "Check limits", "The Executor applies the per-transaction anti-money-laundering cap (collection amount + service fee) before anything moves."));
children.push(step("n1", "Identify the merchant", "The Executor asks Merchant & QR Data to resolve the scanned QR to a real, active merchant (rejecting unknown/suspended ones)."));
children.push(step("n1", "Create a pending transaction", "The Executor asks Transaction Management to record a PENDING transaction, snapshotting the rate, margins and merchant fee."));
children.push(step("n1", "Reserve the partner's float (hold, not charge)", "The Executor asks Prefunding to reserve the partner's balance for payout cost + FX margin + service fee. If the float is short, it declines here — nothing has moved."));
children.push(step("n1", "Check the scheme balance", "The Executor asks the ZeroPay Scheme Adapter to confirm GME holds enough prepaid balance with ZeroPay. If short, the authorization is voided before the customer is charged. Still nothing irreversible."));
children.push(step("n1", "Partner charges its own customer", "Only the partner debits its customer's wallet. GME never touches the customer's money."));
children.push(step("n1", "Confirm & pay the merchant", "The partner confirms; the Executor tells the ZeroPay Scheme Adapter to submit the payment to ZeroPay, which pays the merchant. This is the only irreversible step, and it is done last."));
children.push(step("n1", "Capture & commit", "On approval, Prefunding turns the hold into an actual debit (capture), and Transaction Management marks the transaction APPROVED and emits a “payment approved” event."));
children.push(step("n1", "Book revenue & notify", "Revenue Ledger consumes the event and books the FX margin, service fee and fee-share as a balanced journal entry; Notification/Webhook sends the result to the partner."));
children.push(note("If confirm declines, the hold is released and the transaction is marked FAILED. If confirm times out, the funds stay held and the transaction is marked UNCERTAIN for reconciliation. If it is never confirmed, an expiry sweeper releases the hold."));

// F2 outbound
children.push(H2("4.2  Make a payment — outbound (a customer pays a merchant abroad, e.g. Nepal)"));
children.push(P("A wallet scans a foreign QR (e.g. Fonepay). Routing is decided by the QR itself, and the platform fails over across partners if one is unavailable."));
children.push(step("n2", "Scan & classify", "The customer scans a Nepali QR. The hub's QR Classifier reads the QR's network identity (e.g. fonepay.com) and country — it routes by the QR, not just the country."));
children.push(step("n2", "Find the partners", "The Executor asks Smart Router which partner(s) can process that network in that country. Smart Router returns an ordered candidate list (by priority) from the partner configuration."));
children.push(step("n2", "Try the first partner", "The Executor calls the Nepal Scheme Adapter, which asks the partner (Khalti/Fonepay) to pay — a single synchronous step returning a payment reference."));
children.push(step("n2", "Fail over safely if needed", "On a technical failure (timeout/outage) the Executor first asks that partner whether the payment actually went through; only if it definitely did not does it try the next partner. A business decline (e.g. invalid QR) is final — no failover, no double-charge."));
children.push(step("n2", "If all fail", "The customer sees a clear “scheme unavailable,” not a vague error."));
children.push(step("n2", "Record", "On approval, Transaction Management records the transaction; every attempt is logged for audit."));
children.push(note("The Nepal corridor is single-phase today; adding FX (KRW→NPR) and prefunding reserve/capture for it is a documented next step."));

// F3 scan / amount
children.push(H2("4.3  Scan the QR and enter the amount"));
children.push(bulletB("Static merchant QR", " — no amount is encoded, so the customer types the amount, which is what gets authorized."));
children.push(bulletB("Dynamic merchant QR", " — the amount is already in the QR and is used as-is."));
children.push(bulletB("Customer-presented (CPM)", " — the wallet generates a one-time token (with a prefunding reservation) that the merchant terminal scans."));

// F4 refund
children.push(H2("4.4  Refund / cancel a payment"));
children.push(step("n4", "Request", "The partner (or an operator in the admin portal) requests a same-day cancel/refund for a transaction."));
children.push(step("n4", "Reverse at the scheme", "The Executor calls the scheme adapter's cancel/refund; the merchant payment is reversed."));
children.push(step("n4", "Restore & record", "Prefunding restores the actual reserved/debited amount, Transaction Management moves the transaction to REVERSED/REFUNDED, and Revenue Ledger books the mirror reversal at the originally locked rate."));

// F5 FX
children.push(H2("4.5  Quote the exchange rate & compute the amounts (FX)"));
children.push(P("Rate-FX prices every cross-border payment using a USD-intermediary, two-leg model (collection leg + payout leg) with a three-tier rate cascade."));
children.push(bulletB("Three-tier cascade", " — the scheme's KRW payout rate, plus GME's margin (quoted to the partner in KRW or USD), plus the partner's own margin (to its customer)."));
children.push(bulletB("Locked & time-limited", " — the quote is locked for a short window (~15 min); an expired quote is rejected at confirm; the lock survives a restart."));
children.push(bulletB("GME bears the FX risk", " between quote and settlement; domestic same-currency payments skip FX entirely (flat KRW fee)."));

// F6 prefunding
children.push(H2("4.6  Manage prefunding / balances"));
children.push(bulletB("Reserve → capture → release", " — funds are held at authorize, converted to a debit on success, or released on decline/expiry; concurrency-safe so a balance can never go negative."));
children.push(bulletB("Top-up & credit limit", " — partners top up in bulk; Config Registry can push a credit limit and AML caps to Prefunding."));
children.push(bulletB("Low-balance alerts", " — when a balance crosses a threshold, Prefunding emits an alert that Notification/Webhook sends to the partner and operations."));
children.push(bulletB("Two float relationships", " — partner→GME and GME→scheme (e.g. GME’s prefund/postfund with Khalti) are tracked independently."));

// F7 settlement
children.push(H2("4.7  Settle & reconcile"));
children.push(step("n7", "Calculate what is owed", "Settlement & Reconciliation computes each partner's position — net for domestic, gross for international — summing at full precision."));
children.push(step("n7", "Match against the scheme", "It matches GME's records against the scheme's settlement files per merchant; any mismatch or missing item raises a reconciliation exception and an ops alert."));
children.push(step("n7", "Book the journal & residual", "It rounds the per-partner liability once at the end and posts the tiny rounding difference as a journal entry in Revenue Ledger (exactly once per batch)."));
children.push(step("n7", "Ship the files & announce", "It generates the scheme settlement files (over secure transfer) and emits a “settlement completed” event; cross-date refunds are netted back."));

// F8 revenue
children.push(H2("4.8  Book revenue & accounting entries"));
children.push(bulletB("On “payment approved”", " — Revenue Ledger books a revenue-capture entry (FX margin + service fee) and the fee-share split as balanced double-entry lines."));
children.push(bulletB("On “settlement booked”", " — a journal entry is triggered for the settlement position and the rounding residual."));
children.push(bulletB("Configurable split", " — the fee-share (e.g. with the scheme) is configuration-driven, not hardcoded, and every entry balances."));

// F9 onboarding
children.push(H2("4.9  Onboard & configure a partner"));
children.push(step("n9", "Draft the partner", "An operator fills the multi-step onboarding wizard in Config Registry (identity, contacts, banking, pricing, schemes/corridors, credentials)."));
children.push(step("n9", "Verify (KYB)", "Config Registry calls the KYB Adapter to screen the business and verify its registration; the verdict (pass / fail / manual-review) is stored."));
children.push(step("n9", "Approve (4-eyes)", "A second operator approves the change; every change is maker-checker controlled and written to a tamper-evident audit trail."));
children.push(step("n9", "Issue credentials & wire up", "On activation, Config Registry issues partner API credentials (via Auth-Identity), registers the partner's webhook (via Notification), and pushes the credit limit to Prefunding."));
children.push(step("n9", "Set pricing rules", "Per partner × scheme × direction pricing (margins, rate source) is stored, enforcing the cross-border minimum-margin rule."));

// F10 routing
children.push(H2("4.10  Register QR schemes & route intelligently (many partners per country)"));
children.push(bulletB("Config-driven", " — each scheme/partner is registered with the QR network identifier(s) it serves and a priority. Adding a partner is a configuration change, not new code."));
children.push(bulletB("Route by the QR", " — Smart Router maps a scanned QR's network to the partner(s) that can serve it, filtered by country, mode and direction."));
children.push(bulletB("Failover", " — when more than one partner serves the same QR, they are tried in priority order; on a technical failure the platform checks whether the first already paid (to avoid double-charging) before moving to the next."));

// F11 merchant data
children.push(H2("4.11  Sync & validate merchant / QR data"));
children.push(bulletB("Validation", " — every scanned QR is checked against a real, active merchant; suspended/deactivated merchants are rejected with a precise reason."));
children.push(bulletB("Sync", " — merchant and QR data are mirrored from the scheme (files), including deactivation-on-receipt and orphan reconciliation."));

// F12 webhooks
children.push(H2("4.12  Notify partners (webhooks)"));
children.push(step("n12", "Enqueue", "When a payment is approved/failed (or a settlement completes), Notification/Webhook records a pending delivery in the same step as the event."));
children.push(step("n12", "Deliver & sign", "It signs the payload and posts it to the partner over HTTPS."));
children.push(step("n12", "Retry & DLQ", "On failure it retries with increasing back-off, and after exhaustion moves the item to a dead-letter queue and raises an alert; deliveries are idempotent."));

// F13 reporting
children.push(H2("4.13  Regulatory reporting"));
children.push(bulletB("Bank of Korea (FX)", " — every cross-border commit produces an FX report record (FX1014/1015), including the rate-locked “offer rate” field; domestic is exempt."));
children.push(bulletB("KoFIU (AML)", " — large/suspicious transactions are detected and filed (CTR/STR)."));
children.push(bulletB("Hometax (tax)", " — monthly overseas merchant-fee tax invoices are generated."));
children.push(note("The report content is produced by the platform; the live government submission channels (mTLS/SFTP) are provided by the regulators and are integrated when available."));

// F14 security
children.push(H2("4.14  Security & access control"));
children.push(bulletB("Partner API security", " — signed requests (HMAC), replay protection, per-partner rate limits, and credentials from a real store (not hardcoded)."));
children.push(bulletB("Operator security", " — human login via a real identity provider (OIDC), role-based access, partner-scoped data, and maker-checker (4-eyes) on configuration changes."));
children.push(bulletB("Audit", " — a tamper-evident (hash-chained) audit trail of every sensitive change."));

// F15 portals
children.push(H2("4.15  Admin & partner portals"));
children.push(bulletB("Operations", " — live dashboards for transactions, settlement, revenue, rates and system health."));
children.push(bulletB("Onboarding & approvals", " — the partner wizard, the approvals queue and the audit trail."));
children.push(bulletB("Sandbox console", " — an in-portal console (incl. a Nepal QR tab) to walk a payment through the simulators and see the stored request/response."));
children.push(bulletB("Partner self-service", " — partners view their balance, transaction history and statements (scoped to their own data)."));

// F16 platform
children.push(H2("4.16  Platform & operations"));
children.push(bulletB("Cloud-agnostic", " — the same container images run on-premise, AWS or Azure; only a configuration file changes per target."));
children.push(bulletB("One database per service", " — services never share a database; they coordinate only via APIs and events."));
children.push(bulletB("Events", " — business changes are published reliably (an outbox drained to a message bus) so revenue, webhooks and reporting react asynchronously."));
children.push(new Paragraph({ children: [new PageBreak()] }));

// ---------- 5. MONEY WATERFALL ----------
children.push(H1("5. The Money Waterfall"));
children.push(P("Money flows in a layered waterfall, and the funding behind it flows the opposite way:"));
children.push(new Paragraph({ alignment: AlignmentType.CENTER, spacing: { before: 120, after: 120 },
  children: [new TextRun({ text: "customer  →  partner float (with GME)  →  GME scheme float  →  merchant (full payout)", bold: true, size: 24, color: "1F3864" })] }));
children.push(bulletB("Only the partner charges the customer", " — GME never debits the end customer."));
children.push(bulletB("GME submits to the scheme last", " — the irreversible payment to the merchant happens only after the partner has confirmed and charged its customer."));
children.push(bulletB("Double prefund", " — the partner floats with GME, and GME floats with the scheme; both are drawn per transaction."));
children.push(bulletB("Three revenue streams", " — FX margin, service fee (GME→partner), and a conditional merchant-fee (MDR) share."));
children.push(bulletB("Refund is a mirror", " — a refund reverses the full amount at the originally locked rate."));
children.push(new Paragraph({ children: [new PageBreak()] }));

// ---------- 6. COORDINATION RULES ----------
children.push(H1("6. Cross-Service Coordination Rules (the non-negotiables)"));
children.push(bulletB("Submit last, and only once", " — the scheme submission is the single irreversible step and must be the last one."));
children.push(bulletB("Never double-charge on failover", " — before retrying another partner after a technical failure, confirm the first one did not already pay."));
children.push(bulletB("Idempotency everywhere", " — the same payment key never charges twice; retries replay the original result."));
children.push(bulletB("One database per service", " — services talk only through APIs and events, never a shared database."));
children.push(bulletB("Maker-checker on configuration", " — every partner/pricing/scheme change needs a second approver and is audited."));
children.push(bulletB("Reliable events", " — business writes and their events are recorded together (outbox), so revenue, webhooks and reporting never miss one."));
children.push(bulletB("Quotes are locked and time-boxed", " — and GME carries the FX risk within that window."));
children.push(new Paragraph({ children: [new PageBreak()] }));

// ---------- 7. PHASING & EXTERNAL ----------
children.push(H1("7. Phasing & External Dependencies"));
children.push(H2("Delivery phases"));
children.push(bulletB("Phase 1", " — GME Remit (domestic) and SendMN (overseas inbound) over ZeroPay."));
children.push(bulletB("Phase 2+", " — additional partners and schemes (incl. Nepal/Khalti), automatic QR-based routing, and multi-partner failover."));
children.push(H2("Depends on external parties (specifiable now, live only when the third party is ready)"));
children.push(bullet("ZeroPay / KFTC certification and live connectivity."));
children.push(bullet("The real Nepal partner (Khalti) live endpoint, signed requests, and IP allow-listing."));
children.push(bullet("Bank of Korea, Hometax and KoFIU live submission channels."));
children.push(bullet("Production infrastructure (managed database, message bus, cache, identity)."));
children.push(new Paragraph({ spacing: { before: 320 }, children: [new TextRun({ text: "— End of document —", italics: true, color: "777777" })] }));

// ---------- DOCUMENT ----------
const doc = new Document({
  creator: "GMEPay+ Platform", title: "GMEPay+ End-to-End Feature Specification",
  styles: {
    default: { document: { run: { font: "Arial", size: 22 } } },
    paragraphStyles: [
      { id: "Heading1", name: "Heading 1", basedOn: "Normal", next: "Normal", quickFormat: true,
        run: { size: 32, bold: true, font: "Arial", color: "1F3864" },
        paragraph: { spacing: { before: 280, after: 160 }, outlineLevel: 0 } },
      { id: "Heading2", name: "Heading 2", basedOn: "Normal", next: "Normal", quickFormat: true,
        run: { size: 26, bold: true, font: "Arial", color: "2E5496" },
        paragraph: { spacing: { before: 220, after: 120 }, outlineLevel: 1 } },
      { id: "Heading3", name: "Heading 3", basedOn: "Normal", next: "Normal", quickFormat: true,
        run: { size: 23, bold: true, font: "Arial", color: "444444" },
        paragraph: { spacing: { before: 160, after: 80 }, outlineLevel: 2 } },
    ]
  },
  numbering: { config: numConfigs },
  sections: [{
    properties: { page: { size: { width: 12240, height: 15840 }, margin: { top: 1440, right: 1440, bottom: 1440, left: 1440 } } },
    footers: { default: new Footer({ children: [new Paragraph({ alignment: AlignmentType.CENTER,
      children: [new TextRun({ text: "GMEPay+ — End-to-End Feature Specification   ·   Page ", size: 18, color: "999999" }),
                 new TextRun({ children: [PageNumber.CURRENT], size: 18, color: "999999" })] })] }) },
    children
  }]
});

Packer.toBuffer(doc).then(buf => {
  fs.writeFileSync("../GMEPay+_Feature_Specification.docx", buf);
  console.log("WROTE ../GMEPay+_Feature_Specification.docx", buf.length, "bytes");
});
