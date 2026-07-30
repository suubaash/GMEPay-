/**
 * content.js — the ONE content model for the exec-facing feature specification.
 *
 * Both renderings (`GMEPay+_Feature_Specification.docx` and `feature_spec_artifact.html`) are
 * produced from this array. They previously drifted apart: the .docx was corrected for the
 * AML/filing overstatement and the .html was not, so the prettier artifact — the one actually
 * shown to people — kept asserting that CTR/STR reports are filed. Two renderers, one model, is
 * the structural fix.
 *
 * EVERY qualified claim cites the register gap that justifies it via `assertGap(...)`, so a gap
 * moving breaks the build (see truth.js).
 */

'use strict';

const {
  assertGap, token, noSloTargetsDeclared, STATE, assertNothingClaimsLive,
} = require('./truth');

// ---------------------------------------------------------------------------
// Facts derived once, up front. Each assertGap call is load-bearing: it is the
// justification for the sentence(s) that use it.
// ---------------------------------------------------------------------------

// Regulatory filing: no lane has a live channel; filings terminate at a named status.
assertGap('T5-2', 'PARTIAL');
const NOT_FILED = token('filingChannel', 'NOT_FILED_CHANNEL_UNAVAILABLE');
token('adminFilingStatus', 'Green means filed, and nothing else.');

// Settlement transmission: nothing has ever left the platform.
assertGap('T4-5', 'PARTIAL');
const NOT_TRANSMITTED = token('settlementTransmission', 'NOT_TRANSMITTED_CHANNEL_UNAVAILABLE');
token('settlementChannel', 'channel_live');
token('adminSettlementStatus', 'Green means transmitted, and nothing else.');

// Screening: no provider, and no subject to screen.
assertGap('T5-3', 'PARTIAL');
assertGap('T5-11', 'OPEN');
assertGap('T1-4', 'PARTIAL');
const NO_PROVIDER = token('screeningProvenance', 'NO_PROVIDER_ID');

// Webhooks: signing is fixed, but no partner has ever verified a signature.
assertGap('T5-4', 'PARTIAL');
assertGap('T5-9', 'OPEN');

// Corridors.
assertGap('T4-1', 'PARTIAL');   // Nepal — structure closed, pricing pending an owner
assertGap('T4-3', 'OPEN');      // ZeroPay production edges are simulator-shaped
assertGap('T4-6', 'OPEN');      // SENDMN placeholders
assertGap('T4-7', 'DECISION');  // 9Pay payout has no hub orchestration

// Environment / operability.
assertGap('T1-6', 'OPEN');      // no always-on environment
assertGap('T3-1', 'PARTIAL');   // backup/restore built
assertGap('T3-8', 'PARTIAL');   // no restore drill has ever run
assertGap('T3-2', 'PARTIAL');   // metrics exist; nothing deploys Prometheus
assertGap('T3-3', 'PARTIAL');   // alert sweepers armed; no paging target
assertGap('T3-5', 'PARTIAL');   // load harness never run; SLOs undeclared
assertGap('T3-9', 'PARTIAL');   // Helm has no StatefulSet/PVC
assertGap('T3-10', 'PARTIAL');  // Helm path never exercised

// Partner-facing product.
assertGap('T1-2', 'PARTIAL');   // portal login unreachable
assertGap('T1-3', 'PARTIAL');   // portal pages are fixtures
assertGap('T1-5', 'DECISION');  // no self-serve journey

// Money mechanics.
assertGap('T2-4', 'PARTIAL');   // main P&L never reaches the journal
assertGap('T2-6', 'PARTIAL');   // refund money-path incomplete
assertGap('T2-7', 'PARTIAL');   // cross-border refunds misroute
assertGap('T2-12', 'OPEN');     // no adapter can transmit a partial refund
assertGap('T2-2', 'PARTIAL');   // no settlement feed for SendMN/9Pay
assertGap('T2-3', 'OPEN');      // 9Pay reversal dies in the adapter
assertGap('T2-5', 'PARTIAL');   // no replay job, no 3-way recon, no FX exposure tracking
assertGap('T3-4', 'PARTIAL');   // batch ops
assertGap('T3-11', 'PARTIAL');  // per-replica stores, no read timeouts
assertGap('T5-5', 'PARTIAL');   // no encryption/TLS/retention, root containers
assertGap('T5-8', 'CLOSED');    // rotation mechanism exists

// Security / audit.
assertGap('T0-2', 'PARTIAL');
assertGap('T0-3', 'PARTIAL');
assertGap('T0-6', 'PARTIAL');
assertGap('T0-7', 'PARTIAL');
assertGap('T5-1', 'PARTIAL');

// Things that ARE closed, and may therefore be stated without a caveat.
assertGap('T4-2', 'CLOSED');    // wallet limits enforced
assertGap('T4-4', 'CLOSED');    // merchant name on receipts
assertGap('T3-6', 'CLOSED');    // operating hours / cutoffs enforced
assertGap('T3-12', 'CLOSED');   // rounding-residual journal posts

const SLO_UNDECLARED = noSloTargetsDeclared();
if (!SLO_UNDECLARED) {
  throw new Error(
    'Documentation/SLO_TARGETS.properties now declares a target. Section 8 says the platform has '
    + 'no service-level objective; rewrite that row before regenerating.',
  );
}

/**
 * How many claims in this file are pinned to a register marker — counted from this file's own
 * source rather than typed, so the number in Section 9 cannot go stale when an assertion is added
 * or removed.
 */
