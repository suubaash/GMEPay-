> 작업: Business Development audit / 출처: agent

# GMEPay+ — Commercial / Growth Gap Audit
**Auditor lens:** Head of Business Development / Commercial. Date: 2026-07-01.
**Purpose:** What is still missing to *sell, onboard, price, and grow* — to sign more wallet/remittance partners, add schemes/corridors fast, and make partners drive more volume.

Scope note: the platform is strong at *processing* the payment and *booking* the money. It was built product-in, not market-out. The onboarding wizard, config-driven scheme registration, per-partner×scheme×direction pricing, self-service portal (balance/history/statements), signed APIs, sandbox+simulators, and (from Ops/Finance) control tower, disputes, billing, corridor profitability all exist. The gaps below are the **commercial layer** that turns a working platform into a *sellable, self-expanding business*. None are currently built. I deliberately do not repeat Ops (control tower, disputes, work-queues) or Finance (billing, corridor P&L, treasury).

---

## TOP COMMERCIAL / GROWTH GAPS

### A. Developer experience & self-serve integration — the #1 deal accelerator
1. **Public API documentation & developer portal.** Signed APIs exist; there is no partner-facing docs site (endpoint reference, auth/HMAC guide, error catalog, webhook spec, state diagrams, changelog). Today integration means a GME engineer on a call for every partner. This is the single biggest throttle on how many partners we can onboard in parallel — and the first thing a partner's tech team asks to see before signing.
2. **Self-service sandbox API keys + interactive quickstart.** The sandbox console + simulators exist, but a prospect can't get keys and fire a test transaction *without us*. A partner who runs a green test in the sandbox on day one is 10x more likely to sign. We need instant self-issued sandbox credentials, a "hello-payment in 15 min" quickstart, and downloadable Postman/OpenAPI collections.
3. **Client SDKs / helper libraries (at least Node, Java, PHP).** Partners re-implement HMAC signing, idempotency, retry, and webhook-signature verification by hand — the slowest, most bug-prone, most support-heavy part of every integration. SDKs cut time-to-live from weeks to days and cut our support load.
4. **Published API versioning & backward-compatibility contract.** Events use BACKWARD schema compat internally, but there is no *partner-facing* versioned API surface, deprecation policy, or sandbox-vs-prod parity guarantee. Without it, every platform change risks silently breaking live partners — the fastest way to lose the ones we worked hardest to sign.

### B. Partner acquisition funnel — lead → trial → contract → go-live
5. **Self-service partner signup / trial front door.** Onboarding starts at the KYB wizard, which is ops-operated and assumes an already-committed, contracted partner. There is no top-of-funnel: no "request sandbox access / start a trial" entry that a prospect self-serves before contract. We are invisible until a salesperson manually opens a case.
6. **Go-live readiness & certification checklist.** No structured pre-production gate (required test scenarios passed, webhook endpoint verified, credentials rotated to prod, limits set, sign-off). Every go-live is bespoke and slow; a repeatable certification lets Ops promote partners to production without an engineering escort.
7. **Sales-facing pipeline / funnel visibility.** No view of prospects by stage (lead → sandbox-active → integrating → certified → live) or where they stall. I can't manage BD reps, forecast go-lives, or see that a signed partner has been "integrating" for 60 days and needs a nudge.

### C. Commercial pricing flexibility — beyond a single margin
8. **Tiered / volume-based & minimum-commitment pricing.** Pricing today is one margin per partner×scheme×direction. There is no volume tiering (margin drops as monthly volume rises), minimum-volume commitment, or rebate/true-up — the standard levers that both *close* large partners and *incentivize them to push volume* to us instead of a competitor.
9. **Promotional / introductory rates with effective-dating & expiry.** No time-boxed launch pricing ("0 margin for first 90 days / first ₩X volume, then standard"). This is the classic tool to win a corridor launch and beat an incumbent on day one; without effective-dated auto-expiring promo rates it can't be offered safely.
10. **Packaged plans / rate cards as a sellable catalog.** Every deal is hand-configured field-by-field. There's no concept of standard commercial plans (Starter / Growth / Enterprise) a rep can quote from, which slows every negotiation and makes pricing inconsistent across partners.