const ASSERTED_GAP_COUNT = (require('fs')
  .readFileSync(__filename, 'utf8')
  .match(/^assertGap\(/gm) || []).length;
if (ASSERTED_GAP_COUNT < 30) {
  throw new Error(`only ${ASSERTED_GAP_COUNT} assertGap calls found — the self-count regex broke`);
}

// ---------------------------------------------------------------------------
// The never-exercised banner. This is the single most important block in the
// document, and it is derived: every line names the register gap that makes it
// true, so it cannot be softened without the register moving.
// ---------------------------------------------------------------------------

const NEVER_EXERCISED = {
  t: 'banner',
  title: 'READ THIS FIRST — WHAT HAS NEVER BEEN EXERCISED',
  intro:
    'This document describes a platform that has been BUILT and TESTED, and that has never been '
    + 'RUN IN ANGER. Every statement below is a verified fact about this branch, not a caveat:',
  lines: [
    ['No live fleet.', 'Nothing in this branch has ever run against a standing, always-on environment. '
      + 'The platform runs on one Windows laptop under Docker Compose; the Helm chart has never been '
      + 'applied to any cluster, and it carries no StatefulSet or volume claim for the datastores. (T1-6, T3-9, T3-10)'],
    ['No regulatory filing, ever.', 'Not one report has been submitted to the Bank of Korea, KoFIU or '
      + `Hometax. No lane has a transmission channel, so every filing stops at ${NOT_FILED}. (T5-2)`],
    ['No settlement file has ever been transmitted.', 'Files are generated and can be inspected; the only '
      + 'transport writes to a local directory, and a local directory is not a channel. Every batch in every '
      + `environment is terminal at ${NOT_TRANSMITTED}. (T4-5)`],
    ['No partner has ever verified a webhook.', 'The global signing secret was never the secret handed to '
      + 'partners, so partner-side verification could not have matched — for any partner, ever. That is fixed by '
      + 'derivation, but every existing endpoint still has to be rotated and its new secret handed over, and '
      + 'there is no end-to-end proof that any partner has verified one of our signatures. (T5-4, T5-9)'],
    ['No transaction has ever been screened.', 'There is no sanctions/PEP screening and no AML monitoring in '
      + 'the payment path. There is also nobody to screen: both payment entry points carry only an opaque '
      + `payer handle, so the screening provider id is literally ${NO_PROVIDER}. (T5-3, T5-11)`],
    ['No scheme certification.', 'The real-time ZeroPay path runs against a simulator; the Nepal corridor '
      + 'refuses every payment until an owner supplies its pricing; SENDMN runs on placeholder credentials; '
      + '9Pay payout has no hub orchestration at all. (T4-3, T4-1, T4-6, T4-7)'],
    ['No restore drill has ever run.', 'Backup scripts and a runbook exist. Nothing is scheduled, there is no '
      + 'WAL archiving and no off-host copy, the per-database dumps are not mutually consistent, and no '
      + 'restore has ever been performed. The recovery-point objective is 24 hours by accident, not by design. (T3-1, T3-8)'],
    ['No load test has ever been executed.', 'A load and soak harness exists and ships unrun: no baseline, no '
      + '10x run, no published result. There is also no service-level objective to measure against — '
      + 'SLO_TARGETS.properties ships entirely blank on purpose, because the targets are a business decision. (T3-5)'],
    ['No continuous monitoring.', 'The metrics endpoint and the payment SLIs are real and were verified by '
      + 'scraping a local fleet. Nothing deploys Prometheus, nothing aggregates logs, and there is no paging '
      + 'target — the alert sweepers are armed and terminate in a database table nobody is woken by. (T3-2, T3-3)'],
  ],
  closing:
    'Nothing in this document should be read as "in production". The honest summary is: the money mechanics '
    + 'are built and tested, and every edge that touches a real external counterparty — scheme, regulator, '
    + 'bank, partner — is either refusing on purpose or unproven.',
};

// ---------------------------------------------------------------------------
// Services. Each row carries a STATE so the table cannot describe a capability
// without saying how far it has actually got.
// ---------------------------------------------------------------------------

const SERVICES = [
  ['API Gateway', STATE.LOCAL,
    'The front door. Authenticates partner requests (signature/keys), enforces rate limits and replay '
    + 'protection, and routes to internal services. Both web UIs still call the BFF directly rather than '
    + 'through it, and the rate-limit and replay stores are per-replica in memory. (T0-8, T3-11)'],
  ['Payment Executor', STATE.LOCAL,
    'The orchestrator of a payment. Coordinates rate, merchant check, limits, prefunding, scheme submission '
    + 'and recording in the right order.'],
  ['Smart Router', STATE.LOCAL,
    'Decides which partner/scheme should handle a scanned QR, and in what order (failover), based on the '
    + 'QR\'s network and the country. Never exercised with two simultaneously live partners, because there '
    + 'are none.'],
  ['QR Service', STATE.LOCAL,
    'Generates customer-presented QR/tokens and parses/validates scanned QR payloads (EMVCo).'],
  ['Rate-FX', STATE.LOCAL,
    'Produces locked exchange-rate quotes (USD-intermediary), applies margins, and holds the rate for a '
    + 'short window.'],
  ['Prefunding', STATE.LOCAL,
    'Manages each partner\'s prepaid balance (float) with GME and GME\'s float with schemes: reserve, '
    + 'capture, release, top-up, low-balance alerts, credit limits, and the cumulative regulatory ceilings.'],
  ['Transaction Management', STATE.LOCAL,
    'The system of record for every transaction: state machine, history, idempotency, and the rate-locked '
    + 'details used for reporting. Its shared idempotency store activates only where Redis is configured, '
    + 'which today is the gateway alone. (T3-11)'],
  ['Scheme Adapter · ZeroPay', STATE.GATED,
    'Speaks ZeroPay\'s protocol: two-phase authorize then commit, settlement files and refunds. The '
    + 'real-time path runs against a simulator, several fixed-format QR fields are empty and the file '
    + 'transfer is a local-directory stub. Needs KFTC certification. (T4-3)'],
  ['Scheme Adapter · SENDMN (Mongolia)', STATE.GATED,
    'Mongolia QR pay-in. Built end to end and running on PLACEHOLDER credentials with an assumed QR '
    + 'application identifier, so any Mongolian QR classifies to it. Blocked on eight external items from '
    + 'the counterparty. (T4-6)'],
  ['Scheme Adapter · Nepal (Khalti/Fonepay)', STATE.GATED,
    'Decode, pay (single step) and status lookup, with real RSA request signing. It REFUSES every payment '
    + 'until an owner supplies the KRW→NPR margin and the service fee — by design, so that no price is '
    + 'invented. There is no scheme-side refund. (T4-1)'],
  ['Scheme Adapter · 9Pay (Vietnam)', STATE.DECISION,
    'A production-shaped VND payout adapter that NOTHING CALLS. Wiring it needs the product decision on how '
    + 'a payout is initiated, funded and approved. A post-success bank reversal currently dies inside the '
    + 'adapter without restoring float or reversing the ledger. (T4-7, T2-3)'],
  ['Merchant & QR Data', STATE.LOCAL,
    'Holds the mirror of merchant/QR data and validates a scanned QR against a real, active merchant. The '
    + 'mirror is fed by scheme files, and no scheme publishes one yet.'],
  ['Revenue Ledger', STATE.PARTIAL,
    'Double-entry journal entries for FX margin, service fee, fee-share split and rounding. The main P&L '
    + 'does not all reach the journal yet, so there is no trial balance, and the commission split is '
    + 'record-only. (T2-4)'],
  ['Settlement & Reconciliation', STATE.GATED,
    'Calculates what is owed (net/gross), matches GME\'s records against a scheme confirmation file, flags '
    + 'exceptions and books the residual. It has never transmitted anything, and there is no settlement feed '
    + `at all for the SENDMN or 9Pay corridors. Every batch is ${NOT_TRANSMITTED}. (T4-5, T2-2)`],
  ['Reporting & Compliance', STATE.GATED,
    'Produces the CONTENT of regulatory outputs: Bank of Korea FX reports, KoFIU AML report bodies and '
    + 'Hometax tax invoices. It does not file them — no submission channel is live, for any lane, and the '
    + 'status model now refuses to represent an unfiled report as accepted. (T5-2)'],
  ['Config Registry', STATE.LOCAL,
    'The source of truth for partners, schemes, pricing rules, corridors and credentials, with maker-checker '
    + '(4-eyes) approval and a hash-chained audit trail. The trail is not write-once storage and covers 4 of '
    + '21 services. (T5-1)'],
  ['Auth-Identity', STATE.LOCAL,
    'Issues and verifies machine credentials (partner API keys, service tokens) and rotates them. Also owns '
    + 'role-based access control and approval workflows.'],
  ['KYB Adapter', STATE.GATED,
    'The integration POINT for Know-Your-Business screening and business-registration verification at '
    + 'onboarding. No vendor is connected: the only responder is an in-process keyword matcher that consults '
    + 'no list, so activation REFUSES rather than passing an unscreened partner. (T1-4)'],
  ['Notification / Webhook', STATE.GATED,
    'Signs and posts payment and settlement results to partners, with retries and a dead-letter queue. No '
    + 'partner has ever successfully verified one of these signatures. (T5-9)'],
  ['Ops/Partner BFF', STATE.LOCAL,
    'The backend that aggregates data from the services for the Admin and Partner web portals.'],
  ['Admin & Partner Portals', STATE.PARTIAL,
    'Admin: operations dashboards, onboarding, approvals, settlement/revenue views and a sandbox console. '
    + 'Partner: transaction history and a settlement statement are real and read-only; API-key and webhook '
    + 'management are still fixtures, and partner login is unreachable in every environment as shipped. '
    + '(T1-2, T1-3)'],
  ['Simulators (sandbox)', STATE.LOCAL,
    'Stand-in mocks of the schemes/wallets (ZeroPay, SENDMN, 9Pay, Nepal QR, rate provider) used to exercise '
    + 'flows without live partners; they record every request/response. Everything this document calls '
    + '"exercised" was exercised against these.'],
];

// ---------------------------------------------------------------------------
// Operational readiness — the section the old document did not have at all.
// Omitting operability from an exec summary that describes dashboards and
// alerts is itself an overstatement, so it now has its own status table.
// ---------------------------------------------------------------------------

const READINESS = [
  { capability: 'Always-on environment', state: STATE.ABSENT,
    evidence: 'One Windows laptop running Docker Compose. The Helm chart has never been applied; it has no '
      + 'StatefulSet or volume claim, and one money-path service had no datasource in it at all — so the Helm '
      + 'path has never been exercised. The tunnel that supposedly fronts it exists nowhere in the repo. (T1-6, T3-9, T3-10)' },
  { capability: 'Monitoring', state: STATE.PARTIAL,
    evidence: 'The metrics endpoint exists (it did not before), and payment latency/outcome plus outbox depth '
      + 'and lag were verified by scraping a local fleet after 200 real payments. Nothing deploys Prometheus, '
      + 'there is no log aggregation, no per-partner indicator, and nowhere for a continuous measurement to live. (T3-2)' },
  { capability: 'Alerting / on-call', state: STATE.PARTIAL,
    evidence: 'The decline-spike and stuck-transaction sweepers are armed and durable. There is no paging '
      + 'target and no on-call rotation: an alert lands in a table and waits for someone to look. (T3-3)' },
  { capability: 'Backup', state: STATE.PARTIAL,
    evidence: 'Per-database dump scripts and a runbook exist and work. Nothing is scheduled, there is no WAL '
      + 'archiving, no off-host copy, and the dumps are sequential so they are not mutually consistent. (T3-1, T3-8)' },
  { capability: 'Restore / disaster recovery', state: STATE.ABSENT,
    evidence: 'No restore drill has ever been run, so the recovery time is unknown and the backups are '
      + 'unverified. The effective recovery-point objective is 24 hours. There is no second site and no '
      + 'failover plan. (T3-8)' },
  { capability: 'Service-level objectives', state: STATE.ABSENT,
    evidence: 'SLO_TARGETS.properties ships with every value blank, deliberately: the targets are a business '
      + 'decision, and the platform reports "no targets declared" rather than inventing one. The prior '
      + 'question an owner must answer is whether a declined payment counts against availability. (T3-5)' },
  { capability: 'Load / capacity proof', state: STATE.PARTIAL,
    evidence: 'The per-transaction footprint is MEASURED against a real local fleet (about 12 rows and 11 KB '
      + 'retained per payment, roughly 45 % below the earlier estimate, so AWS sizing was conservative in the '
      + 'safe direction). Latency and saturation are NOT measured: the load harness has never been run. (T3-5)' },
  { capability: 'Batch operations', state: STATE.PARTIAL,
    evidence: 'Failures are durable, alerted and re-runnable, and scheme operating hours and cutoffs are now '
      + 'enforced. The uncertain/stuck-payment operations loop is still only half-wired, and the manual '
      + 'escalation path for one corridor has no named owner. (T3-4, T3-5, T3-6)' },
  { capability: 'Secrets management', state: STATE.PARTIAL,
    evidence: 'Committed development defaults are no longer the live values in the hardened services, but '
      + 'there is no vault, no rotation and no key versioning, and a vendor credential is tracked in git '
      + 'history. (T0-6)' },
  { capability: 'Data protection', state: STATE.PARTIAL,
    evidence: 'No column encryption anywhere, no transport TLS between services, most containers run as root, '
      + 'and there is no retention or erasure policy. (T5-5)' },
];

// ---------------------------------------------------------------------------
// Sections
// ---------------------------------------------------------------------------

const SECTIONS = [
  {
    id: 's0', num: '0', navTitle: 'What has never been exercised', title: 'What Has Never Been Exercised',
    nodes: [NEVER_EXERCISED],
  },

  {
    id: 's1', num: '1', navTitle: 'What GMEPay+ is', title: 'What GMEPay+ Is',
    nodes: [
      { t: 'lead',
        text: 'GMEPay+ is GME\'s cross-border QR-payment hub. It sits in the middle of three parties and lets '
          + 'a wallet/remittance partner\'s own customer pay a merchant by scanning a QR code — across borders '
          + 'and currencies — while GME routes the payment, prices the foreign exchange, funds it, records it, '
          + 'and PRODUCES the settlement and regulatory artifacts. It does not yet deliver those artifacts to '
          + 'anyone: no settlement file has been transmitted to a scheme and no report has been filed with an '
          + 'authority.' },
      { t: 'h3', text: 'Who is involved' },
      { t: 'bullet', label: 'Wallet / remittance partners ("API clients")',
        text: ' — GME Remit (domestic KRW) and SendMN (overseas inbound) are the two integrated today. SendMN '
          + 'runs on placeholder credentials, so "integrated" means the code path is complete, not that the '
          + 'counterparty has signed off. (T4-6)' },
      { t: 'bullet', label: 'Their customers',
        text: ' — the people who scan and pay. GME never charges them directly; only the partner debits its own '
          + 'customer. The platform receives no identifying detail about them at all — only an opaque handle — '
          + 'which is why nobody can be screened. (T5-11)' },
      { t: 'bullet', label: 'QR scheme providers',
        text: ' — the local rails: ZeroPay in Korea; Mongolia via SendMN; Khalti/Fonepay in Nepal; 9Pay in '
          + 'Vietnam; later Alipay, WeChat, QRIS, KHQR and others. None of them has certified GMEPay+, and the '
          + 'real-time paths run against simulators. (T4-3)' },
      { t: 'bullet', label: 'Merchants',
        text: ' — the shops that receive the money and always get the full payout.' },
      { t: 'bullet', label: 'GME (the hub)',
        text: ' — parses the QR, routes it, prices the FX, funds the payment, computes the settlement position '
          + 'and generates the regulatory content.' },
      { t: 'bullet', label: 'Regulators',
        text: ' — Bank of Korea (FX reporting), KoFIU (anti-money-laundering) and the tax authority (Hometax '
          + 'invoices). There is no live submission channel to any of the three, and nothing has been filed '
          + 'with any of them. (T5-2)' },
      { t: 'h3', text: 'What GME provides' },
      { t: 'p', text: 'QR parsing, smart routing, payment orchestration, multi-currency FX, prefunding (float) '
        + 'management, settlement calculation and reconciliation, revenue accounting, regulatory report '
        + 'generation, partner onboarding, and admin/partner web portals.' },
      { t: 'h3', text: 'How GME earns' },
      { t: 'p', text: 'Three revenue streams: (1) an FX margin on cross-border payments, (2) a service fee '
        + 'charged to the partner, and (3) a conditional share of the merchant fee (MDR) where applicable. '
        + 'Every partner × scheme × direction can be priced independently. The FX margin and service fee are '
        + 'booked; the merchant-fee share is calculated and recorded but not yet billed or collected, and the '
        + 'main profit-and-loss does not all reach the double-entry journal, so there is no trial balance yet. (T2-4)' },
      { t: 'h3', text: 'Phasing (a plan, not a delivered state)' },
      { t: 'bullet', label: 'Phase 1',
        text: ' — GME Remit (domestic KRW) and SendMN (overseas inbound) over ZeroPay. Both money paths are '
          + 'built and pass end-to-end tests against simulators. Neither has processed a real payment.' },
      { t: 'bullet', label: 'Phase 2 and beyond',
        text: ' — more partners and more schemes (Nepal/Khalti, Vietnam/9Pay), with automatic QR-based routing '
          + 'and failover across multiple partners per country. Nepal is built and refusing on price; 9Pay is '
          + 'built and unwired pending a product decision.' },
      { t: 'note', text: 'How to read the rest of this document: every capability carries a state — '
        + `${STATE.LIVE.label}, ${STATE.LOCAL.label}, ${STATE.GATED.label}, ${STATE.PARTIAL.label}, `
        + `${STATE.ABSENT.label} or ${STATE.DECISION.label}. ${STATE.LIVE.label} means ${STATE.LIVE.gloss}; `
        + 'nothing in this platform is in that state, and the generator that produced this document refuses to '
        + 'let anything claim it while the register says there is no always-on environment.' },
    ],
  },

  {
    id: 's2', num: '2', navTitle: 'Key concepts', title: 'Key Concepts (plain-language glossary)',
    nodes: [
      { t: 'bullet', label: 'Partner vs customer vs merchant',
        text: ' — the partner is GME\'s client; the customer is the partner\'s user who pays; the merchant is '
          + 'who gets paid.' },
      { t: 'bullet', label: 'MPM vs CPM',
        text: ' — MPM (Merchant-Presented Mode): the customer scans the merchant\'s QR. CPM '
          + '(Customer-Presented Mode): the merchant scans a QR/token from the customer\'s wallet.' },
      { t: 'bullet', label: 'Static vs dynamic QR',
        text: ' — a static merchant QR carries no amount (the customer types it in); a dynamic QR already '
          + 'contains the amount.' },
      { t: 'bullet', label: 'Inbound vs outbound',
        text: ' — inbound = a foreign customer pays a Korean merchant; outbound = a customer pays a merchant in '
          + 'another country (e.g. Nepal).' },
      { t: 'bullet', label: 'Domestic vs cross-border',
        text: ' — domestic has no FX (flat KRW fee); cross-border applies an FX margin.' },
      { t: 'bullet', label: 'Prefunding / float (double prefund)',
        text: ' — partners keep a prepaid balance with GME, and GME keeps a prepaid balance with each scheme. '
          + 'Both are drawn down per transaction and topped up in bulk.' },
      { t: 'bullet', label: 'Authorize → Confirm (two-phase)',
        text: ' — the payment is first reserved (nothing irreversible), then confirmed; GME only submits to the '
          + 'scheme after the partner has charged its customer.' },
      { t: 'bullet', label: 'Net vs gross settlement',
        text: ' — domestic settles net; overseas settles gross with a separate monthly merchant-fee invoice.' },
      { t: 'bullet', label: 'A regulatory LIMIT is not a SCREENING',
        text: ' — the platform enforces statutory value and velocity ceilings (per-transaction, daily, monthly, '
          + 'annual, and a daily count). Those are numeric comparisons. They consult no sanctions list, no '
          + 'politically-exposed-person register and no adverse-media source, and they detect no pattern. This '
          + 'document uses "limit" for the first and reserves "screening" for the second, which does not exist. (T5-3)' },
      { t: 'bullet', label: 'Generated vs transmitted vs filed',
        text: ' — three different facts, routinely collapsed into one word. GME generates settlement files and '
          + 'report content (true today), transmits them to a scheme or authority (never happened), and '
          + 'receives an acknowledgement (never happened). The platform now carries a separate status for each '
          + `so the distinction survives into every screen and record. (T4-5, T5-2)` },
    ],
  },

  {
    id: 's3', num: '3', navTitle: 'The microservices at a glance', title: 'The Microservices at a Glance',
    nodes: [
      { t: 'p', text: 'The platform is built as independent microservices, each owning its own database and '
        + 'talking to the others only through APIs or events (never a shared database). The features described '
        + 'later are delivered by these services coordinating with each other.' },
      { t: 'p', text: 'The STATE column is the important one. It is not a maturity opinion: it says how far '
        + 'each service has been exercised, and the words mean exactly what the legend says they mean.' },
      { t: 'legend' },
      { t: 'services', items: SERVICES.map(([term, state, def]) => ({ term, state, def })) },
    ],
  },

  {
    id: 's4', num: '4', navTitle: 'End-to-end feature flows', title: 'End-to-End Feature Flows',
    nodes: [
      { t: 'p', text: 'Each feature below is described as a coordinated sequence across the microservices — '
        + 'what each step does and what it triggers next. Unless a step says otherwise, it has been exercised '
        + 'only against simulators and automated tests.' },

      { t: 'h3', text: '4.1  Make a payment — inbound (foreign customer pays a Korean merchant, via ZeroPay)',
        state: STATE.GATED },
      { t: 'p', text: 'This is the flagship two-phase flow. The guiding rules: only the partner charges its own '
        + 'customer, and GME submits to the scheme last (the only irreversible step). The flow is complete and '
        + 'covered end to end by automated tests against the ZeroPay SIMULATOR; the real-time protocol edge is '
        + 'not certified and several fixed-format QR fields are still empty. (T4-3)' },
      { t: 'steps',
        items: [
          ['Quote the rate', 'The partner asks Rate-FX for a locked exchange-rate quote. Rate-FX returns a '
            + 'USD-based quote that is valid for a short window (about 15 minutes).'],
          ['Start the payment (authorize)', 'The partner sends the request to the Payment Executor, echoing the '
            + 'quoted amount. The Executor re-reads the quote from Rate-FX and checks the amount still matches.'],
          ['Check the regulatory LIMITS', 'The Executor applies the statutory per-transaction value ceiling and '
            + 'the cumulative daily/monthly/annual and daily-count ceilings before anything moves. These are '
            + 'numeric caps enforcing the small-sum overseas-transfer limits. NO SCREENING HAPPENS HERE: no '
            + 'list is consulted, and the request carries no party name to consult one with. (T4-2, T5-3, T5-11)'],
          ['Identify the merchant', 'The Executor asks Merchant & QR Data to resolve the scanned QR to a real, '
            + 'active merchant (rejecting unknown/suspended ones), and the merchant name is carried through to '
            + 'the receipt. (T4-4)'],
          ['Create a pending transaction', 'The Executor asks Transaction Management to record a PENDING '
            + 'transaction, snapshotting the rate, margins and merchant fee.'],
          ['Reserve the partner\'s float (hold, not charge)', 'The Executor asks Prefunding to reserve the '
            + 'partner\'s balance for payout cost + FX margin + service fee. If the float is short, it declines '
            + 'here — nothing has moved.'],
          ['Check the scheme balance', 'The Executor asks the ZeroPay Scheme Adapter to confirm GME holds '
            + 'enough prepaid balance with ZeroPay. If short, the authorization is voided before the customer '
            + 'is charged. Still nothing irreversible.'],
          ['Partner charges its own customer', 'Only the partner debits its customer\'s wallet. GME never '
            + 'touches the customer\'s money.'],
          ['Confirm & pay the merchant', 'The partner confirms; the Executor tells the ZeroPay Scheme Adapter '
            + 'to submit the payment, which pays the merchant. This is the only irreversible step and it is '
            + 'done last. Today it is submitted to a simulator. (T4-3)'],
          ['Capture & commit', 'On approval, Prefunding turns the hold into an actual debit (capture), and '
            + 'Transaction Management marks the transaction APPROVED and emits a "payment approved" event.'],
          ['Book revenue & notify', 'Revenue Ledger consumes the event and books the FX margin, service fee '
            + 'and fee-share as balanced journal lines; Notification/Webhook signs and posts the result to the '
            + 'partner — a delivery no partner has yet verified. (T2-4, T5-9)'],
        ] },
      { t: 'note', text: 'If confirm declines, the hold is released and the transaction is marked FAILED. If '
        + 'confirm times out, the funds stay held and the transaction is marked UNCERTAIN for reconciliation — '
        + 'and the operational loop for resolving an UNCERTAIN payment is only half-wired, so today that means '
        + 'an operator notices. If it is never confirmed, an expiry sweeper releases the hold. (T3-5)' },

      { t: 'h3', text: '4.2  Make a payment — outbound (a customer pays a merchant abroad, e.g. Nepal)',
        state: STATE.GATED },
      { t: 'p', text: 'A wallet scans a foreign QR (e.g. Fonepay). Routing is decided by the QR itself, and the '
        + 'platform is built to fail over across partners if one is unavailable — never yet exercised with two '
        + 'live partners, because there are none.' },
      { t: 'steps',
        items: [
          ['Scan & classify', 'The customer scans a Nepali QR. The hub\'s QR Classifier reads the QR\'s network '
            + 'identity (e.g. fonepay.com) and country — it routes by the QR, not just the country.'],
          ['Find the partners', 'The Executor asks Smart Router which partner(s) can process that network in '
            + 'that country. Smart Router returns an ordered candidate list (by priority) from configuration.'],
          ['Price it, or refuse', 'The corridor reads its FX margin and service fee from the commercial-terms '
            + 'configuration. If either is missing it REFUSES the payment outright — no float moves and no '
            + 'scheme call is made. This is the corridor\'s state today: an owner has not supplied the KRW→NPR '
            + 'margin or the service fee, so the Nepal corridor declines every payment and is not sellable. (T4-1)'],
          ['Try the first partner', 'The Nepal Scheme Adapter asks the partner (Khalti/Fonepay) to pay — a '
            + 'single synchronous step returning a payment reference, RSA-signed with a real key. Against the '
            + 'simulator today; there is no live Khalti endpoint, credential or IP allow-listing.'],
          ['Fail over safely if needed', 'On a technical failure (timeout/outage) the Executor first asks that '
            + 'partner whether the payment actually went through; only if it definitely did not does it try the '
            + 'next partner. A business decline (e.g. invalid QR) is final — no failover, no double-charge.'],
          ['If all fail', 'The customer sees a clear "scheme unavailable", not a vague error.'],
          ['Record', 'On approval, Transaction Management records the transaction; every attempt is logged.'],
        ] },
      { t: 'note', text: 'Two honest residuals on this corridor: it has no scheme-side refund at all, and a '
        + 'cross-border cancellation currently routes to the wrong scheme adapter. (T4-1, T2-7)' },

      { t: 'h3', text: '4.3  Scan the QR and enter the amount', state: STATE.LOCAL },
      { t: 'bullet', label: 'Static merchant QR',
        text: ' — no amount is encoded, so the customer types the amount, which is what gets authorized.' },
      { t: 'bullet', label: 'Dynamic merchant QR',
        text: ' — the amount is already in the QR and is used as-is.' },
      { t: 'bullet', label: 'Customer-presented (CPM)',
        text: ' — the wallet generates a one-time token (with a prefunding reservation) that the merchant '
          + 'terminal scans.' },

      { t: 'h3', text: '4.4  Refund / cancel a payment', state: STATE.PARTIAL },
      { t: 'steps',
        items: [
          ['Request', 'The partner (or an operator in the admin portal) requests a same-day cancel/refund.'],
          ['Reverse at the scheme', 'The Executor calls the scheme adapter\'s cancel/refund; the merchant '
            + 'payment is reversed.'],
          ['Restore & record', 'Prefunding restores the reserved/debited amount, Transaction Management moves '
            + 'the transaction to REVERSED/REFUNDED, and Revenue Ledger books the mirror reversal at the '
            + 'originally locked rate.'],
        ] },
      { t: 'note', text: 'What does not work yet: the hub side of PARTIAL refunds is complete and tested, but '
        + 'no scheme adapter can transmit one, so a partial refund cannot actually be executed. A cross-border '
        + 'cancellation carries no scheme code and misroutes to ZeroPay. Nepal has no scheme-side refund. '
        + '(T2-6, T2-12, T2-7)' },

      { t: 'h3', text: '4.5  Quote the exchange rate & compute the amounts (FX)', state: STATE.LOCAL },
      { t: 'p', text: 'Rate-FX prices every cross-border payment using a USD-intermediary, two-leg model '
        + '(collection leg + payout leg) with a three-tier rate cascade.' },
      { t: 'bullet', label: 'Three-tier cascade',
        text: ' — the scheme\'s KRW payout rate, plus GME\'s margin (quoted to the partner in KRW or USD), plus '
          + 'the partner\'s own margin (to its customer).' },
      { t: 'bullet', label: 'Locked & time-limited',
        text: ' — the quote is locked for a short window (~15 min); an expired quote is rejected at confirm; the '
          + 'lock survives a restart.' },
      { t: 'bullet', label: 'GME bears the FX risk',
        text: ' between quote and settlement; domestic same-currency payments skip FX entirely (flat KRW fee). '
          + 'That exposure is not tracked anywhere for the unhedged currencies, and there is no day-close '
          + 'position report. (T2-5)' },

      { t: 'h3', text: '4.6  Manage prefunding / balances', state: STATE.LOCAL },
      { t: 'bullet', label: 'Reserve → capture → release',
        text: ' — funds are held at authorize, converted to a debit on success, or released on decline/expiry; '
          + 'concurrency-safe so a balance can never go negative.' },
      { t: 'bullet', label: 'Top-up & credit limit',
        text: ' — partners top up in bulk; Config Registry can push a credit limit and the statutory value '
          + 'ceilings to Prefunding.' },
      { t: 'bullet', label: 'Low-balance alerts',
        text: ' — when a balance crosses a threshold, Prefunding emits an alert that Notification/Webhook '
          + 'delivers to the partner and records for operations. Nothing pages a human: the operations end of '
          + 'every alert is a table someone has to open. (T3-3)' },
      { t: 'bullet', label: 'Two float relationships',
        text: ' — partner→GME and GME→scheme are tracked independently.' },

      { t: 'h3', text: '4.7  Settle & reconcile', state: STATE.GATED },
      { t: 'steps',
        items: [
          ['Calculate what is owed', 'Settlement & Reconciliation computes each partner\'s position — net for '
            + 'domestic, gross for international — summing at full precision.'],
          ['Match against the scheme', 'It matches GME\'s records against the scheme\'s confirmation file per '
            + 'merchant; any mismatch or missing item raises a reconciliation exception and an operations '
            + 'alert. No scheme publishes a confirmation file yet, so both sides of every reconciliation are '
            + 'currently GME-owned records.'],
          ['Book the journal & residual', 'It rounds the per-partner liability once at the end and posts the '
            + 'rounding difference as a journal entry in Revenue Ledger, exactly once per batch. (T3-12)'],
          ['GENERATE the files — and stop there', 'It produces the scheme settlement files, checksums them and '
            + 'emits a "settlement batch generated" event. IT DOES NOT SEND THEM. The only transport writes to '
            + 'a local directory, which the platform deliberately classifies as NO CHANNEL, so every batch in '
            + `every environment ends at ${NOT_TRANSMITTED} and says why. Real transfer needs scheme `
            + 'credentials and a certification run. (T4-5)'],
        ] },
      { t: 'note', text: 'Two more things an exec should know here: a batch can legitimately read RECONCILED '
        + 'while GME never sent the request file — those are separate columns on purpose — and there is no '
        + 'settlement or reconciliation feed at all for the SENDMN (USD) or 9Pay (VND) corridors, so nobody '
        + 'reconciles those against the float deductions. Netting is wired as a REPORT only; funding on a '
        + 'netted basis is an unmade treasury decision. (T4-5, T2-2)' },

      { t: 'h3', text: '4.8  Book revenue & accounting entries', state: STATE.PARTIAL },
      { t: 'bullet', label: 'On "payment approved"',
        text: ' — Revenue Ledger books a revenue-capture entry (FX margin + service fee) and the fee-share '
          + 'split as balanced double-entry lines. Every entry that is booked balances.' },
      { t: 'bullet', label: 'On "settlement booked"',
        text: ' — a journal entry is triggered for the settlement position and the rounding residual.' },
      { t: 'bullet', label: 'What is not booked yet',
        text: ' — the main profit-and-loss does not all reach the double-entry journal, so THERE IS NO TRIAL '
          + 'BALANCE; the commission split is record-only; the scheme\'s actual fee field is unused; and there '
          + 'is no merchant-fee billing or collection subsystem. Ledger posting is also fire-and-forget with a '
          + 'durable failure sink but no replay job, and there is no three-way daily reconciliation between '
          + 'scheme, ledger and float. (T2-4, T2-5)' },

      { t: 'h3', text: '4.9  Onboard & configure a partner', state: STATE.GATED },
      { t: 'steps',
        items: [
          ['Draft the partner', 'An operator fills the multi-step onboarding wizard in Config Registry '
            + '(identity, contacts, banking, pricing, schemes/corridors, credentials).'],
          ['Verify (KYB) — or record that nobody did', 'Config Registry calls the KYB Adapter to screen the '
            + 'business and verify its registration. NO SCREENING PROVIDER IS CONNECTED: the only responder is '
            + 'an in-process keyword matcher that consults no sanctions list, no politically-exposed-person '
            + 'register and no adverse-media source. The verdict is therefore recorded as NOT SCREENED, WITH '
            + 'ITS PROVENANCE, and activation REFUSES — an unscreened partner cannot be made live. The '
            + 'politically-exposed-person flag is a self-declared checkbox, not a register lookup. (T1-4)'],
          ['Approve (4-eyes)', 'A second operator approves the change; every change is maker-checker '
            + 'controlled and written to a hash-chained audit trail. (T5-1)'],
          ['Issue credentials & wire up', 'On activation, Config Registry issues partner API credentials (via '
            + 'Auth-Identity), registers the partner\'s webhook (via Notification), and pushes the credit limit '
            + 'to Prefunding.'],
          ['Set pricing rules', 'Per partner × scheme × direction pricing (margins, rate source) is stored, '
            + 'enforcing the cross-border minimum-margin rule. A corridor with no pricing refuses payments '
            + 'rather than guessing. (T4-1)'],
        ] },

      { t: 'h3', text: '4.10  Register QR schemes & route intelligently (many partners per country)',
        state: STATE.LOCAL },
      { t: 'bullet', label: 'Config-driven',
        text: ' — each scheme/partner is registered with the QR network identifier(s) it serves and a priority. '
          + 'Adding a partner is a configuration change, not new code.' },
      { t: 'bullet', label: 'Route by the QR',
        text: ' — Smart Router maps a scanned QR\'s network to the partner(s) that can serve it, filtered by '
          + 'country, mode and direction. One caveat worth naming: the Mongolian QR application identifier is '
          + 'assumed, so today ANY Mongolian QR classifies to SENDMN. (T4-6)' },
      { t: 'bullet', label: 'Failover',
        text: ' — when more than one partner serves the same QR, they are tried in priority order; on a '
          + 'technical failure the platform checks whether the first already paid (to avoid double-charging) '
          + 'before moving to the next. Correct by construction and by test; never exercised with two live '
          + 'partners.' },
      { t: 'bullet', label: 'Operating hours & cutoffs',
        text: ' — a scheme outside its operating window or past its cutoff is skipped as a failover candidate '
          + 'with a structured reason, rather than being called and failing. (T3-6)' },

      { t: 'h3', text: '4.11  Sync & validate merchant / QR data', state: STATE.LOCAL },
      { t: 'bullet', label: 'Validation',
        text: ' — every scanned QR is checked against a real, active merchant; suspended/deactivated merchants '
          + 'are rejected with a precise reason.' },
      { t: 'bullet', label: 'Sync',
        text: ' — merchant and QR data are mirrored from scheme files, including deactivation-on-receipt and '
          + 'orphan reconciliation. No scheme delivers those files yet, so every mirror is currently seeded '
          + 'from GME-side fixtures.' },

      { t: 'h3', text: '4.12  Notify partners (webhooks)', state: STATE.GATED },
      { t: 'steps',
        items: [
          ['Enqueue', 'When a payment is approved/failed (or a settlement batch is generated), '
            + 'Notification/Webhook records a pending delivery in the same step as the event, so an event can '
            + 'never be lost.'],
          ['Sign & deliver', 'It derives a per-endpoint signing secret, signs the payload and posts it to the '
            + 'partner over HTTPS. The signature is now derivable, which it was not: the global secret used '
            + 'previously was never the secret handed to partners, so PARTNER-SIDE VERIFICATION COULD NEVER '
            + 'HAVE MATCHED, FOR ANY PARTNER, EVER. (T5-4, T5-9)'],
          ['Retry & dead-letter', 'On failure it retries with increasing back-off, and after exhaustion moves '
            + 'the item to a dead-letter queue and raises an alert; deliveries are idempotent.'],
          ['Rotate — the outstanding partner task', 'Every pre-existing endpoint holds a secret that cannot be '
            + 'derived and must be rotated, with the new secret handed to that partner. Until that has happened '
            + 'for a given partner, "webhooks work" is unproven for them, and there is no end-to-end proof that '
            + 'any partner has ever verified a GMEPay+ signature. (T5-8, T5-9)'],
        ] },

      { t: 'h3', text: '4.13  Regulatory reporting', state: STATE.GATED },
      { t: 'bullet', label: 'Bank of Korea (FX)',
        text: ' — every cross-border commit produces an FX report record (FX1014/1015), including the '
          + 'rate-locked "offer rate" field; domestic is exempt. Generated, never sent.' },
      { t: 'bullet', label: 'KoFIU (AML)',
        text: ' — report CONTENT for a currency-transaction or suspicious-transaction report can be produced '
          + 'from transaction data. SUSPICIOUS-TRANSACTION DETECTION DOES NOT EXIST: there is no transaction '
          + 'screening and no AML monitoring in the payment path, so nothing is ever auto-flagged. And there is '
          + 'no route to file even a hand-identified case, because the KoFIU channel is not live. No report has '
          + 'ever been filed. (T5-3, T5-2)' },
      { t: 'bullet', label: 'Hometax (tax)',
        text: ' — monthly overseas merchant-fee tax invoices are generated. Generated, never sent.' },
      { t: 'bullet', label: 'What the platform will not pretend',
        text: ` — a filing's most advanced reachable status is ${NOT_FILED}, on every lane, with the missing `
          + 'configuration named. The invented "accepted"/"submitted" statuses that stubs used to produce are '
          + 'rejected at the database, and the admin surface reserves a success colour for a real filing — so '
          + 'nothing on any screen is green. (T5-2)' },
      { t: 'note', text: 'NOTHING HAS BEEN FILED WITH ANY REGULATOR. The platform produces report content; '
        + 'every government submission channel (mutual-TLS, secure file transfer, entity registration) is '
        + 'unconfigured. Separately, suspicious-transaction detection cannot be bought as a product: the '
        + 'payment path carries no payer identity at all — only an opaque handle — so a screening vendor would '
        + 'return "no match" on every transaction, which is indistinguishable from a clean result. The order of '
        + 'work is: partner contract change to carry party identity → vendor → compliance-owned rules → an '
        + 'alert-triage function → a filing channel. The first step is a partner-integration programme, not a '
        + 'purchase. (T5-11, T5-3)' },

      { t: 'h3', text: '4.14  Security & access control', state: STATE.PARTIAL },
      { t: 'bullet', label: 'Partner API security',
        text: ' — signed requests (HMAC), replay protection and per-partner rate limits at the gateway, with '
          + 'credentials from a real store. The rate-limit and replay-nonce stores are per-replica and in '
          + 'memory, so they hold only because everything runs at one replica. (T0-7, T3-11)' },
      { t: 'bullet', label: 'Operator security',
        text: ' — human login through a real identity provider (OpenID Connect), role-based access, '
          + 'partner-scoped data, and maker-checker on configuration changes. The hardened services verify the '
          + 'token; not every service has an authentication layer of its own yet. (T0-2, T0-3)' },
      { t: 'bullet', label: 'Audit',
        text: ' — a hash-chained, tamper-evident audit trail of sensitive changes. It is not write-once '
          + 'storage, it covers 4 of 21 services, and its off-box and archival tiers are not deployed. (T5-1)' },
      { t: 'bullet', label: 'What is missing',
        text: ' — no secrets vault, rotation or key versioning; no encryption of any database column; no '
          + 'transport TLS between services; most containers run as root; no retention or erasure policy; and '
          + 'the supply-chain security gates (static analysis, image scanning, software bill of materials) do '
          + 'not exist in the pipeline. (T0-6, T5-5)' },

      { t: 'h3', text: '4.15  Admin & partner portals', state: STATE.PARTIAL },
      { t: 'bullet', label: 'Operations (admin)',
        text: ' — dashboards for transactions, settlement, revenue, rates and system health; the approvals '
          + 'queue; the audit trail; a compliance overview. Filing and settlement honesty is enforced in the '
          + 'user interface itself: a success colour is reserved for a genuine filing or transmission, an '
          + 'absent status renders as UNKNOWN rather than being promoted, and standing banners state that '
          + 'nothing has been filed or transmitted — driven by the services, not hardcoded, so they self-remove '
          + 'if a channel ever goes live. This document mirrors that discipline deliberately. (T5-2, T4-5)' },
      { t: 'bullet', label: 'Onboarding & approvals',
        text: ' — the partner wizard, the four-eyes approvals queue and the audit trail.' },
      { t: 'bullet', label: 'Sandbox console',
        text: ' — an in-portal console to walk a payment through the simulators and see the stored '
          + 'request/response.' },
      { t: 'bullet', label: 'Partner self-service — read carefully',
        text: ' — a partner can view its balance, transaction history and a real settlement statement, scoped '
          + 'to its own data. API-key and webhook management are still fixtures with no implementation path, '
          + 'and PARTNER LOGIN IS UNREACHABLE IN EVERY ENVIRONMENT as shipped (realm/client/port mismatch). '
          + 'There is no self-serve sign-up: whether to build one or reposition the portal as read-only '
          + 'reporting with operator-led onboarding is an OPEN PRODUCT DECISION. Until it is made, the product '
          + 'should not be presented as self-service. (T1-2, T1-3, T1-5)' },

      { t: 'h3', text: '4.16  Platform & operations', state: STATE.PARTIAL },
      { t: 'bullet', label: 'Cloud-agnostic by design, unexercised in fact',
        text: ' — the same container images are intended to run on-premise, on AWS or on Azure with only a '
          + 'configuration change. That has never been demonstrated: the Helm chart has never been applied to a '
          + 'cluster, it declares no persistent storage for the datastores, and one money-path service had no '
          + 'database configured in it at all — which would have run the payment path on an in-memory database. '
          + 'Treat the deployment path as untested. (T1-6, T3-9, T3-10)' },
      { t: 'bullet', label: 'One database per service',
        text: ' — services never share a database; they coordinate only via APIs and events. This one is '
          + 'structurally true and enforced.' },
      { t: 'bullet', label: 'Events',
        text: ' — business changes are published reliably (a transactional outbox drained to a message bus) so '
          + 'revenue, webhooks and reporting react asynchronously. The outbox is real and monitored for depth '
          + 'and lag; the message bus is not deployed in the cluster path, so the off-box tiers that depend on '
          + 'it (including the audit log\'s regulator-defensible copy) do not exist yet. (T5-1)' },
      { t: 'bullet', label: 'Operability',
        text: ' — see Section 8. It is the weakest part of the platform and the part an exec is most likely to '
          + 'assume is present.' },
    ],
  },

  {
    id: 's5', num: '5', navTitle: 'The money waterfall', title: 'The Money Waterfall',
    nodes: [
      { t: 'p', text: 'Money flows in a layered waterfall, and the funding behind it flows the opposite way:' },
      { t: 'waterfall', nodes: ['customer', 'partner float (with GME)', 'GME scheme float', 'merchant · full payout'] },
      { t: 'bullet', label: 'Only the partner charges the customer',
        text: ' — GME never debits the end customer.' },
      { t: 'bullet', label: 'GME submits to the scheme last',
        text: ' — the irreversible payment to the merchant happens only after the partner has confirmed and '
          + 'charged its customer.' },
      { t: 'bullet', label: 'Double prefund',
        text: ' — the partner floats with GME, and GME floats with the scheme; both are drawn per transaction.' },
      { t: 'bullet', label: 'Three revenue streams',
        text: ' — FX margin, service fee (GME→partner), and a conditional merchant-fee (MDR) share. The third '
          + 'is calculated and recorded but not billed or collected. (T2-4)' },
      { t: 'bullet', label: 'Refund is a mirror — for a FULL refund',
        text: ' — a full refund reverses the amount at the originally locked rate. A partial refund is computed '
          + 'correctly by the hub and cannot be transmitted by any adapter, so it cannot be executed. '
          + '(T2-6, T2-12)' },
      { t: 'bullet', label: 'The waterfall stops one step short',
        text: ' — the final leg, telling the scheme what GME owes it by transmitting a settlement file, has '
          + 'never happened. Positions are computed and booked; the file sits on a local disk. (T4-5)' },
    ],
  },

  {
    id: 's6', num: '6', navTitle: 'Coordination non-negotiables',
    title: 'Cross-Service Coordination Rules (the non-negotiables)',
    nodes: [
      { t: 'p', text: 'These are the platform\'s invariants. Each is enforced in code and covered by tests; '
        + 'where enforcement depends on a deployment property that is not yet true, that is stated.' },
      { t: 'bullet', label: 'Submit last, and only once',
        text: ' — the scheme submission is the single irreversible step and must be the last one.' },
      { t: 'bullet', label: 'Never double-charge on failover',
        text: ' — before retrying another partner after a technical failure, confirm the first one did not '
          + 'already pay.' },
      { t: 'bullet', label: 'Idempotency everywhere',
        text: ' — the same payment key never charges twice; retries replay the original result. The shared '
          + 'idempotency store activates only where a cache is configured, which today is the gateway alone, so '
          + 'this invariant currently depends on running one replica of each service. (T3-11)' },
      { t: 'bullet', label: 'One database per service',
        text: ' — services talk only through APIs and events, never a shared database.' },
      { t: 'bullet', label: 'Maker-checker on configuration',
        text: ' — every partner/pricing/scheme change needs a second approver and is audited. One exception is '
          + 'known and named: the velocity-limit write is a direct single-actor change with no approval '
          + 'applier, which must be fixed before any real detection threshold is configured through it. (T5-3)' },
      { t: 'bullet', label: 'Reliable events',
        text: ' — business writes and their events are recorded together (outbox), so revenue, webhooks and '
          + 'reporting never miss one.' },
      { t: 'bullet', label: 'Quotes are locked and time-boxed',
        text: ' — and GME carries the FX risk within that window.' },
      { t: 'bullet', label: 'A capability that cannot act must REFUSE, not pretend',
        text: ' — this is the invariant this document exists to respect. An unscreened partner cannot be '
          + 'activated; a corridor with no configured price declines every payment; a settlement batch with no '
          + 'channel records that it was never sent and why; a filing with no channel terminates in a status '
          + 'that says so. Across the platform the refusing state is deliberately distinct from "not yet", '
          + 'because "cannot" and "not yet" are different facts. (T1-4, T4-1, T4-5, T5-2)' },
    ],
  },

  {
    id: 's7', num: '7', navTitle: 'Phasing & external dependencies',
    title: 'Phasing & External Dependencies',
    nodes: [
      { t: 'h3', text: 'Delivery phases (planned, not delivered)' },
      { t: 'bullet', label: 'Phase 1',
        text: ' — GME Remit (domestic) and SendMN (overseas inbound) over ZeroPay. Built; not certified; not '
          + 'live.' },
      { t: 'bullet', label: 'Phase 2+',
        text: ' — additional partners and schemes (Nepal/Khalti, Vietnam/9Pay), automatic QR-based routing and '
          + 'multi-partner failover. Nepal built and refusing on price; 9Pay built and unwired.' },
      { t: 'h3', text: 'Every external gate, and what it blocks' },
      { t: 'p', text: 'None of these can be closed by writing code. Each one is a counterparty, a contract or '
        + 'a decision, and each blocks a capability this document describes.' },
      { t: 'table',
        head: ['External gate', 'What it blocks until it opens'],
        rows: [
          ['ZeroPay / KFTC certification and live connectivity',
            'The whole Korea real-time path (today: a simulator), the fixed-format QR fields that are still '
            + 'empty, and any settlement transmission to ZeroPay. (T4-3, T4-5)'],
          ['SendMN counterparty sign-off (credentials, QR application identifier, envelope)',
            'The SENDMN corridor leaving placeholder credentials, and correct QR classification for Mongolia. (T4-6)'],
          ['Khalti live endpoint, credential and IP allow-listing',
            'The Nepal corridor connecting to anything real — though the corridor is blocked FIRST by a GME-side '
            + 'pricing decision. (T4-1)'],
          ['A 9Pay payout product decision (initiation, funding, approval)',
            'Any use of the Vietnam payout adapter, which nothing calls today. (T4-7)'],
          ['KoFIU entity registration, endpoint and file layout',
            'Filing any AML report. Detection is separately blocked by a partner-contract change. (T5-2, T5-3, T5-11)'],
          ['Bank of Korea secure-file-transfer endpoint',
            'Filing any FX report. (T5-2)'],
          ['Hometax mutual-TLS certificate',
            'Issuing any tax invoice to the authority. (T5-2)'],
          ['A screening vendor contract AND a partner contract change carrying party identity',
            'Any sanctions/PEP screening of a payer or beneficiary. The vendor alone buys nothing: with only an '
            + 'opaque handle, a vendor returns "no match" on everything. (T5-3, T5-11)'],
          ['Compliance ownership of rules, thresholds and alert triage',
            'AML monitoring. The rule engine ships with an EMPTY rule list on purpose — a plausible-looking '
            + 'invented threshold would read as a control that exists. (T5-3)'],
          ['Partner-by-partner webhook secret rotation and handover',
            'Any claim that webhook delivery is verified end to end. (T5-8, T5-9)'],
          ['Production infrastructure (managed database, message bus, cache, identity) and a standing environment',
            'Everything in Section 8: monitoring, alerting, backup verification, disaster recovery, and any '
            + 'measurement of a service level. (T1-6)'],
          ['A named owner for corridor pricing, netting, and the open finance decisions',
            'The Nepal corridor being sellable, funding on a netted basis, and several accounting treatments '
            + 'that are deliberately unimplemented rather than guessed. (T4-1, T4-5, T2-4)'],
        ] },
    ],
  },

  {
    id: 's8', num: '8', navTitle: 'Operational readiness', title: 'Operational Readiness (measured, not assumed)',
    nodes: [
      { t: 'p', text: 'The earlier version of this document had no section like this one. That was itself a '
        + 'form of overstatement: it described dashboards, alerts and a cloud-agnostic platform, and left a '
        + 'reader to assume the operating discipline behind them. This is the actual state.' },
      { t: 'status', rows: READINESS },
      { t: 'note', text: 'The single most useful sentence for planning: the money mechanics are the mature part '
        + 'of this platform, and operability is the immature part. Every item above is bounded work — none of '
        + 'it is research — but none of it is done, and two of them (a standing environment and a restore '
        + 'drill) gate the credibility of everything else.' },
    ],
  },

  {
    id: 's9', num: '9', navTitle: 'How this document stays true',
    title: 'How This Document Stays True',
    nodes: [
      { t: 'p', text: 'This document is GENERATED, not written. Both renderings — the Word file and the web '
        + 'artifact — come from one content model, so they cannot drift apart the way they previously did (the '
        + 'Word file was corrected for a false AML claim and the web artifact, the one people were actually '
        + 'shown, kept making it).' },
      { t: 'p', text: 'Every qualified statement above names the gap-register entry that justifies it, and the '
        + 'generator ASSERTS that entry\'s status at build time. If a gap closes, the build FAILS and names the '
        + 'sentence to revisit — so this document cannot quietly keep a stale pessimistic claim after the work '
        + 'lands. If a gap re-opens, likewise. The status words printed in Section 3 and Section 8 are read out '
        + 'of the platform\'s own source: the settlement transmission states, the filing-channel status, the '
        + 'screening provenance, and the admin user interface\'s own rule that a success colour means a real '
        + 'filing and nothing else. If any of those are renamed, the build fails rather than printing a word '
        + 'the platform no longer uses.' },
      { t: 'p', text: 'One value is structurally unreachable: no capability may be presented as LIVE while the '
        + 'register records that there is no always-on environment. That is enforced in the generator, not by '
        + 'editorial care.' },
      { t: 'bullet', label: 'Source of truth',
        text: ' — Documentation/GAP_REGISTER.md, which records for every gap whether it is closed, '
          + 'partly closed, open, or blocked on an owner\'s decision. A live tally is deliberately '
          + 'NOT printed here: it would make this document\'s bytes change on every unrelated '
          + 'register edit, and a check that cries wolf gets switched off. What is enforced instead '
          + `is precise — ${ASSERTED_GAP_COUNT} individual claims each assert the marker of the gap they cite.` },
      { t: 'bullet', label: 'Generator',
        text: ' — outputs/docgen/build.js (content in content.js, derivations in truth.js). Run '
          + '"node outputs/docgen/build.js" to regenerate both renderings.' },
      { t: 'bullet', label: 'If you find an overstatement in here',
        text: ' — it is a bug in content.js, and the fix belongs there rather than in the rendered file, which '
          + 'is overwritten on every build.' },
    ],
  },
];

// Structural interlock: nothing claims LIVE.
const stateBearing = [
  ...SERVICES.map(([term, state]) => ({ term, state })),
  ...READINESS,
  ...SECTIONS.flatMap((s) => s.nodes.filter((n) => n.state).map((n) => ({ term: n.text, state: n.state }))),
];
assertNothingClaimsLive(stateBearing);

const META = {
  title: 'GMEPay+',
  subtitle: 'End-to-End Feature Specification',
  tagline: 'What the platform does, what it only produces, and what has never been exercised',
  version: 'Version 2.0 · regenerated from the gap register',
  provenance:
    'Grounded in a full review of the GMEPay+ repository — the Hub-Core PRD, the Business Scenario & '
    + 'Use-Case document, the Specification, the Service Map, the inter-service contracts, the Settlement Flow '
    + 'Spec and all 16 ADRs — and reconciled line by line against Documentation/GAP_REGISTER.md, which is the '
    + 'authority on what is built, what is gated and what does not exist.',
};

module.exports = { META, SECTIONS, STATE };