### D. Time-to-market for new scheme / corridor / country
11. **Scheme/corridor launch playbook & readiness tracker.** Scheme *registration* is config, but standing up a new corridor is still a project (adapter, simulator, KYB provider, regulatory mapping, FX source, prefunding account). There is no templated launch tracker showing what's done vs blocking for a target corridor. This is our core growth engine — "how fast can we open Corridor X?" is the question every partner and exec asks, and today the answer is unmanaged.
12. **Pre-commercial corridor pilot / limited-launch mode.** No way to run a new corridor in controlled/whitelisted pilot (capped volume, selected partners, shadow pricing) before full commercial launch. Partners want to co-launch new corridors with us; we need a safe "test in prod with real money, small blast radius" mode.

### E. Partner analytics & insights — drive more usage from existing partners
13. **Partner-facing growth analytics dashboard.** The portal shows balance/history/statements (operational), not *insight*: their volume trend, success/decline rates and decline reasons, top corridors, average ticket, month-over-month growth, funnel drop-off. Partners who can *see* where they're losing transactions push more volume and escalate fewer support tickets. This is the highest-leverage retention/expansion feature we don't have.
14. **Decline-reason transparency & actionable error insight for partners.** Beyond raw decline counts, partners need categorized, actionable reasons (float empty, FX-quote expired, KYC, scheme timeout) so they can self-fix integration and UX issues that are silently killing their volume.

### F. Partner tiering, SLA & relationship management
15. **Commercial partner tiering with differentiated SLAs.** No tier model (Bronze/Silver/Gold) driving support-response SLA, rate limits, uptime commitment, and account-management level. Tiering is both a sales carrot ("hit Gold, get priority payout + better rate") and the mechanism that lets us serve 50 partners without treating all like the top 5.
16. **Published, measured commercial SLAs (uptime/latency/support) as a sales asset.** We have internal service health (Ops), but no *externally committed* SLA (99.9% uptime, payout-latency, support-response) with partner-visible status. Enterprise partners will not sign without a contractual SLA; measured uptime is also our strongest competitive stat.
17. **Partner CRM, health-scoring & QBR tooling.** No system of record for the commercial relationship: contacts, account owner, health score (volume trend + support load + float behavior), churn-risk flags, and QBR/business-review pack generation. Health scoring tells me which live partners are about to churn or ready to expand — you cannot grow an account book blind.

### G. Contract & commercial-terms lifecycle
18. **Contract / commercial-terms lifecycle management.** Onboarding captures pricing config but not the *contract*: signed agreement storage, term dates, renewal/expiry alerts, amendment history, linkage between the legal terms and the live pricing config. Today there's no way to know a partner's contract renews next month, or to prove the live margin matches what was signed.

### H. Positioning, coverage & incentives
19. **Live coverage map / scheme marketplace as a sales asset.** Config knows which partner×scheme×direction combos are live, but there is no partner/prospect-facing "here's every corridor, scheme and country we support, and what's coming" map. This is a primary sales asset ("we cover 8 corridors and 6 schemes") and it also lets partners self-discover expansion opportunities and request new corridors.
20. **Referral / incentive / co-marketing program.** No mechanism for partner referrals, volume-milestone bonuses, or co-marketing (partners promoting GMEPay+-powered corridors). Wallet ecosystems grow by referral; a structured incentive turns existing partners into a distribution channel.

### I. White-label & global reach
21. **White-label / co-branding & multi-language partner surface.** The self-service portal, statements, and any customer-facing rate/receipt surface are single-brand and (presumably) Korean/English only. Global partners (Mongolia, Nepal, and future corridors) need localized language/currency/date formats and, for larger deals, co-branded or embeddable widgets. Localization + white-label is often the deciding factor for a wallet partner choosing whose rails to embed.

### J. Merchant-side acquisition angle
22. **Merchant-facing value & acquisition surface.** Merchants pay MDR and get monthly invoices but have no portal, no cross-border-volume insight, and no "accept payments from N wallets/countries" acquisition story. Turning cross-border acceptance into a merchant-side pitch (either direct or co-sold with schemes) opens a second growth flywheel beyond wallet partners.

---

## Priority for the commercial engine
- **Unblock parallel onboarding (do first):** #1 API docs/dev portal, #2 self-serve sandbox keys, #5 self-service front door, #3 SDKs — these break the "one engineer per partner" ceiling and multiply how many partners BD can run at once.
- **Win & keep deals:** #8/#9 tiered + promo pricing, #16 committed SLAs, #15 tiering, #18 contract lifecycle.
- **Grow existing accounts:** #13 partner growth analytics, #17 CRM/health-scoring, #19 coverage map, #20 referral/incentives.
- **Scale the map:** #11 corridor launch tracker, #12 pilot mode — the machinery to add corridors as a repeatable product, not a project.
