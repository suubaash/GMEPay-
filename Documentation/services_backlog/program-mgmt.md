# program-mgmt  (program)

**Scope:** PM, requirements/open-items, go-live coordination (non-code)

**Owned WBS work-packages:** 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 16.2, 16.3, 16.5  ·  **Tickets:** 259  ·  **Est:** 176.0h

## Service contract (MSA: own DB, API-only communication)

- **Datastore (owned by this service):** n/a
- **APIs / events I EXPOSE:** program coordination, requirements, go-live (non-code)
- **APIs / events I CONSUME:** —
- **Integration rule:** never read another service's database or import its private entities — call its API or consume its event; stub consumed services with WireMock in tests.

> Self-contained backlog for this service. Build it as its own repo/module with its own DB + Flyway migrations, against the `shared-libs` contracts (lib-money / lib-errors / lib-events / lib-api-contracts only). Each ticket has a deliverable + acceptance checks.


## WBS 1.1 — Project kickoff & mobilization
### 1.1-T01 — Create Git repository and establish trunk-based branching structure  _(45 min)_
**Context:** GMEPay+ is a Global QR Payment Hub built by an external dev vendor for GME. The vendor is responsible for all code repository setup (DOC-00 Assumption A-00-04). OPS-13 mandates trunk-based development with branches: feature/<ticket> (PR to main), main (auto-deploys to int), release/<version> (auto-deploys to staging), hotfix/<ticket> (fast-track to prod). Repository must use GitHub or GitLab (recommended per SAD-02 tech stack). All code, migration scripts (db/migrations/), and OpenAPI spec are committed here.
**Steps:** Create a new private repository named gmepay-plus (or equivalent org namespace) on GitHub or GitLab.; Create branch protection rules: require PR review and passing CI status before merge to main.; Commit an initial README.md and .gitignore (Java + PostgreSQL, no secrets or env files).; Create the db/migrations/ directory with a .gitkeep placeholder.; Add a CODEOWNERS file mapping backend, frontend, devops, and qa paths to initial team leads.; Confirm all provisioned team members can clone and push feature branches.
**Deliverable:** Git repository live at a confirmed URL with main branch protected, branch structure documented in repo README, and team access verified.
**Acceptance / logic checks:**
- main branch has at least one push-ruleset requiring 1 approving review and passing status checks before merge.
- A developer with repo access can create feature/test-branch, push a commit, raise a PR to main, and see it blocked until approved.
- db/migrations/ directory exists at repository root.
- No secrets, credentials, or .env files are present in any commit.
- CODEOWNERS file exists and maps at least 4 path patterns to named owners.

### 1.1-T02 — Provision dev (E1) environment infrastructure and confirm access  _(60 min)_
**Context:** OPS-13 §2 defines four environment tiers: E1 dev (individual feature work, synthetic data, developers only), E2 int, E3 staging, E4 prod. All tiers deploy the same container images; only runtime config differs. E1 uses: PostgreSQL primary, Redis cache, message queue. Network zones: Public DMZ (LB+WAF), API Zone, Worker Zone, Data Zone, Management Zone. Cloud provider is a GME infrastructure decision; dev vendor provisions via IaC (Terraform or Pulumi). Secrets injected from secrets manager at container start - never in env files or Docker images.
**Steps:** Provision E1 (dev) cloud resources using IaC (Terraform or Pulumi): VPC/network, PostgreSQL instance, Redis, message queue placeholder, and a container runtime (Kubernetes or equivalent).; Configure secrets manager (HashiCorp Vault, AWS Secrets Manager, or Azure Key Vault) for the dev path and verify service retrieval at container start.; Deploy a skeleton hello-world container image to confirm the pipeline can reach E1.; Verify DNS label gmepay-dev (or equivalent) resolves and returns a 200 from the skeleton service.; Document all provisioned resource IDs, connection strings (without credentials), and environment-name conventions in a team wiki page.
**Deliverable:** E1 dev environment live and accessible to all developers; IaC scripts committed to repository under infra/; environment connection details documented in team wiki.
**Acceptance / logic checks:**
- Terraform/Pulumi plan applies with zero errors; state file stored in remote backend (not committed to repo).
- PostgreSQL on E1 is reachable from the API zone container but NOT from the public internet.
- Secrets manager dev path stores at least one test secret; a container can read it at startup and it does not appear in logs or image layers.
- DNS name for E1 resolves and skeleton service returns HTTP 200.
- A developer who was not the provisioner can access E1 using only the documented wiki instructions.
**Depends on:** 1.1-T01

### 1.1-T03 — Provision int (E2) environment infrastructure and confirm access  _(60 min)_
**Context:** OPS-13 §2.1 defines E2 integration as the environment for multi-service integration and eventual ZeroPay 한결원 test connection. E2 uses the same topology as E1 but is shared by Dev + QA. E2 is the only environment that will connect to 한결원 ZeroPay SFTP sandbox (separate SFTP credentials per environment; never share with prod). Milestone: dev environment provisioned by Apr 17, 2026 (PM-14 §3). Deploy same container images as E1; only runtime config differs.
**Steps:** Provision E2 (int) cloud resources using the same IaC module as E1, parameterised for the int environment name.; Configure secrets manager int path; reserve a placeholder secret slot for 한결원 SFTP credentials (to be filled when 한결원 provides them, target mid-May 2026).; Configure CI/CD auto-deploy trigger: merge to main branch automatically deploys to E2.; Verify DNS label gmepay-int (or equivalent) resolves and smoke-test returns 200.; Document E2 access in team wiki; confirm QA team members have access.
**Deliverable:** E2 int environment live with auto-deploy from main branch; SFTP credential slot reserved in secrets manager; documented in wiki.
**Acceptance / logic checks:**
- Merging a trivial commit to main triggers an automated deploy to E2 within 5 minutes of merge.
- E2 PostgreSQL and Redis are isolated from E1 (separate credentials, separate host names).
- Secrets manager int path exists and has a named placeholder key ZEROPAY_SFTP_HOST with a sentinel value TBD_PENDING_KFTC.
- QA team members can access E2 API endpoint but cannot access E1 secrets manager production path.
- E2 environment name is identical to the string int in all CI/CD pipeline variables and DNS labels (per OPS-13 canonical naming convention).
**Depends on:** 1.1-T02

### 1.1-T04 — Set up CI/CD pipeline skeleton with all 8 stages gated correctly  _(60 min)_
**Context:** OPS-13 §4.1 mandates an 8-stage pipeline: (1) Build/lint/type-check, (2) Unit test (coverage >= 80%), (3) Security scan (SAST + dep vuln + secrets leak; no critical/high CVEs), (4) Integration test (ephemeral env), (5) Artifact (container image tagged <git-sha>+<branch>+<semver>, pushed to registry, signed), (6) Deploy to int (auto, smoke tests pass), (7) Deploy to staging (auto on release/* cut, manual approval gate), (8) Deploy to prod (human approval: Release Manager + change record + QA sign-off). Branching: feature/* -> main -> int (auto); release/* -> staging (auto); hotfix/* -> prod (expedited gate). Tool: GitHub Actions or GitLab CI.
**Steps:** Create .github/workflows/ci.yml (or .gitlab-ci.yml) with all 8 stages as named jobs in the correct dependency order.; Stage 1: add a lint job that fails on any lint error; stage 2: run an empty test suite (placeholder) and assert coverage placeholder passes.; Stage 3: add SAST plugin (e.g. CodeQL or Semgrep) and a dependency scanner (e.g. OWASP Dependency Check or Snyk); configure to fail on critical/high CVEs.; Stage 5: build and push a hello-world container image to the artifact registry tagged with git SHA; add image signing step (placeholder if signing tooling not yet available).; Stage 6: trigger auto-deploy to E2 on main merge using the smoke test endpoint returning 200.; Stages 7-8: add manual approval gates as placeholder jobs (can be approved immediately for now); document approval flow in wiki.
**Deliverable:** CI/CD pipeline defined in version control; all 8 stages run on a test PR; stages 1-6 automated; stages 7-8 have manual gates defined.
**Acceptance / logic checks:**
- A PR to main that introduces a lint error causes stage 1 to fail and blocks merge.
- A PR with a high-severity CVE in a dependency causes stage 3 to fail and blocks merge.
- Merging to main triggers auto-deploy to E2 (stage 6) and smoke test passes without human intervention.
- Container image in artifact registry has a tag matching the git SHA of the triggering commit.
- Stages 7 (staging) and 8 (prod) are present in the pipeline definition and require a manual approval token before execution.
**Depends on:** 1.1-T02, 1.1-T03

### 1.1-T05 — Configure project management tooling: Jira project, epics, and initial sprint  _(60 min)_
**Context:** WBS 1.1 requires tooling setup. GMEPay+ has 4 phases: Phase 1 Build Core (Apr 10 - Jun 20, 2026), Phase 2 Integrate ZeroPay (May 15 - Jul 31), Phase 3 Launch GME Remit (Aug 1 - Oct 10), Phase 4 Launch Overseas Partners (Oct 1 - Dec 10). Go-live Oct 10, 2026. 8 workstreams: WS-1 Hub Core Backend, WS-2 Northbound API, WS-3 ZeroPay Southbound, WS-4 Admin System, WS-5 Partner Portal, WS-6 Security, WS-7 DevOps, WS-8 QA. RAID log entries from PM-14 must be tracked. Dev vendor PM is primary point of contact with GME Product Team (DOC-00 A-00-02).
**Steps:** Create a Jira project named GMEPay+ (key: GMEP) with Scrum board and 2-week sprint cadence.; Create epics matching the 8 workstreams (WS-1 through WS-8) plus one epic for WBS 1.1 Project Kickoff.; Create milestone markers as Jira Versions: v0.0-kickoff (Apr 10), v1.0-phase1-gate (Jun 20), v2.0-phase2-gate (Jul 31), v3.0-go-live (Oct 10), v4.0-phase4-gate (Dec 10).; Import or create Jira issues for each RAID log entry from PM-14 §7: Risks R-01 through R-08, Issues I-01 through I-04, Dependencies D-01 through D-08.; Configure Jira to link to the Git repository for automatic branch/commit traceability (feature/<ticket-id> convention).; Create Sprint 1 (Apr 10-24, 2026) and add the WBS 1.1 mobilization tickets as the initial backlog.
**Deliverable:** Jira project GMEP live with 8 epics, 5 versions, all RAID items as issues, Sprint 1 created and populated, and Git integration confirmed.
**Acceptance / logic checks:**
- All 8 workstream epics exist in GMEP project with correct names.
- Versions v1.0-phase1-gate through v4.0-phase4-gate exist with due dates matching PM-14 milestones (Jun 20, Jul 31, Oct 10, Dec 10, 2026).
- RAID risks R-01 through R-08 exist as Jira issues with severity and owner fields populated per PM-14 §7.1.
- Issues I-01 through I-04 exist as Jira issues with Open status and target dates matching PM-14 §7.3.
- Creating a Git branch named feature/GMEP-1-test links to the corresponding Jira issue automatically.

### 1.1-T06 — Set up team communication channels and meeting cadence  _(45 min)_
**Context:** WBS 1.1 comms plan requirement. DOC-00 §7 and PM-14 §8 (RACI) require regular communication between: Dev Vendor (builds everything), GME Product (requirements owner, business gate sign-off, accountable for RACI items A/R), GME Ops/Finance (rates, settlement, UAT), GME Compliance (BOK reporting, tax), and external parties (한결원/KFTC for ZeroPay, partners GME Remit/SendMN/T-Bank). PM-14 A-14-03: vendor adds RAID items and notifies GME PM at weekly status meeting; GME Product reviews within 2 business days. DOC-00 A-00-02: dev vendor nominates lead architect, lead backend, lead frontend, and PM.
**Steps:** Create a shared Slack (or Teams) workspace/channel structure: #gmepay-general, #gmepay-dev, #gmepay-ops, #gmepay-alerts (for CI/CD and monitoring), #gmepay-pm.; Schedule recurring meetings: (a) weekly status meeting (dev vendor PM + GME Product PM, 30 min), (b) bi-weekly sprint review/demo (all stakeholders, 45 min), (c) daily dev standup (dev vendor team only, 15 min).; Draft and circulate a one-page Comms Plan document listing: meeting cadence, channel purposes, escalation path for blockers, and contact roster (dev vendor leads + GME counterparts).; Confirm dev vendor lead nominations in writing to GME Product: lead architect, lead backend developer, lead frontend developer, PM (per DOC-00 A-00-02).; Set up an email distribution list (or equivalent) for formal written communications requiring a paper trail (spec changes, phase gate sign-offs, RAID updates).
**Deliverable:** Comms Plan document (PDF or Confluence page) circulated and acknowledged by GME Product; Slack/Teams channels live; recurring meetings in all calendars.
**Acceptance / logic checks:**
- Comms Plan lists a named contact for each of the 5 RACI roles: GME Product, GME Ops/Finance, GME Compliance, Dev Vendor, and a GME Business Development contact for 한결원 escalations.
- All four dev vendor lead roles (architect, backend, frontend, PM) have been nominated in writing per DOC-00 A-00-02.
- Weekly status meeting recurring invite exists in both the GME Product PM and dev vendor PM calendars.
- #gmepay-alerts channel is configured to receive CI/CD build failure notifications from the pipeline.
- Comms Plan explicitly states the RAID update protocol: vendor notifies GME PM at weekly status meeting; GME Product acknowledges within 2 business days.
**Depends on:** 1.1-T05

### 1.1-T07 — Circulate and obtain acknowledgement of the full documentation handover package  _(30 min)_
**Context:** DOC-00 states the handover package (16 documents) is self-contained; every developer, architect, QA, and PM must read relevant documents before raising clarification questions. Document statuses: For Development Handover (sufficient to begin Phase 1). Change control (DOC-00 §6.4): after vendor accepts, changes require written proposal, impact assessment by vendor, GME Product Owner approval, and a change note in the affected document. DOC-00 A-00-01: all 16 docs delivered simultaneously. A-00-03: For Development Handover status is sufficient to begin Phase 1 work. The 16 docs are: DOC-00, BRD-01, SAD-02, DAT-03, RATE-04, API-05, SCH-06, PRD-07, PRD-08, SEC-09, NFR-10, UX-11, QA-12, OPS-13, PM-14, REF-15.
**Steps:** Confirm receipt of all 16 documents in the dev vendor's shared storage (Confluence, SharePoint, or equivalent); verify none are missing.; Create a distribution confirmation task: each nominated lead (architect, backend, frontend, PM) signs off in writing (email or Confluence) that they have received and begun reading their role-specific documents per DOC-00 §4 reading paths.; Log the acceptance date (Apr 10, 2026 per PM-14 milestone) and document status as For Development Handover in the project wiki.; Record any immediate clarification questions raised within 48 hours of receipt as Jira issues tagged CLARIFICATION, linked to the relevant source document code.; Confirm change control process is understood: create a template for the written change proposal format (document code, section, proposed change, impact estimate).
**Deliverable:** Signed acknowledgement (email or Confluence record) from all four dev vendor leads confirming receipt of all 16 documents; change control template committed to wiki.
**Acceptance / logic checks:**
- A dated acknowledgement record exists showing all 16 document codes (DOC-00 through REF-15) were received on or before Apr 10, 2026.
- All four lead roles have individually acknowledged receipt in the record.
- Change control proposal template exists in the project wiki with fields: document code, section reference, proposed change text, requester, impact estimate (effort + timeline delta).
- Any clarification questions raised within 48 hours are logged as Jira issues with document code as a label.
- The wiki page records document status as For Development Handover for all 16 documents.
**Depends on:** 1.1-T05, 1.1-T06

### 1.1-T08 — Document ways-of-working: definition of done, branching convention, and ticket workflow  _(45 min)_
**Context:** WBS 1.1 ways-of-working requirement. OPS-13 §4.2 mandates trunk-based development with feature/<ticket> branches. PM-14 A-14-04: config-only for new partner/scheme is an acceptance criterion from Phase 1 - a code change to add a second partner is a defect. QA-12 notes: unit test coverage >= 80%; P1 defects fixed within same sprint; P2 defects fixed before UAT entry. All services emit structured JSON logs with txn_ref, partner_id, scheme_id, request_id (SAD-02 §11.2). OpenAPI 3.x spec committed to repository; ADRs required for design deviations (NFR-10 §12.3).
**Steps:** Write a Definition of Done (DoD) checklist covering: unit tests pass (coverage >= 80%), integration tests pass, no critical/high CVEs in security scan, OpenAPI spec updated if endpoint changed, structured JSON logs added for all new endpoints (fields: txn_ref, partner_id, scheme_id, request_id), PR reviewed by at least 1 peer, Jira ticket in Done state.; Write a branching and PR convention guide: branch naming feature/GMEP-{ticket-id}-{slug}, commit message format, PR description template, and squash-merge policy.; Define severity SLAs for defects: P1 (system down / data corruption) = fix within same sprint; P2 (functional defect blocking UAT) = fix before UAT entry; P3/P4 = scheduled per sprint priority.; Define ADR (Architecture Decision Record) template and location (docs/adr/) for design deviations from spec.; Publish all four documents to the project wiki and confirm acknowledgement from all team leads.
**Deliverable:** Four working-agreement documents in project wiki: Definition of Done, Branching Convention, Defect Severity SLAs, ADR template; all acknowledged by team leads.
**Acceptance / logic checks:**
- DoD checklist includes the structured JSON log requirement (fields txn_ref, partner_id, scheme_id, request_id) as a mandatory check.
- DoD checklist includes a check that adding a new partner requires only configuration - no code change - per PM-14 A-14-04.
- Branch naming guide specifies feature/GMEP-{ticket-id}-{slug} as the required format and includes an example.
- Defect SLA table maps P1 to same-sprint fix and P2 to pre-UAT-entry fix.
- ADR template exists at docs/adr/template.md in the repository.
**Depends on:** 1.1-T07

### 1.1-T09 — Configure secrets manager paths and access policies for all environments  _(60 min)_
**Context:** OPS-13 §3.5 and §4.7: secrets manager (HashiCorp Vault, AWS Secrets Manager, or Azure Key Vault) is the single source of truth for all credentials. Secrets are never stored in environment variable files, Docker images, or source code. Services retrieve secrets at startup. Secret categories (SEC-09 §4): DB credentials, Redis auth, API signing keys, SFTP credentials (한결원 - env-specific, never share across tiers), partner API keys. Path convention: gmepay/{env}/{service}/{secret-name} where env in {dev, int, staging, prod}. Dev and int use separate credential sets from staging and prod.
**Steps:** Create the secrets manager namespace gmepay/ with four sub-paths: gmepay/dev/, gmepay/int/, gmepay/staging/, gmepay/prod/.; Populate each path with placeholder secrets for: DB_URL, DB_PASSWORD, REDIS_AUTH, API_SIGNING_KEY, ZEROPAY_SFTP_HOST, ZEROPAY_SFTP_PORT, ZEROPAY_SFTP_USER, ZEROPAY_SFTP_KEY.; Define access policies: developers read gmepay/dev/ and gmepay/int/ only; DBA role reads gmepay/staging/ DB secrets; Release Manager reads gmepay/prod/; no human role reads gmepay/prod/SFTP credentials directly (break-glass only).; Write a secrets-rotation runbook stub: SFTP credentials rotate per OPS-13; API keys rotate every 12 months or on request (SEC-09); DB passwords rotate on suspected compromise.; Verify no secret values appear in application logs by deploying the skeleton app and checking log output contains only redacted references (e.g. DB_URL=****).
**Deliverable:** Secrets manager namespace gmepay/ with 4 env sub-paths, placeholder secrets populated, access policies applied, rotation runbook stub in wiki, and log-redaction verified.
**Acceptance / logic checks:**
- A developer credential can read gmepay/dev/DB_PASSWORD but receives 403 when attempting to read gmepay/prod/DB_PASSWORD.
- Container start logs show DB_URL=**** (redacted) not the actual connection string.
- ZEROPAY_SFTP_HOST in gmepay/dev/ and gmepay/int/ have distinct values confirming env separation (per OPS-13 §2.2: never share SFTP credentials across tiers).
- gmepay/prod/ SFTP credential path requires break-glass access (elevated role not held by any developer by default).
- Rotation runbook stub exists in wiki with at minimum: secret name, rotation frequency, and responsible role for each secret category.
**Depends on:** 1.1-T02, 1.1-T03

### 1.1-T10 — Set up observability stack: Prometheus, Grafana, and OpenTelemetry agent in E1/E2  _(60 min)_
**Context:** SAD-02 §11.2 mandates: Prometheus (metrics), Grafana (dashboards), OpenTelemetry (distributed tracing spanning all inbound API calls through scheme adapter). All services emit structured JSON logs with correlation fields: txn_ref, partner_id, scheme_id, request_id. Key metrics to capture from day one: payment success rate per partner, p99 API latency, prefunding balance per partner, SFTP batch delivery success, UNCERTAIN transaction count, webhook delivery success rate. Alert thresholds (SAD-02): partner p95 latency > 1000 ms, payment failure rate > 2%, UNCERTAIN transactions unresolved > 4 h, prefunding balance < USD 10,000 per partner.
**Steps:** Deploy Prometheus, Grafana, and an OpenTelemetry Collector into the Management Zone of E1 and E2 environments.; Configure the OTel Collector to receive traces from services and export to a backend (Jaeger or equivalent); configure Prometheus to scrape a /metrics endpoint on the skeleton service.; Create a Grafana datasource pointing at Prometheus; create a starter dashboard with placeholder panels for each of the 6 key metrics listed in SAD-02 §11.2.; Configure 4 alert rules in Prometheus AlertManager matching the SAD-02 thresholds: p95 latency > 1000 ms, failure rate > 2%, UNCERTAIN unresolved > 4 h, prefunding balance < USD 10000 per partner.; Verify end-to-end: send a test request to the skeleton service, confirm a trace appears in Jaeger and a metric increment appears in Grafana.
**Deliverable:** Observability stack deployed in E1 and E2; Grafana dashboard with 6 metric panels; 4 Prometheus alert rules configured; trace visible for test request.
**Acceptance / logic checks:**
- Grafana dashboard named GMEPay+ Overview exists with panels for all 6 metrics: payment success rate, p99 latency, prefunding balance, SFTP batch success, UNCERTAIN count, webhook success rate.
- AlertManager has exactly 4 alert rules with thresholds matching SAD-02: p95_latency_ms > 1000, failure_rate_pct > 2, uncertain_unresolved_hours > 4, prefunding_balance_usd < 10000.
- A test HTTP request to the skeleton service produces a trace ID visible in Jaeger with at least one span tagged with request_id.
- Prometheus scrapes the skeleton /metrics endpoint successfully (no scrape errors in Prometheus targets page).
- Alert rules fire correctly when a test metric is manually set to exceed the threshold.
**Depends on:** 1.1-T02, 1.1-T03

### 1.1-T11 — Create RACI register in project wiki and confirm stakeholder assignments  _(40 min)_
**Context:** PM-14 §8 defines the full RACI matrix for all project activities. Roles: GME Product (A/R on requirements sign-off, phase gate sign-off business), GME Ops/Finance (A/R on rate/margin config, FX treasury updates, settlement batch monitoring, UAT), GME Compliance (R on BOK reporting format, tax invoice ID), Dev Vendor (A/R on architecture, rate engine, API build, ZeroPay integration, CI/CD, Admin/Partner portal, security impl, defect resolution, training), 한결원 (R on test environment access, ZP00xx spec), Partner (R on prefunding top-up, low-balance alert response). Activities include 28 rows in PM-14 §8.
**Steps:** Create a RACI table in the project wiki (Confluence page or equivalent) with all 28 activity rows from PM-14 §8, each with R/A/C/I assignments for all 6 roles.; Add a named contact row below each role header: confirm actual named individuals for GME Product PM, GME Ops lead, GME Compliance lead, and dev vendor PM.; Flag the 4 open RACI items that are pending external parties: 한결원 test environment (D-01), ZP00xx file spec (D-02), BOK format (D-03), Tax invoice API (D-04).; Send the completed RACI to GME Product for formal acknowledgement; record the acknowledgement date.; Add a RACI change-log section at the bottom of the page: any subsequent changes require GME Product sign-off per DOC-00 §6.4 change control.
**Deliverable:** RACI register page in project wiki with all 28 activity rows, named contacts, 4 flagged open dependencies, and formal acknowledgement from GME Product.
**Acceptance / logic checks:**
- All 28 activity rows from PM-14 §8 are present in the wiki table with no blanks in any R/A/C/I column.
- Dev Vendor is assigned A/R for: architecture design, rate engine implementation, Northbound API build, ZeroPay batch integration, Admin System build, Partner Portal build, CI/CD, security implementation, defect triage, production deployment.
- GME Product is assigned A/R for: requirements sign-off and business phase gate sign-off.
- The 4 external dependencies (한결원 test env, ZP00xx spec, BOK format, tax invoice API) are flagged as unconfirmed with the target dates from PM-14 (D-01: May 15; D-02: May 15; D-03: TBD; D-04: before Phase 1 dev start).
- A date-stamped acknowledgement from GME Product is recorded on the wiki page.
**Depends on:** 1.1-T07

### 1.1-T12 — Log and assign all open RAID items as tracked Jira issues  _(50 min)_
**Context:** PM-14 §7 contains the full RAID log: 8 Risks (R-01 through R-08), 4 Issues (I-01 through I-04), 8 Assumptions (A-01 through A-08), 8 Dependencies (D-01 through D-08). PM-14 A-14-03: RAID is a living document; vendor adds new items and notifies GME PM weekly; GME Product acknowledges within 2 business days. Critical items: R-01 (한결원 test env delayed, High/High), I-04 (한결원 test env access not confirmed, open, due mid-May 2026), D-01 (한결원 ZeroPay test env, required by May 15, 2026). I-02 (Tax invoice API) is open-urgent and blocks Phase 1 go-live readiness (OI-02). I-03 (BOK reporting format OI-03) is open-urgent, blocks SEC-09 reporting module.
**Steps:** Create Jira issues for all 8 Risks (R-01 to R-08) in GMEP project with type=Risk; populate fields: description (from PM-14), likelihood, impact, mitigation, owner (named role), linked epic.; Create Jira issues for all 4 Issues (I-01 to I-04) with type=Issue; for I-02 and I-03 set priority=Critical and add BLOCKER label; set target dates per PM-14 §7.3.; Create Jira issues for all 8 Dependencies (D-01 to D-08) with type=Dependency; set due dates from PM-14 §6: D-01 and D-02 due May 15, 2026; D-05 due Aug 1, 2026; D-06 due Oct 1, 2026.; Link R-01, I-04, and D-01 to each other as related issues (all concern 한결원 test environment).; Set up a weekly reminder or Jira automation: every Friday, post a summary of all open RAID items to #gmepay-pm Slack channel.
**Deliverable:** 20 RAID Jira issues created (8 risks, 4 issues, 8 dependencies); I-02 and I-03 marked Critical; D-01 through D-08 have due dates; weekly RAID summary automation configured.
**Acceptance / logic checks:**
- Jira contains exactly 8 issues of type=Risk named R-01 through R-08 with owner and mitigation fields populated.
- I-02 (Tax Invoice API) and I-03 (BOK Reporting Format) have priority=Critical and label=BLOCKER; I-02 has target date before Phase 1 dev start.
- D-01 (한결원 ZeroPay test env) is linked to R-01 and I-04 as related issues.
- All 8 dependency issues have due dates: D-01 and D-02 = May 15, 2026; D-05 = Aug 1, 2026; D-06 = Oct 1, 2026; D-07 = before Phase 4 go-live; D-08 = Sep 26, 2026.
- A Jira automation or scheduled report exists that outputs open RAID items to the PM channel every Friday.
**Depends on:** 1.1-T05, 1.1-T11

### 1.1-T13 — Confirm and document open items OI-01, OI-02, OI-03 with interim development assumptions  _(45 min)_
**Context:** PM-14 §9 lists three open items that block specific components. OI-01 (Customer Approval Method): approval UX for CPM/MPM not specified; dev vendor must implement a generic partner-configurable approval step; target before Phase 3 go-live. OI-02 (Tax Invoice API): Hometax or KFTC API not identified; UC-04-04 is blocked; interim: stub with manual admin-portal workflow; target before Phase 1 dev start (urgent). OI-03 (BOK Reporting Format): FX1014/FX1015 fields, frequency, channel unknown; rate engine produces correct derived rates but reporting output module is blocked; if not resolved before Oct 10, 2026, GME assumes regulatory risk. PM-14 A-14-03: vendor adds RAID items and notifies GME PM weekly.
**Steps:** For OI-02: draft a one-paragraph interim assumption document stating that UC-04-04 will be implemented as a manual admin-portal workflow (operator manually triggers invoice issuance) with a future API hook stub; circulate to GME Account/Tech for written acknowledgement.; For OI-03: draft an interim assumption document stating that the BOK reporting module will be built with a pluggable output format interface; the data capture fields will be implemented from day one (preventing data loss); the submission channel and format will be integrated once OI-03 is confirmed; circulate to GME Compliance.; For OI-01: document that all approval steps will be implemented as a partner-configurable flag (no hard-coded approval mechanism); circulate to GME Tech/Product.; Create a Confluence table (or equivalent) listing OI-01, OI-02, OI-03 with: description, owner, target date, interim assumption text, acknowledgement status.; Add a recurring action item to the weekly status meeting agenda: check OI status and escalate if owner has not responded within 1 week.
**Deliverable:** Three interim assumption documents circulated and acknowledged; OI register page in wiki with current status and escalation path for each open item.
**Acceptance / logic checks:**
- OI-02 interim assumption explicitly states UC-04-04 will have a manual fallback in Phase 1 AND a future API hook stub in the codebase.
- OI-03 interim assumption explicitly states the reporting module will have a pluggable output interface and will capture all BOK fields from day one.
- OI-01 interim assumption explicitly states no approval mechanism will be hard-coded.
- All three OI documents have a named owner from the RACI (OI-01: GME Tech/Product; OI-02: GME Account/Tech; OI-03: GME Account/Compliance) and a written target date.
- The weekly status meeting agenda template includes a standing OI status check item.
**Depends on:** 1.1-T12

### 1.1-T14 — Produce and distribute kickoff pack: project charter, milestone schedule, and team contacts  _(50 min)_
**Context:** WBS 1.1 parent deliverable is a Kickoff Pack. PM-14 §3.1 milestones: contract signed Apr 10 2026; dev environment provisioned Apr 17 2026; Hub Core schema and rate engine May 9 2026; Admin portal core screens May 23 2026; Northbound API sandbox Jun 6 2026; Phase 1 gate Jun 20 2026; Phase 2 gate Jul 31 2026; Phase 3 go-live Oct 10 2026; Phase 4 gate Dec 10 2026. DOC-00 A-00-02: vendor nominates lead architect, lead backend, lead frontend, PM. Four components: Hub Core Backend, Admin System, Partner Portal, Northbound Partner API (all in scope Phase 1). Critical path: 한결원 test environment by mid-May 2026 (GME responsibility).
**Steps:** Create a Kickoff Pack document (Confluence page or PDF) with sections: (1) Project Overview (GMEPay+ Global QR Payment Hub summary, go-live Oct 10, 2026), (2) Team Contacts (named leads for all 6 RACI roles), (3) Milestone Schedule (all 15 milestones from PM-14 §3.1 with dates and owners), (4) Phase Plan summary (4 phases, durations, gates), (5) Critical Path highlight (한결원 test env by mid-May 2026), (6) Communication channels and meeting cadence.; Include the 4-component scope summary: Hub Core Backend (scheme/partner registry, rate engine, settlement, prefunding, BOK hooks), Admin System (Ops portal), Partner Portal (read-only), Northbound REST API + sandbox docs.; Include the ways-of-working summary: trunk-based development, 2-week sprints, DoD checklist, RAID update protocol.; Distribute to: all dev vendor team leads, GME Product PM, GME Ops lead, GME Compliance lead; request read acknowledgements.; File the signed/acknowledged kickoff pack as the official record of Phase 1 commencement (date: Apr 10, 2026).
**Deliverable:** Kickoff Pack document containing all six sections; distributed to all stakeholders; acknowledgement received from GME Product confirming Phase 1 commencement.
**Acceptance / logic checks:**
- Kickoff Pack contains all 15 milestones from PM-14 §3.1 with correct dates and owners (e.g. development environment provisioned = Apr 17, 2026, Dev Vendor; Phase 1 gate = Jun 20, 2026, Dev Vendor).
- All 6 RACI roles have a named contact in the Team Contacts section.
- Critical path note explicitly states: 한결원 test environment must be available by mid-May 2026; any delay propagates 1:1 to Phase 2 and Phase 3 go-live; GME BD is responsible for securing access.
- Scope section lists all 4 components as Phase 1 deliverables.
- A dated acknowledgement from GME Product PM is on file confirming the kickoff pack was received and Phase 1 build is authorised to begin.
**Depends on:** 1.1-T06, 1.1-T08, 1.1-T11, 1.1-T13

### 1.1-T15 — Confirm RACI is acknowledged and Phase 1 authority to proceed is granted  _(30 min)_
**Context:** PM-14 §5.1 and DOC-00 §7.1: GME Product is accountable for requirements sign-off and phase gate sign-off (business). Dev Vendor is accountable for technical phase gate. The contract signed and handover package accepted milestone date is Apr 10, 2026. DOC-00 §6.4 change control is triggered from this point. PM-14 A-14-04 is an acceptance criterion from Phase 1: config-only for adding partner/scheme. This ticket closes WBS 1.1 by obtaining a formal sign-off that all pre-conditions for Phase 1 build are met.
**Steps:** Prepare a Phase 1 Pre-Conditions Checklist with the following items: (a) Git repository created and team access confirmed, (b) E1 dev environment provisioned, (c) E2 int environment provisioned, (d) CI/CD pipeline skeleton live, (e) Jira project with all RAID items created, (f) Comms channels and meetings scheduled, (g) All 16 handover documents acknowledged, (h) Ways of working published and acknowledged, (i) Secrets manager configured, (j) Observability stack deployed in E1/E2, (k) RACI confirmed with named contacts, (l) Open items OI-01/OI-02/OI-03 documented with interim assumptions, (m) Kickoff pack distributed and acknowledged.; Send the checklist to GME Product PM and request sign-off that all 13 items are complete.; Record the sign-off date as the official Phase 1 Authority to Proceed (ATP) date.; Add an ATP record to the Jira project as a milestone marker and close all WBS 1.1 Jira tickets as Done.; Update the project wiki with the ATP date and the name of the GME Product signatory.
**Deliverable:** Phase 1 Authority to Proceed record with dated sign-off from GME Product PM; all 13 pre-condition items confirmed complete; WBS 1.1 Jira tickets closed.
**Acceptance / logic checks:**
- Phase 1 ATP record exists with a named GME Product signatory and a date on or before Apr 17, 2026 (development environment provisioned milestone).
- All 13 checklist items are ticked with a reference ticket ID or wiki link for each.
- The ATP date is recorded in the Jira project as a milestone event.
- All Jira tickets in the WBS 1.1 epic have status=Done at the time of ATP.
- The wiki ATP page references PM-14 A-14-04 explicitly: the Phase 1 build must support adding a second partner by configuration only; a code change to do so is a defect, not a change request.
**Depends on:** 1.1-T01, 1.1-T02, 1.1-T03, 1.1-T04, 1.1-T05, 1.1-T06, 1.1-T07, 1.1-T08, 1.1-T09, 1.1-T10, 1.1-T11, 1.1-T12, 1.1-T13, 1.1-T14


## WBS 1.2 — Requirements walkthrough & open-item resolution
### 1.2-T01 — Compile master open-items register from all source docs  _(45 min)_
**Context:** WBS 1.2 drives OI-01, OI-02, OI-03 to decisions. Before any resolution can happen, every reference to these three open items across all 16 spec docs must be collected into one authoritative register. PM-14 is the canonical tracking document. OI-01 = customer approval UX (CPM/MPM); OI-02 = tax-invoice API for Korean merchants (UC-04-04); OI-03 = BOK FX reporting format/frequency/channel (FX1014, FX1015).
**Steps:** Read every Assumptions and Open Items section in spec_full.txt (BRD-01, SAD-02, DAT-03, RATE-04, API-05, SCH-06, PRD-07, PRD-08, SEC-09, QA-12, PM-14) and extract every mention of OI-01, OI-02, OI-03.; For each OI entry record: source document, section, current interim assumption, affected component, phase gate, owner, and urgency.; Produce a markdown table with columns: OI-ID, Source-Doc, Section, Current-Interim-Assumption, Affected-Component, Phase-Gate, Owner, Urgency.; Verify the table against the PM-14 RAID log entries I-01, I-02, I-03 and dependency entries D-03, D-04.; Save the consolidated register as docs/clarification-log/oi-register.md in the project repo.
**Deliverable:** docs/clarification-log/oi-register.md — a complete table of all OI-01/OI-02/OI-03 references across all 16 source documents, with interim assumptions and phase-gate dependencies.
**Acceptance / logic checks:**
- OI-01 has at least 8 unique source references (BRD-01 §9, SAD-02 §13, DAT-03 §12, API-05 §12, RATE-04 §8, PRD-07 §16, QA-12, PM-14 §9).
- OI-02 is marked urgency=URGENT with target Before Phase 1 dev start and owner GME Account/Tech.
- OI-03 is marked urgency=URGENT with target TBD and owner GME Account/Compliance.
- Every entry carries the specific affected component (e.g. OI-03 affects bok_report_record schema, UC-08 main flow, SEC-09 §8.1.3, FEATURE_BOK_REPORTING flag).
- The register is cross-referenced against PM-14 RAID issues I-01, I-02, I-03 and dependencies D-03, D-04 — no discrepancy.

### 1.2-T02 — Draft OI-01 decision brief: customer approval UX options  _(40 min)_
**Context:** OI-01 (Customer Approval Method): for CPM the customer sees the final collection_amount only after the merchant scans and payment.pending_debit is dispatched; for MPM the customer inputs the target_payout amount and sees the receipt post-payment. Three candidate approval mechanisms are PIN, biometric, and confirmation button. The spec requires a generic, partner-configurable approval step with no code change per partner (config only). Owner: GME Tech/Product; target: before Phase 3 go-live. The hub is not involved in approval UX — it dispatches payment.pending_debit with offer_rate and collection_amount and the partner app handles it. SAD-02 §13 confirms the hub architecture is unaffected.
**Steps:** State the two payment modes with their timing constraints: MPM (customer inputs amount pre-payment; receipt shown post-payment) and CPM (customer sees collection_amount only after payment.pending_debit webhook arrives — after merchant scans).; List the three candidate approval mechanisms: PIN, biometric, confirmation button.; Identify the config field that enables each mechanism per partner without code change (suggest a partner-level field approval_method ENUM: PIN | BIOMETRIC | CONFIRMATION | SILENT).; Document the hub-side contract: GMEPay+ is not aware of, does not validate, and does not change its API based on the partner-chosen approval method. payment.pending_debit always delivers offer_rate and collection_amount.; Document the implication for the payment.pending_debit webhook fields — no additional fields are required for any of the three options.; Produce a 1-page decision brief with: option comparison table, recommended default, config schema, and the question for GME Tech/Product to answer.
**Deliverable:** docs/clarification-log/oi-01-decision-brief.md — option table with config schema and a single yes/no question to GME Tech/Product.
**Acceptance / logic checks:**
- Brief specifies that SILENT option (no acknowledgement required) is architecturally valid because hub-side flow is unchanged.
- Brief documents that CPM prefunding is deducted at POST /v1/payments/cpm/generate (QR token issuance), not at customer approval — this timing is fixed and not affected by OI-01 resolution.
- Config field approval_method defined as partner-level ENUM, not rule-level, with default value CONFIRMATION.
- Hub webhook payload section confirms payment.pending_debit fields (offer_rate, collection_amount, txn_ref) are sufficient for all three options with no field additions.
- Decision question is unambiguous: Must the partner app gate the final scheme commit on an explicit customer action, or may it approve silently?
**Depends on:** 1.2-T01

### 1.2-T03 — Draft OI-01 config schema: partner_approval_config table  _(35 min)_
**Context:** OI-01 resolution requires a partner-level config table that stores the approval method without code change. The partner entity already exists (partner table with id, type LOCAL|OVERSEAS, name, status). Adding approval_method as a column on partner or a separate partner_approval_config table allows ops to change it via the Admin portal. No code change per partner is the hard constraint (PM-14 A-14-04). Approval method applies to both CPM and MPM modes and may differ by mode.
**Steps:** Design the partner_approval_config table: columns partner_id FK, payment_mode (MPM|CPM|ALL), approval_method ENUM(PIN, BIOMETRIC, CONFIRMATION, SILENT), effective_from TIMESTAMPTZ, created_by, created_at.; Write a SQL migration (V1.2__partner_approval_config.sql) using Flyway-style naming.; Add a CHECK constraint: payment_mode IN ('MPM','CPM','ALL').; Add a UNIQUE constraint on (partner_id, payment_mode) to prevent duplicate rows.; Insert default rows for existing partners: payment_mode=ALL, approval_method=CONFIRMATION.; Verify the migration runs without error against an empty schema and against the schema with existing partner rows.
**Deliverable:** db/migration/V1.2__partner_approval_config.sql — migration file creating the partner_approval_config table with constraints and default seed rows.
**Acceptance / logic checks:**
- Table creates successfully in a clean PostgreSQL schema with no FK violations.
- Inserting a second row for the same (partner_id, payment_mode) raises a unique-constraint violation.
- payment_mode value OTHER raises a check-constraint violation.
- Default rows seeded for all existing partners with approval_method=CONFIRMATION.
- Migration is idempotent when re-applied to a schema that already contains the table (use CREATE TABLE IF NOT EXISTS or Flyway versioning).
**Depends on:** 1.2-T02

### 1.2-T04 — Document OI-01 interim assumption and impact on QA-12 test cases  _(30 min)_
**Context:** Until GME Tech/Product resolves OI-01, development proceeds with the interim assumption that the approval step is a generic confirmation button (CONFIRMATION). QA-12 test cases for CPM (HC-004, PA-008) and MPM payment flows reference the approval step. The test vector must be updated to note which field drives the step and that the field is configurable. The hub does not change its behaviour — the impact is entirely in partner integration tests.
**Steps:** Locate QA-12 test cases HC-004 (CPM prefund at QR generate), PA-008 (rate quote TTL), and the MPM end-to-end test cases in the test case register.; For each affected test case add a note: Approval method = CONFIRMATION (interim default per OI-01); will be overridden when OI-01 is resolved.; Document that the hub-side acceptance criterion is unchanged: payment.pending_debit is dispatched after merchant scan; the test does not assert what the partner app does with it.; Add a new OI-01 assumption row to docs/clarification-log/assumptions-update.md: A-OI-01: approval_method defaults to CONFIRMATION per partner_approval_config; hub flow unchanged.; Update the PM-14 RAID issue I-03 status column to IN_PROGRESS with the interim assumption recorded.
**Deliverable:** docs/clarification-log/assumptions-update.md with A-OI-01 entry; updated QA-12 test case notes.
**Acceptance / logic checks:**
- A-OI-01 entry states the exact interim assumption (CONFIRMATION), the affected phase gate (Phase 3), and the owner (GME Tech/Product).
- QA-12 test case HC-004 note specifies that prefunding deduction timing (at POST /v1/payments/cpm/generate) is independent of OI-01 and does not change under any resolution.
- The assumptions update doc is appended, not overwriting existing assumptions.
- PM-14 RAID I-03 reflects in-progress status with the interim assumption and target date.
- No test case asserts a specific customer-app behaviour for the approval step.
**Depends on:** 1.2-T03

### 1.2-T05 — Draft OI-02 decision brief: tax-invoice API options for Korean merchant billing  _(40 min)_
**Context:** OI-02 (Tax Invoice API): GME must issue monthly tax invoices to Korean merchants for the merchant fee on overseas (cross-border) transactions (UC-04-04). Formula: merchant_fee_krw = monthly KRW total x fee_rate; vat_krw = merchant_fee_krw x 10%; invoice_amount_krw = merchant_fee_krw + vat_krw; zeropay_share_krw = monthly KRW total x 0.21%. The tax_invoice table already has invoice_ref VARCHAR(64) UNIQUE and issued_at TIMESTAMPTZ as placeholder columns pending API confirmation. Two candidate paths: (A) Hometax API (e-tax invoice API provided by NTS, National Tax Service); (B) KFTC-intermediated mechanism. Fallback: manual admin-portal workflow where GME Finance exports the monthly aggregation and issues invoices manually. Owner: GME Account/Tech; target: before Phase 1 dev start. Urgency = URGENT.
**Steps:** State the business obligation: monthly KRW merchant fee invoicing for all inbound (Overseas partner) ZeroPay transactions.; List the two candidate APIs: Hometax NTS e-Tax API and KFTC-intermediated mechanism; document what is known and unknown about each.; Describe the fallback: manual export from Admin portal showing per-merchant monthly totals (total_transaction_amount_krw, fee_rate, merchant_fee_krw, vat_krw, invoice_amount_krw) — this must work for Phase 1.; Define the stub contract: the tax_invoice.invoice_ref column is NULL until OI-02 resolved; the issued_at column is set to the manual issuance timestamp; status transitions DRAFT -> ISSUED -> COLLECTED | FAILED.; Specify the questions GME Account must answer: (1) Which API provider? (2) Is it a push (GME initiates) or pull (NTS pulls from GME) model? (3) What auth mechanism?; Produce a 1-page decision brief with the two options, the fallback, the stub contract, and the three questions.
**Deliverable:** docs/clarification-log/oi-02-decision-brief.md — option comparison, fallback spec, stub contract, and 3 questions for GME Account/Tech.
**Acceptance / logic checks:**
- Brief confirms that service charge (KRW 500 per transaction) and FX margin are NOT included in invoiceable merchant fee — they are GME-only revenue.
- Brief specifies the manual fallback export fields exactly: merchant_id, invoice_period (first day of month), total_transaction_amount_krw, fee_rate, merchant_fee_krw, vat_krw, invoice_amount_krw, zeropay_share_krw.
- Stub contract defines that tax_invoice rows are created in DRAFT status at month-end even with no API; status moves to ISSUED only after invoice_ref is populated.
- Brief states the Phase 1 gate: manual fallback is acceptable for Phase 1; automated API is required before Phase 4 (overseas partner go-live, Dec 10 2026).
- The three questions are answerable by GME Account with a one-line response each.
**Depends on:** 1.2-T01

### 1.2-T06 — Specify tax_invoice table stub and manual-export endpoint contract  _(45 min)_
**Context:** OI-02 resolution is blocked. Per PM-14 §10, if OI-02 is unresolved before Phase 1 dev start, UC-04-04 is implemented as a manual admin-portal workflow with a future API hook. The tax_invoice table schema exists (DAT-03 §8.3) with invoice_ref VARCHAR(64) UNIQUE, issued_at TIMESTAMPTZ, status ENUM(DRAFT, ISSUED, COLLECTED, FAILED). The monthly aggregation query must sum all committed inbound transactions grouped by merchant and month. fee_rate comes from the rule table (merchant fee rate configured per scheme). zeropay_share_krw = monthly_gross_krw x 0.0021 (0.21%, ZeroPay gross settlement share).
**Steps:** Write the SQL query that aggregates per-merchant monthly fee data: SELECT merchant_id, DATE_TRUNC('month', committed_at) AS invoice_period, SUM(target_payout) AS total_krw, rule.merchant_fee_rate, SUM(target_payout) * rule.merchant_fee_rate AS merchant_fee_krw, ROUND(SUM(target_payout) * rule.merchant_fee_rate * 0.10) AS vat_krw, ROUND(SUM(target_payout) * rule.merchant_fee_rate * 0.0021) AS zeropay_share_krw FROM transaction JOIN rule USING (rule_id) WHERE transaction.direction = 'Inbound' AND transaction.status = 'COMMITTED' GROUP BY merchant_id, invoice_period, rule.merchant_fee_rate.; Define the Admin portal export endpoint: GET /admin/v1/tax-invoices/export?period=2026-05 returns CSV with the above columns.; Define the tax_invoice row lifecycle: one DRAFT row inserted per merchant per month by a scheduled job on the 1st of the following month; manual operator action sets status=ISSUED and populates invoice_ref; API hook slot is a no-op stub that logs INVOICE_API_STUB_CALLED.; Document the future hook interface: TaxInvoiceGateway.issue(invoice_id) -> InvoiceRef that the automated API will implement.; Verify: for a merchant with 100 transactions at KRW 50,000 each, fee_rate 0.50%: merchant_fee = 5,000 x 0.0050 x 100 = 2,500,000 x 0.0050 = 25,000 ... wait, correct calc: total_krw = 5,000,000; merchant_fee_krw = 5,000,000 x 0.005 = 25,000; vat_krw = 2,500; invoice_amount_krw = 27,500; zeropay_share_krw = ROUND(5,000,000 x 0.0021) = 10,500.
**Deliverable:** docs/clarification-log/oi-02-stub-contract.md with the aggregation SQL, export endpoint spec, lifecycle, and numeric example.
**Acceptance / logic checks:**
- Numeric example: total_transaction_amount_krw=5,000,000, fee_rate=0.50%, merchant_fee_krw=25,000, vat_krw=2,500, invoice_amount_krw=27,500, zeropay_share_krw=10,500.
- Export endpoint returns 400 if period format is not YYYY-MM.
- Stub logs INVOICE_API_STUB_CALLED when issue() is called; does not raise an error.
- DRAFT row is created even if the merchant had zero transactions in the period (to support manual investigation); query result will show total_krw=0.
- zeropay_share_krw is calculated on monthly gross KRW total, not on merchant_fee_krw.
**Depends on:** 1.2-T05

### 1.2-T07 — Document OI-02 interim assumption and update PM-14 RAID  _(25 min)_
**Context:** Until OI-02 is resolved, the system proceeds with the manual fallback for UC-04-04. The interim assumption must be recorded in the clarification log. PM-14 RAID issue I-02 must be updated with the interim assumption. The Admin portal (PRD-07) must include the placeholder invoice workflow. The tax_invoice table schema is not blocked — it is complete with stub columns. The issue is only the issuance mechanism (invoice_ref population).
**Steps:** Add assumption A-OI-02 to docs/clarification-log/assumptions-update.md: Manual admin-portal export and manual invoice_ref entry is the Phase 1 fallback for OI-02. The TaxInvoiceGateway stub is deployed; automated issuance is deferred.; Update PM-14 RAID I-02 status to IN_PROGRESS with: interim assumption, fallback mechanism, and the gate condition (automated API required before Phase 4 overseas go-live).; Add a TODO comment in the TaxInvoiceGateway stub implementation pointing to OI-02 and the decision brief.; Cross-reference PRD-07 Admin System assumption A-08: the Admin System will display gross settlement amounts and allow Finance to export data for manual invoice preparation.; Confirm the affected test case (UC-04-04 in QA-12) is marked P2 pending OI-02.
**Deliverable:** Updated docs/clarification-log/assumptions-update.md with A-OI-02; updated PM-14 RAID I-02 notes.
**Acceptance / logic checks:**
- A-OI-02 entry names the exact fallback: GET /admin/v1/tax-invoices/export CSV export plus manual invoice_ref entry by operator.
- A-OI-02 specifies the phase gate: automated issuance required before Dec 10 2026 (Phase 4 overseas go-live).
- PM-14 RAID I-02 shows owner GME Account/Tech and target Before Phase 1 dev start.
- QA-12 UC-04-04 test case marked P2-Medium with note OI-02 open.
- No code path raises an unhandled exception when TaxInvoiceGateway.issue() is called in stub mode.
**Depends on:** 1.2-T06

### 1.2-T08 — Draft OI-03 decision brief: BOK FX reporting format and channel  _(45 min)_
**Context:** OI-03 (BOK Reporting Format): GME must submit FX reports to the Bank of Korea for all cross-border transactions. FX1014 = Korean customer paying overseas (Outbound); FX1015 = payment to Korean merchant by overseas payer (Inbound). Confirmed mapping: offer_rate_coll (= send_amount / (collection_usd - collection_margin_usd)) maps to FX1015 field #14. FX1015 source fields already captured: offer_rate_coll (locked at commit), target_payout (payout_amount KRW), payout_usd_cost (USD intermediate), committed_at timestamp, partner_id. FX1014 source fields: collection_ccy, collection_amount, send_amount, collection_usd, partner_id, committed_at. The bok_report_record table has report_type (FX1014|FX1015), report_date, partner_id, collection_amount, collection_ccy, payout_amount, payout_ccy, offer_rate_coll, usd_amount, submission_status (PENDING|SUBMITTED|CONFIRMED|FAILED). Three unknowns: format (CSV/XML/API), frequency (daily/monthly/real-time), channel (portal upload/API/SFTP). Owner: GME Account/Compliance; urgency URGENT.
**Steps:** State what is confirmed: data capture complete at commit; offer_rate_coll = FX1015 #14; FX1014 fields captured in transaction record for Phase 2.; State what is unknown: file format, field order, encoding, submission frequency, submission channel.; List candidate channel options: (A) BOK portal manual upload (CSV); (B) BOK direct API; (C) SFTP batch.; For each option describe the implication for bok_report_record.submission_status lifecycle.; Document the interim build plan per SEC-09 §8.1.3: configurable export job (daily or on-demand), CSV or structured output, no hard-coded submission logic, FEATURE_BOK_REPORTING=false flag in Phase 1.; Identify the Hub direction sub-item OI-03a: overseas-to-overseas (Hub direction) has no confirmed FX form mapping; record as a separate open question.; Produce the decision brief with the 5 questions GME Compliance must answer.
**Deliverable:** docs/clarification-log/oi-03-decision-brief.md — confirmed fields, unknown elements, option table, interim build plan, 5 questions for GME Compliance.
**Acceptance / logic checks:**
- Brief confirms offer_rate_coll formula: send_amount / (collection_usd - collection_margin_usd), maps to FX1015 #14. Numeric example: send_amount=36.9714, collection_usd=36.9714, collection_margin_usd=0.3697 => offer_rate_coll = 36.9714/36.6017 = 1.01010.
- Brief states all FX1015 source fields that are already captured and locked at commit (offer_rate_coll, target_payout, payout_usd_cost, committed_at, partner_id).
- OI-03a (Hub direction) is listed as a separate sub-question with note: no confirmed FX form mapping exists.
- Interim build plan specifies FEATURE_BOK_REPORTING feature flag defaulting to false; export job is pluggable with output format as a configuration parameter.
- 5 questions are each answerable with a one-line response (format?, field order?, encoding?, frequency?, channel?).
**Depends on:** 1.2-T01

### 1.2-T09 — Specify bok_report_record schema and population logic  _(40 min)_
**Context:** OI-03 is unresolved but data capture must proceed from day one (SEC-09 §8.1.3: no data loss in interim). The bok_report_record table (DAT-03 §8.1) has: id BIGINT PK, txn_id BIGINT FK transaction, report_type VARCHAR(10) CHECK IN ('FX1014','FX1015'), report_date DATE, partner_id BIGINT FK partner, collection_amount DECIMAL(20,4), collection_ccy CHAR(3), payout_amount DECIMAL(20,4), payout_ccy CHAR(3), offer_rate_coll DECIMAL(20,8) — BOK FX1015 field #14, usd_amount DECIMAL(20,4) — payout_usd_cost, submission_status VARCHAR(20) DEFAULT 'PENDING', submitted_at TIMESTAMPTZ, created_at TIMESTAMPTZ, updated_at TIMESTAMPTZ. Population trigger: at transaction commit (step 6 of 8-step event trail), insert one FX1015 row for Inbound transactions; FX1014 row deferred to Phase 2 (Outbound). Domestic (same-currency) transactions are exempt.
**Steps:** Write the SQL migration V1.2__bok_report_record_constraints.sql adding CHECK constraints for report_type and submission_status if not already present.; Specify the insert statement executed at CommitTransaction: INSERT INTO bok_report_record (txn_id, report_type, report_date, partner_id, collection_amount, collection_ccy, payout_amount, payout_ccy, offer_rate_coll, usd_amount, submission_status) SELECT id, 'FX1015', committed_at::DATE, partner_id, collection_amount, collection_ccy, target_payout, payout_ccy, offer_rate_coll, payout_usd_cost, 'PENDING' FROM transaction WHERE id = :txn_id AND direction = 'Inbound'.; Add a guard: if transaction.direction = 'Domestic' (same-currency), skip the insert.; Document the future FX1014 hook: a placeholder function insertFX1014Report(txn_id) that logs FX1014_DEFERRED and returns without inserting.; Verify with numeric example: txn with target_payout=50000 KRW, payout_usd_cost=36.2319, offer_rate_coll=1.01010, collection_amount=51086.49 KRW, collection_ccy=KRW, payout_ccy=KRW => FX1015 row has usd_amount=36.2319, offer_rate_coll=1.01010.
**Deliverable:** db/migration/V1.2__bok_report_record_constraints.sql and docs/clarification-log/oi-03-population-spec.md with insert logic and numeric example.
**Acceptance / logic checks:**
- FX1015 row is inserted for every Inbound committed transaction; no row for Domestic transactions.
- offer_rate_coll in the inserted row equals the locked value from the transaction table (not recomputed).
- submission_status defaults to PENDING on insert.
- Numeric check: offer_rate_coll=1.01010, usd_amount=36.2319, payout_amount=50000, collection_amount matches the committed transaction value.
- insertFX1014Report logs FX1014_DEFERRED and does not raise an exception.
**Depends on:** 1.2-T08

### 1.2-T10 — Specify BOK report export job interface (pluggable output format)  _(40 min)_
**Context:** Per SEC-09 §8.1.3, until OI-03 is resolved: a configurable export job that outputs all FX1014/FX1015 source fields per transaction must exist; output format (CSV or structured file) must be pluggable; no hard-coded submission logic. The FEATURE_BOK_REPORTING feature flag defaults to false. The export job reads from bok_report_record joining to transaction for any additional locked fields. Frequency is configurable (daily cron or on-demand via Admin portal).
**Steps:** Define the BokReportExporter interface: exportPending(reportType: 'FX1015'|'FX1014', fromDate: LocalDate, toDate: LocalDate, format: 'CSV'|'JSON') -> ExportResult.; Define ExportResult: { rowCount: int, fileRef: string, generatedAt: Instant }.; Specify the CSV column order for FX1015: txn_id, report_date, partner_id, collection_amount, collection_ccy, payout_amount, payout_ccy, offer_rate_coll, usd_amount, submission_status.; Implement CsvBokReportFormatter as the only concrete formatter in Phase 1; add a JsonBokReportFormatter stub that throws UnsupportedOperationException.; Document: after successful export, submission_status remains PENDING; it only moves to SUBMITTED when the (future) submission channel confirms receipt. Admin portal shows count of PENDING rows as a compliance KPI.; Specify the on-demand Admin portal endpoint: POST /admin/v1/bok-reports/export with body { reportType, fromDate, toDate, format } returning ExportResult.
**Deliverable:** docs/clarification-log/oi-03-exporter-spec.md defining the BokReportExporter interface, CSV column order, and Admin portal endpoint contract.
**Acceptance / logic checks:**
- CsvBokReportFormatter produces a header row followed by one data row per bok_report_record; offer_rate_coll is formatted to 8 decimal places.
- Exporting with FEATURE_BOK_REPORTING=false returns ExportResult { rowCount: 0, fileRef: null } without error.
- exportPending with fromDate > toDate returns a 400 Bad Request.
- offer_rate_coll in the exported CSV matches the value locked in the transaction table to 8 decimal places.
- Column order in CSV matches the spec: txn_id first, offer_rate_coll in position 8.
**Depends on:** 1.2-T09

### 1.2-T11 — Document OI-03 interim assumption and update PM-14 RAID  _(25 min)_
**Context:** Until OI-03 is confirmed by GME Compliance / BOK, development proceeds with: (1) all FX1015 source fields captured and locked at commit; (2) pluggable export job built; (3) no hard-coded submission; (4) FEATURE_BOK_REPORTING=false. The bok_report_record schema is provisional — additional columns will be needed once the full BOK field list is confirmed. PM-14 RAID I-01 and D-03 track this. OI-03a (Hub direction reporting) is a separate sub-item with no confirmed form.
**Steps:** Add assumption A-OI-03 to docs/clarification-log/assumptions-update.md with: data capture complete from day one; export job pluggable; submission deferred; schema provisional.; Add assumption A-OI-03a: Hub direction (overseas-to-overseas) BOK reporting form is undefined; no bok_report_record row is inserted for Hub direction transactions until OI-03a is resolved.; Update PM-14 RAID I-01 status to IN_PROGRESS with the interim assumption and the gate: format must be confirmed before Phase 3 go-live (Oct 10, 2026) or GME assumes regulatory risk.; Cross-reference SEC-09 §8.1.3 in the assumption: no data loss; all FX1015 source fields locked at commit.; Flag the schema column count as provisional: bok_report_record currently has 11 data columns; final BOK field list may require additional columns.
**Deliverable:** Updated docs/clarification-log/assumptions-update.md with A-OI-03 and A-OI-03a; updated PM-14 RAID I-01 notes.
**Acceptance / logic checks:**
- A-OI-03 states explicitly that all FX1015 source fields are captured at commit (offer_rate_coll, target_payout, payout_usd_cost, committed_at, partner_id).
- A-OI-03a notes that Hub direction has no confirmed BOK form and zero rows will be inserted for Hub direction transactions.
- PM-14 RAID I-01 shows urgency=URGENT, target TBD, and the regulatory risk statement if unresolved by Oct 10 2026.
- The assumption doc states the column addition process: new migration file required when BOK field list is confirmed; existing rows remain valid.
- No other assumption entries are modified or overwritten.
**Depends on:** 1.2-T10

### 1.2-T12 — Conduct document-by-document requirements walkthrough: BRD-01 and SAD-02  _(50 min)_
**Context:** WBS 1.2 requires a structured walkthrough of all spec documents to confirm scope and surface conflicts. BRD-01 is the business requirements document; SAD-02 is the system architecture. Key items to confirm: (1) all 4 components in scope for Phase 1 (Hub Core, Admin portal, Partner Portal, ZeroPay Adapter); (2) go-live date Oct 10 2026 = Phase 3 gate (first domestic partner GME Remit on ZeroPay); (3) adding scheme/partner is config only, no code change; (4) prefunding for OVERSEAS only; (5) three-currency model with USD pivot required by BOK. Conflicts found: Business doc (UC-05-01) references xe.com/15-min live FX — PRD-07 §8 marks live FX feed out of scope for Phase 1; PRD wins per CONVENTIONS.md.
**Steps:** Read BRD-01 §1–9 and list all in-scope features flagged for Phase 1.; Read SAD-02 §1–13 and confirm the four-component architecture matches BRD-01.; Record the xe.com FX conflict as a confirmed resolution: Phase 1 = manual treasury rate entry by GME Ops; Phase 2 = live FX feed. Record in docs/clarification-log/conflict-resolutions.md.; Confirm CPM prefunding deduction timing: at POST /v1/payments/cpm/generate per PRD-01 §5.8; Business doc UC-02-01 step 9 says at completion — PRD wins. Record in conflict-resolutions.md.; List any open questions or ambiguities in SAD-02 requiring GME clarification.
**Deliverable:** docs/clarification-log/conflict-resolutions.md with entries for the xe.com FX conflict and CPM prefunding timing conflict; BRD-01/SAD-02 walkthrough notes.
**Acceptance / logic checks:**
- xe.com conflict entry states: PRD-07 §8 wins; live FX feed deferred to Phase 2; Phase 1 uses manual treasury.usd_{ccy} updates by GME Ops.
- CPM timing entry states: PRD-01 §5.8 wins; deduction at POST /v1/payments/cpm/generate; not at merchant scan or final approval.
- SAD-02 four-component list matches BRD-01 in-scope list (Hub Core, Admin System, Partner Portal, ZeroPay Adapter).
- go-live date Oct 10 2026 confirmed as Phase 3 gate (first domestic transaction), not full overseas partner launch.
- Config-only constraint (A-14-04) confirmed as an acceptance criterion from Phase 1.
**Depends on:** 1.2-T01

### 1.2-T13 — Conduct requirements walkthrough: DAT-03 and RATE-04  _(50 min)_
**Context:** DAT-03 is the data model; RATE-04 is the rate engine spec. Key items: (1) rate engine 5-step sequence must be implemented exactly — any deviation invalidates QA test vectors and BOK FX1015 #14; (2) pool identity invariant: |collection_usd - collection_margin_usd - payout_margin_usd - payout_usd_cost| < 0.01 USD; (3) all monetary values stored as DECIMAL(20,8); (4) KRW = 0 decimal places, USD = 2; (5) same-currency short-circuit when collection_ccy = settle_a = settle_b = payout_ccy; (6) min combined margin m_a + m_b >= 2.0% for cross-border, 0 allowed for same-currency; (7) rate-lock at commit — all 5-step values + derived rates stored immutably.
**Steps:** Walk through RATE-04 §2–5 step by step and confirm all 5 formula steps are unambiguous.; Verify numeric example: target_payout=50,000 KRW, cost_rate_pay=1380.00, m_a=0.01, m_b=0.01, cost_rate_coll=1.0 (identity USD). Step1: payout_usd_cost=50000/1380=36.2319. Step2: collection_usd=36.2319/0.98=36.9714. Step3a: collection_margin=36.9714*0.01=0.3697. Step3b: payout_margin=36.9714*0.01=0.3697. Step4: send_amount=36.9714*1.0=36.9714. Step5: collection_amount=36.9714+service_charge. Derived: offer_rate_coll=36.9714/(36.9714-0.3697)=36.9714/36.6017=1.01010; cross_rate=50000/36.9714=1352.24.; Confirm DAT-03 column types: payout_usd_cost DECIMAL(20,8), collection_usd DECIMAL(20,8), offer_rate_coll DECIMAL(20,8).; Check RATE-04 §11.3: min margin check at rule configuration time, not at payment time.; Record any ambiguities in docs/clarification-log/walkthrough-notes.md.
**Deliverable:** docs/clarification-log/walkthrough-notes.md with RATE-04 numeric verification and any ambiguities; DAT-03 type confirmation.
**Acceptance / logic checks:**
- Numeric verification passes all 5 steps with values matching the spec exactly: payout_usd_cost=36.2319, collection_usd=36.9714, collection_margin_usd=0.3697, payout_margin_usd=0.3697, send_amount=36.9714, offer_rate_coll=1.01010, cross_rate=1352.24.
- Pool identity check: 36.9714 - 0.3697 - 0.3697 = 36.2320; delta from payout_usd_cost=36.2319 is 0.0001, within 0.01 USD tolerance.
- DAT-03 column types confirmed: all rate-engine intermediary values DECIMAL(20,8); collection_amount DECIMAL(20,4) for display.
- Min margin rule confirmed as a configuration-time check, not a payment-time runtime guard.
- Ambiguities list is either empty (no ambiguities found) or each entry has a proposed resolution and an owner.
**Depends on:** 1.2-T12

### 1.2-T14 — Conduct requirements walkthrough: API-05 and SCH-06  _(50 min)_
**Context:** API-05 is the Northbound Partner API spec; SCH-06 is the ZeroPay scheme interface. Key items for API-05: (1) offer_rate in API responses is offer_rate_coll from RATE-04; (2) collection_amount in GET /v1/rates response = send_amount + service_charge; (3) partner does NOT validate or compute collection_amount — it is informational; (4) GMEPay+ debits partner prefunding by collection_usd, not collection_amount; (5) payment.pending_debit webhook delivers offer_rate and collection_amount for CPM. SCH-06: ZeroPay real-time API spec not yet in hand (external dependency; see PM-14 D-02). SFTP batch specs ZP0011-ZP0066 are defined by KFTC.
**Steps:** Walk through API-05 §4–6 and confirm field name consistency: offer_rate = offer_rate_coll from RATE-04; collection_amount = send_amount + service_charge; collection_usd is the prefunding debit amount.; Confirm OI-01 impact on API-05: payment.pending_debit delivers offer_rate and collection_amount — no field change needed for any approval method (PIN/biometric/button/silent).; Confirm OI-03 impact on API-05: offer_rate_coll and cross_rate are admin-portal-only in responses — not in real-time partner API responses unless BOK requires partner submission (pending OI-03).; Review SCH-06 and confirm which items depend on the KFTC test environment (D-01, D-02) and which can proceed.; Record SCH-06 gaps in docs/clarification-log/walkthrough-notes.md.
**Deliverable:** Updated docs/clarification-log/walkthrough-notes.md with API-05 field mapping confirmation, OI-01/OI-03 API impact analysis, and SCH-06 dependency gaps.
**Acceptance / logic checks:**
- API-05 walkthrough confirms offer_rate in API responses corresponds to offer_rate_coll (= send_amount / (collection_usd - collection_margin_usd)); field is named offer_rate in partner-facing JSON.
- Confirmed: GMEPay+ debits partner prefunding by collection_usd (not collection_amount); collection_amount is informational.
- OI-01 API impact: no additional fields in payment.pending_debit are required for any of the three candidate approval methods.
- OI-03 API impact: if BOK requires partner data submission, offer_rate_coll field would need to be added to GET /v1/rates response; noted as a conditional change pending OI-03.
- SCH-06 gap list identifies which batch file types (ZP0011-ZP0066) are blocked on KFTC test environment (D-01) vs. those that can be spec-reviewed independently.
**Depends on:** 1.2-T13

### 1.2-T15 — Conduct requirements walkthrough: SEC-09, PRD-07, PRD-08, QA-12  _(45 min)_
**Context:** SEC-09 is the security spec (BOK compliance architecture, §8); PRD-07 is the Admin System; PRD-08 is the Partner Portal; QA-12 is the QA plan. SEC-09 §8.1.3 defines the interim BOK build plan. PRD-07 A-08 confirms the manual invoice workflow fallback for OI-02. PRD-08 OI-01 note: the Partner Portal shows committed collection_amount and status only — approval UX is partner-app side, out of scope for portal. QA-12 UC-04-04 is marked P2 pending OI-02; UC-08-01/02 cannot be written until OI-03 confirmed.
**Steps:** Walk through SEC-09 §8 and confirm the BOK compliance architecture is consistent with the interim assumptions (A-OI-03): data capture at commit, pluggable export, FEATURE_BOK_REPORTING flag.; Walk through PRD-07 §16 (open items) and confirm A-08 matches the OI-02 stub contract in 1.2-T06.; Walk through PRD-08 OI-01 entry and confirm the partner portal is unaffected by OI-01 resolution.; Walk through QA-12 §6.1 and list the test cases that are blocked or marked TBD due to OI-01, OI-02, OI-03.; Record findings in docs/clarification-log/walkthrough-notes.md.
**Deliverable:** Updated docs/clarification-log/walkthrough-notes.md with SEC-09/PRD-07/PRD-08/QA-12 walkthrough findings and blocked test case list.
**Acceptance / logic checks:**
- SEC-09 §8.1.3 interim plan is consistent with A-OI-03: all FX1015 source fields locked at commit, pluggable export, no hard-coded channel.
- PRD-07 A-08 manual export is confirmed as the Phase 1 fallback; consistent with oi-02-stub-contract.md from 1.2-T06.
- PRD-08 portal OI-01 note confirmed: portal shows committed collection_amount only; no UX change needed for any approval method.
- Blocked QA-12 test cases are listed: UC-04-04 (OI-02, P2), UC-08-01 and UC-08-02 (OI-03, cannot be written).
- No contradictions found between SEC-09, PRD-07, and the oi-03 interim build plan.
**Depends on:** 1.2-T14

### 1.2-T16 — Compile clarification log and updated assumptions document  _(45 min)_
**Context:** WBS 1.2 parent deliverable is a Clarification Log and Updated Assumptions document. All previous tickets have produced individual artifacts. This ticket assembles them into the final deliverable. The clarification log must record: all OI decisions made (interim assumptions), all conflicts resolved, all ambiguities still open, and the owner and target date for each open item.
**Steps:** Merge the following into docs/clarification-log/CLARIFICATION_LOG.md: oi-register.md, oi-01-decision-brief.md, oi-02-decision-brief.md, oi-03-decision-brief.md, oi-02-stub-contract.md, oi-03-population-spec.md, oi-03-exporter-spec.md, conflict-resolutions.md, walkthrough-notes.md.; Produce a summary table: Section, OI/Conflict, Status (RESOLVED/INTERIM/OPEN), Decision/Assumption, Owner, Target Date.; Produce a second table: Updated Assumptions, with columns A-ID, Text, Affected-Component, Phase-Gate.; Mark OI-01 status as INTERIM (awaiting GME Tech/Product decision); OI-02 status as INTERIM (manual fallback active); OI-03 status as INTERIM (data capture active, submission deferred).; Add a cover section: Clarification Log for WBS 1.2, prepared by Dev Vendor, date, version 1.0.
**Deliverable:** docs/clarification-log/CLARIFICATION_LOG.md — the complete, assembled clarification log and updated assumptions document for WBS 1.2.
**Acceptance / logic checks:**
- Summary table has exactly 3 OI rows (OI-01, OI-02, OI-03) each with status INTERIM, and 2 conflict rows (xe.com FX, CPM prefunding timing) each with status RESOLVED.
- Updated Assumptions table includes A-OI-01, A-OI-02, A-OI-03, A-OI-03a, plus the two conflict-resolution assumptions.
- OI-02 row shows target Before Phase 1 dev start and urgency URGENT.
- OI-03 row shows target TBD and the regulatory risk note: GME assumes risk of manual reporting if unresolved by Oct 10 2026.
- The document is self-contained: a reader with no other context can understand the current status of all three open items and the interim assumptions in effect.
**Depends on:** 1.2-T11, 1.2-T15

### 1.2-T17 — Unit test: OI-01 partner_approval_config table constraints  _(30 min)_
**Context:** The partner_approval_config table created in 1.2-T03 must enforce its constraints correctly. Test vectors: (1) valid insert with payment_mode=ALL, approval_method=CONFIRMATION; (2) duplicate (partner_id, payment_mode) rejects; (3) invalid payment_mode rejects; (4) invalid approval_method rejects; (5) NULL partner_id rejects (FK or NOT NULL). This ticket writes integration tests against the actual database schema using a test DB container.
**Steps:** Set up a test PostgreSQL database with the V1.2__partner_approval_config.sql migration applied.; Write test T1: INSERT with payment_mode='ALL', approval_method='CONFIRMATION' -> succeeds, row count = 1.; Write test T2: INSERT a second row with the same (partner_id, payment_mode) -> raises unique constraint violation.; Write test T3: INSERT with payment_mode='BOTH' (invalid) -> raises check constraint violation.; Write test T4: INSERT with approval_method='THUMBPRINT' (invalid) -> raises check constraint violation.; Write test T5: INSERT with partner_id=NULL -> raises NOT NULL or FK violation.
**Deliverable:** test/db/PartnerApprovalConfigConstraintTest.java (or equivalent) with 5 test methods covering all constraint paths.
**Acceptance / logic checks:**
- T1 passes: row inserted with approval_method=CONFIRMATION.
- T2 fails with SQLIntegrityConstraintViolationException (unique).
- T3 fails with SQLIntegrityConstraintViolationException (check).
- T4 fails with SQLIntegrityConstraintViolationException (check).
- T5 fails with NOT NULL or FK constraint error.
**Depends on:** 1.2-T03

### 1.2-T18 — Unit test: bok_report_record population at CommitTransaction  _(35 min)_
**Context:** At CommitTransaction (step 6 of 8-step event trail), a FX1015 row must be inserted into bok_report_record for Inbound transactions. Domestic transactions must not generate a row. The insert uses locked values from the transaction record. Test vectors: (A) Inbound transaction with offer_rate_coll=1.01010, payout_usd_cost=36.2319, target_payout=50000 KRW; (B) Domestic transaction (same-currency, direction=Domestic); (C) Hub direction transaction.
**Steps:** Create a test transaction record with direction=Inbound, committed, offer_rate_coll=1.01010, payout_usd_cost=36.2319, target_payout=50000, payout_ccy=KRW, collection_amount=51136 KRW.; Call CommitTransaction and verify a FX1015 row is inserted into bok_report_record with offer_rate_coll=1.01010, usd_amount=36.2319, payout_amount=50000.; Create a Domestic transaction (direction=Domestic, same-currency KRW); call CommitTransaction; verify NO bok_report_record row is inserted.; Create a Hub direction transaction; call CommitTransaction; verify NO bok_report_record row is inserted (OI-03a not resolved).; Verify that the offer_rate_coll value in bok_report_record equals the locked value from transaction (not recomputed from current treasury rates).
**Deliverable:** test/domain/BokReportRecordPopulationTest.java with 4 test methods.
**Acceptance / logic checks:**
- Inbound test: bok_report_record row has report_type=FX1015, offer_rate_coll=1.01010 (8 decimal places), usd_amount=36.2319, submission_status=PENDING.
- Domestic test: bok_report_record has zero rows after CommitTransaction.
- Hub test: bok_report_record has zero rows after CommitTransaction.
- The offer_rate_coll value is read from the locked transaction field, not computed at report-generation time.
- Pool identity: verify the inserted usd_amount equals payout_usd_cost from the transaction; not collection_usd.
**Depends on:** 1.2-T09, 1.2-T13

### 1.2-T19 — Unit test: BOK report CSV exporter output format  _(30 min)_
**Context:** The CsvBokReportFormatter (specified in 1.2-T10) must produce a CSV with specific column order: txn_id, report_date, partner_id, collection_amount, collection_ccy, payout_amount, payout_ccy, offer_rate_coll, usd_amount, submission_status. offer_rate_coll must be formatted to 8 decimal places. Test with a known set of bok_report_record rows; verify exact CSV output. Also test: FEATURE_BOK_REPORTING=false returns empty result; fromDate > toDate returns 400.
**Steps:** Insert 2 bok_report_record rows with known values into the test DB: row1 offer_rate_coll=1.01010000, row2 offer_rate_coll=1.00000000.; Call exportPending(reportType='FX1015', fromDate=2026-05-01, toDate=2026-05-31, format='CSV').; Assert the CSV header equals: txn_id,report_date,partner_id,collection_amount,collection_ccy,payout_amount,payout_ccy,offer_rate_coll,usd_amount,submission_status; Assert row1 offer_rate_coll field is '1.01010000' (exactly 8 decimal places).; Set FEATURE_BOK_REPORTING=false, call exportPending, assert rowCount=0 and no exception.; Call exportPending with fromDate=2026-05-31, toDate=2026-05-01 (inverted range), assert 400 / IllegalArgumentException.
**Deliverable:** test/reporting/BokReportCsvExporterTest.java with 4 test methods.
**Acceptance / logic checks:**
- CSV header row matches exact column order as specified.
- offer_rate_coll formatted as '1.01010000' (8 decimal places, no scientific notation).
- FEATURE_BOK_REPORTING=false returns ExportResult { rowCount: 0, fileRef: null }.
- Inverted date range throws IllegalArgumentException or returns HTTP 400.
- Two data rows are present in the output for the two inserted records.
**Depends on:** 1.2-T10, 1.2-T17

### 1.2-T20 — Unit test: tax_invoice monthly aggregation query  _(35 min)_
**Context:** The monthly aggregation query (specified in 1.2-T06) computes merchant_fee_krw = total_transaction_amount_krw x fee_rate; vat_krw = merchant_fee_krw x 10%; zeropay_share_krw = total_krw x 0.0021. Test vector: 100 Inbound transactions at KRW 50,000 each for one merchant with fee_rate=0.50% (0.005). Expected: total_krw=5,000,000; merchant_fee_krw=25,000; vat_krw=2,500; invoice_amount_krw=27,500; zeropay_share_krw=10,500. Also test: no transactions in period returns a row with all amounts zero; Domestic transactions are excluded.
**Steps:** Insert 100 committed Inbound transactions for merchant_id=1, target_payout=50000 KRW each, committed in May 2026.; Insert 10 committed Domestic transactions for the same merchant in May 2026 (should be excluded).; Run the aggregation query for merchant_id=1, period=2026-05.; Assert: total_transaction_amount_krw=5000000, merchant_fee_krw=25000, vat_krw=2500, invoice_amount_krw=27500, zeropay_share_krw=10500.; Run the aggregation for period=2026-04 (no transactions); assert a zero row is returned or an empty result, per the spec.; Verify the Domestic transactions are excluded from the total.
**Deliverable:** test/reporting/TaxInvoiceAggregationTest.java with 4 test methods covering normal case, zero-transaction month, Domestic exclusion, and fee arithmetic.
**Acceptance / logic checks:**
- Normal case: total_transaction_amount_krw=5,000,000, merchant_fee_krw=25,000, vat_krw=2,500, invoice_amount_krw=27,500, zeropay_share_krw=10,500.
- Domestic transactions (direction=Domestic) do not appear in the aggregate total.
- Zero-transaction month: query returns zero row without exception.
- zeropay_share_krw = ROUND(5,000,000 * 0.0021) = 10,500 exactly.
- invoice_amount_krw = merchant_fee_krw + vat_krw = 25,000 + 2,500 = 27,500.
**Depends on:** 1.2-T06, 1.2-T17


## WBS 1.3 — Delivery plan, environments & estimates
### 1.3-T01 — Draft WBS 1.3 scope document: delivery plan, environments and estimates  _(30 min)_
**Context:** WBS 1.3 covers the Delivery Plan, Environments and Estimates work-package. Parent deliverable is a baselined project plan. This ticket produces the single scoping document that frames all subsequent 1.3 tickets so every developer knows what is in scope. GMEPay+ has four phases: Phase 1 Build Core (Apr 10 - Jun 20 2026, 10 weeks), Phase 2 Integrate ZeroPay (May 15 - Jul 31 2026, 11 weeks, overlaps Phase 1), Phase 3 Launch GME Remit (Aug 1 - Oct 10 2026), Phase 4 Launch Overseas Partners (Oct 1 - Dec 10 2026). Go-live is Oct 10 2026 (Phase 3 gate). Source: PM-14 Section 2.
**Steps:** Create docs/planning/WBS_1.3_scope.md in the repository.; List all sub-deliverables: detailed schedule, environment plan, refined estimates, sprint cadence.; State the baseline date (Apr 10 2026 contract signed) and go-live date (Oct 10 2026).; Record the phase gate criteria as written in PM-14 Section 5 (each phase criterion must be met before the next phase is authorised).; Confirm out-of-scope items: partner app UIs, merchant-facing systems, live FX feed (Phase 1 uses manual rates).
**Deliverable:** docs/planning/WBS_1.3_scope.md containing phase boundaries, gate criteria, and out-of-scope items
**Acceptance / logic checks:**
- Document lists all four phases with exact start/end dates and durations matching PM-14: Ph1 Apr 10-Jun 20, Ph2 May 15-Jul 31, Ph3 Aug 1-Oct 10, Ph4 Oct 1-Dec 10.
- All five Phase 1 acceptance criteria from PM-14 Section 5.1 are reproduced verbatim (rate-engine vectors, API sandbox, admin portal, partner portal, pool identity, same-currency short-circuit, audit log, security).
- Out-of-scope list explicitly names live FX feed, partner app UIs, and merchant-facing systems.
- Document is version-controlled and references PM-14 as canonical source.

### 1.3-T02 — Produce milestone table with owners and target dates from PM-14 Section 3  _(35 min)_
**Context:** PM-14 Section 3.1 defines 19 milestones with target dates and owners. This ticket creates a machine-readable milestone register (CSV or YAML) that will feed sprint planning, burndown tracking, and the RAID log. Key milestones: contract signed Apr 10 2026 (GME Product); dev env provisioned Apr 17 (Dev Vendor); Hub Core schema + rate engine May 9 (Dev Vendor); Admin portal core May 23; Northbound API sandbox Jun 6; KFTC test env mid-May 2026; Phase 1 gate Jun 20; all ZP00xx verified Jul 18; Phase 2 gate Jul 31; GME Remit staging Sep 5; UAT sign-off Sep 26; prod deployment Oct 10; Phase 3 gate Oct 10; overseas partner staging Nov 7; UAT sign-off Phase 4 Nov 28; prod deployment Dec 10.
**Steps:** Create docs/planning/milestones.csv with columns: id, name, target_date, owner, phase, status.; Populate all 19 milestones from PM-14 Section 3.1.; Mark status as PLANNED for all.; Cross-check that the critical-path dependency chain is represented: KFTC test env (mid-May) gates Phase 2; Phase 2 gates Phase 3; Phase 3 gates Phase 4.
**Deliverable:** docs/planning/milestones.csv with 19 rows covering all PM-14 Section 3.1 milestones
**Acceptance / logic checks:**
- CSV contains exactly 19 milestone rows matching PM-14 Section 3.1.
- Phase 1 gate date is Jun 20 2026 with owner Dev Vendor.
- Oct 10 2026 appears as both prod deployment and Phase 3 gate entries.
- KFTC test environment milestone is flagged as critical-path with owner GME Business/KFTC and target mid-May 2026.
- All Phase 4 milestones have dates in Oct-Dec 2026 range.
**Depends on:** 1.3-T01

### 1.3-T03 — Define sprint cadence structure and sprint boundary dates for all phases  _(40 min)_
**Context:** GMEPay+ is delivered across four phases spanning Apr 10 - Dec 10 2026. WBS 1.3 requires a sprint cadence that aligns sprint boundaries with phase gates. Phases 1 and 2 overlap (Phase 2 begins May 15 while Phase 1 runs until Jun 20). A 2-week sprint cadence is standard; sprints should be numbered S1 through S18 to cover the full programme. Sprint 1 starts Apr 10; Phase 1 spans approx S1-S11; Phase 2 S6-S16; Phase 3 S18-S27; Phase 4 S25-S35. Exact boundary alignment must ensure that each phase gate milestone lands at a sprint end. Source: PM-14 Sections 2 and 3.
**Steps:** Create docs/planning/sprint_cadence.csv with columns: sprint_id, start_date, end_date, phase(s), key_milestone.; Assign 2-week sprints starting Apr 13 2026 (Monday after contract Apr 10).; Ensure phase gate milestones land at sprint ends: Phase 1 gate Jun 20, Phase 2 gate Jul 31, Phase 3 gate Oct 10, Phase 4 gate Dec 10.; Mark sprints where phases overlap (S6-S11) so staffing assignments can reflect dual-workstream load.; Add a row for Sprint 0 (Apr 10-Apr 12) for environment setup.
**Deliverable:** docs/planning/sprint_cadence.csv with sprint-by-sprint schedule aligned to all phase gates
**Acceptance / logic checks:**
- Sprint end dates for the four phase gates match PM-14 exactly: Jun 20, Jul 31, Oct 10, Dec 10.
- Overlap period (Phase 1 and Phase 2 concurrent) is correctly marked for sprints whose date range falls between May 15 and Jun 20.
- No sprint exceeds 2 weeks.
- The final sprint ends on or before Dec 10 2026.
- Sprint 0 covers environment provisioning with milestone Apr 17 2026.
**Depends on:** 1.3-T02

### 1.3-T04 — Document environment tier definitions (E1-E4) and per-tier configuration matrix  _(35 min)_
**Context:** OPS-13 Section 2 defines four environment tiers: E1 dev (individual feature work, synthetic data, developers only), E2 int (multi-service integration, ZeroPay 한결원 test SFTP, dev+QA), E3 staging/UAT (production-like masked PII, QA+Ops+partner tech leads), E4 prod (real data, break-glass access). Per-tier config from OPS-13 Section 5.3: DB pool sizes 5/20/80/200; prefunding low-balance threshold $100/$1000/$10000/$10000; rate quote TTL 300s/300s/per-config/per-config; log level DEBUG/DEBUG/INFO/INFO; batch schedule manual/manual/KST production windows/KST production windows. Secret paths follow convention gmepay/<env>/<service>/<secret-name>.
**Steps:** Create docs/planning/environments.md with a definition table for tiers E1-E4.; Include access control rules from OPS-13 Section 2.5 for each tier.; Include the configuration matrix from OPS-13 Section 5.3 with all config items and their per-tier values.; Document the KFTC ZeroPay test env constraint: E2 connects exclusively to 한결원 test SFTP; never share credentials across tiers.; State the environment parity principle: same container images, only runtime config differs.
**Deliverable:** docs/planning/environments.md with environment tier table, access rules, and configuration matrix
**Acceptance / logic checks:**
- Four tiers (E1-E4) are defined with correct names (dev, int, staging, prod) and purposes matching OPS-13.
- Configuration matrix shows DB pool 5/20/80/200 for dev/int/staging/prod.
- E2 is explicitly mapped to 한결원 ZeroPay test SFTP; E4 to production SFTP.
- Access control section states prod DB and secrets accessible only via break-glass procedure.
- Secret path convention gmepay/<env>/<service>/<secret-name> is documented with an example.
**Depends on:** 1.3-T01

### 1.3-T05 — Define feature flag register with Phase 1 defaults  _(25 min)_
**Context:** OPS-13 Section 5.2 mandates a feature-flag mechanism for controlling in-flight features without deployment. Five flags are defined: FEATURE_LIVE_FX_FEED (default false - manual updates only Phase 1), FEATURE_PARTNER_REFUND_API (default false - admin portal only), FEATURE_OUTBOUND_PAYMENTS (default false), FEATURE_BOK_REPORTING (default false - pending OI-03 BOK format confirmation), FEATURE_MULTI_SCHEME_ROUTING (default false - Phase 1 single scheme). Flags are stored in secrets/config manager and loaded at service startup. Changing a flag requires config update and service restart (no full deployment).
**Steps:** Create docs/planning/feature_flags.md listing all five flags.; For each flag record: name, what it controls, Phase 1 default value, and the open item or condition that gates enabling it.; State storage mechanism: config manager entry loaded at startup, not bundled in image.; State that enabling a flag requires a config update and service restart, not a code deployment.; Cross-reference OI-03 as the gate for FEATURE_BOK_REPORTING.
**Deliverable:** docs/planning/feature_flags.md with all five flags, defaults, and enabling conditions
**Acceptance / logic checks:**
- All five flags from OPS-13 Section 5.2 are present with exact names as listed.
- FEATURE_LIVE_FX_FEED default is false with note that live FX feed is Phase 2 scope per PRD conflict-resolution note.
- FEATURE_BOK_REPORTING default is false with reference to OI-03 (BOK reporting format not yet confirmed).
- Document states flags are NOT application code constants - they are runtime config.
- FEATURE_MULTI_SCHEME_ROUTING default false is explained by Phase 1 single-scheme constraint.
**Depends on:** 1.3-T04

### 1.3-T06 — Produce workstream-to-phase staffing matrix from PM-14 Section 4  _(35 min)_
**Context:** PM-14 Section 4 defines eight workstreams: WS-1 Hub Core Backend (SAD-02, DAT-03, RATE-04 - active phases 1,2,3,4), WS-2 Northbound API (API-05, SEC-09 - phases 1,3,4), WS-3 ZeroPay Southbound (SCH-06, DAT-03 - phase 2), WS-4 Admin System (PRD-07, UX-11 - phases 1,2,3), WS-5 Partner Portal (PRD-08, UX-11 - phases 1,3,4), WS-6 Security (SEC-09, NFR-10 - phases 1,2,3,4), WS-7 DevOps (OPS-13, NFR-10 - phases 1,2,3,4), WS-8 QA (QA-12 - phases 1,2,3,4). PM-14 A-14-05 requires at least one senior backend developer allocated to ZeroPay batch interface from May 15 2026 independently of Phase 1 completion.
**Steps:** Create docs/planning/staffing_matrix.md with workstreams as rows and phases (1-4) as columns.; Mark each cell as Active, Inactive, or Overlap.; Add a note for WS-1 and WS-3 overlap from May 15 - Jun 20 (assumption A-14-05: one senior backend dev must be allocated to ZeroPay from May 15 regardless of Phase 1 state).; State sequencing constraints from PM-14 Section 4.2 (WS-1 must be substantially complete before WS-2 can be fully tested; WS-4/WS-5 frontend can start in parallel once API contracts stable; WS-6 applies from Sprint 1; WS-8 starts Sprint 1 with RATE-04 unit tests).; List the primary reference documents for each workstream.
**Deliverable:** docs/planning/staffing_matrix.md mapping workstreams to phases with sequencing constraints
**Acceptance / logic checks:**
- All eight workstreams are present with correct phase activation matching PM-14 Section 4.1.
- WS-3 (ZeroPay) is marked active only in Phase 2 per PM-14.
- Overlap note for WS-1/WS-3 between May 15 and Jun 20 is explicitly stated with reference to A-14-05.
- WS-6 (Security) and WS-8 (QA) are active in all four phases.
- Sequencing constraint that WS-8 begins Sprint 1 with RATE-04 unit test vectors is documented.
**Depends on:** 1.3-T03

### 1.3-T07 — Produce effort estimates table for Phase 1 deliverables  _(45 min)_
**Context:** PM-14 Section 2.2 Phase 1 deliverables: Hub Core backend (scheme registry, partner registry, rate engine per RATE-04, settlement engine, prefunding ledger, revenue ledger, audit log); Admin System per PRD-07/UX-11; Partner Portal per PRD-08/UX-11; Northbound REST API per API-05 with sandbox docs; database schema and migration scripts per DAT-03; CI/CD pipeline and dev+staging environments per OPS-13. Phase 1 runs Apr 10 - Jun 20 2026 (10 weeks). Hub Core schema + rate engine milestone is May 9 (week 4), Admin portal core May 23 (week 6), API sandbox Jun 6 (week 8), Phase 1 gate Jun 20 (week 10).
**Steps:** Create docs/planning/estimates_phase1.md with a table of Phase 1 deliverables.; For each deliverable assign estimate in person-days (backend, frontend, QA, DevOps breakdown).; Mark which WBS sub-packages and WBS IDs cover each deliverable.; Cross-reference each deliverable to its primary spec document (e.g., rate engine to RATE-04, schema to DAT-03).; Sum estimates to verify they are achievable within 10 weeks given the team size assumption from PM-14 (staffing to be confirmed with GME PM).; Highlight the critical path: Hub Core schema (May 9) is a hard dependency before other components can progress.
**Deliverable:** docs/planning/estimates_phase1.md with per-deliverable person-day estimates and WBS cross-references
**Acceptance / logic checks:**
- Table includes all seven Phase 1 deliverable categories from PM-14 Section 2.2.
- Each deliverable is cross-referenced to at least one spec document (SAD-02, DAT-03, RATE-04, API-05, PRD-07, PRD-08, OPS-13).
- Hub Core schema milestone May 9 is identified as blocking Admin System, Partner Portal, and Northbound API progress.
- Estimates sum to a total that is achievable within 10 working weeks.
- CI/CD pipeline and environment provisioning estimates are separate from application code estimates.
**Depends on:** 1.3-T06

### 1.3-T08 — Produce effort estimates table for Phase 2 deliverables  _(40 min)_
**Context:** PM-14 Section 2.2 Phase 2 deliverables: ZeroPay registered as active scheme in backend; all ZP00xx message types (ZP0011/0012/0021/0022/0041/0043/0045/0047/0051/0053/0055/0061-0066) verified against 한결원 test environment; SFTP batch proven end-to-end. Phase 2 runs May 15 - Jul 31 2026 (11 weeks, overlaps Phase 1). Milestones: ZeroPay merchant sync passing Jun 13, all ZP00xx verified Jul 18, Phase 2 gate Jul 31. Critical dependency: 한결원 test environment available by mid-May 2026 (D-01). If test env unavailable by May 15, dev vendor must use mock SFTP in parallel (risk mitigation R-01). SFTP batch job schedule: 13 job types with windows from 02:00 to 22:00 KST (OPS-13 Section 6.2).
**Steps:** Create docs/planning/estimates_phase2.md with per-deliverable estimates.; List all 16 ZP00xx file type implementations as individual line items.; Flag D-01 (KFTC test env) as a hard external dependency with due date May 15 2026.; Include mock SFTP contingency estimate (R-01 mitigation) as a separate line item.; Cross-reference each ZP00xx type to SCH-06 section.; Sum estimates against 11 available weeks noting the Phase 1/Phase 2 overlap reduces available senior backend bandwidth in May-Jun.
**Deliverable:** docs/planning/estimates_phase2.md with ZP00xx line items, KFTC dependency flag, and contingency estimates
**Acceptance / logic checks:**
- 16 ZP00xx file types are listed individually as implementation line items.
- D-01 KFTC dependency is flagged with target date May 15 2026 and stated impact (Phase 2 gate Jul 31 at risk if delayed).
- Mock SFTP stub estimate is included as a risk-mitigation contingency line item.
- Phase 1/Phase 2 overlap bandwidth reduction is noted in the header assumptions.
- Total estimate fits within 11 weeks with stated staffing assumption.
**Depends on:** 1.3-T07

### 1.3-T09 — Produce effort estimates table for Phase 3 and Phase 4 deliverables  _(40 min)_
**Context:** PM-14 Section 2.2: Phase 3 (Aug 1 - Oct 10 2026, 10 weeks) deliverables: GME Remit partner onboarded (LOCAL type, KRW/KRW/KRW, same-currency short-circuit, KRW 500 service charge, no prefunding), MPM+CPM working in production, daily ZeroPay settlement batch running, GME Ops trained, BOK domestic reporting functional (gated by OI-03). Phase 4 (Oct 1 - Dec 10 2026, 10 weeks) deliverables: SendMN/T-Bank onboarded (OVERSEAS type, FX margin 2%, KRW 500 service charge, prefunding), MPM+CPM working, prefunding atomicity confirmed under load, low-balance alert at USD 10000, monthly merchant fee process (gated by OI-02). Phases 3 and 4 overlap Oct 1-10 per A-14-02.
**Steps:** Create docs/planning/estimates_phase34.md with separate sections for Phase 3 and Phase 4.; List GME Remit onboarding config items: partner type LOCAL, settlement ccy KRW/KRW/KRW, service_charge KRW 500, prefunding not required.; List overseas partner onboarding config items: partner type OVERSEAS, m_a+m_b >= 2.0%, service_charge KRW 500, prefunding required.; Flag OI-02 (tax invoice API) as gating Phase 4 overseas merchant billing automation.; Flag OI-03 (BOK reporting format) as gating Phase 3 automated BOK submission.; Note A-14-02: Phase 3 production stability must be confirmed before primary resource shifts to Phase 4.
**Deliverable:** docs/planning/estimates_phase34.md with Phase 3 and Phase 4 deliverable estimates and open-item gates
**Acceptance / logic checks:**
- GME Remit is correctly specified as LOCAL type with KRW/KRW/KRW same-currency short-circuit and no prefunding requirement.
- Overseas partners (SendMN/T-Bank) are correctly specified as OVERSEAS type requiring prefunding and m_a+m_b >= 2.0%.
- OI-02 and OI-03 are flagged as gates with explicit description of what is blocked if unresolved.
- Phase 3/Phase 4 overlap window Oct 1-10 is noted with A-14-02 constraint (Phase 3 stability first).
- Service charge KRW 500 appears in both Phase 3 and Phase 4 onboarding specs.
**Depends on:** 1.3-T08

### 1.3-T10 — Create RAID log template and populate from PM-14 Sections 7 and 9  _(45 min)_
**Context:** PM-14 Section 7 defines 8 risks, 8 assumptions, 4 issues, and 8 dependencies. Section 9 has 3 open items. Key risks: R-01 KFTC test env delayed (High/High); R-02 BOK reporting format late (Med/High); R-03 rate engine error (Med/High); R-04 prefunding atomicity bug (Med/High); R-05 tax invoice API unavailable (Med/Med); R-06 ZeroPay file format changes (Low/Med); R-07 overseas partner delayed (Med/Med); R-08 FX rates stale (Med/Med). Issues: I-01 OI-03 BOK spec open; I-02 OI-02 tax invoice open (urgent); I-03 OI-01 customer approval method open; I-04 KFTC test env access not confirmed.
**Steps:** Create docs/planning/RAID_log.csv with columns: type (R/A/I/D), id, description, likelihood, impact, mitigation_or_status, owner, target_date.; Populate all 8 risks, 8 assumptions, 4 issues, and 8 dependencies from PM-14 Sections 7.1-7.4.; Populate 3 open items from PM-14 Section 9 as Issues.; Mark I-02 (OI-02 tax invoice) and I-04 (KFTC env) as URGENT with escalation owners.; Confirm that R-03 (rate engine error) mitigation is RATE-04 test vectors run from Sprint 1.
**Deliverable:** docs/planning/RAID_log.csv with all RAID entries from PM-14 Sections 7 and 9
**Acceptance / logic checks:**
- CSV contains exactly 8 risk rows (R-01 through R-08), 8 assumption rows (A-01 through A-08), 4 issue rows (I-01 through I-04), and 8 dependency rows (D-01 through D-08).
- R-01 has likelihood High, impact High, mitigation referencing mock SFTP contingency.
- R-03 mitigation explicitly states RATE-04 test vectors run from Sprint 1.
- I-02 and I-04 are marked urgent with correct owners (GME Account/Tech and GME BD respectively).
- D-01 dependency from KFTC to Dev Vendor has required-by date May 15 2026.
**Depends on:** 1.3-T01

### 1.3-T11 — Create RACI matrix document from PM-14 Section 8  _(35 min)_
**Context:** PM-14 Section 8 defines a RACI for 28 activities across six roles: GME Product, GME Ops/Finance, GME Compliance, Dev Vendor, KFTC/한결원, Partner. Key RACI entries include: Rate engine implementation (Dev Vendor A/R, GME Product C, GME Ops/Finance C); ZeroPay batch integration (Dev Vendor A/R, KFTC C); CI/CD and environments (Dev Vendor A/R only); Rate/margin configuration in Admin portal (GME Ops/Finance A/R); FX treasury rate updates Phase 1 (GME Ops/Finance A/R); Phase gate sign-off technical (Dev Vendor A/R); Phase gate sign-off business (GME Product A/R); UAT execution (GME Ops/Finance A/R, Dev Vendor R); Production deployment (Dev Vendor A/R).
**Steps:** Create docs/planning/RACI.md with a matrix table: activities as rows, roles as columns.; Populate all 28 activities from PM-14 Section 8 with R/A/C/I values per role.; Highlight activities where Dev Vendor is solely accountable with no GME counterpart as Responsible (potential bottleneck).; Note activities where external parties (KFTC, Partner) have R or A responsibilities - these cannot be expedited by Dev Vendor.; Cross-reference RACI rows that relate to open items (e.g., BOK reporting format confirmation = GME Compliance R, GME Product A).
**Deliverable:** docs/planning/RACI.md with 28-row RACI matrix covering all activities from PM-14 Section 8
**Acceptance / logic checks:**
- Matrix has exactly 28 activity rows and 6 role columns matching PM-14 Section 8.
- Rate engine implementation row shows Dev Vendor as A/R and GME Ops/Finance as C.
- FX treasury rate updates row shows GME Ops/Finance as A/R with no Dev Vendor responsibility.
- ZP00xx file spec provision row shows KFTC as R and GME Product as A (outside Dev Vendor control).
- Training (GME Ops on Admin portal) shows Dev Vendor as A/R.
**Depends on:** 1.3-T01

### 1.3-T12 — Document external dependency schedule and escalation protocol  _(35 min)_
**Context:** PM-14 Section 6 defines six external dependencies with due dates: KFTC test environment (mid-May 2026, owner GME BD, impact: Phase 2 and all downstream delayed 1:1); ZP00xx file specs (May 15 2026, owner GME BD/KFTC); BOK FX reporting format OI-03 (TBD - escalate urgently, owner GME Compliance); tax invoice API OI-02 (before Phase 1 dev start, owner GME Account/Tech); GME Remit integration readiness (Aug 1 2026, owner GME Remit Tech); SendMN/T-Bank integration readiness (Oct 1 2026, owner external partners); initial prefunding deposit (before Phase 4 go-live). PM-14 Section 3.2 states dev vendor must notify GME immediately if KFTC access not granted by May 15 2026.
**Steps:** Create docs/planning/external_dependencies.md listing all dependencies from PM-14 Section 6 and RAID D-01 through D-08.; For each dependency include: id, description, owner, due date, impact-if-late, current-status.; Define escalation protocol: check KFTC access status weekly from Apr 10; if not confirmed by Apr 17 (written confirmation per I-04), escalate immediately.; Define fallback for D-01: begin mock SFTP development in parallel per R-01 mitigation.; Flag OI-02 and OI-03 as requiring urgent resolution before Phase 1 dev start and Phase 3 go-live respectively.
**Deliverable:** docs/planning/external_dependencies.md with dependency register and escalation protocol
**Acceptance / logic checks:**
- All six dependency categories from PM-14 Section 6 are present with correct owners and due dates.
- KFTC test environment dependency impact is stated as 1:1 delay propagation to all downstream phases.
- Escalation trigger for D-01/I-04 is written confirmation by Apr 17 2026 (as per I-04 action).
- Mock SFTP fallback for R-01 is documented as a parallel workstream starting if KFTC access not confirmed by May 15.
- OI-02 (tax invoice) is flagged as blocking UC-04-04 automation with manual fallback instruction matching PM-14 Section 10.
**Depends on:** 1.3-T10

### 1.3-T13 — Define phase gate acceptance criteria checklist (Phases 1-4)  _(40 min)_
**Context:** PM-14 Section 5 defines acceptance criteria per phase. Phase 1 (8 criteria): rate-engine test vectors in QA-12 pass within 0.01 USD tolerance; API sandbox all endpoints respond correctly; Admin portal allows scheme/partner/margin setup without developer assistance; Partner portal login + prefunding balance + transaction list visible; pool identity (collection_usd - collection_margin_usd - payout_margin_usd = payout_usd_cost) verified; same-currency short-circuit for KRW/KRW/KRW; audit log captures actor+timestamp+previous value; API auth+secrets+endpoints per SEC-09. Phase 2: all ZP00xx types correct; daily batch windows met; E2E test with zero reconciliation discrepancy; discrepancy alerts within SLA. Phase 3: UAT sign-off; 10 live E2E transactions; 3 consecutive settlement days clean; Ops trained. Phase 4: prefunding atomicity under load; 5 FX scenarios; low-balance alert at $10000; collection/payout match offer_rate.
**Steps:** Create docs/planning/phase_gate_checklists.md with one section per phase.; For each criterion write a verifiable pass/fail test statement (who performs the test, what the expected outcome is).; Phase 1: include the pool identity formula explicitly: collection_usd - collection_margin_usd - payout_margin_usd == payout_usd_cost within 0.01 USD.; Phase 4: include the prefunding atomicity test description: concurrent POST requests must not over-deduct balance (SELECT FOR UPDATE pattern, see RATE-04).; Cross-reference each criterion to its source document.
**Deliverable:** docs/planning/phase_gate_checklists.md with verifiable pass/fail acceptance statements for all four phases
**Acceptance / logic checks:**
- Phase 1 checklist has 8 items matching PM-14 Section 5.1 exactly.
- Pool identity criterion states tolerance 0.01 USD and references RATE-04.
- Same-currency short-circuit criterion explicitly names KRW/KRW/KRW (Domestic direction, GME Remit).
- Phase 3 criterion includes minimum 10 live E2E transactions and 3 consecutive settlement days.
- Phase 4 prefunding atomicity test references SELECT FOR UPDATE and concurrent load scenario.
**Depends on:** 1.3-T11

### 1.3-T14 — Create sprint-level backlog skeleton: Sprint 1 and Sprint 2 tickets for Phase 1  _(40 min)_
**Context:** Sprint 1 starts Apr 13 2026 (first sprint after contract signed Apr 10). PM-14 and WBS mandate: Sprint 1 must include RATE-04 unit tests (WS-8 starts Sprint 1 with rate-engine vectors), environment provisioning (milestone Apr 17), and DB schema + migration scripting start (DAT-03). Sprint 2 runs Apr 27 - May 10. Milestone May 9: Hub Core schema + rate engine implemented. Rate engine uses 5-step USD-volume margin RECEIVE mode: step 1 payout_usd_cost = target_payout / cost_rate_pay; step 2 collection_usd = payout_usd_cost / (1-m_a-m_b); step 3 margins; step 4 send_amount = collection_usd * cost_rate_coll; step 5 collection_amount = send_amount + service_charge. Min combined margin m_a+m_b >= 2.0% for cross-border.
**Steps:** Create docs/planning/sprint_backlog_S1_S2.md listing epic-level tickets for Sprints 1 and 2.; Sprint 1 (Apr 13-26): environment provisioning (WS-7), DB schema setup (WS-1), RATE-04 unit test vectors (WS-8), CI/CD pipeline skeleton (WS-7).; Sprint 2 (Apr 27-May 10): complete Hub Core rate engine (WS-1), complete DB migration scripts (WS-1), Admin System skeleton (WS-4).; For each ticket stub include: title, workstream, primary doc reference, acceptance condition.; Flag the May 9 milestone as the Sprint 2 end deliverable gate.
**Deliverable:** docs/planning/sprint_backlog_S1_S2.md with Sprint 1 and 2 ticket stubs and milestone gate
**Acceptance / logic checks:**
- Sprint 1 includes RATE-04 unit tests as a first-class ticket (not optional).
- Sprint 1 includes environment provisioning with Apr 17 milestone target.
- Sprint 2 end date aligns with or precedes May 9 Hub Core schema milestone.
- Each ticket stub references its primary spec document.
- Rate engine 5-step RECEIVE mode is referenced as the Sprint 2 core implementation target.
**Depends on:** 1.3-T03, 1.3-T07

### 1.3-T15 — Create sprint-level backlog skeleton: Sprints 3-6 covering Admin portal and Northbound API  _(40 min)_
**Context:** Sprint 3 Apr 27-May 10 (overlaps with Sprint 2 boundary adjustment), Sprint 4 May 11-24, Sprint 5 May 25-Jun 7, Sprint 6 Jun 8-20. Milestone May 23: Admin portal core screens functional (scheme + partner setup). Milestone Jun 6: Northbound API sandbox available. Milestone Jun 20: Phase 1 gate. Phase 2 also starts May 15 (Sprint 4 begins ZeroPay WS-3). Admin System must allow GME Ops to: register a QR scheme, register a Partner (type LOCAL/OVERSEAS), set margins m_a and m_b, view test transactions. Partner Portal must show prefunding balance and transaction list. Adding scheme/partner is CONFIG not code - generic-build constraint A-14-04 applies from Phase 1.
**Steps:** Create docs/planning/sprint_backlog_S3_S6.md for Sprints 3 through 6.; Sprint 4 (May 11-24): Admin portal core screens (WS-4), ZeroPay SFTP skeleton (WS-3 start).; Sprint 5 (May 25-Jun 7): Northbound API sandbox (WS-2), Partner Portal skeleton (WS-5).; Sprint 6 (Jun 8-20): Phase 1 gate preparation, integration testing, sandbox docs.; Flag generic-build constraint: no scheme/partner logic hardcoded - all registered via DB config.
**Deliverable:** docs/planning/sprint_backlog_S3_S6.md with Sprint 3-6 ticket stubs and two milestones (May 23, Jun 6)
**Acceptance / logic checks:**
- Sprint 4 stubs include Admin portal core screens with May 23 milestone gate.
- Sprint 5 stubs include Northbound API sandbox with Jun 6 milestone gate.
- Sprint 6 is explicitly reserved for Phase 1 gate preparation and demo.
- ZeroPay WS-3 work appears starting Sprint 4 (after May 15).
- Generic-build constraint is documented as a Phase 1 acceptance condition.
**Depends on:** 1.3-T14

### 1.3-T16 — Create sprint-level backlog skeleton: Sprints 7-14 covering Phase 2 ZeroPay integration  _(45 min)_
**Context:** Phase 2 spans May 15 - Jul 31 2026 (Sprints 4-14). Milestones: ZeroPay merchant sync (ZP0041/ZP0051) passing Jun 13 (Sprint 5-6 boundary); all ZP00xx verified Jul 18 (Sprint 12); Phase 2 gate Jul 31 (Sprint 14). 16 ZP00xx file types: ZP0011 payment result, ZP0012 payment reg result, ZP0021 refund result, ZP0022 refund reg result, ZP0041/0043/0045/0047 merchant sync, ZP0051/0053/0055 full merchant list, ZP0061-0066 settlement. OPS-13 Section 6.2 defines 13 batch job types (JOB-ZP-01 through JOB-ZP-13) with KST windows. SFTP batch must be idempotent: check run-state DB record before generating new file; unique job_run_id UUID per run.
**Steps:** Create docs/planning/sprint_backlog_S7_S14.md for Sprints 7 through 14.; Group ZP00xx implementation by subsystem: payment/refund (ZP0011/0012/0021/0022), merchant sync (ZP0041-0055), settlement (ZP0061-0066).; Assign each ZP00xx group to a sprint with acceptance condition referencing SCH-06.; Sprint 12 target: all ZP00xx types verified (Jul 18 milestone).; Sprint 14 target: Phase 2 gate E2E ZeroPay test transaction (Jul 31 milestone).; Include idempotency requirements: job_run_id UUID, DB run-state check before file generation.
**Deliverable:** docs/planning/sprint_backlog_S7_S14.md with ZP00xx sprint assignments and two milestones (Jul 18, Jul 31)
**Acceptance / logic checks:**
- All 16 ZP00xx file types appear in the backlog distributed across Sprints 7-12.
- Sprint 12 end is mapped to the Jul 18 milestone (all ZP00xx verified).
- Sprint 14 end is mapped to the Jul 31 Phase 2 gate milestone.
- Idempotency requirement (job_run_id, run-state DB record) is documented for each batch job type.
- SFTP credential separation (test vs. production) is noted as a constraint.
**Depends on:** 1.3-T15, 1.3-T08

### 1.3-T17 — Create sprint-level backlog skeleton: Sprints 15-22 covering Phase 3 GME Remit launch  _(40 min)_
**Context:** Phase 3 runs Aug 1 - Oct 10 2026 (10 weeks, approx Sprints 15-24). Milestones: GME Remit staging integration test Sep 5 (Sprint 19-20 boundary); UAT sign-off Sep 26 (Sprint 22); Production deployment Oct 10 (Sprint 24); Phase 3 gate Oct 10. GME Remit: LOCAL partner, KRW/KRW/KRW, same-currency short-circuit (collection_amount = target_payout + service_charge, no USD pool), service_charge KRW 500. Phase 3 acceptance: UAT signed off by GME Ops and Finance per QA-12; at least 10 E2E live production transactions (MPM and CPM); settlement batch runs without exception for 3 consecutive business days; GME Ops trained and Admin portal runbook confirmed per OPS-13; payment success rate >= 98%; settlement batch delivery 100% on-time; new scheme/partner setup < 30 minutes.
**Steps:** Create docs/planning/sprint_backlog_S15_S22.md for Sprints 15-22.; Sprint 15-18: GME Remit partner onboarding config, MPM+CPM production readiness, Ops training material.; Sprint 19-20: staging integration test with GME Remit tech team (D-05: readiness required Aug 1).; Sprint 21-22: UAT execution (Sep 26 milestone), defect remediation.; Document GME Remit same-currency short-circuit: when collection=settle_A=settle_B=payout=KRW, skip USD pool; collection_amount = target_payout + service_charge.; Include 30-minute partner setup benchmark as acceptance test.
**Deliverable:** docs/planning/sprint_backlog_S15_S22.md with Phase 3 sprint stubs and three milestones (Sep 5, Sep 26, Oct 10)
**Acceptance / logic checks:**
- Sprint 20 end is aligned to Sep 5 GME Remit staging test milestone.
- Sprint 22 end is aligned to Sep 26 UAT sign-off milestone.
- Same-currency short-circuit for KRW/KRW/KRW is documented as the key technical test case for Phase 3.
- D-05 dependency (GME Remit tech team available Aug 1) is noted as gating Sprint 19 start.
- 30-minute new partner setup time is listed as a Phase 3 success metric with reference to BRD-01 Section 9.
**Depends on:** 1.3-T16, 1.3-T09

### 1.3-T18 — Create sprint-level backlog skeleton: Sprints 23-30 covering Phase 4 overseas partner launch  _(40 min)_
**Context:** Phase 4 runs Oct 1 - Dec 10 2026 (10 weeks, overlaps Phase 3 tail per A-14-02). Milestones: SendMN/T-Bank staging test Nov 7 (Sprint 27); UAT sign-off Phase 4 Nov 28 (Sprint 29); Production deployment Dec 10 (Sprint 31). Overseas partner (OVERSEAS type): m_a+m_b >= 2.0%, service_charge KRW 500, prefunding required (SELECT FOR UPDATE deduction). Phase 4 acceptance: prefunding atomicity under concurrent load; FX margin correct for 5 distinct rate scenarios; low-balance alert at USD 10000 threshold; collection/payout amounts match offer_rate; monthly merchant fee process operational (gated by OI-02). D-06 SendMN/T-Bank integration readiness required Oct 1. D-07 initial prefunding deposit confirmed before Phase 4 go-live.
**Steps:** Create docs/planning/sprint_backlog_S23_S30.md for Sprints 23-30.; Sprint 23-24: Phase 3 production stabilisation (A-14-02 constraint), Phase 4 overseas partner config preparation.; Sprint 25-27: overseas partner integration testing with SendMN/T-Bank (D-06 required Oct 1).; Sprint 28-29: Phase 4 UAT (Nov 28 milestone), prefunding load testing.; Sprint 30: Phase 4 go-live deployment (Dec 10 milestone).; Document D-07: initial prefunding deposit must be confirmed by GME Finance before Phase 4 activation.
**Deliverable:** docs/planning/sprint_backlog_S23_S30.md with Phase 4 sprint stubs and three milestones (Nov 7, Nov 28, Dec 10)
**Acceptance / logic checks:**
- A-14-02 constraint (Phase 3 production stability before Phase 4 primary resource) is explicitly noted on Sprint 23.
- Sprint 25 start is gated by D-06 (external partner integration readiness Oct 1).
- D-07 (initial prefunding deposit) is documented as a pre-activation gate, not a dev vendor action.
- Prefunding atomicity load test is a Phase 4 UAT requirement with SELECT FOR UPDATE referenced.
- Five distinct FX rate scenario tests are listed as Phase 4 acceptance criteria.
**Depends on:** 1.3-T17

### 1.3-T19 — Define CI/CD pipeline stages specification document  _(35 min)_
**Context:** OPS-13 Section 4.1 defines 8 pipeline stages for every code change: (1) Build - compile+lint, zero errors gate; (2) Unit test - all pass, coverage >= 80%; (3) Security scan - SAST, dependency vuln scan, secrets leak scan, no critical/high CVEs; (4) Integration test - ephemeral env with DB, all tests pass; (5) Artifact - build container image, tag with git-sha+branch+semver, push to registry, sign image; (6) Deploy to int - automated, smoke tests pass; (7) Deploy to staging - on release/* merge, manual approval gate; (8) Deploy to prod - Release Manager approval + change record + QA sign-off. Branching: trunk-based, feature/<ticket> -> main (int auto-deploy), release/<version> (staging auto), hotfix/<ticket> (fast-track with two senior engineers + on-call).
**Steps:** Create docs/planning/cicd_pipeline_spec.md describing all 8 pipeline stages with gate conditions.; Define branching strategy: feature/<ticket>, main, release/<version>, hotfix/<ticket> with deploy targets.; Document approval gates: int = fully automated; staging = automated on release/* + stakeholder sign-off; prod = Release Manager + change record + QA sign-off.; Document rollback strategy: automated rollback on smoke test failure within 5 min (redeploys previous image tag); manual rollback via runbook (OPS-13 Section 8.1).; State migration rule: no down migrations in production; blue-green for reversibility.; State secret injection: gmepay/<env>/<service>/<secret-name> paths, loaded at container start.
**Deliverable:** docs/planning/cicd_pipeline_spec.md with 8-stage pipeline definition and branching strategy
**Acceptance / logic checks:**
- All 8 pipeline stages are listed with exact gate conditions from OPS-13 Section 4.1 (e.g., coverage >= 80%, no high/critical CVEs).
- Three approval levels are defined: automated for int, stakeholder for staging, Release Manager + change record for prod.
- Automated rollback trigger is specified as smoke test failure within 5 minutes.
- Secret path convention is documented with an example path.
- Hotfix branch fast-track approval (two senior engineers + on-call, post-deployment review mandatory) is documented.
**Depends on:** 1.3-T04

### 1.3-T20 — Define database migration strategy and versioning rules  _(30 min)_
**Context:** OPS-13 Section 4.6 mandates: versioned migration tool (Flyway or Liquibase); every migration has unique sequential version number; migrations applied automatically at deploy time before new app version starts; backward-compatible with currently running version (additive-only: add columns/tables, never rename or drop in same release); to remove a column: release 1 removes code references, release 2 drops column; scripts in db/migrations/ reviewed as strictly as app code; all runs logged with version, timestamp, duration, operator identity; no down migrations in production. Blue-green strategy provides reversibility. OPS-13 Section 4.5 also defines blue-green for major schema-changing deploys and canary (5% traffic, 15-min monitor, SLO-breach rollback) for high-risk API changes.
**Steps:** Create docs/planning/db_migration_strategy.md with migration rules as numbered constraints.; Define the two-release column removal process with example: Release N removes code reference to column X; Release N+1 drops column X.; Document migration file naming convention: V<number>__<descriptive_name>.sql in db/migrations/.; Document the blue-green deployment process for schema-changing releases.; Document the canary deployment process: 5% traffic to new version, 15-minute window, auto-promote or auto-rollback based on SLO breach.; State that migration run logs must include: version, timestamp, duration, and operator identity (for audit trail per DAT-03 audit requirements).
**Deliverable:** docs/planning/db_migration_strategy.md with migration rules, naming convention, and deployment processes
**Acceptance / logic checks:**
- Two-release column removal process is documented with a concrete example.
- Migration naming convention V<number>__<name>.sql matches Flyway/Liquibase convention.
- Blue-green process states it provides rollback via LB/DNS pointer flip with zero downtime.
- Canary process specifies 5% traffic, 15-minute window, and SLO-breach as the auto-rollback trigger.
- No down migrations rule is stated with justification (blue-green provides reversibility).
**Depends on:** 1.3-T19

### 1.3-T21 — Define observability and alerting plan: metrics, logging, and dashboard specifications  _(40 min)_
**Context:** OPS-13 Section 7 defines: RED metrics (api_request_rate, api_error_rate < 1%, api_latency_p95 < 500ms); USE metrics for PostgreSQL (CPU/IOPS, replication lag), Redis (memory, eviction rate), SFTP worker (job duration, queue depth); structured JSON logging with required fields: timestamp ISO-8601 UTC, level, service, env, trace_id UUID, span_id UUID, partner_id, transaction_id UUID, event, message; OpenTelemetry tracing with 8-step transaction trail (rate_quote_issued, payment_initiated, prefund_deducted, scheme_request_sent, scheme_response_received, transaction_committed, webhook_dispatched, webhook_delivered/failed); six dashboards (Operations Overview, API SLO, Batch Health, Prefunding Monitor, Revenue, Security).
**Steps:** Create docs/planning/observability_plan.md with sections for metrics, logging, tracing, dashboards, and alerts.; List all RED metrics with SLO thresholds (error rate < 1%, p95 latency < 500ms from NFR-10).; List all 8 required structured log fields and specify that monetary amounts (collection_amount, prefund_balance) are not logged in production log streams.; Define the 8 OpenTelemetry trace steps for a full transaction.; Specify all 6 dashboard names and their key panels.; Cross-reference OPS-13 Section 7.5 alert catalog for P1/P2/P3/P4 conditions.
**Deliverable:** docs/planning/observability_plan.md with metrics, logging schema, tracing spec, and dashboard list
**Acceptance / logic checks:**
- RED metrics include api_error_rate < 1% and api_latency_p95 < 500ms with reference to NFR-10.
- All 10 required structured log fields are listed (timestamp, level, service, env, trace_id, span_id, partner_id, transaction_id, event, message).
- Security note states that collection_amount and prefund_balance are excluded from production log streams.
- 8 OpenTelemetry trace steps are listed in order matching OPS-13 Section 7.3.
- 6 dashboard names match OPS-13 Section 7.4 (Operations Overview, API SLO, Batch Health, Prefunding Monitor, Revenue, Security).
**Depends on:** 1.3-T04

### 1.3-T22 — Define prefunding alerting thresholds and escalation runbook per environment  _(30 min)_
**Context:** OPS-13 Section 7.5.2 defines prefunding alerts: low balance < USD 10,000 (P2 - email partner + notify GME Ops); critical balance < USD 2,000 (P1 - suspend partner payments + escalate immediately); balance zero (P1 - all partner payments suspended). OPS-13 Section 5.3 environment config: dev $100, int $1,000, staging $10,000, prod $10,000 per partner config. OVERVIEW from canonical facts: prefunding applies only to OVERSEAS partners (SendMN, T-Bank type = OVERSEAS); LOCAL partners (GME Remit) require no prefunding. Deduction is atomic via SELECT FOR UPDATE. Low-balance alert is a Phase 4 acceptance criterion (PM-14 Section 5.4): alert at USD 10,000 must be confirmed working.
**Steps:** Create docs/planning/prefunding_alert_spec.md with alert threshold table for each environment.; Define three alert levels: Low (P2 < $10,000), Critical (P1 < $2,000), Zero (P1 = 0), with actions for each.; State that prefunding applies only to OVERSEAS partner type; LOCAL partners do not have a prefunding balance.; Define escalation for P1: suspend partner payments, escalate to GME Ops; confirm partner is notified via email.; State that deduction atomicity (SELECT FOR UPDATE) prevents overdraft; schema design must ensure no race condition between concurrent payment requests.; Cross-reference Phase 4 acceptance criterion: low-balance alert at $10,000 must be demonstrated in UAT.
**Deliverable:** docs/planning/prefunding_alert_spec.md with per-tier thresholds, alert levels, and escalation actions
**Acceptance / logic checks:**
- Three alert levels are defined with exact USD thresholds: $10,000 (P2), $2,000 (P1), $0 (P1).
- Per-environment threshold table matches OPS-13 Section 5.3 values (dev $100, int $1,000, staging $10,000, prod $10,000).
- Document explicitly states prefunding applies to OVERSEAS partners only; LOCAL partners are excluded.
- SELECT FOR UPDATE atomicity requirement is referenced for Phase 4 implementation.
- Phase 4 UAT step to demonstrate low-balance alert firing is listed.
**Depends on:** 1.3-T13

### 1.3-T23 — Define batch job schedule, retry policy, and alerting plan for ZeroPay SFTP jobs  _(40 min)_
**Context:** OPS-13 Section 6 defines 13 ZeroPay batch jobs (JOB-ZP-01 through JOB-ZP-13) with KST windows. Critical jobs: JOB-ZP-01 ZP0011 GME->ZeroPay by 02:00 KST; JOB-ZP-02 ZP0021 GME->ZeroPay by 02:00 KST; JOB-ZP-03 ZP0012 ZeroPay->GME expected by 05:00 KST; JOB-ZP-05 ZP0061 settlement request GME->ZeroPay by 05:00 KST; JOB-ZP-06 ZP0062 settlement result expected by 10:00 KST. Retry policy: up to 3 automatic retries, exponential back-off 30s/120s/300s; after 3 failures job enters FAILED state, P1 alert fired, dependency chain halts. Alert on missed windows: ZP0011/0021 not submitted by 01:45 KST = P1 PagerDuty + Ops Slack. Idempotency: unique job_run_id UUID per run; duplicate runs for same logical date no-op.
**Steps:** Create docs/planning/batch_job_schedule.md with a table of all 13 jobs.; For each job include: job_id, file(s), direction (GME->ZeroPay or ZeroPay->GME), submission or receipt window (KST), dependency on other jobs, criticality level.; Define the daily critical-path dependency chain: transactions close -> JOB-ZP-01/02 02:00 -> JOB-ZP-03/04 05:00 receive -> validate -> JOB-ZP-05 05:00 submit -> JOB-ZP-06 10:00 receive -> reconcile -> JOB-ZP-07 14:00 -> JOB-ZP-08 19:00 -> JOB-ZP-09 22:00.; Document retry policy: max 3 retries, back-off 30s/120s/300s, FAILED state after 3 failures with P1 alert.; Document P1 alert conditions for missed windows with exact KST times and notification channels (PagerDuty + Ops Slack).
**Deliverable:** docs/planning/batch_job_schedule.md with 13-job table, dependency chain, retry policy, and alert conditions
**Acceptance / logic checks:**
- All 13 batch jobs (JOB-ZP-01 through JOB-ZP-13) are present with correct KST windows matching OPS-13 Section 6.2.
- Critical-path chain is documented in order: JOB-ZP-01/02 -> JOB-ZP-03/04 -> JOB-ZP-05 -> JOB-ZP-06 -> JOB-ZP-07 -> JOB-ZP-08 -> JOB-ZP-09.
- Retry policy shows exactly 30s/120s/300s back-off intervals with FAILED state and P1 alert after 3rd failure.
- P1 alert for ZP0011/0021 missed window is at 01:45 KST (15 minutes before 02:00 deadline).
- Idempotency via job_run_id UUID and duplicate-run no-op is documented.
**Depends on:** 1.3-T16

### 1.3-T24 — Unit test: verify sprint dates and phase gate alignment in sprint_cadence.csv  _(30 min)_
**Context:** Sprint cadence document from ticket 1.3-T03 must have sprint boundaries correctly aligned to four phase gates: Phase 1 gate Jun 20 2026, Phase 2 gate Jul 31 2026, Phase 3 gate Oct 10 2026, Phase 4 gate Dec 10 2026. Sprint duration must be exactly 14 days (2 weeks). Phases 1 and 2 overlap May 15 - Jun 20. All dates must be Monday start (sprint starts on Monday). The test also verifies that no sprint spans across a phase gate date without the gate date landing exactly on a sprint end date.
**Steps:** Write a test script in tests/planning/test_sprint_cadence.py (or equivalent).; Parse docs/planning/sprint_cadence.csv.; Assert all four phase gate dates are sprint end dates.; Assert all sprint durations are exactly 14 days.; Assert that sprints covering May 15 - Jun 20 are marked as Phase 1+2 overlap.; Assert no gap between consecutive sprint end and next sprint start.
**Deliverable:** tests/planning/test_sprint_cadence.py that validates sprint_cadence.csv structure and gate alignment
**Acceptance / logic checks:**
- Test passes when Jun 20, Jul 31, Oct 10, and Dec 10 all appear as sprint_end dates in the CSV.
- Test fails with clear message if any sprint duration is not exactly 14 days.
- Test confirms at least one sprint is marked with both Phase 1 and Phase 2 in its phase column (the overlap period).
- Test confirms no date gaps between consecutive sprint rows.
- Test is runnable with a single command and produces pass/fail output.
**Depends on:** 1.3-T03

### 1.3-T25 — Unit test: verify RAID log completeness and required field population  _(25 min)_
**Context:** RAID log document from ticket 1.3-T10 must contain exactly 8 risks, 8 assumptions, 4 issues, and 8 dependencies as defined in PM-14 Sections 7 and 9. Critical checks: R-01 must be present with High likelihood and High impact; R-03 mitigation must reference Sprint 1; D-01 must have due date May 15 2026; I-02 must be marked urgent; I-04 must have target date mid-May 2026. All rows must have non-empty id, description, owner, and status fields.
**Steps:** Write tests/planning/test_raid_log.py (or equivalent) to validate RAID_log.csv.; Assert type=R count is 8, type=A count is 8, type=I count is 4, type=D count is 8.; Assert R-01 likelihood == High and impact == High.; Assert D-01 target_date contains May and 2026 and owner contains GME BD.; Assert I-02 status field contains urgent.; Assert all rows have non-empty id, description, owner columns.
**Deliverable:** tests/planning/test_raid_log.py that validates completeness and critical field values in RAID_log.csv
**Acceptance / logic checks:**
- Test counts exactly 8+8+4+8 = 28 rows in RAID_log.csv; fails with count mismatch message otherwise.
- R-01 row validation passes when likelihood=High and impact=High.
- D-01 due-date check passes when value references May 2026.
- I-02 urgency check passes when status field contains urgent.
- Test is runnable standalone and produces actionable error messages on failure.
**Depends on:** 1.3-T10

### 1.3-T26 — Unit test: verify feature flag register completeness and default values  _(20 min)_
**Context:** Feature flag register from ticket 1.3-T05 must contain exactly 5 flags as defined in OPS-13 Section 5.2: FEATURE_LIVE_FX_FEED (default false), FEATURE_PARTNER_REFUND_API (default false), FEATURE_OUTBOUND_PAYMENTS (default false), FEATURE_BOK_REPORTING (default false), FEATURE_MULTI_SCHEME_ROUTING (default false). All Phase 1 defaults must be false because: live FX feed is Phase 2 scope; refund API is admin-only in Phase 1; outbound payments not in Phase 1 scope; BOK reporting pending OI-03; multi-scheme routing not needed in Phase 1 single-scheme configuration.
**Steps:** Write tests/planning/test_feature_flags.py to parse docs/planning/feature_flags.md.; Assert all 5 flag names are present (exact string match).; Assert all 5 Phase 1 default values are false.; Assert FEATURE_BOK_REPORTING has a reference to OI-03 in the enabling_condition field.; Assert FEATURE_LIVE_FX_FEED has a note referencing Phase 2.; Fail with clear message if any flag is missing or has a non-false default.
**Deliverable:** tests/planning/test_feature_flags.py that validates feature flag names and defaults
**Acceptance / logic checks:**
- Test asserts presence of all 5 exact flag names as listed in OPS-13 Section 5.2.
- Test asserts all 5 default values are false for Phase 1.
- FEATURE_BOK_REPORTING validation includes check for OI-03 reference.
- FEATURE_LIVE_FX_FEED validation includes check for Phase 2 scope reference.
- Test is runnable and produces clear output per flag checked.
**Depends on:** 1.3-T05

### 1.3-T27 — Unit test: verify milestone table completeness and date consistency  _(25 min)_
**Context:** Milestone table from ticket 1.3-T02 must contain exactly 19 milestones from PM-14 Section 3.1. Key date constraints: contract signed = Apr 10 2026; dev env provisioned = Apr 17 2026 (7 days after contract); Hub Core schema + rate engine = May 9 2026; Admin portal core = May 23 2026; Northbound API sandbox = Jun 6 2026; Phase 1 gate = Jun 20 2026; KFTC test env = mid-May 2026; all ZP00xx verified = Jul 18 2026; Phase 2 gate = Jul 31 2026; GME Remit staging = Sep 5 2026; UAT sign-off Phase 3 = Sep 26 2026; Production deployment = Oct 10 2026; Phase 3 gate = Oct 10 2026; overseas staging = Nov 7 2026; UAT sign-off Phase 4 = Nov 28 2026; prod deployment Phase 4 = Dec 10 2026.
**Steps:** Write tests/planning/test_milestones.py to parse docs/planning/milestones.csv.; Assert row count is 19.; Assert specific target dates for at least 8 named milestones (contract Apr 10, dev env Apr 17, Hub Core May 9, API sandbox Jun 6, Phase 1 gate Jun 20, Phase 2 gate Jul 31, Phase 3 gate Oct 10, Phase 4 deployment Dec 10).; Assert phase gate milestones precede or equal the subsequent phase start date (e.g., Phase 1 gate Jun 20 <= Phase 2 start May 15 is intentional overlap - flag this as overlap not error).; Assert KFTC milestone is flagged as critical-path.
**Deliverable:** tests/planning/test_milestones.py that validates milestone count, dates, and critical-path flag
**Acceptance / logic checks:**
- Test asserts exactly 19 milestone rows; fails with count message otherwise.
- 8 specific date assertions all pass for the named milestones.
- Phase 1/Phase 2 date overlap is detected and accepted (not flagged as error) because PM-14 explicitly plans this overlap.
- KFTC milestone critical-path flag check passes.
- Test output identifies which specific milestone failed the date check.
**Depends on:** 1.3-T02

### 1.3-T28 — Unit test: verify phase gate checklist completeness and acceptance criteria field presence  _(25 min)_
**Context:** Phase gate checklists from ticket 1.3-T13 must contain: Phase 1 - 8 criteria including pool identity formula, same-currency short-circuit, audit log, security (SEC-09); Phase 2 - 4 criteria (ZP00xx types, batch windows, E2E test, discrepancy alert); Phase 3 - 5 criteria (UAT, 10 E2E transactions, 3 settlement days, Ops trained, success metrics); Phase 4 - 5 criteria (atomicity, 5 FX scenarios, $10,000 alert, offer_rate match, tax invoice). Pool identity formula must appear verbatim: collection_usd - collection_margin_usd - payout_margin_usd == payout_usd_cost.
**Steps:** Write tests/planning/test_phase_gate_checklists.py to parse docs/planning/phase_gate_checklists.md.; Assert Phase 1 section contains 8 checklist items.; Assert pool identity formula string appears in Phase 1 section.; Assert Phase 3 section contains the text 10 E2E and 3 consecutive.; Assert Phase 4 section contains USD 10,000 and SELECT FOR UPDATE.; Assert all four phases have a section heading present.
**Deliverable:** tests/planning/test_phase_gate_checklists.py that validates checklist structure and key formula presence
**Acceptance / logic checks:**
- Phase 1 checklist count assertion passes for 8 items.
- Pool identity formula string search finds the formula with collection_usd, collection_margin_usd, payout_margin_usd, payout_usd_cost all present.
- Phase 3 assertions for 10 transactions and 3 consecutive days both pass.
- Phase 4 assertions for $10,000 threshold and SELECT FOR UPDATE atomicity both pass.
- Test fails gracefully with section name when a required term is missing.
**Depends on:** 1.3-T13

### 1.3-T29 — Unit test: verify RACI matrix completeness (28 activities, 6 roles)  _(25 min)_
**Context:** RACI document from ticket 1.3-T11 must contain 28 activity rows and 6 role columns (GME Product, GME Ops/Finance, GME Compliance, Dev Vendor, KFTC/한결원, Partner). Critical checks from PM-14 Section 8: Rate engine implementation - Dev Vendor must be A/R; FX treasury rate updates - GME Ops/Finance must be A/R and Dev Vendor must not appear; Production deployment - Dev Vendor must be A/R; ZP00xx file spec provision - KFTC must be R; Training GME Ops on Admin portal - Dev Vendor must be A/R. Every row must have exactly one A (Accountable) assignment.
**Steps:** Write tests/planning/test_raci.py to parse docs/planning/RACI.md (or a RACI.csv version).; Assert 28 activity rows present.; Assert each row has exactly one cell with A or A/R value.; Assert Rate engine implementation row has Dev Vendor = A/R.; Assert FX treasury rate updates row has GME Ops/Finance = A/R and Dev Vendor cell is empty or I.; Assert ZP00xx file spec provision row has KFTC = R.
**Deliverable:** tests/planning/test_raci.py that validates RACI structure, row count, and critical role assignments
**Acceptance / logic checks:**
- Test passes on 28-row count; reports actual vs expected on failure.
- Single-accountable-per-row rule check passes for all 28 rows.
- Rate engine implementation role check passes (Dev Vendor A/R).
- FX rate updates check passes (GME Ops/Finance A/R, Dev Vendor not responsible).
- ZP00xx spec provision check passes (KFTC R).
**Depends on:** 1.3-T11

### 1.3-T30 — Produce environment provisioning runbook for E1 (dev) and E2 (int) environments  _(45 min)_
**Context:** OPS-13 Section 2 and Section 3 define the environment architecture. E1 (dev): synthetic data only, developers only, DB pool size 5, manual batch trigger, mock SFTP (no real KFTC connection), secret path gmepay/dev/<service>/<secret>. E2 (int): synthetic + ZeroPay sandbox data, dev+QA access, DB pool 20, manual batch trigger, live connection to 한결원 test SFTP (D-01 critical dependency - must not be configured until KFTC credentials received), secret path gmepay/int/<service>/<secret>. OPS-13 Section 3.1 defines cloud-agnostic topology: Public DMZ (WAF, LB), API Zone, Worker Zone, Data Zone (PostgreSQL primary+replica, Redis, message queue), Management Zone.
**Steps:** Create docs/planning/env_runbook_E1_E2.md with step-by-step provisioning instructions for both tiers.; E1 dev: steps for provisioning PostgreSQL (pool 5), Redis, mock SFTP server, secret manager with gmepay/dev/* paths, log level DEBUG.; E2 int: same steps but pool 20, KFTC test SFTP credentials (note D-01 dependency - do not provision SFTP connection until credentials received from KFTC), log level DEBUG.; Include network zone boundaries from OPS-13 Section 3.2: Public DMZ, API Zone, Worker Zone, Data Zone, Management Zone.; Include smoke test commands to verify each environment tier is functional after provisioning.
**Deliverable:** docs/planning/env_runbook_E1_E2.md with provisioning steps, config values, and smoke tests for E1 and E2
**Acceptance / logic checks:**
- E1 provisioning specifies DB pool = 5, mock SFTP, DEBUG log level, and synthetic data only.
- E2 provisioning specifies DB pool = 20, KFTC test SFTP (with D-01 gate note), DEBUG log level.
- Five network zones (Public DMZ, API Zone, Worker Zone, Data Zone, Management Zone) are described for each environment.
- D-01 dependency (KFTC test SFTP credentials) is explicitly gated: E2 SFTP step must not be executed until credentials are in hand.
- Smoke test section includes at least: API health check, DB connection test, and SFTP connectivity test.
**Depends on:** 1.3-T04, 1.3-T12

### 1.3-T31 — Produce environment provisioning runbook for E3 (staging) and E4 (prod) environments  _(45 min)_
**Context:** OPS-13 Section 2.3 and 2.4: E3 staging - production-like with masked PII, monthly refresh cycle, QA+Ops+partner tech leads access, PR merge approval required for deployment, DB pool 80, KST production batch windows, KFTC test SFTP (same as E2). E4 prod - real data, GDPR/PIPA data residency, break-glass only for DB and secrets, change-approval workflow for deployments, DB pool 200, KST production windows, KFTC production SFTP. OPS-13 Section 4.5: staging uses blue-green for major releases; prod uses blue-green for schema-changing deploys and canary (5% traffic, 15-min window) for high-risk API changes. Production DB and secrets accessible ONLY via break-glass procedure (SEC-09 Section 6).
**Steps:** Create docs/planning/env_runbook_E3_E4.md.; E3 staging: provisioning steps for DB pool 80, KFTC test SFTP, KST batch windows, masked PII data refresh procedure, deployment gate (PR merge approval).; E4 prod: provisioning steps for DB pool 200, KFTC production SFTP, change-approval workflow, break-glass access procedure reference (SEC-09 Section 6).; Include data handling rules: staging = anonymised schema refresh monthly; prod = real data, GDPR/PIPA constraints.; Include blue-green and canary deployment instructions for E3 and E4.
**Deliverable:** docs/planning/env_runbook_E3_E4.md with provisioning steps, deployment gates, and access controls for E3 and E4
**Acceptance / logic checks:**
- E3 provisioning specifies DB pool = 80, KFTC test SFTP (same credentials as E2), and deployment gate requiring PR merge approval.
- E4 provisioning specifies DB pool = 200, KFTC production SFTP, and break-glass reference to SEC-09 Section 6.
- Data handling rules correctly distinguish staging (masked/anonymised, monthly refresh) from prod (real data, GDPR/PIPA).
- Blue-green deployment process references 5% canary traffic and 15-minute monitoring window for high-risk API changes in prod.
- Batch job schedule for E3 and E4 specifies KST production windows (not manual trigger).
**Depends on:** 1.3-T30

### 1.3-T32 — Define open items resolution plan and impact assessment for OI-01, OI-02, OI-03  _(35 min)_
**Context:** PM-14 Section 9 defines three open items. OI-01 Customer Approval Method: in-app UX for CPM/MPM not specified; dev vendor implements generic partner-configurable approval step; target before Phase 3 go-live. OI-02 Tax Invoice API: no confirmed third-party API (Hometax or KFTC) for Korean merchant monthly fee tax invoices; UC-04-04 blocked; dev vendor stubs with manual admin-portal workflow (operator triggers invoice issuance manually) with future API hook; target before Phase 1 dev start (urgent). OI-03 BOK Reporting Format: exact fields/frequency/channel for FX1014/FX1015 not confirmed; FEATURE_BOK_REPORTING flag is false until resolved; dev vendor captures all required fields from day one; target TBD (escalate urgently). PM-14 Section 10 confirms: if OI-03 unresolved before Phase 3 go-live, initial live service runs without automated BOK reporting and GME assumes regulatory risk.
**Steps:** Create docs/planning/open_items_plan.md with one section per open item.; OI-01: document the generic partner-configurable approval step design requirement; state this cannot be hardcoded.; OI-02: document stub implementation plan (manual admin-portal workflow for UC-04-04 with future API hook); mark as urgent with owner GME Account/Tech.; OI-03: document the plug-in reporting module design requirement; state that all FX1014/FX1015 required fields must be captured from day 1 (no data loss if format later confirmed); state regulatory risk assumption from PM-14 Section 10.; For each open item list: current status, owner, target date, what is blocked, interim approach.
**Deliverable:** docs/planning/open_items_plan.md with resolution plans and interim approaches for OI-01, OI-02, OI-03
**Acceptance / logic checks:**
- OI-01 section states approval step must be generic and partner-configurable, not hardcoded per partner.
- OI-02 section describes manual stub for UC-04-04 in Phase 1 with explicit future API hook design point.
- OI-03 section states that all FX1014/FX1015 fields must be stored from day 1 to avoid data loss when format is confirmed.
- OI-03 section includes the PM-14 Section 10 regulatory risk assumption verbatim or paraphrased accurately.
- Each section has owner, target date, and blocked-component fields populated.
**Depends on:** 1.3-T12

### 1.3-T33 — Produce consolidated baselined plan document linking all planning artefacts  _(30 min)_
**Context:** WBS 1.3 parent deliverable is a Baselined Plan. All planning artefacts created in WBS 1.3 (milestones.csv, sprint_cadence.csv, estimates_phase1-4, RAID_log.csv, RACI.md, environments.md, feature_flags.md, cicd_pipeline_spec.md, batch_job_schedule.md, observability_plan.md, open_items_plan.md) must be consolidated into a single baselined plan document that serves as the entry point for GME Product Team review and sign-off. The baselined plan is frozen at the end of WBS 1.3; all future changes require a change request reviewed by GME PM within 2 business days (per PM-14 A-14-03).
**Steps:** Create docs/planning/BASELINED_PLAN.md as a table of contents linking all planning artefacts.; Include a version number (v1.0), baseline date (Apr 10 2026 or date of completion), and status (BASELINED).; Include a one-paragraph executive summary: four phases, Oct 10 2026 go-live, critical path dependency on KFTC, generic-build constraint.; List each planning artefact with its file path, description, and the PM-14 section it implements.; Add a change-control note: post-baseline changes require change request reviewed by GME PM within 2 business days.; Include the four phase gate dates as a top-level summary table.
**Deliverable:** docs/planning/BASELINED_PLAN.md serving as the consolidated entry point for all WBS 1.3 planning artefacts
**Acceptance / logic checks:**
- Document has version number, baseline date, and status = BASELINED in the header.
- Executive summary accurately states Oct 10 2026 go-live, four phases, KFTC critical dependency, and generic-build constraint.
- All planning artefact file paths listed are valid relative paths within docs/planning/.
- Four phase gate dates (Jun 20, Jul 31, Oct 10, Dec 10) appear in the top-level summary table.
- Change-control note references PM-14 A-14-03 and specifies 2 business days for GME PM review.
**Depends on:** 1.3-T09, 1.3-T10, 1.3-T11, 1.3-T13, 1.3-T19, 1.3-T21, 1.3-T22, 1.3-T23, 1.3-T31, 1.3-T32


## WBS 1.4 — Architecture & design sign-off
### 1.4-T01 — Review SAD-02 architectural goals and non-negotiable constraints  _(45 min)_
**Context:** WBS 1.4 architecture sign-off. SAD-02 defines 6 non-negotiable architectural goals: (1) config-not-code for schemes and partners, (2) BOK-compliant three-currency USD pivot, (3) scheme-agnostic routing via pluggable Scheme Adapters, (4) partner-agnostic Northbound API, (5) all four directions (Domestic/Inbound/Outbound/Hub) supported from day one with Phase 1 activating only Domestic and Inbound, (6) payout-first RECEIVE mode. Every design decision must preserve these goals.
**Steps:** Read SAD-02 sections 1-2 (purpose, goals, ADR table AD-01 through AD-12) in full; For each of the 12 ADRs verify the proposed implementation approach complies; Flag any proposed deviations and document written justification for GME Product Team; Confirm in writing that RECEIVE mode (payout-first) is the only supported computation direction; Record sign-off against each ADR in the architecture review checklist
**Deliverable:** Completed architecture review checklist with pass/flag/defer status for all 12 ADRs (AD-01 through AD-12)
**Acceptance / logic checks:**
- All 12 ADRs reviewed; none marked pass without evidence
- AD-04 (config-not-code) confirmed: adding ZeroPay as a scheme requires only a DB row insert, not a code change
- AD-05 (payout-first) confirmed: no SEND-mode computation path exists in proposed design
- AD-06 (atomic prefunding before scheme call) confirmed: SELECT FOR UPDATE on prefunding_account before any scheme call
- Any flagged deviations have written GME Product Team approval or a remediation plan

### 1.4-T02 — Review SAD-02 Logical Component Architecture and component inventory  _(40 min)_
**Context:** WBS 1.4 architecture sign-off. SAD-02 section 4 defines 14 logical components across Northbound/Auth, Core Processing, Scheme Layer, Data/Config, and Supporting Services. Core components: API Gateway, Auth Service, QR Parser, Smart Router, Rate Engine, Transaction Orchestrator, Prefunding Ledger, Settlement Engine, Reconciliation Engine, Scheme Adapter (ZeroPay + future), Config/Registry, Audit Log Store, Admin Service, Partner Portal Service, Notification/Webhook, Reporting/BOK. Each has defined responsibilities and key dependencies.
**Steps:** Walk through each of the 14 components in the inventory table; Verify all stated responsibilities are captured in the proposed build plan; Check that component dependencies (e.g. Rate Engine depends on Config/Registry and Rate Table) are reflected in the proposed module structure; Confirm no core component (Orchestrator, Rate Engine) contains scheme-specific or partner-specific code branches; Note any missing components or unassigned responsibilities
**Deliverable:** Component coverage matrix: each of the 14 SAD-02 components listed with build-plan reference, owner team, and sign-off status
**Acceptance / logic checks:**
- All 14 components are mapped to at least one concrete build task
- Transaction Orchestrator is the ONLY component that transitions transaction state
- Scheme Adapter boundary is clear: ZeroPay protocol details live inside the adapter only, not in Orchestrator or Rate Engine
- Config/Registry is identified as the single source of truth for Scheme/Partner/Rule config with a Redis hot cache layer
- No component missing from the build plan
**Depends on:** 1.4-T01

### 1.4-T03 — Review SAD-02 Transaction Orchestrator state machine for correctness  _(50 min)_
**Context:** WBS 1.4 architecture sign-off. SAD-02 section 5.2 defines 9 transaction states: QUOTED, PENDING_DEBIT, DEBITED, SCHEME_SENT, APPROVED, UNCERTAIN, FAILED, REVERSED, REFUNDED. Key transitions: QUOTED->PENDING_DEBIT (OVERSEAS commit), QUOTED->DEBITED (LOCAL commit, no prefund), PENDING_DEBIT->FAILED (insufficient balance), SCHEME_SENT->UNCERTAIN (timeout), UNCERTAIN->APPROVED/FAILED (batch reconciliation within 24h). Rate-lock happens at APPROVED. UNCERTAIN resolution uses ZP0012/ZP0022 files received ~05:00 KST daily.
**Steps:** Draw the complete state transition diagram and compare against SAD-02 section 5.2 state machine; Verify every terminal state (FAILED, REVERSED, REFUNDED) has no outbound transitions; Confirm the UNCERTAIN->APPROVED and UNCERTAIN->FAILED transitions are driven only by the Settlement/Reconciliation Engine; Verify that rate-lock (all USD pool values + derived rates become immutable) occurs at CommitTransaction before APPROVED; Check that prefunding reversal on UNCERTAIN->FAILED is explicitly modelled
**Deliverable:** Annotated state machine diagram with transition guards, actors, and rate-lock trigger point marked; sign-off from tech lead and GME Product
**Acceptance / logic checks:**
- 9 states and all specified transitions are present; no undocumented states added
- UNCERTAIN state has a 24-hour resolution SLA; unresolved > 24h triggers ops alert (per SAD-02 section 5.2)
- Prefunding deduction is held during UNCERTAIN and reversed only on confirmed FAILED - not on timeout alone
- Rate-lock (immutable USD pool columns) occurs at CommitTransaction call, not at APPROVED - SAD-02 AD-08
- LOCAL partner path skips PENDING_DEBIT and goes QUOTED->DEBITED directly
**Depends on:** 1.4-T02

### 1.4-T04 — Review SAD-02 Rate Engine component design and 5-step formula alignment  _(55 min)_
**Context:** WBS 1.4 architecture sign-off. Rate Engine executes 5-step RECEIVE-mode: (1) payout_usd_cost = target_payout / cost_rate_pay, (2) collection_usd = payout_usd_cost / (1 - m_a - m_b), (3) collection_margin_usd = collection_usd * m_a; payout_margin_usd = collection_usd * m_b, (4) send_amount = collection_usd * cost_rate_coll, (5) collection_amount = send_amount + service_charge. Pool identity: collection_usd - collection_margin_usd - payout_margin_usd == payout_usd_cost (tolerance 0.01 USD). Same-currency short-circuit: collection_amount = target_payout + service_charge. Rate source modes: IDENTITY, LIVE, MANUAL, PARTNER.
**Steps:** Confirm the proposed Rate Engine module implements all 5 steps in exact sequence; Verify pool identity check is enforced after Step 3 and before committing any rate quote; Confirm same-currency short-circuit condition (collection = settle_A = settle_B = payout) is detected from rule.is_same_ccy_shortcircuit flag; Verify cross-border rules enforce m_a + m_b >= 0.02 (2% minimum) per DAT-03 rule table constraint; Confirm offer_rate_coll = send_amount / (collection_usd - collection_margin_usd) and cross_rate = target_payout / send_amount are derived (not configured)
**Deliverable:** Rate Engine design review sign-off document listing each of the 5 steps, the pool identity check, the short-circuit path, and derived field formulas with explicit confirmation or issue flag
**Acceptance / logic checks:**
- Example: target_payout=50000 KRW, cost_rate_pay=1380, m_a=0.01, m_b=0.01, cost_rate_coll=1.0, service_charge=0 -> payout_usd_cost=36.232 USD, collection_usd=36.966 USD, send_amount=36.966 USD, collection_amount=36.966 USD; pool identity holds within 0.01 USD
- service_charge is not included in pool calculations - it is added after Step 4 only
- m_a + m_b < 0.02 on a cross-border rule is rejected at rule save time (DB CHECK constraint)
- IDENTITY rate source returns exactly 1.0 with no treasury lookup
- PARTNER rate source triggers Partner B Quote Client call; deviation > 1% returns PARTNER_B_QUOTE_DEVIATION
**Depends on:** 1.4-T02

### 1.4-T05 — Review SAD-02 Prefunding Ledger design for atomicity and OVERSEAS-only enforcement  _(45 min)_
**Context:** WBS 1.4 architecture sign-off. Prefunding is OVERSEAS partners only (e.g. SendMN, T-Bank with partner_type = OVERSEAS). LOCAL partners (e.g. GME Remit) skip prefunding entirely. Deduction is atomic using SELECT FOR UPDATE on prefunding_account.balance. For MPM, deduction at POST /v1/payments. For CPM, deduction at POST /v1/payments/cpm/generate (AD-07). Scheme is NEVER called without a prior committed deduction (AD-06). prefunding_ledger_entry is immutable, append-only (no UPDATE/DELETE). Low-balance alert when balance < low_balance_threshold (default USD 10,000).
**Steps:** Confirm prefunding deduction logic branches correctly on partner.partner_type: OVERSEAS deducts, LOCAL skips; Verify SELECT FOR UPDATE is used on prefunding_account row (not application-level locking) to prevent concurrent overdraw; Confirm CPM deduction happens at QR-generate step, not at confirm step (AD-07); Verify prefunding_ledger_entry table has no UPDATE or DELETE paths - every balance change creates a new row; Confirm low-balance alert fires when balance_after < threshold and dispatches email to low_balance_alert_email
**Deliverable:** Prefunding design review checklist: partner-type branching, locking mechanism, CPM timing, ledger immutability, alert trigger - all signed off or flagged
**Acceptance / logic checks:**
- Concurrent requests from OVERSEAS partner cannot result in negative balance: only one SELECT FOR UPDATE succeeds per row at a time
- LOCAL partner (GME_REMIT, partner_type=LOCAL) has no prefunding_account row and skips deduction without error
- CPM deduction at generate means partner prefunding is held while QR is displayed but before merchant scans - balance_after reflects this reservation
- balance_after on each ledger entry equals the sum of all prior entries for that account (running balance snapshot)
- Insufficient balance returns HTTP 402 or equivalent rejection before any scheme call is attempted
**Depends on:** 1.4-T03

### 1.4-T06 — Review SAD-02 Scheme Adapter pattern and pluggability contract  _(40 min)_
**Context:** WBS 1.4 architecture sign-off. SAD-02 section 5.3 defines the SchemeAdapter interface with 6 methods: authoriseCpm, submitMpm, generateBatchFiles, parseBatchFile, processMerchantSync, healthCheck. Adding a new scheme requires only: (1) implement SchemeAdapter interface, (2) register in Config/Registry with scheme_id, (3) configure rules - no changes to Orchestrator, Rate Engine, or any other core component. ZeroPay Adapter handles: CPM relay to KFTC REST API, SFTP file assembly/parsing for all ZP00xx types, merchant/QR sync ingestion, net vs gross settlement logic.
**Steps:** Verify the proposed SchemeAdapter interface exposes all 6 methods and no scheme-specific arguments leak into the interface signature; Confirm the Transaction Orchestrator calls only the interface methods - no instanceof or scheme_code conditionals; Verify ZeroPay-specific logic (ZP00xx file formats, KFTC API endpoint, net/gross settlement) is entirely within the ZeroPay Adapter class; Confirm registration: adding a second scheme would require only a new adapter class + Config/Registry insert; Review ZeroPay Adapter CPM relay path: inbound KFTC push -> inboundCpmPending event -> Orchestrator rate compute -> prefunding deduct -> confirm/decline to KFTC
**Deliverable:** Scheme Adapter interface review document: method signatures, pluggability test scenario, ZeroPay boundary confirmation, sign-off
**Acceptance / logic checks:**
- No scheme_code, zeropay, or KFTC references exist in Transaction Orchestrator source
- SchemeAdapter interface compiles/type-checks independently of any ZeroPay implementation
- CPM relay path diagram shows KFTC push arrives at adapter endpoint and is forwarded to Orchestrator via inboundCpmPending event
- A hypothetical second scheme (e.g. KHQR) could be added by implementing 6 interface methods and inserting one Config/Registry row
- healthCheck method returns AdapterHealth object usable by the observability stack without scheme-specific parsing
**Depends on:** 1.4-T02

### 1.4-T07 — Review SAD-02 Settlement Engine design and batch scheduling correctness  _(50 min)_
**Context:** WBS 1.4 architecture sign-off. SAD-02 section 5.4 defines Settlement Engine cron windows (all KST; note KST = UTC+09:00 constant, no DST): ZP0011/ZP0021 at ~02:00 KST (17:00 UTC prior day), ZP0061 at ~05:00 KST (20:00 UTC prior day), ZP0063 at ~14:00 KST (05:00 UTC), ZP0065/ZP0066 at ~22:00 KST (13:00 UTC). Inbound: ZP0012/ZP0022 polled ~05:00, ZP0062 ~10:00, ZP0064 ~19:00. All batch jobs must be idempotent. Distributed locking (Redis SETNX or K8s leader election) prevents duplicate execution. Net vs gross: domestic = net settlement, international = gross.
**Steps:** Verify all 5 outbound file generation cron expressions are defined in UTC equivalents of the KST windows; Confirm all settlement batch jobs are idempotent (running twice does not generate duplicate files or double-debit ledger); Verify distributed locking mechanism: only one instance generates each file type per settlement_date; Confirm inbound file polling schedule for ZP0012 (~05:00 KST), ZP0062 (~10:00 KST), ZP0064 (~19:00 KST); Review UNCERTAIN resolution path: ZP0012/ZP0022 inbound triggers Orchestrator transition to APPROVED or FAILED
**Deliverable:** Settlement Engine scheduling review table: each file type with KST window, UTC cron expression, idempotency mechanism, and sign-off status
**Acceptance / logic checks:**
- 02:00 KST = 17:00 UTC (prior day); cron expression for ZP0011 generates is '0 17 * * *' UTC
- A batch job triggered twice for the same settlement_date and file_type returns the existing settlement_batch row without creating a duplicate
- UNCERTAIN transactions from prior day are resolved within the next ZP0012/ZP0022 processing cycle (no later than ~06:00 KST next day)
- settlement_batch.status transitions: PENDING -> GENERATED -> TRANSMITTED -> RECEIVED -> RECONCILED; ERROR is a terminal recovery state
- Domestic net settlement: GME retains its margin share and remits only the net amount to KFTC; cross-border gross settlement: GME remits full payout amount
**Depends on:** 1.4-T03

### 1.4-T08 — Review SAD-02 integration architecture - Northbound API contracts  _(40 min)_
**Context:** WBS 1.4 architecture sign-off. Northbound API: REST over HTTPS; authentication via HMAC-SHA256 API key+secret (per-request signature over canonical request string); Idempotency-Key header required on all POST calls (deduplicated for 24h via Redis keyed by partner_id + idempotency_key); per-partner rate limiting at API Gateway; versioned at /v1/; webhook events: payment.pending_debit, payment.approved, payment.failed, payment.reversed; webhook retry: exponential backoff 5s/30s/2min/10min/1h/6h max 6 attempts; partner returns HTTP 200 to acknowledge; dead-letter after final retry.
**Steps:** Verify HMAC-SHA256 signature scheme covers the canonical request string (method + path + timestamp + body hash); Confirm Idempotency-Key deduplication window is exactly 24 hours, keyed by (partner_id, idempotency_key); Verify all 4 webhook event types are dispatched with HMAC signature and partner must return HTTP 200; Confirm 6-attempt exponential backoff schedule matches: 5s, 30s, 2min, 10min, 1h, 6h; Verify that internal rate fields (m_a, m_b, cost_rate_coll, cost_rate_pay) are NOT included in any Northbound API response
**Deliverable:** Northbound API integration review checklist: auth scheme, idempotency, webhook delivery, backoff schedule, data privacy - signed off or flagged
**Acceptance / logic checks:**
- Replaying same POST /v1/payments with identical Idempotency-Key within 24h returns the stored response (HTTP 200 with original txn_ref) without creating a second transaction
- Webhook delivery failure after 6 attempts stores a dead-letter event in a queryable ops table
- Partner API response for GET /v1/rates contains offer_rate, send_amount, service_charge, collection_amount, validUntil - but NOT m_a, m_b, cost_rate_coll, or cost_rate_pay
- HMAC-SHA256 signature verification rejects any request where the signature does not match, returning HTTP 401
- URL-path versioning: /v1/ prefix on all endpoints; v2 introduced only for breaking changes
**Depends on:** 1.4-T02

### 1.4-T09 — Review SAD-02 technology stack and infrastructure topology  _(35 min)_
**Context:** WBS 1.4 architecture sign-off. SAD-02 section 9 recommends: Java 21 + Spring Boot 3.x (or Node.js 20 + NestJS); PostgreSQL 16 (OLTP); Redis 7 (rate quote cache TTL, idempotency keys, config hot cache); Apache Kafka or RabbitMQ (message broker); AWS S3 or S3-compatible (object storage for ZP00xx batch files); Docker + Kubernetes (EKS/GKE/on-prem); Kong/AWS API Gateway/Spring Cloud Gateway; HashiCorp Vault or AWS Secrets Manager. Section 10 defines 4 environments: dev, sandbox, staging, production. Production must be in Korea region (Seoul ap-northeast-2 or Korean colocation) per data residency requirement.
**Steps:** Confirm vendor has selected one backend language (Java or Node) and documented the choice; Verify PostgreSQL is selected and PostgreSQL 16 or higher for SELECT FOR UPDATE support; Confirm Redis 7 is selected for rate quote cache with TTL support; Verify Korea-region deployment is planned for production (AWS ap-northeast-2 or equivalent); Confirm secrets are managed via Vault or AWS Secrets Manager - no credentials in env vars or source code; Review message broker choice (Kafka vs RabbitMQ) against event-replay requirement for BOK audit trail
**Deliverable:** Technology stack decision record: each SAD-02 recommended component with selected technology, justification, and deviations (if any) approved by GME Product Team
**Acceptance / logic checks:**
- Backend language is standardised to one choice across all services; no mixed Java/Node polyglot without explicit approval
- PostgreSQL version >= 16 confirmed; floating-point money types (FLOAT/DOUBLE/REAL) are absent from schema design
- Redis TTL configuration supports range 60-1800 seconds per rate_quote_ttl_seconds field on partner table
- No SFTP credentials, API keys, or DB passwords appear in source code, Dockerfiles, or CI/CD pipeline definitions
- Any deviation from recommended stack has written GME Product Team approval documented in the stack decision record
**Depends on:** 1.4-T01

### 1.4-T10 — Review SAD-02 deployment topology and network zone architecture  _(35 min)_
**Context:** WBS 1.4 architecture sign-off. SAD-02 section 10.4 defines 3 network zones: (1) DMZ/public subnet - API Gateway and Load Balancer only, (2) Application subnet (private) - Hub Core, Admin Service, Partner Portal Service, Rate Engine, Transaction Orchestrator, Settlement Engine, (3) Data subnet (private, most restricted) - PostgreSQL, Redis, Message Broker; plus a dedicated (4) Egress/SFTP proxy zone with static IP for KFTC SFTP allow-listing. Key HA requirements: min 2 replicas for API Gateway and Hub Core; PostgreSQL primary + synchronous standby with Patroni-style failover; Redis cluster (3+3 nodes); message broker replication factor >= 2; distributed locking for settlement batch jobs.
**Steps:** Verify the 4-zone network topology is represented in the infrastructure design (DMZ, App, Data, Egress/SFTP); Confirm API Gateway is the only component accessible from the public internet; Confirm PostgreSQL resides in the data subnet with no direct access from outside the application subnet; Verify SFTP proxy has a static/fixed IP registered with KFTC for allow-listing (SAD-02 assumption A-04); Review HA configuration: 2+ API Gateway replicas, PostgreSQL synchronous standby, Redis cluster mode
**Deliverable:** Infrastructure topology review checklist: 4-zone network design, HA targets, SFTP proxy static IP, Korea-region data residency - all confirmed or flagged
**Acceptance / logic checks:**
- Only port 443 (HTTPS) is inbound to the DMZ; no direct inbound to Application or Data subnets
- PostgreSQL has a synchronous standby replica (not async) to meet RPO <= 1 hour per NFR-10
- SFTP outbound traffic to KFTC originates from a fixed IP; the IP is registered with KFTC before sandbox testing
- Settlement Engine batch jobs use distributed locking so that two pod instances do not generate duplicate ZP00xx files for the same settlement_date
- Kubernetes liveness and readiness probes are defined for all Hub Core services
**Depends on:** 1.4-T09

### 1.4-T11 — Review DAT-03 modelling conventions and money/currency handling rules  _(40 min)_
**Context:** WBS 1.4 data model sign-off. DAT-03 defines: all table/column names snake_case; BIGINT surrogate PK on every table; natural keys as UNIQUE constraints; all timestamps as TIMESTAMPTZ (UTC); soft-delete with is_active BOOLEAN + deactivated_at; audit columns (created_at, updated_at, created_by, updated_by) on all mutable tables; FKs enforced at DB level with ON DELETE RESTRICT default; enums stored as VARCHAR with CHECK constraints. Money: KRW stored as BIGINT (whole won); USD and cross-currency amounts as DECIMAL(20,4); FX-derived intermediary amounts as DECIMAL(20,8). Every amount column paired with a currency column (e.g. collection_amount DECIMAL(20,4) + collection_ccy CHAR(3)).
**Steps:** Review proposed schema DDL for compliance with snake_case naming, BIGINT surrogate PKs, and UNIQUE natural keys; Verify no FLOAT, DOUBLE, or REAL types are used for any monetary column; Verify all amount columns have a paired _ccy CHAR(3) column in the same table; Confirm soft-delete pattern (is_active + deactivated_at) is present on all partner, scheme, and rule tables; Verify all timestamps are TIMESTAMPTZ (not TIMESTAMP WITHOUT TIME ZONE)
**Deliverable:** Schema modelling conventions review: DDL checklist for naming, money types, currency pairing, soft-delete, timestamp types - signed off or flagged
**Acceptance / logic checks:**
- KRW amounts (e.g. target_payout for ZeroPay transactions) are stored as BIGINT, not DECIMAL
- No table uses FLOAT or DOUBLE for any column including rate fields - all rates use DECIMAL(20,8)
- collection_amount column is always accompanied by collection_ccy CHAR(3) in the same row
- transaction.payout_usd_cost, collection_usd, collection_margin_usd, payout_margin_usd are DECIMAL(20,8) not DECIMAL(20,4)
- All tables that can be referenced by a transaction have is_active BOOLEAN and deactivated_at TIMESTAMPTZ; hard DELETE is prohibited
**Depends on:** 1.4-T01

### 1.4-T12 — Review DAT-03 Configuration entities: qr_scheme, scheme_country, partner, partner_credential  _(45 min)_
**Context:** WBS 1.4 data model sign-off. DAT-03 section 4 defines 4 core config entities. qr_scheme: scheme_code VARCHAR(30) UNIQUE, payout_ccy CHAR(3), supported_modes VARCHAR(20) (MPM/CPM/BOTH), partner_b_quote_enabled BOOLEAN, partner_b_quote_deviation_pct DECIMAL(6,4) default 0.0100, sftp_host VARCHAR(255) stored encrypted, api_credential_ref VARCHAR(120) referencing secrets vault, status VARCHAR(20) CHECK IN (TESTING,ACTIVE,INACTIVE). scheme_country: UNIQUE(scheme_id, country_code). partner: partner_code UNIQUE, partner_type CHECK IN (LOCAL,OVERSEAS), rate_quote_ttl_seconds INT CHECK BETWEEN 60 AND 1800 DEFAULT 300, is_active DEFAULT FALSE (Ops activates). partner_credential: api_secret_hash bcrypt-hashed, only one active credential per partner.
**Steps:** Verify qr_scheme DDL includes all columns from DAT-03 section 4.1 with correct types; Verify scheme_country has UNIQUE(scheme_id, country_code) constraint for CPM location-based selection; Verify partner.partner_type CHECK constraint limits to LOCAL and OVERSEAS only; Verify partner_credential stores api_secret_hash (bcrypt) not plaintext; expires_at TIMESTAMPTZ allows credential rotation; Confirm partner.is_active DEFAULT FALSE so new partners are inactive until Ops activates
**Deliverable:** Config entity DDL review: qr_scheme, scheme_country, partner, partner_credential tables verified against DAT-03 spec with pass/flag per column
**Acceptance / logic checks:**
- qr_scheme.partner_b_quote_deviation_pct defaults to 0.0100 (1.0%) and is DECIMAL(6,4)
- scheme_country INSERT of (ZEROPAY_scheme_id, 'KR') succeeds; duplicate insert of same pair fails with UNIQUE constraint violation
- partner.rate_quote_ttl_seconds = 59 is rejected by CHECK BETWEEN 60 AND 1800
- partner_credential.api_secret_hash is a bcrypt hash string starting with '$2a$' or '$2b$'; raw secret is not stored anywhere in DB
- Deactivating partner sets is_active = FALSE and deactivated_at = NOW(); existing transactions are not deleted
**Depends on:** 1.4-T11

### 1.4-T13 — Review DAT-03 rule entity and constraint correctness for rate engine integration  _(45 min)_
**Context:** WBS 1.4 data model sign-off. DAT-03 section 4.6 defines the rule table: PK is BIGINT id; UNIQUE(partner_id, scheme_id, direction); direction CHECK IN (INBOUND,OUTBOUND,DOMESTIC,HUB); rate_coll_source and rate_pay_source CHECK IN (IDENTITY,LIVE,MANUAL,PARTNER); m_a DECIMAL(8,6) CHECK >= 0; m_b DECIMAL(8,6) CHECK >= 0; CHECK constraint: is_same_ccy_shortcircuit OR (m_a + m_b) >= 0.02 (cross-border rules need min 2% combined margin); service_charge_ccy must equal settle_a_ccy; is_same_ccy_shortcircuit is a computed flag set to TRUE when collection_ccy = settle_a_ccy = settle_b_ccy = payout_ccy; status CHECK IN (DRAFT,ACTIVE,SUSPENDED); effective_from TIMESTAMPTZ (changes apply to new transactions only).
**Steps:** Verify UNIQUE(partner_id, scheme_id, direction) constraint exists in DDL; Verify DB-level CHECK: (is_same_ccy_shortcircuit = TRUE OR (m_a + m_b) >= 0.02); Verify is_same_ccy_shortcircuit is computed (trigger or application logic) and never manually editable; Verify manual_rate_coll is NOT NULL when rate_coll_source = 'MANUAL' (enforced by application-level validation or CHECK); Verify service_charge_ccy = settle_a_ccy is enforced (application rule or DB constraint)
**Deliverable:** Rule entity DDL and constraint review document: all CHECK constraints listed and verified with test SQL examples, sign-off status
**Acceptance / logic checks:**
- INSERT rule with m_a=0.005, m_b=0.010, is_same_ccy_shortcircuit=FALSE raises constraint violation (combined 1.5% < 2%)
- INSERT rule with m_a=0.010, m_b=0.010, is_same_ccy_shortcircuit=FALSE succeeds (combined 2.0% meets minimum)
- INSERT rule with same (partner_id, scheme_id, direction) as existing row fails with UNIQUE constraint violation
- rule with rate_coll_source='MANUAL' and manual_rate_coll NULL is rejected before reaching the rate engine
- is_same_ccy_shortcircuit auto-set to TRUE for GME_REMIT on ZEROPAY DOMESTIC (all ccys = KRW)
**Depends on:** 1.4-T12

### 1.4-T14 — Review DAT-03 rate_quote and transaction entities for rate-lock completeness  _(50 min)_
**Context:** WBS 1.4 data model sign-off. DAT-03 sections 5.1 and 5.2 define rate_quote and transaction tables. rate_quote stores all 5-step values plus derived rates at quote time. On CommitTransaction, all values are copied to transaction and become immutable (locked). Locked columns on transaction: collection_usd, payout_usd_cost, collection_margin_usd, payout_margin_usd, send_amount, service_charge, collection_amount, offer_rate_coll, cross_rate, cost_rate_coll, cost_rate_pay - set at committed_at and never UPDATE-able after. For same-currency short-circuit, USD pool fields are NULL; offer_rate_coll and cross_rate are NULL. transaction.committed_at is set on CommitTransaction; transaction.completed_at is set on scheme APPROVED.
**Steps:** Verify rate_quote table has all 5-step pool columns with correct types (DECIMAL(20,8) for USD amounts, DECIMAL(20,4) for KRW); Verify transaction table has all locked rate columns matching rate_quote columns exactly (same names, same types); Confirm there is no application or DB path that allows UPDATE of locked rate columns after committed_at is set; Verify rate_quote.is_used is set to TRUE when the quote is linked to a committed transaction (prevents reuse); Verify NULL handling for same-ccy short-circuit: collection_usd, payout_usd_cost, collection_margin_usd, payout_margin_usd, offer_rate_coll, cross_rate, cost_rate_coll, cost_rate_pay are all nullable
**Deliverable:** Rate-lock review checklist: rate_quote->transaction copy path, locked column list, immutability guarantee, NULL handling for same-ccy path - signed off or flagged
**Acceptance / logic checks:**
- After CommitTransaction, UPDATE transaction SET collection_usd = 99 WHERE id = X is blocked by application-level guard or DB trigger
- rate_quote.valid_until = quote_issued_at + rate_quote_ttl_seconds; CommitTransaction after valid_until returns EXPIRED_QUOTE error
- rate_quote.treasury_rate_id_coll FK links to the specific treasury_rate row used, providing point-in-time rate audit trail
- For a same-currency GME Remit transaction: collection_usd IS NULL, offer_rate_coll IS NULL, and collection_amount = target_payout + service_charge
- transaction.rate_quote_id FK ensures every committed transaction has exactly one locked quote
**Depends on:** 1.4-T13

### 1.4-T15 — Review DAT-03 prefunding_account and prefunding_ledger_entry for correctness  _(45 min)_
**Context:** WBS 1.4 data model sign-off. DAT-03 section 6 defines prefunding entities. prefunding_account: one row per OVERSEAS partner (UNIQUE partner_id FK), currency always USD, balance DECIMAL(20,4), low_balance_threshold DECIMAL(20,4) default 10000.00. prefunding_ledger_entry: immutable append-only (no updated_at, no UPDATE/DELETE permitted), entry_type CHECK IN (DEBIT_PAYMENT, DEBIT_REVERSAL, CREDIT_TOPUP, CREDIT_REVERSAL), amount DECIMAL(20,4) CHECK > 0 (direction indicated by entry_type), balance_after DECIMAL(20,4) running snapshot, INDEX(account_id, created_at). low_balance_alert_config: one per partner, threshold_usd, alert_email.
**Steps:** Verify prefunding_account has UNIQUE(partner_id) constraint - one account per OVERSEAS partner only; Verify prefunding_ledger_entry has no updated_at column and no UPDATE path in application code; Verify balance_after is a denormalized running snapshot and equals the sum of all prior DEBIT and CREDIT entries for the account; Verify INDEX(account_id, created_at) on prefunding_ledger_entry for balance history queries; Confirm low_balance_alert_config.threshold_usd default is 10000.00 and triggers alert when balance drops below threshold
**Deliverable:** Prefunding entity DDL review: prefunding_account, prefunding_ledger_entry, low_balance_alert_config tables verified against DAT-03 with pass/fail per constraint
**Acceptance / logic checks:**
- INSERT prefunding_account for a LOCAL partner (partner_type=LOCAL) is prohibited at application layer (not just DB constraint)
- Two concurrent transactions that each try to deduct USD 60,000 from a USD 80,000 balance result in exactly one success and one INSUFFICIENT_FUNDS rejection (SELECT FOR UPDATE guarantees this)
- prefunding_ledger_entry.amount = -500 is rejected by CHECK > 0 constraint; negative debits are represented by entry_type=DEBIT_PAYMENT with positive amount
- Running balance can be reconstructed from scratch: SUM(amount) WHERE entry_type IN (CREDIT_TOPUP, CREDIT_REVERSAL) - SUM(amount) WHERE entry_type IN (DEBIT_PAYMENT, DEBIT_REVERSAL) = current balance
- Alert fires exactly once when balance crosses below threshold, not repeatedly on every subsequent debit
**Depends on:** 1.4-T14

### 1.4-T16 — Review DAT-03 settlement, reconciliation, and merchant/QR sync entities  _(45 min)_
**Context:** WBS 1.4 data model sign-off. DAT-03 sections 7.1-7.6 define: settlement_batch (one per scheme/file_type/date, status CHECK IN PENDING/GENERATED/TRANSMITTED/RECEIVED/RECONCILED/ERROR, idempotency key is UNIQUE(scheme_id, file_type, settlement_date)); settlement_file (FK to batch, stores filename, sftp_path, SHA-256 checksum); reconciliation_item (match_status CHECK IN MATCHED/DISCREPANCY/MISSING_GME/MISSING_SCHEME, resolution_status CHECK IN UNRESOLVED/RESOLVED/ESCALATED); merchant (merchant_id VARCHAR(50) UNIQUE ZeroPay ID, account_no AES-256 encrypted at rest); qr_code (qr_code_value VARCHAR(512) UNIQUE, qr_code_type CHECK IN STATIC_MPM/DYNAMIC_CPM); merchant_sync_log (one per ZP file sync run).
**Steps:** Verify settlement_batch has UNIQUE(scheme_id, file_type, settlement_date) to enforce idempotency; Verify settlement_file stores SHA-256 checksum for integrity verification; Verify reconciliation_item covers all 4 match statuses and 3 resolution statuses; Verify merchant.account_no is marked for AES-256 encryption at rest in the schema/ORM layer; Confirm qr_code.is_active = FALSE immediately when deactivation sync arrives (ZP0043/ZP0053)
**Deliverable:** Settlement, reconciliation, and merchant/QR entity DDL review with pass/flag per table and constraint; sign-off from tech lead and GME Ops
**Acceptance / logic checks:**
- Second INSERT into settlement_batch with same (scheme_id, file_type, settlement_date) fails - idempotency enforced at DB level
- reconciliation_item with match_status=DISCREPANCY is automatically created when gme_amount != scheme_amount for the same txn_ref
- merchant.account_no is never returned in plain text from any API endpoint; ORM/repository layer applies decryption only for internal settlement processing
- qr_code.status deactivation is processed atomically: a deactivated QR code at scan time returns an appropriate error before rate quote or payment proceeds
- merchant_sync_log records_deactivated count matches number of merchant rows set to is_active=FALSE in that sync run
**Depends on:** 1.4-T11

### 1.4-T17 — Review DAT-03 reporting entities: bok_report_record, revenue_record, tax_invoice  _(45 min)_
**Context:** WBS 1.4 data model sign-off. DAT-03 section 8 defines reporting entities. bok_report_record: one per cross-border transaction, report_type CHECK IN (FX1014, FX1015), stores offer_rate_coll (BOK FX1015 field #14), usd_amount (intermediary), submission_status CHECK IN (PENDING, SUBMITTED, CONFIRMED, FAILED). Domestic (same-ccy) transactions are exempt. revenue_record: populated at transaction commit, fx_margin_usd = collection_margin_usd + payout_margin_usd, fee_share_pct = 0.70 for ZeroPay (70% to GME). tax_invoice: for cross-border merchants, merchant_fee_krw = total_transaction_amount_krw * fee_rate, vat_krw = merchant_fee_krw * 0.10, zeropay_share_krw = 0.21% of invoice subtotal, invoice_amount_krw = merchant_fee_krw + vat_krw.
**Steps:** Verify bok_report_record is created for every INBOUND/OUTBOUND/HUB transaction but NOT for DOMESTIC (same-ccy) transactions; Verify bok_report_record.offer_rate_coll is copied from transaction.offer_rate_coll (locked value); Verify revenue_record.fx_margin_usd = collection_margin_usd + payout_margin_usd at commit time; Verify tax_invoice formula: merchant_fee_krw = total * fee_rate, vat_krw = merchant_fee_krw * 0.10, invoice_amount = merchant_fee_krw + vat_krw, zeropay_share_krw = invoice subtotal * 0.0021; Confirm all bok_report_record values derive from locked transaction fields (never re-computed from live rates)
**Deliverable:** Reporting entity DDL review: bok_report_record, revenue_record, tax_invoice with formula verification and pass/flag per column
**Acceptance / logic checks:**
- bok_report_record is absent for a GME_REMIT DOMESTIC transaction where is_same_ccy_shortcircuit = TRUE
- revenue_record.fx_margin_usd for a transaction with collection_margin_usd = 0.37 USD and payout_margin_usd = 0.37 USD equals 0.74 USD exactly
- tax_invoice for a merchant with monthly_total = 1,000,000 KRW at fee_rate 0.0050: merchant_fee = 5,000 KRW, vat = 500 KRW, zeropay_share = 10.50 KRW, invoice_amount = 5,500 KRW
- bok_report_record.submission_status starts as PENDING; transitions to SUBMITTED only after BOK confirmation channel call; never auto-submitted without operator or scheduler trigger
- revenue_record.fee_share_pct = 0.70 for all ZeroPay transactions (qr_scheme.gme_fee_share_pct = 0.70)
**Depends on:** 1.4-T16

### 1.4-T18 — Review DAT-03 audit_log and api_request_log entities for compliance  _(40 min)_
**Context:** WBS 1.4 data model sign-off. DAT-03 section 9 defines audit entities. audit_log: immutable append-only (NO UPDATE or DELETE permitted), stores actor_id, actor_type CHECK IN (OPERATOR, SYSTEM, PARTNER), action CHECK IN (CREATE, UPDATE, DELETE, ACTIVATE, DEACTIVATE), entity_type (table name), entity_id (row PK), before_value JSONB (NULL for CREATE), after_value JSONB (NULL for DELETE), occurred_at TIMESTAMPTZ, ip_address INET, request_id. Retention >= 7 years (BOK/KYC requirement). api_request_log: request_id UNIQUE, stores method, path, status_code, request_body_hash SHA-256 (not plaintext), response_time_ms, ip_address.
**Steps:** Verify audit_log has no UPDATE or DELETE grants in application role permissions; Verify before_value is NULL for CREATE actions and after_value is NULL for DELETE actions; Verify api_request_log.request_body_hash stores SHA-256 hash, not plaintext request body; Confirm a DB-level trigger or application invariant prevents any UPDATE to audit_log rows; Verify audit_log.retention policy: confirm rows are never purged before 7 years from occurred_at
**Deliverable:** Audit and security entity DDL review: audit_log immutability, retention policy, api_request_log body hashing - signed off or flagged
**Acceptance / logic checks:**
- Application DB user has INSERT privilege on audit_log but no UPDATE or DELETE privilege
- UPDATE audit_log SET before_value = '{}' WHERE id = 1 returns permission denied or equivalent error
- Rate table update (treasury_rate insert) creates an audit_log entry with actor_id = operator ID, action = CREATE, entity_type = treasury_rate, after_value = JSON of new rate row
- api_request_log.request_body_hash = SHA256 of raw request bytes; plaintext body is never stored
- 7-year retention: a SELECT on audit_log for a row 8 years old still returns data (no auto-purge within retention window)
**Depends on:** 1.4-T11

### 1.4-T19 — Review DAT-03 RBAC entities: hub_user and hub_role  _(35 min)_
**Context:** WBS 1.4 data model sign-off. DAT-03 section 9.2 defines hub_user: id, email, name, role_id FK -> hub_role, is_active BOOLEAN, last_login_at TIMESTAMPTZ, created_at, updated_at, created_by. hub_role: id, role_code VARCHAR UNIQUE (SUPER_ADMIN, OPS_ADMIN, OPS_VIEWER, FINANCE), permissions JSONB. Four defined roles: SUPER_ADMIN (full access), OPS_ADMIN (config + settlement + exceptions), OPS_VIEWER (read-only), FINANCE (reporting and revenue only). Role changes must be audit-logged. SAD-02 section 11.1 requires RBAC for all operator actions; config changes, rate overrides, and refund initiation require authenticated session.
**Steps:** Verify hub_role has CHECK or UNIQUE constraint on role_code limiting to 4 defined values (SUPER_ADMIN, OPS_ADMIN, OPS_VIEWER, FINANCE); Verify hub_user.role_id FK references hub_role with ON DELETE RESTRICT; Confirm role changes (update hub_user.role_id) generate an audit_log entry with before_value and after_value; Verify hub_user.is_active = FALSE blocks login at authentication layer; Review permissions JSONB structure: define what actions each role permits (e.g. OPS_VIEWER cannot call rate override endpoints)
**Deliverable:** RBAC entity review: hub_user and hub_role DDL, role_code constraints, permission matrix for all 4 roles, sign-off
**Acceptance / logic checks:**
- hub_role INSERT with role_code = BILLING is rejected by CHECK constraint (not in allowed values)
- An OPS_VIEWER session attempting to update a treasury_rate receives HTTP 403 Forbidden
- SUPER_ADMIN can deactivate a partner; audit_log records this with actor_id, before_value = {is_active:true}, after_value = {is_active:false}
- hub_user.last_login_at is updated on every successful authentication, enabling idle session detection
- hub_user with is_active = FALSE cannot authenticate via the Admin System login flow
**Depends on:** 1.4-T18

### 1.4-T20 — Review DAT-03 enum reference values and CHECK constraints completeness  _(35 min)_
**Context:** WBS 1.4 data model sign-off. DAT-03 section 11 defines canonical enum values stored as VARCHAR with CHECK constraints. Key enums: payment_mode (MPM, CPM); direction (INBOUND, OUTBOUND, DOMESTIC, HUB); transaction_status (INITIATED, PREFUND_DEDUCTED, SUBMITTED, APPROVED, DECLINED, UNCERTAIN, FAILED, CANCELLED, REFUNDED); rate_source (IDENTITY, LIVE, MANUAL, PARTNER); partner_type (LOCAL, OVERSEAS); scheme_status (TESTING, ACTIVE, INACTIVE); partner_status (ONBOARDING, ACTIVE, SUSPENDED, INACTIVE); settlement_batch.status (PENDING, GENERATED, TRANSMITTED, RECEIVED, RECONCILED, ERROR); ZeroPay file_type codes (ZP0011-ZP0066).
**Steps:** List all enum columns across all tables and verify each has a corresponding CHECK constraint in DDL; Verify transaction_status values match SAD-02 state machine states exactly (no extra states, no missing states); Confirm ZeroPay file_type codes in settlement_batch are restricted to the 14 valid ZP00xx codes; Verify rate_source CHECK IN ('IDENTITY','LIVE','MANUAL','PARTNER') on rule.rate_coll_source and rule.rate_pay_source; Check that no enum is implemented as a PostgreSQL ENUM type (must be VARCHAR + CHECK for portability)
**Deliverable:** Complete enum/CHECK constraint audit table: every VARCHAR enum column listed with allowed values and DDL CHECK expression verified
**Acceptance / logic checks:**
- transaction_status = 'PROCESSING' is rejected (not in allowed values); valid states are INITIATED, PREFUND_DEDUCTED, SUBMITTED, APPROVED, DECLINED, UNCERTAIN, FAILED, CANCELLED, REFUNDED
- settlement_batch.file_type = 'ZP0099' is rejected by CHECK constraint
- direction = 'NORTHBOUND' is rejected; valid values are INBOUND, OUTBOUND, DOMESTIC, HUB only
- No PostgreSQL ENUM type appears in any CREATE TABLE statement - all enums are VARCHAR with CHECK
- Adding a new transaction_status value requires only a migration to update the CHECK constraint - no code change needed for the column type itself
**Depends on:** 1.4-T11

### 1.4-T21 — Review DAT-03 indexes and query performance design  _(40 min)_
**Context:** WBS 1.4 data model sign-off. DAT-03 requires: every FK column is indexed; transaction composite indexes: (partner_id, committed_at), (scheme_id, completed_at), (status, committed_at), (scheme_ref), (hub_txn_ref); prefunding_ledger_entry index: (account_id, created_at); treasury_rate indexed on ccy_pair (latest rate lookup: WHERE ccy_pair = 'usd_krw' ORDER BY effective_at DESC LIMIT 1); qr_code.qr_code_value UNIQUE (used at payment scan time). Rate quote lookup by quote_ref (UNIQUE index). Performance targets from SAD-02/NFR-10: GET /v1/rates p99 < 300ms, POST /v1/payments p99 < 2000ms.
**Steps:** List all FK columns across all tables and verify each has an explicit CREATE INDEX statement in migration files; Verify all 5 composite indexes on transaction table are present; Verify treasury_rate has an index on (ccy_pair, effective_at DESC) for efficient latest-rate lookup; Verify qr_code.qr_code_value has a UNIQUE index (primary payment path: QR scan lookup); Confirm rate_quote.quote_ref has a UNIQUE index (CommitTransaction lookup path)
**Deliverable:** Index coverage report: all FK columns, composite indexes, and performance-critical lookup indexes listed with CREATE INDEX statements and query plan estimates
**Acceptance / logic checks:**
- EXPLAIN ANALYZE for latest treasury rate SELECT uses index scan on ccy_pair, not sequential scan
- EXPLAIN ANALYZE for transaction lookup by txn_ref uses UNIQUE index scan, row estimate = 1
- EXPLAIN ANALYZE for partner transaction history (partner_id, committed_at range) uses composite index scan
- prefunding_ledger_entry balance history query for 10,000 entries returns in < 50ms with index on (account_id, created_at)
- No missing FK index detected: running pg_catalog query for unindexed FKs returns 0 rows
**Depends on:** 1.4-T20

### 1.4-T22 — Review SAD-02 open items and assumptions register for Phase 1 impact  _(45 min)_
**Context:** WBS 1.4 architecture sign-off. SAD-02 section 13 lists 7 assumptions (A-01 to A-07) and 3 open items (OI-01 to OI-03). Key Phase 1 impacts: A-01 (manual FX rates only, xe.com feed is Phase 2 - rate table schema must support both); A-06 (Partner B quote not needed for Phase 1 ZeroPay, built but not exercised); A-07 (no partner-API refunds in Phase 1, admin portal only); OI-02 (tax invoice API TBD - Hometax or equivalent); OI-03 (BOK FX1014/FX1015 field mapping and submission channel TBD). DAT-03 section 12 adds A1-A7 assumptions including KRW as BIGINT, DECIMAL(20,8) for USD intermediary amounts.
**Steps:** Document the status of all 7 SAD-02 assumptions and 3 open items - confirmed, assumed, or blocked; Confirm treasury_rate table schema supports both MANUAL and LIVE source columns (source CHECK IN ('LIVE','MANUAL')); Confirm bok_report_record schema is provisioned with placeholder columns sufficient for known FX1014/FX1015 fields even though submission channel is TBD; Confirm refund data model (refund table + REFUNDED state) is present even though partner API refunds are Phase 2; Get written confirmation or deferral decision from GME on each open item before Phase 1 dev start
**Deliverable:** Open items and assumptions review document: each SAD-02/DAT-03 assumption confirmed or flagged; each open item with owner, resolution target date, and Phase 1 impact assessment
**Acceptance / logic checks:**
- treasury_rate.source has both MANUAL and LIVE values in CHECK constraint even though LIVE feed is Phase 2
- bok_report_record table exists with offer_rate_coll, usd_amount, collection_amount, payout_amount columns pre-provisioned for future FX1014/FX1015 mapping
- refund table and REFUNDED transaction_status exist in Phase 1 schema even though partner API refund endpoint is not built
- OI-03 (BOK reporting) has a named owner at GME (e.g. Compliance team) and a resolution target date in PM-14 RAID log
- Any unresolved open item that could require schema migration if resolved incorrectly is escalated to GME sponsor before schema is finalized
**Depends on:** 1.4-T09

### 1.4-T23 — Unit test: verify 5-step rate engine formula with numeric example (cross-border)  _(55 min)_
**Context:** WBS 1.4 architecture sign-off. The 5-step RECEIVE-mode formula must be testable before sign-off. Inputs: target_payout = 50000 KRW, cost_rate_pay = 1380.0 (usd_krw), m_a = 0.01, m_b = 0.01, cost_rate_coll = 1.0 (settle_a = USD), service_charge = 0 USD. Expected: payout_usd_cost = 50000/1380 = 36.2319 USD (DECIMAL(20,8)), collection_usd = 36.2319/(1-0.01-0.01) = 36.9713 USD, collection_margin_usd = 36.9713*0.01 = 0.3697 USD, payout_margin_usd = 0.3697 USD, send_amount = 36.9713*1.0 = 36.9713 USD, collection_amount = 36.9713+0 = 36.9713 USD. Pool identity: 36.9713 - 0.3697 - 0.3697 = 36.2319 = payout_usd_cost (within 0.01 USD tolerance). offer_rate_coll = 36.9713/(36.9713-0.3697) = 1.0101. cross_rate = 50000/36.9713 = 1352.38 (KRW per USD).
**Steps:** Write a unit test that calls the Rate Engine computeQuote function with the exact inputs above; Assert each intermediate value matches the expected output to 4 significant decimal places; Assert pool identity: abs(collection_usd - collection_margin_usd - payout_margin_usd - payout_usd_cost) <= 0.01; Assert offer_rate_coll and cross_rate match expected derived values; Assert validUntil = quote_issued_at + rate_quote_ttl_seconds (300s default)
**Deliverable:** Passing unit test file: RateEngineTest.crossBorderMpmExample() with all 5 intermediate values, pool identity, and derived rate assertions
**Acceptance / logic checks:**
- Test fails if payout_usd_cost computation uses floating-point arithmetic (use DECIMAL/BigDecimal only)
- Test asserts collection_amount = send_amount + service_charge (service_charge = 0.0 in this case)
- Test asserts service_charge is NOT included in collection_usd (pool identity would break if it were)
- Test uses rounding to DECIMAL(20,8) for USD intermediary values and DECIMAL(20,4) for final collection_amount
- Test is deterministic across 1000 repeated runs (no floating-point non-determinism)
**Depends on:** 1.4-T04

### 1.4-T24 — Unit test: verify same-currency short-circuit and minimum margin enforcement  _(50 min)_
**Context:** WBS 1.4 architecture sign-off. Same-currency short-circuit: when collection_ccy = settle_a_ccy = settle_b_ccy = payout_ccy (e.g. GME_REMIT DOMESTIC on ZeroPay, all KRW), the USD pool is bypassed entirely. collection_amount = target_payout + service_charge. USD pool fields (collection_usd, payout_usd_cost, collection_margin_usd, payout_margin_usd, cost_rate_coll, cost_rate_pay, offer_rate_coll, cross_rate) are all NULL or 0. Minimum margin: m_a + m_b >= 0.02 for cross-border rules (enforced at rule creation); 0 is allowed for same-currency rules.
**Steps:** Write unit test: same-ccy short-circuit with target_payout=50000 KRW, service_charge=500 KRW; assert collection_amount=50500 KRW, all USD pool fields NULL; Write unit test: cross-border rule creation with m_a=0.005, m_b=0.010; assert rule.save() raises ValidationException with message containing 'minimum combined margin'; Write unit test: cross-border rule creation with m_a=0.010, m_b=0.010; assert rule.save() succeeds; Write unit test: same-currency rule creation with m_a=0.0, m_b=0.0; assert rule.save() succeeds (0% allowed for domestic); Assert is_same_ccy_shortcircuit is auto-computed: rule with collection=KRW, settleA=KRW, settleB=KRW, payout=KRW sets flag to TRUE automatically
**Deliverable:** Passing unit test file: RateEngineTest.sameCurrencyShortCircuit() and RuleValidationTest.minimumMarginEnforcement() with all assertions
**Acceptance / logic checks:**
- Short-circuit test: collection_amount = 50500, collection_usd IS NULL, offer_rate_coll IS NULL, cross_rate IS NULL
- Cross-border rule with m_a=0.005 + m_b=0.010 = 1.5% < 2% raises error at rule save, not at rate computation time
- Cross-border rule with m_a=0.010 + m_b=0.010 = 2.0% passes validation (boundary condition)
- Domestic rule with m_a=0.0, m_b=0.0, is_same_ccy_shortcircuit=TRUE passes validation (0% allowed)
- service_charge of 500 KRW is added after the short-circuit check; changing service_charge does not affect the short-circuit condition
**Depends on:** 1.4-T23

### 1.4-T25 — Unit test: verify rate quote TTL enforcement and quote expiry on CommitTransaction  _(55 min)_
**Context:** WBS 1.4 architecture sign-off. Rate quote TTL: validUntil = quote_issued_at + rate_quote_ttl_seconds (default 300s, range 60-1800s). On CommitTransaction, the Orchestrator checks current_time <= validUntil; if expired, returns error EXPIRED_QUOTE and the transaction remains in QUOTED state (not committed). rate_quote.is_used is set to TRUE atomically with CommitTransaction to prevent reuse of the same quote for two transactions. Redis stores the quote with TTL key rate_quote:{quote_ref}; Redis expiry is aligned with validUntil.
**Steps:** Write unit test: issue a quote with ttl=60s; commit at t+30s; assert transaction reaches DEBITED state; Write unit test: issue a quote with ttl=60s; commit at t+65s; assert EXPIRED_QUOTE error is returned; Write unit test: commit same quote_ref twice concurrently; assert exactly one succeeds and one receives QUOTE_ALREADY_USED error; Write unit test: partner with rate_quote_ttl_seconds=1800; assert validUntil = quote_issued_at + 1800s; Assert ttl values outside 60-1800 range on partner record are rejected at partner creation/update
**Deliverable:** Passing unit test file: RateQuoteTtlTest with TTL enforcement, expiry, concurrent-use, and boundary tests
**Acceptance / logic checks:**
- Commit at t+60s exactly (boundary): test confirms this succeeds (valid_until is exclusive - test documents boundary semantics)
- Commit at t+61s: EXPIRED_QUOTE error returned; transaction.status remains QUOTED, not FAILED
- Concurrent double-commit test uses two threads with a shared barrier; only one thread receives APPROVED outcome
- rate_quote_ttl_seconds = 59 on partner raises ValidationException at save time
- Redis key rate_quote:{quote_ref} has TTL within 1s of validUntil timestamp at quote issuance time
**Depends on:** 1.4-T23

### 1.4-T26 — Unit test: verify prefunding atomic deduction and concurrent overdraw prevention  _(55 min)_
**Context:** WBS 1.4 architecture sign-off. Prefunding deduction must be atomic using SELECT FOR UPDATE on prefunding_account. Scenario: OVERSEAS partner SendMN has balance USD 1000.00. Two concurrent payments each requesting USD 800.00 collection_usd. Only one should succeed; the other returns INSUFFICIENT_PREFUNDING. The successful deduction creates a prefunding_ledger_entry with entry_type=DEBIT_PAYMENT and balance_after = 200.00. The failed deduction does not create a ledger entry. Scheme is never called unless deduction committed successfully.
**Steps:** Write integration test with two concurrent transaction threads against a real (test) PostgreSQL instance; Thread 1 and Thread 2 both call deductPrefunding(partner_id, 800.00 USD) simultaneously; Assert exactly one call returns success; exactly one returns INSUFFICIENT_PREFUNDING error; Assert prefunding_account.balance = 200.00 after both calls complete; Assert exactly one prefunding_ledger_entry row exists for the payment deduction (the failed one creates no entry); Assert the scheme adapter was called exactly once (only for the successful deduction)
**Deliverable:** Passing integration test: PrefundingAtomicityTest.concurrentOverdrawPrevention() requiring a real PostgreSQL test database
**Acceptance / logic checks:**
- Test must use actual DB transactions with SELECT FOR UPDATE - cannot mock the lock
- After test: prefunding_account.balance = 200.00 (not 1000.00 and not -600.00)
- Only 1 prefunding_ledger_entry row with entry_type=DEBIT_PAYMENT exists; balance_after = 200.00
- Scheme adapter mock was invoked exactly once; InvocationCount assertion on mock
- Test is repeatable: running it 100 times always produces the same result (no race condition)
**Depends on:** 1.4-T15

### 1.4-T27 — Unit test: verify pool identity invariant and breach detection  _(55 min)_
**Context:** WBS 1.4 architecture sign-off. Pool identity invariant: collection_usd - collection_margin_usd - payout_margin_usd == payout_usd_cost (tolerance 0.01 USD). This must be checked by the Rate Engine after Step 3 of the 5-step calculation. If breached, the rate computation must fail with a hard error (transaction must not commit). Inputs that could cause a breach: floating-point arithmetic errors, incorrect rounding of intermediate values, incorrect margin application.
**Steps:** Write unit test: normal cross-border computation; assert pool identity holds within 0.0001 USD (tighter than 0.01 tolerance); Write unit test: deliberately inject a rounding error into collection_usd (add 0.005 USD); assert pool identity check PASSES (within 0.01 tolerance); Write unit test: inject a rounding error of 0.015 USD into collection_usd; assert pool identity check FAILS with PoolIdentityBreach exception; Write unit test: run 1000 random valid inputs (varying m_a, m_b, target_payout, cost_rates); assert pool identity holds for all; Assert PoolIdentityBreach exception prevents CommitTransaction from proceeding (transaction stays in QUOTED state)
**Deliverable:** Passing unit test file: PoolIdentityTest with identity check, tolerance boundary tests, and 1000-iteration random test
**Acceptance / logic checks:**
- Tolerance boundary: deviation of 0.0099 USD passes; deviation of 0.0101 USD fails (strict greater-than check)
- Using DECIMAL/BigDecimal arithmetic (not float/double), pool identity should hold to within 0.0000001 USD for all normal inputs
- PoolIdentityBreach is logged as a critical error with the specific deviation amount for debugging
- All 1000 random inputs with m_a in [0.01, 0.05], m_b in [0.01, 0.05], target_payout in [1000, 100000] KRW pass pool identity
- No floating-point type appears in any rate computation code path (verified by static analysis or code review)
**Depends on:** 1.4-T23

### 1.4-T28 — Unit test: verify transaction state machine transitions and invalid transition rejection  _(55 min)_
**Context:** WBS 1.4 architecture sign-off. DAT-03 section 11.3 and SAD-02 section 5.2 define 9 transaction states. Valid transitions: QUOTED->PENDING_DEBIT (OVERSEAS commit), QUOTED->DEBITED (LOCAL commit), QUOTED->FAILED (TTL expired), PENDING_DEBIT->DEBITED (deduction ok), PENDING_DEBIT->FAILED (insufficient funds), DEBITED->SCHEME_SENT, SCHEME_SENT->APPROVED, SCHEME_SENT->FAILED, SCHEME_SENT->UNCERTAIN, UNCERTAIN->APPROVED, UNCERTAIN->FAILED, APPROVED->REVERSED, APPROVED->REFUNDED. Invalid: APPROVED->QUOTED, FAILED->APPROVED, DEBITED->QUOTED, etc.
**Steps:** Write unit test for each valid state transition; assert resulting status is correct; Write unit test for 5 invalid transitions (e.g. APPROVED->QUOTED, FAILED->DEBITED, UNCERTAIN->QUOTED); assert InvalidStateTransitionException; Write unit test: OVERSEAS partner path: QUOTED->PENDING_DEBIT->DEBITED->SCHEME_SENT->APPROVED (full happy path); Write unit test: LOCAL partner path: QUOTED->DEBITED->SCHEME_SENT->APPROVED (no PENDING_DEBIT step); Write unit test: UNCERTAIN->APPROVED via batch reconciliation; confirm rate-lock values are not re-computed (they were locked at original CommitTransaction)
**Deliverable:** Passing unit test file: TransactionStateMachineTest with valid transitions, invalid transition rejections, and two full happy-path flows (OVERSEAS and LOCAL)
**Acceptance / logic checks:**
- All valid transitions from SAD-02 state diagram are tested; 100% transition coverage
- APPROVED->QUOTED transition throws InvalidStateTransitionException with error code INVALID_STATE_TRANSITION
- LOCAL partner transaction never visits PENDING_DEBIT state (assert state history contains no PENDING_DEBIT event)
- UNCERTAIN->APPROVED transition does NOT re-compute rate fields; locked values from CommitTransaction are unchanged
- Terminal states FAILED, REVERSED, REFUNDED have no outbound transitions; test confirms attempting to transition from them throws exception
**Depends on:** 1.4-T03

### 1.4-T29 — Review SAD-02/DAT-03 data residency, security, and secrets management  _(40 min)_
**Context:** WBS 1.4 architecture sign-off. SAD-02 assumption A-02: all transaction and personal data for Korean QR payments must be stored in Korea (AWS ap-northeast-2 or Korean colocation). SEC-09 requires: TLS 1.2+ (1.3 preferred) for all API communication; API credentials stored hashed (bcrypt); SFTP credentials and external API keys stored in Vault/Secrets Manager (never in env vars, config files, or source code); prefunding ledger access restricted to Transaction Orchestrator service account; rate-lock columns on transaction are immutable after CommitTransaction (no UPDATE permitted on locked columns after committed_at is set); audit_log retention >= 7 years.
**Steps:** Verify production deployment region is confirmed as Korea (AWS ap-northeast-2 or equivalent); Verify all SFTP credentials and external API keys are referenced via vault path (api_credential_ref) not stored inline; Confirm no credentials appear in any Dockerfile, docker-compose.yml, CI/CD pipeline definition, or application.properties; Verify rate-lock immutability: confirm application code or DB trigger blocks UPDATE of transaction locked columns after committed_at IS NOT NULL; Verify audit_log has no TTL or auto-purge mechanism; confirm 7-year retention policy is documented and operationally enforced
**Deliverable:** Security and data residency review checklist: Korea region, secrets management, credential hashing, rate-lock immutability, audit retention - all confirmed or flagged
**Acceptance / logic checks:**
- No API key, SFTP password, or DB password appears in any source file (automated secret-scanning passes)
- transaction UPDATE attempt that modifies collection_usd after committed_at is set is blocked and generates an audit_log entry
- partner_credential.api_secret_hash contains bcrypt hash (starts with $2a$ or $2b$); retrieving the partner credential via Admin API never returns the hash
- audit_log rows are only INSERT-able; UPDATE and DELETE grant is absent from the application DB role
- Production Terraform or infra-as-code specifies Korea region; no other region is permitted for transaction data stores
**Depends on:** 1.4-T18

### 1.4-T30 — Conduct formal architecture walkthrough with GME and obtain sign-off  _(60 min)_
**Context:** WBS 1.4 architecture sign-off. All prior tickets (T01-T29) represent the detailed review tasks. This ticket is the formal sign-off session with GME stakeholders. The deliverable of WBS 1.4 is a signed-off design. Participants: GME Product Owner, GME Tech Lead, GME Compliance (for BOK open items OI-03), development vendor tech lead. Artefacts to present: architecture review checklist (T01-T02), state machine diagram (T03), rate engine review (T04), open items register (T22), technology stack decision record (T09), unit test pass report (T23-T28), security review checklist (T29).
**Steps:** Schedule formal walkthrough session with all named participants; Present each artefact from T01-T29 in order; highlight any flagged items requiring GME decision; Obtain explicit written decision on all open items (OI-01, OI-02, OI-03) or formally defer with named owner and date; Obtain sign-off signature (email confirmation acceptable) from GME Product Owner on: architecture approach, data model, technology stack, and Phase 1 scope boundary; Record all decisions and action items in PM-14 RAID log
**Deliverable:** Signed-off design approval document: email or formal sign-off from GME Product Owner confirming SAD-02 architecture and DAT-03 data model are approved for Phase 1 build
**Acceptance / logic checks:**
- GME Product Owner has reviewed and approved all 12 ADRs (AD-01 through AD-12) with no outstanding deviations
- All open items (OI-01, OI-02, OI-03) have a named GME owner and resolution date recorded in PM-14
- Technology stack decision record is signed off - no deviations require post-sign-off re-approval
- All unit tests from T23-T28 are passing at time of sign-off presentation
- Sign-off document is stored in version-controlled project artefacts folder and referenced in PM-14 as WBS 1.4 completion evidence
**Depends on:** 1.4-T01, 1.4-T02, 1.4-T03, 1.4-T04, 1.4-T05, 1.4-T06, 1.4-T07, 1.4-T08, 1.4-T09, 1.4-T10, 1.4-T11, 1.4-T12, 1.4-T13, 1.4-T14, 1.4-T15, 1.4-T16, 1.4-T17, 1.4-T18, 1.4-T19, 1.4-T20, 1.4-T21, 1.4-T22, 1.4-T23, 1.4-T24, 1.4-T25, 1.4-T26, 1.4-T27, 1.4-T28, 1.4-T29


## WBS 1.5 — Ongoing project management & reporting
### 1.5-T01 — Define RAID log DB schema and migration for risks, assumptions, issues, dependencies  _(35 min)_
**Context:** PM-14 §7 defines four RAID categories. Risks (R-01..R-08) have: id, category ENUM(RISK,ASSUMPTION,ISSUE,DEPENDENCY), raid_id VARCHAR(10) UNIQUE (e.g. R-01), title VARCHAR(200), description TEXT, likelihood VARCHAR(10) CHECK(HIGH,MEDIUM,LOW,NA), impact VARCHAR(10) CHECK(HIGH,MEDIUM,LOW,NA), mitigation TEXT, owner VARCHAR(120), status VARCHAR(20) CHECK(OPEN,CLOSED,MITIGATED,ACCEPTED), phase VARCHAR(10), target_date DATE, created_at TIMESTAMPTZ, updated_at TIMESTAMPTZ, created_by VARCHAR(120), updated_by VARCHAR(120), is_active BOOLEAN DEFAULT TRUE. Table name: raid_log. A dependency entry may additionally carry: from_party VARCHAR(120), to_party VARCHAR(120), required_by DATE. Store all four categories in one table, nullable fields that apply only to specific categories.
**Steps:** Create db/migrations/V_PM01__raid_log.sql; Write CREATE TABLE raid_log with all columns, CHECK constraints on category/likelihood/impact/status; Add UNIQUE index on raid_id; Add standard audit columns; Apply migration to test DB and verify schema
**Deliverable:** db/migrations/V_PM01__raid_log.sql
**Acceptance / logic checks:**
- raid_log table created with all required columns
- UNIQUE constraint on raid_id enforced: duplicate R-01 insert fails
- CHECK on category rejects value 'TASK'
- CHECK on likelihood rejects value 'VERY_HIGH'
- CHECK on status rejects value 'PENDING'; accepts 'OPEN'
- Nullable dependency-only fields (from_party, required_by) accept NULL for non-dependency rows

### 1.5-T02 — Define sprint_ceremony DB schema: ceremony log and action item tables  _(30 min)_
**Context:** Sprint ceremonies (planning, review, retrospective, standup) must be logged with outcomes and action items. Table sprint_ceremony: id BIGINT PK, ceremony_type VARCHAR(20) CHECK(PLANNING,REVIEW,RETROSPECTIVE,STANDUP,OTHER), sprint_number INTEGER NOT NULL, ceremony_date DATE NOT NULL, facilitator VARCHAR(120), attendees TEXT, summary TEXT, phase VARCHAR(10), created_at TIMESTAMPTZ, updated_at TIMESTAMPTZ, created_by VARCHAR(120). Table ceremony_action_item: id BIGINT PK, ceremony_id BIGINT FK -> sprint_ceremony ON DELETE CASCADE, description TEXT NOT NULL, owner VARCHAR(120), due_date DATE, status VARCHAR(20) CHECK(OPEN,IN_PROGRESS,DONE,CANCELLED), resolution_note TEXT, created_at TIMESTAMPTZ, updated_at TIMESTAMPTZ. Index FK column ceremony_id.
**Steps:** Create db/migrations/V_PM02__sprint_ceremony.sql; Write CREATE TABLE sprint_ceremony with all columns and CHECK constraints; Write CREATE TABLE ceremony_action_item with FK to sprint_ceremony and CHECK on status; Add index on ceremony_action_item.ceremony_id (FK rule); Apply and verify both tables
**Deliverable:** db/migrations/V_PM02__sprint_ceremony.sql
**Acceptance / logic checks:**
- Both tables created; FK from ceremony_action_item to sprint_ceremony enforced
- CHECK on ceremony_type rejects 'KICKOFF'; accepts 'RETROSPECTIVE'
- DELETE of sprint_ceremony row cascades and removes child action items
- ceremony_action_item.status CHECK rejects 'BLOCKED'; accepts 'DONE'
- sprint_number must be NOT NULL: insert without it fails
**Depends on:** 1.5-T01

### 1.5-T03 — Define weekly_status_report DB schema with phase and milestone snapshot  _(30 min)_
**Context:** PM-14 §3 defines milestones tied to phases (Phase 1 gate Jun 20, Phase 2 gate Jul 31, Phase 3 Oct 10, Phase 4 Dec 10, 2026). Table weekly_status_report: id BIGINT PK, report_date DATE NOT NULL, reporting_week_start DATE NOT NULL, phase VARCHAR(10) NOT NULL, overall_rag VARCHAR(6) CHECK(RED,AMBER,GREEN), scope_rag VARCHAR(6), schedule_rag VARCHAR(6), budget_rag VARCHAR(6), key_achievements TEXT, risks_escalated TEXT, blockers TEXT, next_week_plan TEXT, milestone_snapshot JSONB, submitted_by VARCHAR(120), submitted_at TIMESTAMPTZ, created_at TIMESTAMPTZ, updated_at TIMESTAMPTZ. milestone_snapshot stores array of {milestone, target_date, forecast_date, status} capturing the point-in-time state at submission.
**Steps:** Create db/migrations/V_PM03__weekly_status_report.sql; Write CREATE TABLE weekly_status_report with all columns and CHECK constraints on RAG fields; Add partial unique index: UNIQUE(reporting_week_start, phase) WHERE is_active = TRUE to prevent duplicate active reports per week+phase; Add check that overall_rag, scope_rag, schedule_rag, budget_rag each only accept RED, AMBER, GREEN; Apply and verify
**Deliverable:** db/migrations/V_PM03__weekly_status_report.sql
**Acceptance / logic checks:**
- RAG CHECK rejects 'YELLOW'; accepts 'AMBER'
- Unique partial index prevents two active reports for same reporting_week_start + phase
- milestone_snapshot column accepts valid JSONB array and rejects malformed JSON
- submitted_at nullable (draft reports not yet submitted)
- report_date NOT NULL enforced
**Depends on:** 1.5-T01

### 1.5-T04 — Implement RaidLogRepository: CRUD + search with category and status filters  _(45 min)_
**Context:** The RAID log (table raid_log, see 1.5-T01) is maintained throughout all phases. PM-14 A-14-03 states the dev vendor must add new items and the GME PM reviews/accepts within 2 business days. Repository must support: create entry, update entry (status, mitigation, owner), find by id, find by raid_id (e.g. 'R-01'), search by category + status, list all active entries ordered by category then raid_id. Use Spring Data JPA or equivalent ORM. Entity class: RaidLogEntry. Repository interface: RaidLogRepository.
**Steps:** Create entity class RaidLogEntry mapping all raid_log columns; Create RaidLogRepository extending JpaRepository with custom query methods: findByRaidId(String), findByCategoryAndStatus(String,String), findAllByIsActiveTrueOrderByCategoryAscRaidIdAsc; Implement RaidLogService with createEntry, updateEntry (status/mitigation/owner/updatedBy), closeEntry(id, resolutionNote) methods; Ensure updateEntry records updated_at = NOW() and updated_by = actor; Write save and retrieve round-trip for one Risk and one Dependency entry
**Deliverable:** RaidLogEntry entity + RaidLogRepository interface + RaidLogService class
**Acceptance / logic checks:**
- createEntry persists all fields; raid_id UNIQUE constraint violation throws DataIntegrityViolationException not 500
- findByRaidId('R-01') returns correct entry after save
- updateEntry sets updated_at to current timestamp and updated_by to supplied actor
- findByCategoryAndStatus('RISK','OPEN') returns only RISK+OPEN rows
- closeEntry sets status to CLOSED and is_active to FALSE
**Depends on:** 1.5-T01

### 1.5-T05 — Implement WeeklyStatusReportService: create, update, submit lifecycle  _(50 min)_
**Context:** Weekly status reports (table weekly_status_report, see 1.5-T03) follow a draft -> submitted lifecycle. submitted_at is null until explicit submit action. A report may only be submitted once (submitted_at not null = immutable). milestone_snapshot must capture current milestone state at submission time from a MilestoneService. RAG values: RED, AMBER, GREEN. Phase values: PH1, PH2, PH3, PH4. Report covers one calendar week identified by reporting_week_start (Monday). Only one active report allowed per week+phase.
**Steps:** Create WeeklyStatusReport entity and WeeklyStatusReportRepository; Implement WeeklyStatusReportService.createDraft(phase, weekStart, submittedBy) - creates report with all RAG fields null and submitted_at null; Implement updateReport(id, dto) - merges RAG and text fields; throws ReportAlreadySubmittedException if submitted_at is not null; Implement submitReport(id, actorId) - sets submitted_at = NOW(), milestone_snapshot = milestoneService.snapshot(), throws if already submitted; Ensure unique-per-week constraint violation returns clear error, not 500
**Deliverable:** WeeklyStatusReport entity + WeeklyStatusReportRepository + WeeklyStatusReportService
**Acceptance / logic checks:**
- createDraft persists with submitted_at null and all RAG fields null
- updateReport on a submitted report throws ReportAlreadySubmittedException
- submitReport sets submitted_at and captures milestone_snapshot as non-null JSONB
- submitReport called twice on same report throws ReportAlreadySubmittedException second time
- createDraft for same week+phase as existing active report throws DuplicateReportException
**Depends on:** 1.5-T03

### 1.5-T06 — Implement MilestoneService: milestone registry and snapshot capture  _(45 min)_
**Context:** PM-14 §3.1 defines 16 milestones with target dates: contract signed Apr 10, dev env provisioned Apr 17, Hub Core schema+rate engine May 9, Admin portal core May 23, Northbound API sandbox Jun 6, 한결원 test env mid-May, Phase 1 gate Jun 20, ZeroPay merchant sync Jun 13, ZP00xx batch verified Jul 18, Phase 2 gate Jul 31, GME Remit staging Sep 5, UAT Phase 3 Sep 26, Production deployment Oct 10, Phase 3 gate Oct 10, SendMN/T-Bank staging Nov 7, UAT Phase 4 Nov 28, Overseas live Dec 10. Each milestone has: code VARCHAR(20), name, phase, target_date, owner, forecast_date (nullable, updatable), status VARCHAR(20) CHECK(NOT_STARTED,IN_PROGRESS,COMPLETE,DELAYED,AT_RISK). Seed data included. snapshot() returns list of all milestone DTOs.
**Steps:** Create milestone DB table (or seed data in V_PM04__milestones.sql) with the 16 milestones above; Create Milestone entity and MilestoneRepository; Implement MilestoneService.getAll(), updateForecast(code, forecastDate, actor), updateStatus(code, status, actor); Implement MilestoneService.snapshot() returning List<MilestoneSnapshotDto> with fields: code, name, targetDate, forecastDate, status; Write seed migration inserting all 16 rows with correct dates and phases
**Deliverable:** V_PM04__milestones.sql seed migration + MilestoneService + MilestoneSnapshotDto
**Acceptance / logic checks:**
- All 16 milestone rows present after seed migration runs
- Phase 3 gate milestone has target_date = 2026-10-10 and phase = PH3
- updateForecast sets forecast_date and records updated_by and updated_at
- updateStatus to DELAYED leaves target_date unchanged
- snapshot() returns exactly 16 entries with non-null code and name for all
**Depends on:** 1.5-T03

### 1.5-T07 — Implement SprintCeremonyService: log ceremony, add action items, close action items  _(45 min)_
**Context:** Sprint ceremonies (table sprint_ceremony, see 1.5-T02) are logged by the PM or Scrum Master. Each ceremony produces zero or more action items. PM-14 A-14-03 expects the PM to notify GME Product Team of new RAID items at the weekly status meeting - this creates the pattern: ceremony -> action items -> link to RAID entries where applicable. SprintCeremonyService must: create ceremony log, add action items to a ceremony, update action item status (OPEN -> IN_PROGRESS -> DONE/CANCELLED), list open action items across all ceremonies, list ceremonies by sprint number.
**Steps:** Create SprintCeremony and CeremonyActionItem entities and repositories; Implement SprintCeremonyService.logCeremony(dto) persisting ceremony record; Implement addActionItem(ceremonyId, dto) returning saved action item with OPEN status; Implement updateActionItemStatus(itemId, newStatus, resolutionNote, actor) - validate allowed transitions: OPEN->IN_PROGRESS, OPEN->CANCELLED, IN_PROGRESS->DONE, IN_PROGRESS->CANCELLED; Implement listOpenActionItems() and listByCeremony(ceremonyId)
**Deliverable:** SprintCeremony entity + CeremonyActionItem entity + SprintCeremonyService
**Acceptance / logic checks:**
- logCeremony persists with ceremony_type and sprint_number; missing sprint_number fails with constraint violation
- addActionItem creates item with status OPEN and correct ceremony_id FK
- updateActionItemStatus DONE->OPEN transition rejected with InvalidStatusTransitionException
- listOpenActionItems returns only items with status OPEN or IN_PROGRESS
- deleteAllByCeremonyId not allowed (no hard-delete); closing ceremony does not cascade delete action items (CASCADE DELETE only on entity removal, which is not exposed)
**Depends on:** 1.5-T02

### 1.5-T08 — Implement RAID REST API: POST /pm/raid, PATCH /pm/raid/{id}, GET /pm/raid with filters  _(50 min)_
**Context:** Internal Admin API (JWT-authenticated, role ROLE_PM or ROLE_ADMIN) for RAID log management per PM-14 §7. Endpoints: POST /pm/raid (create entry, body: RaidLogEntryDto with category, raid_id, title, description, likelihood, impact, mitigation, owner, phase, target_date); PATCH /pm/raid/{id} (update status, mitigation, owner, forecast; immutable: category, raid_id, created_by); GET /pm/raid?category=RISK&status=OPEN (filter + list); GET /pm/raid/{id} (single entry). All responses use standard envelope: {data, errors, meta}. Return 409 if raid_id already exists on POST.
**Steps:** Create RaidLogController with the four endpoint handlers; Map RaidLogEntryDto <-> RaidLogEntry entity; validate category, likelihood, impact, status values via @Valid annotations; Wire to RaidLogService (1.5-T04) methods; Return 201 on POST with Location header pointing to /pm/raid/{id}; Return 409 with error code RAID_ID_DUPLICATE when raid_id already exists; Secure all endpoints with @PreAuthorize('hasAnyRole(ROLE_PM, ROLE_ADMIN)')
**Deliverable:** RaidLogController with 4 endpoints + RaidLogEntryDto + RaidLogEntryResponse
**Acceptance / logic checks:**
- POST /pm/raid with category=RISK, raid_id=R-99, likelihood=HIGH returns 201 and Location header
- GET /pm/raid?category=RISK&status=OPEN returns only RISK+OPEN entries
- PATCH /pm/raid/{id} with body {status:CLOSED} updates status and sets updated_at
- POST /pm/raid with duplicate raid_id returns 409 with RAID_ID_DUPLICATE error code
- GET /pm/raid/{id} for non-existent id returns 404
- Unauthenticated request returns 401
**Depends on:** 1.5-T04

### 1.5-T09 — Implement Status Report REST API: POST /pm/status-report, PATCH, POST .../submit, GET  _(50 min)_
**Context:** Internal API (role ROLE_PM or ROLE_ADMIN) for weekly status reports per PM-14. Endpoints: POST /pm/status-report (create draft; body: phase, reporting_week_start, overall_rag, scope_rag, schedule_rag, budget_rag, key_achievements, risks_escalated, blockers, next_week_plan); PATCH /pm/status-report/{id} (update draft fields; reject if submitted); POST /pm/status-report/{id}/submit (finalise, sets submitted_at and captures milestone_snapshot); GET /pm/status-report?phase=PH1 (list); GET /pm/status-report/{id}. Return 422 with REPORT_ALREADY_SUBMITTED when PATCH or submit attempted on already-submitted report. reporting_week_start must be a Monday (ISO day-of-week = 1); reject with 400 if not.
**Steps:** Create WeeklyStatusReportController with 5 endpoint handlers; Validate reporting_week_start is Monday; return 400 BAD_REQUEST with INVALID_WEEK_START if not; Wire to WeeklyStatusReportService (1.5-T05); Map Dto <-> Entity; validate RAG enum values; return 422 with descriptive error if invalid; On submit, call WeeklyStatusReportService.submitReport which captures milestone snapshot; Secure with @PreAuthorize('hasAnyRole(ROLE_PM, ROLE_ADMIN)')
**Deliverable:** WeeklyStatusReportController with 5 endpoints + WeeklyStatusReportDto + WeeklyStatusReportResponse
**Acceptance / logic checks:**
- POST /pm/status-report with reporting_week_start=2026-05-04 (Monday) returns 201
- POST /pm/status-report with reporting_week_start=2026-05-05 (Tuesday) returns 400 with INVALID_WEEK_START
- PATCH on submitted report returns 422 with REPORT_ALREADY_SUBMITTED
- POST .../submit sets submitted_at; second submit call returns 422
- GET /pm/status-report?phase=PH1 returns only PH1 reports ordered by reporting_week_start DESC
**Depends on:** 1.5-T05, 1.5-T06

### 1.5-T10 — Implement Sprint Ceremony REST API: POST /pm/ceremony, POST /pm/ceremony/{id}/action-items, PATCH action-item status  _(45 min)_
**Context:** Internal API (role ROLE_PM or ROLE_ADMIN) for sprint ceremonies per PM-14. Endpoints: POST /pm/ceremony (log ceremony; body: ceremony_type, sprint_number, ceremony_date, facilitator, attendees, summary, phase); GET /pm/ceremony?sprint_number=3 (list by sprint); GET /pm/ceremony/{id} (single); POST /pm/ceremony/{id}/action-items (add action item; body: description, owner, due_date); PATCH /pm/ceremony/action-items/{itemId} (update status, resolution_note); GET /pm/ceremony/action-items/open (all open+in-progress items). ceremony_type must be one of PLANNING, REVIEW, RETROSPECTIVE, STANDUP, OTHER.
**Steps:** Create SprintCeremonyController with 6 endpoint handlers; Validate ceremony_type enum; return 400 if invalid value supplied; Wire to SprintCeremonyService (1.5-T07) for all operations; PATCH action-item enforces status transition rules; return 422 with INVALID_STATUS_TRANSITION on bad transition; Return 404 when ceremony or action item not found; Secure all endpoints with @PreAuthorize('hasAnyRole(ROLE_PM, ROLE_ADMIN)')
**Deliverable:** SprintCeremonyController with 6 endpoints + SprintCeremonyDto + CeremonyActionItemDto
**Acceptance / logic checks:**
- POST /pm/ceremony with type=RETROSPECTIVE and sprint_number=5 returns 201
- POST /pm/ceremony with type=KICKOFF returns 400
- POST .../action-items on ceremony creates item with status=OPEN
- PATCH action-item with transition DONE->OPEN returns 422 with INVALID_STATUS_TRANSITION
- GET /pm/ceremony/action-items/open returns only OPEN and IN_PROGRESS items
- GET /pm/ceremony?sprint_number=3 returns only sprint 3 ceremonies
**Depends on:** 1.5-T07

### 1.5-T11 — Implement Milestone REST API: GET /pm/milestones, PATCH forecast and status, GET snapshot  _(40 min)_
**Context:** Milestones (PM-14 §3.1, seeded in 1.5-T06) are maintained by the PM throughout the project. Endpoints: GET /pm/milestones (all milestones); GET /pm/milestones?phase=PH1 (filtered by phase); GET /pm/milestones/{code} (single by code e.g. PHASE1_GATE); PATCH /pm/milestones/{code} (update forecast_date and/or status only; code and target_date are immutable); GET /pm/milestones/snapshot (same as GET all but formatted as snapshot dto: code, name, phase, target_date, forecast_date, status, variance_days). variance_days = forecast_date - target_date in days (negative means ahead, positive means behind, null if forecast_date not set).
**Steps:** Create MilestoneController with 5 endpoint handlers; Validate status enum values (NOT_STARTED, IN_PROGRESS, COMPLETE, DELAYED, AT_RISK) in PATCH body; Compute variance_days in response DTO as (forecast_date - target_date).toDays() when forecast_date is not null, otherwise null; Wire to MilestoneService (1.5-T06); Return 404 for unknown milestone code; Secure with @PreAuthorize('hasAnyRole(ROLE_PM, ROLE_ADMIN)')
**Deliverable:** MilestoneController with 5 endpoints + MilestoneDto + MilestoneSnapshotResponseDto
**Acceptance / logic checks:**
- GET /pm/milestones returns exactly 16 entries after seed
- GET /pm/milestones?phase=PH3 returns only Phase 3 milestones
- PATCH /pm/milestones/PHASE3_GATE with forecast_date=2026-11-01 sets forecast and variance_days=22
- PATCH /pm/milestones/PHASE3_GATE with code change returns 400 BAD_REQUEST (immutable field)
- GET /pm/milestones/snapshot includes variance_days field; entry with no forecast_date has variance_days=null
- PATCH with status=INVALID returns 400
**Depends on:** 1.5-T06

### 1.5-T12 — Implement RAID log auto-seed: insert all PM-14 initial RAID entries at startup  _(40 min)_
**Context:** PM-14 §7 defines 8 Risks (R-01..R-08), 8 Assumptions (A-01..A-08), 4 Issues (I-01..I-04), and 8 Dependencies (D-01..D-08). These must be seeded into the raid_log table at startup via a Flyway migration (V_PM05__raid_seed.sql) so the system ships with the project's baseline RAID log populated. All seeded items have status=OPEN, created_by='SYSTEM'. R-01: 한결원 test env delayed, HIGH/HIGH, owner=GME BD. R-02: BOK reporting late, MEDIUM/HIGH, owner=GME Compliance. R-03: Rate engine error, MEDIUM/HIGH. R-04: Prefunding atomicity bug, MEDIUM/HIGH. R-05: Tax-invoice API unavailable, MEDIUM/MEDIUM. R-06: ZeroPay file format changes, LOW/MEDIUM. R-07: Overseas partner integration delayed, MEDIUM/MEDIUM. R-08: FX rates stale, MEDIUM/MEDIUM.
**Steps:** Create db/migrations/V_PM05__raid_seed.sql; Insert 8 Risk rows (R-01..R-08) with likelihood, impact, mitigation, owner per PM-14 §7.1; Insert 8 Assumption rows (A-01..A-08) per PM-14 §7.2; likelihood=NA, impact per table; Insert 4 Issue rows (I-01..I-04) per PM-14 §7.3; likelihood=NA; Insert 8 Dependency rows (D-01..D-08) per PM-14 §7.4 with from_party, to_party, required_by; Apply migration; verify row counts
**Deliverable:** db/migrations/V_PM05__raid_seed.sql with 28 rows
**Acceptance / logic checks:**
- SELECT COUNT(*) FROM raid_log WHERE category='RISK' = 8
- SELECT COUNT(*) FROM raid_log WHERE category='ASSUMPTION' = 8
- SELECT COUNT(*) FROM raid_log WHERE category='ISSUE' = 4
- SELECT COUNT(*) FROM raid_log WHERE category='DEPENDENCY' = 8
- R-01 row has likelihood=HIGH, impact=HIGH, owner contains 'GME BD'
- D-01 row has from_party='한결원/KFTC', required_by='2026-05-15'
**Depends on:** 1.5-T01

### 1.5-T13 — Implement RAID open-item review workflow: flag for PM review, accept/reject by GME PM  _(50 min)_
**Context:** PM-14 A-14-03: the dev vendor adds RAID items and GME PM reviews/accepts within 2 business days. Workflow: new RAID item created by dev vendor starts with status=OPEN and review_status=PENDING_REVIEW. GME PM (ROLE_PM) can accept (sets review_status=ACCEPTED, reviewed_at, reviewed_by) or reject with a rejection reason (sets review_status=REJECTED). Rejected items can be revised by dev vendor and re-submitted (status back to PENDING_REVIEW). Add columns to raid_log: review_status VARCHAR(20) CHECK(PENDING_REVIEW, ACCEPTED, REJECTED), reviewed_by VARCHAR(120), reviewed_at TIMESTAMPTZ, rejection_reason TEXT. Seeded items from V_PM05 start with review_status=ACCEPTED (pre-approved baseline).
**Steps:** Create migration V_PM06__raid_review_workflow.sql adding review_status, reviewed_by, reviewed_at, rejection_reason columns to raid_log; Update V_PM05 seed (or add to V_PM06) to set review_status=ACCEPTED for all seeded rows; Add RaidLogService.acceptEntry(id, reviewerActorId) and rejectEntry(id, reviewerActorId, rejectionReason) methods; Add resubmit(id, actorId) method - sets review_status=PENDING_REVIEW; only allowed if current status=REJECTED; Add PATCH /pm/raid/{id}/accept and PATCH /pm/raid/{id}/reject endpoints (ROLE_PM only); Add PATCH /pm/raid/{id}/resubmit endpoint (ROLE_PM or ROLE_VENDOR)
**Deliverable:** V_PM06__raid_review_workflow.sql + acceptEntry/rejectEntry/resubmit methods + 3 API endpoints
**Acceptance / logic checks:**
- New RAID entry created via POST starts with review_status=PENDING_REVIEW
- PATCH /pm/raid/{id}/accept sets reviewed_at, reviewed_by, review_status=ACCEPTED
- PATCH /pm/raid/{id}/reject requires rejection_reason in body; sets review_status=REJECTED
- resubmit on an ACCEPTED entry returns 422 with INVALID_REVIEW_TRANSITION
- Seeded R-01..R-08 rows have review_status=ACCEPTED after migration
**Depends on:** 1.5-T04, 1.5-T08

### 1.5-T14 — Implement phase gate tracking: record gate criteria, evidence, and sign-off per PM-14 §5  _(55 min)_
**Context:** PM-14 §5.1-5.4 defines phase acceptance criteria. Each phase has 6-8 gate criteria that must be verified before GME authorises the next phase. Table phase_gate_criterion: id BIGINT PK, phase VARCHAR(10) NOT NULL, criterion_code VARCHAR(30) UNIQUE NOT NULL, description TEXT NOT NULL, evidence_required TEXT, status VARCHAR(20) CHECK(NOT_VERIFIED, VERIFIED, WAIVED), evidence_notes TEXT, verified_by VARCHAR(120), verified_at TIMESTAMPTZ, created_at TIMESTAMPTZ, updated_at TIMESTAMPTZ. Table phase_gate_signoff: id BIGINT PK, phase VARCHAR(10) NOT NULL UNIQUE, signed_off_by VARCHAR(120), signed_off_at TIMESTAMPTZ, notes TEXT, created_at TIMESTAMPTZ. Sign-off can only occur when all criteria for that phase are VERIFIED or WAIVED.
**Steps:** Create db/migrations/V_PM07__phase_gate.sql with both tables; Seed Phase 1-4 criteria from PM-14 §5 (at least 6 rows per phase) with status=NOT_VERIFIED; Implement PhaseGateService.verifyCriterion(criterionCode, evidenceNotes, actorId) setting status=VERIFIED; Implement PhaseGateService.signOffPhase(phase, actorId, notes) - validates all criteria VERIFIED or WAIVED, then inserts phase_gate_signoff row; Add GET /pm/phase-gate?phase=PH1 and POST /pm/phase-gate/{criterionCode}/verify and POST /pm/phase-gate/{phase}/signoff endpoints (ROLE_PM)
**Deliverable:** V_PM07__phase_gate.sql + PhaseGateService + 3 API endpoints
**Acceptance / logic checks:**
- After seed, all criteria have status=NOT_VERIFIED
- verifyCriterion sets status=VERIFIED, verified_by, verified_at
- signOffPhase with one criterion still NOT_VERIFIED returns 422 with PHASE_GATE_CRITERIA_INCOMPLETE
- signOffPhase when all criteria VERIFIED or WAIVED inserts phase_gate_signoff row
- GET /pm/phase-gate?phase=PH1 returns all Phase 1 criteria with current status
- UNIQUE on phase in phase_gate_signoff: signing off same phase twice returns 409
**Depends on:** 1.5-T01

### 1.5-T15 — Implement dependency status tracking: link RAID dependencies to milestone dates and alert on overdue  _(35 min)_
**Context:** PM-14 §7.4 defines 8 external dependencies (D-01..D-08) each with a required_by date. The system must flag when required_by is passed and the dependency is still OPEN: D-01 (한결원 test env) required by 2026-05-15, D-02 (ZP00xx specs) required by 2026-05-15, D-03 (BOK FX format) required by TBD, D-04 (Tax invoice API) before Phase 1 dev start, D-05 (GME Remit readiness) by 2026-08-01, D-06 (SendMN/T-Bank readiness) by 2026-10-01, D-07 (prefunding confirmed) before Phase 4 go-live, D-08 (GME Ops trained) by 2026-09-26. Service method getOverdueDependencies(asOfDate) returns all raid_log rows where category=DEPENDENCY, status=OPEN, required_by < asOfDate. Expose GET /pm/raid/dependencies/overdue?asOf=2026-05-16 endpoint.
**Steps:** Add getOverdueDependencies(LocalDate asOfDate) method to RaidLogService filtering category=DEPENDENCY, status=OPEN, required_by < asOfDate; Add GET /pm/raid/dependencies/overdue endpoint accepting optional asOf query param (defaults to today); Ensure D-01..D-08 seed rows have required_by populated where known (D-03, D-04 may be null); Write logic: if required_by is null, do not include in overdue results; Return results sorted by required_by ASC (most overdue first)
**Deliverable:** RaidLogService.getOverdueDependencies + GET /pm/raid/dependencies/overdue endpoint
**Acceptance / logic checks:**
- GET /pm/raid/dependencies/overdue?asOf=2026-05-16 includes D-01 (required_by=2026-05-15, status=OPEN)
- D-03 (required_by=null) never appears in overdue results
- Dependency with status=CLOSED does not appear in overdue results even if required_by is past
- GET /pm/raid/dependencies/overdue with no asOf param uses today's date
- Results ordered by required_by ASC: D-01 and D-02 (both 2026-05-15) appear before D-05 (2026-08-01)
**Depends on:** 1.5-T04, 1.5-T08, 1.5-T12

### 1.5-T16 — Implement status report dashboard summary endpoint GET /pm/dashboard/summary  _(50 min)_
**Context:** The PM needs a single endpoint that aggregates: current phase (derive from milestone dates and today), open RAID items by category (counts), overdue dependencies count, open action items count, latest submitted status report RAG (overall_rag, scope_rag, schedule_rag, budget_rag), and next milestone (nearest NOT_STARTED or IN_PROGRESS milestone by target_date). Response DTO: DashboardSummaryDto { current_phase, raid_counts: {risks_open, assumptions_open, issues_open, dependencies_open}, overdue_dependencies_count, open_action_items_count, latest_report_rag: {overall, scope, schedule, budget, submitted_at}, next_milestone: {code, name, target_date, status} }. latest_report_rag is null if no submitted report exists.
**Steps:** Create DashboardSummaryDto with all nested fields; Implement DashboardService.getSummary() aggregating data from RaidLogService, SprintCeremonyService, WeeklyStatusReportService, MilestoneService; Compute current_phase by finding the latest phase whose first milestone has status IN_PROGRESS or COMPLETE; Compute open action items count from CeremonyActionItemRepository where status IN (OPEN, IN_PROGRESS); Add GET /pm/dashboard/summary endpoint (ROLE_PM or ROLE_ADMIN)
**Deliverable:** DashboardService + DashboardSummaryDto + GET /pm/dashboard/summary endpoint
**Acceptance / logic checks:**
- Response includes raid_counts with separate counts for each category
- overdue_dependencies_count matches count from /pm/raid/dependencies/overdue
- latest_report_rag is null when no submitted reports exist; populates correctly after submit
- next_milestone returns the milestone with earliest target_date that is NOT COMPLETE
- Endpoint returns 200 even when no ceremonies or action items exist yet (counts = 0)
**Depends on:** 1.5-T04, 1.5-T07, 1.5-T09, 1.5-T11, 1.5-T15

### 1.5-T17 — Unit tests: RaidLogService create, update, review workflow with status and transition validation  _(45 min)_
**Context:** RaidLogService (1.5-T04, 1.5-T13) handles RAID entry lifecycle. Test the business rules: (1) new entry starts PENDING_REVIEW; (2) acceptEntry sets reviewed_at and review_status=ACCEPTED; (3) rejectEntry requires non-blank rejection_reason; (4) resubmit only from REJECTED state; (5) updateEntry throws ReportAlreadySubmittedException for closed entries; (6) findByCategoryAndStatus returns correct subset. Use JUnit 5 + Mockito; mock RaidLogRepository. Test class: RaidLogServiceTest.
**Steps:** Create RaidLogServiceTest in src/test/java under the pm package; Test createEntry: verify saved entity has review_status=PENDING_REVIEW and status=OPEN; Test acceptEntry: mock repository findById returns entry with PENDING_REVIEW; verify reviewed_at set, review_status=ACCEPTED; Test rejectEntry with blank rejection_reason: expect IllegalArgumentException; Test rejectEntry with valid reason: verify review_status=REJECTED, rejection_reason persisted; Test resubmit from ACCEPTED state: expect InvalidReviewTransitionException; Test findByCategoryAndStatus: mock returns 2 RISK+OPEN and 1 ISSUE+OPEN; assert filter returns 2 for RISK+OPEN
**Deliverable:** RaidLogServiceTest with 7+ test methods, all passing
**Acceptance / logic checks:**
- createEntry test: saved entity review_status = PENDING_REVIEW
- acceptEntry test: reviewed_at is not null in saved entity
- rejectEntry with blank reason: IllegalArgumentException thrown
- rejectEntry with valid reason: review_status = REJECTED
- resubmit from ACCEPTED state: InvalidReviewTransitionException thrown
- findByCategoryAndStatus test: only RISK+OPEN rows returned (count = 2)
- All 7 tests pass with zero failures
**Depends on:** 1.5-T04, 1.5-T13

### 1.5-T18 — Unit tests: WeeklyStatusReportService draft/submit lifecycle and duplicate prevention  _(40 min)_
**Context:** WeeklyStatusReportService (1.5-T05) rules: (1) createDraft with non-Monday week_start throws exception; (2) duplicate report for same week+phase throws DuplicateReportException; (3) updateReport on submitted report throws ReportAlreadySubmittedException; (4) submitReport sets submitted_at; (5) submitReport called twice throws exception; (6) RAG value validation (invalid value rejected). Test class: WeeklyStatusReportServiceTest. Use JUnit 5 + Mockito.
**Steps:** Create WeeklyStatusReportServiceTest; Test createDraft with 2026-05-04 (Monday): expect success, submitted_at null; Test createDraft with 2026-05-05 (Tuesday): expect InvalidWeekStartException; Test createDraft duplicate same week+phase when active report exists: expect DuplicateReportException; Test updateReport on submitted report (submitted_at not null): expect ReportAlreadySubmittedException; Test submitReport: verify submitted_at is set, milestone_snapshot is non-null; Test submitReport twice: second call throws ReportAlreadySubmittedException
**Deliverable:** WeeklyStatusReportServiceTest with 7+ test methods, all passing
**Acceptance / logic checks:**
- Monday input creates draft without exception
- Tuesday input throws InvalidWeekStartException
- Duplicate week+phase throws DuplicateReportException
- Update on submitted report throws ReportAlreadySubmittedException
- First submitReport sets submitted_at to non-null value
- Second submitReport call throws ReportAlreadySubmittedException
- All 7 tests pass
**Depends on:** 1.5-T05

### 1.5-T19 — Unit tests: MilestoneService snapshot, variance_days computation, and status transitions  _(35 min)_
**Context:** MilestoneService (1.5-T06) rules: (1) snapshot() returns exactly 16 entries; (2) variance_days = (forecast_date - target_date) in days (positive = delayed, negative = ahead, null if no forecast); (3) updateForecast records updated_by; (4) updateStatus to invalid value rejected; (5) target_date is immutable. Test class: MilestoneServiceTest. Use JUnit 5 + Mockito. Numeric example: Phase3Gate target=2026-10-10, forecast=2026-11-01 -> variance_days=22. Phase3Gate target=2026-10-10, forecast=2026-09-20 -> variance_days=-20.
**Steps:** Create MilestoneServiceTest; Mock MilestoneRepository.findAll() returning 16 dummy milestones; Test snapshot(): assert size=16 and all entries have non-null code; Test variance_days positive: target=2026-10-10, forecast=2026-11-01 -> variance_days=22; Test variance_days negative: target=2026-10-10, forecast=2026-09-20 -> variance_days=-20; Test variance_days null: target=2026-10-10, forecast=null -> variance_days=null; Test updateStatus with invalid string 'BLOCKED': expect IllegalArgumentException
**Deliverable:** MilestoneServiceTest with 7+ test methods, all passing
**Acceptance / logic checks:**
- snapshot() size = 16 when repository returns 16 items
- variance_days = 22 for target 2026-10-10 and forecast 2026-11-01
- variance_days = -20 for target 2026-10-10 and forecast 2026-09-20
- variance_days = null when forecast_date is null
- updateStatus('BLOCKED') throws IllegalArgumentException
- updateForecast saves updated_by field equal to actor param
- All 7 tests pass
**Depends on:** 1.5-T06

### 1.5-T20 — Unit tests: PhaseGateService sign-off rules and criterion verification  _(35 min)_
**Context:** PhaseGateService (1.5-T14) rules: (1) verifyCriterion sets status=VERIFIED, verified_by, verified_at; (2) signOffPhase when all criteria VERIFIED or WAIVED succeeds; (3) signOffPhase when any criterion is NOT_VERIFIED throws PhaseGateCriteriaIncompleteException; (4) signOff twice on same phase throws DuplicateSignOffException; (5) waiveCriterion (status=WAIVED) counts toward completion. Test class: PhaseGateServiceTest. Use JUnit 5 + Mockito.
**Steps:** Create PhaseGateServiceTest; Test verifyCriterion: entity updated with status=VERIFIED, non-null verified_at, correct verified_by; Test signOffPhase happy path: all 4 mock criteria have status VERIFIED -> signoff persisted; Test signOffPhase with one NOT_VERIFIED criterion: throws PhaseGateCriteriaIncompleteException; Test signOffPhase with one WAIVED and rest VERIFIED: succeeds (waived counts); Test duplicate signOff (phase_gate_signoff row already exists): throws DuplicateSignOffException
**Deliverable:** PhaseGateServiceTest with 6+ test methods, all passing
**Acceptance / logic checks:**
- verifyCriterion sets verified_at to non-null
- signOffPhase with all VERIFIED returns success
- signOffPhase with one NOT_VERIFIED throws PhaseGateCriteriaIncompleteException
- signOffPhase with WAIVED criterion alongside VERIFIED others succeeds
- Duplicate signOff throws DuplicateSignOffException
- All 6 tests pass
**Depends on:** 1.5-T14

### 1.5-T21 — Unit tests: DashboardService aggregation and zero-data boundary cases  _(35 min)_
**Context:** DashboardService (1.5-T16) aggregates from multiple services. Test: (1) all counts are 0 and latest_report_rag is null when no data exists; (2) raid_counts.risks_open increments after a RISK+OPEN entry; (3) overdue_dependencies_count uses asOfDate=today; (4) next_milestone returns correct nearest non-COMPLETE milestone; (5) latest_report_rag populated from most-recently-submitted report. Use JUnit 5 + Mockito mocking all dependent services.
**Steps:** Create DashboardServiceTest; Mock all four dependent services to return empty/null; assert all counts=0 and latest_report_rag=null; Mock RaidLogService to return 3 RISK+OPEN, 1 ISSUE+OPEN; assert raid_counts.risks_open=3, issues_open=1; Mock MilestoneService.getAll() with mix of COMPLETE and IN_PROGRESS milestones; assert next_milestone returns earliest non-COMPLETE by target_date; Mock WeeklyStatusReportService to return one submitted report with overall_rag=AMBER; assert latest_report_rag.overall=AMBER; Mock overdue dependencies = 2; assert overdue_dependencies_count=2
**Deliverable:** DashboardServiceTest with 5+ test methods, all passing
**Acceptance / logic checks:**
- Empty-data case: all numeric counts=0, latest_report_rag=null
- raid_counts.risks_open=3 when 3 RISK+OPEN entries mocked
- next_milestone = earliest non-COMPLETE milestone by target_date
- latest_report_rag.overall=AMBER when most-recent submitted report has that value
- overdue_dependencies_count=2 when mocked overdue list has 2 entries
- All 5 tests pass
**Depends on:** 1.5-T16

### 1.5-T22 — Integration test: RAID log API end-to-end with real DB (TestContainers)  _(55 min)_
**Context:** Verify the full RAID log API stack against a real PostgreSQL instance using TestContainers. Test flow: (1) seed DB runs V_PM05 inserting 28 rows; (2) POST /pm/raid creates R-99 with status OPEN, review_status PENDING_REVIEW; (3) GET /pm/raid?category=RISK&status=OPEN returns R-99 plus seeded risks; (4) PATCH /pm/raid/{id}/accept sets review_status=ACCEPTED; (5) PATCH /pm/raid/{id}/reject on already-accepted entry returns 422; (6) GET /pm/raid/dependencies/overdue?asOf=2026-05-16 returns D-01 and D-02. Use Spring Boot Test slice + TestContainers PostgreSQL 15.
**Steps:** Add TestContainers dependency to build.gradle/pom.xml if not already present; Create RaidLogApiIntegrationTest with @SpringBootTest and @Testcontainers; Configure PostgreSQL TestContainer; run Flyway migrations on startup; Implement test method for each of the 6 flow steps above in @Test @Order sequence; Assert HTTP status codes, body fields, and DB state where applicable
**Deliverable:** RaidLogApiIntegrationTest with 6 test methods, all passing against real DB
**Acceptance / logic checks:**
- V_PM05 seed: SELECT COUNT(*) FROM raid_log = 28 after migration
- POST /pm/raid R-99 returns 201 with Location header
- GET /pm/raid?category=RISK&status=OPEN returns at least 9 rows (8 seeded + R-99)
- PATCH accept returns 200 and review_status=ACCEPTED in response
- Second reject on accepted entry returns 422
- GET dependencies/overdue?asOf=2026-05-16 includes D-01 and D-02
**Depends on:** 1.5-T08, 1.5-T12, 1.5-T13, 1.5-T15

### 1.5-T23 — Integration test: Status report and milestone API end-to-end with real DB  _(55 min)_
**Context:** Verify status report and milestone API stack. Test flow: (1) seed runs V_PM04 inserting 16 milestones; (2) POST /pm/status-report creates draft for week 2026-06-01 (Monday), phase PH1; (3) PATCH updates overall_rag=GREEN; (4) POST .../submit sets submitted_at; (5) PATCH on submitted report returns 422; (6) PATCH /pm/milestones/PHASE1_GATE with forecast_date=2026-06-25 sets variance_days=5; (7) GET /pm/dashboard/summary returns open raid counts and next_milestone. Use Spring Boot Test + TestContainers PostgreSQL 15.
**Steps:** Create StatusReportAndMilestoneIntegrationTest with @SpringBootTest and @Testcontainers; Run all Flyway migrations including V_PM04 and V_PM05; Execute 7-step flow in ordered test methods; Assert variance_days = (2026-06-25 minus 2026-06-20) = 5 days in PATCH milestone response; Assert GET /pm/dashboard/summary.raid_counts.risks_open = 8 (from seeded risks); Assert GET /pm/dashboard/summary.next_milestone.code is non-null
**Deliverable:** StatusReportAndMilestoneIntegrationTest with 7 test methods, all passing against real DB
**Acceptance / logic checks:**
- 16 milestones seeded; GET /pm/milestones returns 16 rows
- Status report draft created with submitted_at=null
- PATCH forecast_date=2026-06-25 for PHASE1_GATE (target=2026-06-20) returns variance_days=5
- POST .../submit sets submitted_at to non-null timestamp
- PATCH on submitted report returns 422 with REPORT_ALREADY_SUBMITTED
- GET /pm/dashboard/summary.raid_counts.risks_open = 8
- All 7 tests pass
**Depends on:** 1.5-T09, 1.5-T11, 1.5-T16, 1.5-T22

### 1.5-T24 — Admin portal UI: RAID log screen - list, create, and review workflow  _(55 min)_
**Context:** GME Ops/PM users need a RAID log management screen in the Admin portal (PRD-07). Screen requirements: tabbed view by category (Risks, Assumptions, Issues, Dependencies); each tab shows a filterable table with columns: raid_id, title, likelihood, impact, owner, status, review_status, target_date; Create New button opens a side-panel form; row click opens detail with Accept / Reject buttons (for ROLE_PM users); Rejected items show rejection_reason and Resubmit button. Calls /pm/raid API (1.5-T08, 1.5-T13). Use the existing Admin portal frontend framework (React or equivalent per PRD-07/UX-11).
**Steps:** Create RaidLogPage component with four category tabs (Risks, Assumptions, Issues, Dependencies); Implement RaidLogTable component rendering raid_id, title, likelihood, impact, owner, status, review_status, target_date columns with status badge colour (OPEN=yellow, CLOSED=grey, MITIGATED=green); Implement CreateRaidEntryForm side-panel with fields matching POST /pm/raid DTO; submit calls POST /pm/raid; Implement RaidEntryDetail drawer with Accept / Reject buttons that call PATCH .../accept or .../reject; show rejection_reason field on reject click; Implement Resubmit button visible only when review_status=REJECTED; calls PATCH .../resubmit; Add filter controls: status dropdown and free-text search on title
**Deliverable:** RaidLogPage, RaidLogTable, CreateRaidEntryForm, RaidEntryDetail React components
**Acceptance / logic checks:**
- Risks tab loads and shows only rows with category=RISK from GET /pm/raid?category=RISK
- Create form submits POST /pm/raid and row appears in table after success
- Accept button calls PATCH /pm/raid/{id}/accept; review_status badge updates to ACCEPTED without page reload
- Reject without rejection_reason shows inline validation error before submitting
- Resubmit button only visible when review_status=REJECTED
- Filter by status=OPEN hides CLOSED rows
**Depends on:** 1.5-T08, 1.5-T13

### 1.5-T25 — Admin portal UI: Weekly status report screen - create, edit, submit, and history list  _(55 min)_
**Context:** Admin portal status report screen per PRD-07. Requirements: list view showing submitted and draft reports sorted by reporting_week_start DESC with RAG traffic-light badges; New Report button auto-fills reporting_week_start to nearest past Monday; form fields: phase dropdown (PH1-PH4), RAG dropdowns (RED/AMBER/GREEN) for overall, scope, schedule, budget, and text areas for key_achievements, risks_escalated, blockers, next_week_plan; Save Draft and Submit buttons; submitted reports are read-only (no edit form rendered); milestone snapshot embedded in submitted report detail view. Calls /pm/status-report API (1.5-T09).
**Steps:** Create StatusReportListPage with table of reports, RAG badge column, and New Report button; Implement StatusReportForm component with all fields; on load compute nearest past Monday for reporting_week_start; Submit calls POST /pm/status-report; Save Draft uses same POST then PATCH on updates; Submit Report button calls POST .../submit; disable all form fields after submit; Implement StatusReportDetail component for submitted reports showing milestone_snapshot as read-only table; Add toast notification on successful submit: Report submitted for week of {reporting_week_start}
**Deliverable:** StatusReportListPage, StatusReportForm, StatusReportDetail React components
**Acceptance / logic checks:**
- New Report auto-fills reporting_week_start to nearest past Monday
- Save Draft persists without submitted_at; form remains editable
- Submit disables all form fields and shows submitted_at timestamp
- Submitted report in list shows RAG badges with correct colour (RED=red, AMBER=amber, GREEN=green)
- Milestone snapshot table in detail view shows code, name, target_date, forecast_date, status
- Toast shown on successful submit with correct week date
**Depends on:** 1.5-T09, 1.5-T11

### 1.5-T26 — Admin portal UI: Sprint ceremony log screen and open action items widget  _(55 min)_
**Context:** Admin portal ceremony log screen per PRD-07. Requirements: ceremony log list filtered by sprint number with a dropdown (Sprint 1..N); Log Ceremony button opens form (ceremony_type, sprint_number, ceremony_date, facilitator, attendees textarea, summary textarea, phase); ceremony row expandable to show action items; Add Action Item button per ceremony row; action item row shows description, owner, due_date, status badge; inline status update dropdown (OPEN/IN_PROGRESS/DONE/CANCELLED); Open Action Items widget on sidebar showing all open/in-progress items across sprints with overdue highlight (due_date < today). Calls /pm/ceremony API (1.5-T10).
**Steps:** Create SprintCeremonyPage with sprint filter dropdown and ceremony list; Implement LogCeremonyForm component with all required fields and ceremony_type select (PLANNING/REVIEW/RETROSPECTIVE/STANDUP/OTHER); Implement CeremonyRow expandable component showing nested action items table; Add Add Action Item inline form per ceremony (description, owner, due_date); Implement status inline dropdown per action item calling PATCH ceremony/action-items/{id}; Create OpenActionItemsWidget component calling GET /pm/ceremony/action-items/open; highlight rows where due_date < today in amber/red
**Deliverable:** SprintCeremonyPage, LogCeremonyForm, CeremonyRow, OpenActionItemsWidget React components
**Acceptance / logic checks:**
- Sprint filter dropdown filters ceremonies to selected sprint only
- Log Ceremony form with invalid ceremony_type fails client-side validation before submit
- Expanding ceremony row loads and shows its action items
- Status dropdown on action item sends PATCH request and updates badge without page reload
- Invalid transition (DONE->OPEN) shows error toast from API 422 response
- OpenActionItemsWidget highlights items where due_date < today
**Depends on:** 1.5-T10

### 1.5-T27 — Admin portal UI: PM dashboard summary screen with RAG, RAID counts, and next milestone  _(55 min)_
**Context:** Admin portal PM dashboard screen aggregating GET /pm/dashboard/summary (1.5-T16). Screen layout: top row - 4 RAG traffic-light cards (overall, scope, schedule, budget) from latest submitted report (show grey if no report submitted); middle row - RAID summary cards (Risks Open N, Assumptions Open N, Issues Open N, Dependencies Open N), overdue dependencies count card (red badge if > 0), open action items card; bottom row - next milestone card showing code, name, target_date, status chip, and variance_days (show in red if variance_days > 0); a milestones timeline or table below. Calls /pm/dashboard/summary and /pm/milestones.
**Steps:** Create PMDashboardPage calling GET /pm/dashboard/summary on mount; Implement RagCard component rendering coloured card (RED=red bg, AMBER=amber bg, GREEN=green bg, null=grey bg); Implement RaidCountsRow with 4 count cards and overdue badge; Implement NextMilestoneCard with variance_days display: positive = red text with + prefix, negative = green text, 0 or null = neutral; Implement MilestoneTable calling GET /pm/milestones showing all 16 milestones with status chips and variance_days column; Auto-refresh GET /pm/dashboard/summary every 60 seconds
**Deliverable:** PMDashboardPage, RagCard, RaidCountsRow, NextMilestoneCard, MilestoneTable React components
**Acceptance / logic checks:**
- RAG cards show grey when latest_report_rag is null (no submitted report)
- overdue_dependencies_count card shows red badge when count > 0
- NextMilestoneCard variance_days=22 displayed in red with + prefix
- NextMilestoneCard variance_days=-5 displayed in green
- MilestoneTable shows 16 rows after seeding
- Dashboard refreshes call to /pm/dashboard/summary every 60 seconds (verified via network inspector)
**Depends on:** 1.5-T16, 1.5-T25

### 1.5-T28 — Scheduled job: weekly RAID review reminder email to GME PM for PENDING_REVIEW items  _(50 min)_
**Context:** PM-14 A-14-03: GME PM must review new RAID items within 2 business days. A scheduled job runs every Monday at 08:00 KST and sends a summary email to the configured GME PM email (app.pm.review-email in application.properties) listing all raid_log rows with review_status=PENDING_REVIEW and created_at < (now - 2 business days). Email subject: '[GMEPay+] RAID items awaiting your review'. Body includes: table of pending items (raid_id, category, title, created_by, created_at). Use Spring @Scheduled(cron='0 0 8 * * MON', zone='Asia/Seoul'). Use JavaMailSender or equivalent. If zero items pending, do not send email.
**Steps:** Create RaidReviewReminderJob class with @Component and @Scheduled annotation targeting Monday 08:00 KST; Implement logic: query RaidLogService.findPendingReviewItems(olderThanBusinessDays=2) returning items with review_status=PENDING_REVIEW and created_at before cutoff; Calculate business-day cutoff (skip Saturday and Sunday) for 2 business days ago from current date; Build email body as plain-text table; skip send if list is empty; Configure app.pm.review-email in application.properties (default: placeholder); Unit test: mock date as Wednesday 2026-06-03; cutoff should be Monday 2026-06-01 (2 business days back)
**Deliverable:** RaidReviewReminderJob class + unit test for business-day cutoff logic
**Acceptance / logic checks:**
- Job cron annotation = '0 0 8 * * MON' with zone='Asia/Seoul'
- findPendingReviewItems returns items with review_status=PENDING_REVIEW and created_at < cutoff
- Business-day cutoff test: Wednesday 2026-06-03 minus 2 business days = Monday 2026-06-01
- Business-day cutoff test: Monday 2026-06-08 minus 2 business days = Thursday 2026-06-04 (skipping weekend from Friday)
- If pending list is empty, JavaMailSender.send is NOT called (verified via mock assert never)
- If pending list has 2 items, email is sent exactly once with both items in body
**Depends on:** 1.5-T04, 1.5-T13

### 1.5-T29 — Scheduled job: milestone overdue alert email when target_date passed and status not COMPLETE  _(40 min)_
**Context:** PM-14 §3.1 milestones must be tracked. A daily scheduled job at 07:00 KST checks all milestones: if target_date < today AND status NOT IN (COMPLETE, WAIVED) send an overdue alert email to app.pm.review-email. Use @Scheduled(cron='0 0 7 * * *', zone='Asia/Seoul'). Email subject: '[GMEPay+] Overdue milestones: {N} milestone(s) require attention'. Body: table with code, name, phase, target_date, forecast_date, variance_days, status. If all milestones are on track (none overdue), do not send. Example: if today is 2026-05-10 and KFTC_TEST_ENV milestone has target_date=2026-05-15 and status=NOT_STARTED, it is NOT overdue yet. If target_date=2026-05-09 and status=IN_PROGRESS, it IS overdue.
**Steps:** Create MilestoneOverdueAlertJob with @Component and @Scheduled(cron='0 0 7 * * *', zone='Asia/Seoul'); Implement logic: MilestoneService.getOverdueMilestones(asOfDate) returning milestones where target_date < asOfDate AND status NOT IN (COMPLETE, WAIVED); Build email with overdue milestone table; skip if empty; Unit test: mock today=2026-05-10; milestone with target_date=2026-05-09, status=IN_PROGRESS -> overdue; target_date=2026-05-11, status=IN_PROGRESS -> not overdue; target_date=2026-05-09, status=COMPLETE -> not overdue
**Deliverable:** MilestoneOverdueAlertJob class + MilestoneService.getOverdueMilestones + unit test
**Acceptance / logic checks:**
- Cron = '0 0 7 * * *' with zone='Asia/Seoul'
- getOverdueMilestones(2026-05-10): milestone target 2026-05-09 + IN_PROGRESS -> included
- getOverdueMilestones(2026-05-10): milestone target 2026-05-11 + IN_PROGRESS -> excluded (not past yet)
- getOverdueMilestones(2026-05-10): milestone target 2026-05-09 + COMPLETE -> excluded
- Email not sent when overdue list is empty
- Email subject contains correct overdue count when items present
**Depends on:** 1.5-T06, 1.5-T28

### 1.5-T30 — Docs: PM tooling operational runbook for RAID upkeep, status reporting, and sprint ceremonies  _(40 min)_
**Context:** PM-14 A-14-03 requires the dev vendor to maintain the RAID log and notify GME PM at weekly status meetings. A runbook document must describe: (1) how to add a new RAID item via API or Admin portal; (2) the review/accept/reject workflow (PENDING_REVIEW -> ACCEPTED/REJECTED -> resubmit); (3) how to create and submit a weekly status report (Monday deadline; RAG definitions: GREEN = on track, AMBER = at risk but manageable, RED = off track / blocked); (4) how to log sprint ceremonies and manage action items; (5) how to update milestone forecast dates; (6) how to run /pm/dashboard/summary to get current project health; (7) reminder job configuration (app.pm.review-email). Write as docs/pm-runbook.md.
**Steps:** Create docs/pm-runbook.md with sections matching the 7 areas listed above; Section 1: RAID entry creation - include POST /pm/raid example request with category=RISK, likelihood=HIGH, impact=HIGH; Section 2: Review workflow diagram (text-based: PENDING_REVIEW -> ACCEPTED or REJECTED -> resubmit -> PENDING_REVIEW); Section 3: Status report process - include reporting_week_start must be Monday note; RAG colour definitions; Section 4: Sprint ceremony logging - ceremony types and action item lifecycle; Section 5: Milestone update - PATCH /pm/milestones/{code} example with forecast_date; Section 6: Dashboard endpoint and what each field means; Section 7: Email config property name
**Deliverable:** docs/pm-runbook.md covering all 7 operational areas
**Acceptance / logic checks:**
- Section 1 includes a concrete POST /pm/raid JSON example with raid_id, category, likelihood fields
- Section 2 includes the full review status state machine in text or diagram form
- Section 3 states reporting_week_start must be a Monday and defines RED/AMBER/GREEN
- Section 5 includes PATCH /pm/milestones/{code} example showing variance_days calculation
- Section 7 names the app.pm.review-email property and its default placeholder value
- Document renders without broken markdown (no unclosed code blocks or headers)
**Depends on:** 1.5-T08, 1.5-T09, 1.5-T10, 1.5-T11, 1.5-T16


## WBS 1.6 — As-built documentation & handover
### 1.6-T01 — Audit all 16 spec documents against implemented code and record delta log  _(55 min)_
**Context:** WBS 1.6 produces as-built documentation. The GMEPay+ spec package comprises 16 documents (DOC-00 through REF-15). The as-built pass must compare each document to the actual implementation and record every deviation. Deviations include: fields renamed in implementation, endpoints removed or added vs API-05, schema differences vs DAT-03, batch job window changes vs OPS-13 SCH-06, and any open items (OI-01 through OI-OPS-04) that were resolved during build. Output is a structured delta log (CSV or Markdown table) with columns: doc_code, section, spec_statement, as_built_status (MATCH / CHANGED / ADDED / REMOVED), deviation_note.
**Steps:** Clone the final production-deployed codebase branch (tag: release/1.0 or equivalent); For each of the 16 docs listed in DOC-00 Section 3 (BRD-01 through REF-15), run a section-by-section comparison against the implementation: API endpoints vs OpenAPI export, DB schema vs Flyway migration files, batch jobs vs scheduler config, RBAC roles vs Admin System config; Record every deviation in delta_log.csv with columns: doc_code, section, spec_statement, as_built_status, deviation_note; Flag open items (OI-01, OI-02, OI-03, OI-OPS-01 through OI-OPS-04) as RESOLVED, DEFERRED, or STILL_OPEN with resolution details; Save delta_log.csv to docs/as-built/ in the repository
**Deliverable:** delta_log.csv in docs/as-built/ covering all 16 documents with per-section deviation status
**Acceptance / logic checks:**
- File contains at least one row per document (16 doc_code values present)
- Every CHANGED or REMOVED row has a non-empty deviation_note explaining what was built instead
- All 7 open items from DOC-00 §9 and OPS-13 §13.2 appear with an explicit RESOLVED, DEFERRED, or STILL_OPEN status
- No row has as_built_status other than MATCH, CHANGED, ADDED, or REMOVED
- File is valid CSV parseable without errors

### 1.6-T02 — Update DOC-00 master index to reflect as-built document versions and open item resolutions  _(45 min)_
**Context:** DOC-00 (00_Documentation_Index_and_Handover.md) is the master index and handover guide. After build, it must reflect: (a) actual document versions and statuses (changing 'For Development Handover' to 'Approved' where signed off), (b) resolution of open items OI-01 (customer approval method), OI-02 (tax invoice API), OI-03 (BOK reporting format), (c) any documents added or removed during development, (d) updated reading order if architecture changed. Version is incremented per DOC-00 §6.3 rules: major if scope/architecture changed, minor if clarifications only. META header fields: version, date, status.
**Steps:** Open 00_Documentation_Index_and_Handover.md from the repository; Update the META header: version to 1.x or 2.0 as appropriate, date to today (2026-06-05), status to Approved for any signed-off documents; Update Section 3 document map: revise Primary Audience column if team changed; add any new documents created during build; Update Section 9 open items: for each of OI-01, OI-02, OI-03 mark RESOLVED with resolution text or DEFERRED to Phase 2 with justification; Add a change note at the bottom of the document per DOC-00 §6.4 format: date, version, author, summary of changes
**Deliverable:** Updated 00_Documentation_Index_and_Handover.md with current version, resolved open items, and accurate document status column
**Acceptance / logic checks:**
- META header version and date fields are updated (not still v1.0 / June 2026 placeholder)
- Every document in Section 3 table has a status that reflects its actual signoff state (not all 'For Development Handover' if some are approved)
- OI-01, OI-02, OI-03 each have an explicit resolution or deferral note in Section 9
- A change note entry appears at the bottom of the file with date >= 2026-06-05
- No broken cross-references to document file names (all 16 filenames in Section 3 match files actually present in the repository)
**Depends on:** 1.6-T01

### 1.6-T03 — Update SAD-02 architecture document to reflect as-built component topology  _(50 min)_
**Context:** SAD-02 (02_System_Architecture_Document.md) describes the context architecture, tech stack, deployment topology, and sequence flows. As-built changes commonly include: actual cloud provider chosen (OPS-13 A-OPS-01 assumed cloud-agnostic), actual message queue product (OPS-13 A-OPS-04), actual secrets manager (OPS-13 A-OPS-05), actual observability stack, and any sequence flow changes discovered during implementation. The four network zones (Public DMZ, API Zone, Worker Zone, Data Zone, Management Zone) from OPS-13 §3.2 must match the actual deployment. Tech stack: Java + PostgreSQL (per memory). Any architecture decisions that deviate from SAD-02 assumptions must be documented.
**Steps:** Compare SAD-02 architecture diagram and component list against the actual deployed infrastructure (Terraform/IaC files, Kubernetes manifests, or equivalent); Update the tech stack section: record actual Java version, Spring Boot version, PostgreSQL version, Redis version, message queue product name and version; Update network topology diagram to reflect actual zones and any additions; Update sequence flow diagrams (MPM payment, CPM payment, rate quote, prefunding deduction) if the implementation differs from spec; Add an 'As-Built Notes' subsection at the end of each major section noting deviations from the original spec; Increment document version in META header and add a change note
**Deliverable:** Updated 02_System_Architecture_Document.md with as-built component names, versions, and any topology deviations noted per section
**Acceptance / logic checks:**
- Tech stack table lists concrete versions (e.g. Java 21, Spring Boot 3.x, PostgreSQL 16) not placeholder text
- Each of the 5 network zones from OPS-13 §3.2 appears in the topology with actual service names
- At least one As-Built Notes subsection exists (even if stating 'no deviation from spec')
- Document version in META header is incremented and change note is appended
- All sequence diagrams reference actual service names matching the deployed codebase package structure
**Depends on:** 1.6-T01

### 1.6-T04 — Update DAT-03 data model to reflect as-built schema (tables, columns, indexes)  _(55 min)_
**Context:** DAT-03 (03_Data_Model_and_ERD.md) is the canonical data model. During build, columns may have been renamed, types changed, new tables added, or indexes added beyond the spec. The as-built version must match the actual Flyway/Liquibase migration files in db/migrations/. Key tables to verify: transactions, prefunding_ledger, treasury_rates, batch_job_runs, audit_log, schemes, partners, rules. Money columns must show NUMERIC(x,y) precision and currency_code pairing. The treasury_rates table (per 4.1-T10) has columns id, currency_code VARCHAR(3), rate NUMERIC(20,8), source VARCHAR(10), effective_date DATE, created_at TIMESTAMP.
**Steps:** Export the actual DB schema from the staging or production database using pg_dump --schema-only or equivalent; Diff every table definition against DAT-03: check column names, types, constraints, and NOT NULL flags; Update the DAT-03 entity descriptions and table-by-table data dictionary to match the actual schema; Update the ERD diagram (text-based Mermaid or ASCII) to include any new tables or relationships; Verify and update the money-handling section: confirm KRW scale=0 and USD scale=2 are enforced in NUMERIC column definitions; Increment document version and add change note
**Deliverable:** Updated 03_Data_Model_and_ERD.md with as-built table definitions, updated ERD, and accurate data dictionary
**Acceptance / logic checks:**
- Every table present in the production schema appears in the data dictionary (no undocumented tables)
- treasury_rates table documents columns: currency_code VARCHAR(3), rate NUMERIC(20,8), source VARCHAR(10) CHECK IN ('LIVE','MANUAL'), effective_date DATE
- transactions table entry documents the 8-step event trail fields (event_type, occurred_at) and the terminal status column (APPROVED, FAILED, UNCERTAIN, CANCELLED)
- KRW money columns show scale=0 and USD columns show scale=2 in the type column
- ERD shows the relationship between rules, partners, schemes, and transactions correctly
**Depends on:** 1.6-T01

### 1.6-T05 — Update RATE-04 rate engine spec to reflect as-built implementation and add worked example cross-checks  _(50 min)_
**Context:** RATE-04 (04_Rate_Engine_and_Settlement_Spec.md) is the foundational rate engine document. It defines the 5-step RECEIVE-mode sequence: (1) payout_usd_cost = target_payout / cost_rate_pay, (2) collection_usd = payout_usd_cost / (1 - m_a - m_b), (3) margins, (4) send_amount = collection_usd * cost_rate_coll, (5) collection_amount = send_amount + service_charge. Pool identity invariant: collection_usd - collection_margin_usd - payout_margin_usd == payout_usd_cost (tolerance 0.01 USD). As-built updates: confirm the same-currency short-circuit implementation (collection_amount = target_payout + service_charge), confirm Partner B deviation tolerance default (1.0%), confirm rate quote TTL values (60s aggregator-bound, 300s otherwise). Record any rounding-point decisions made during implementation.
**Steps:** Review the rate engine implementation (RateEngineService and related classes) against RATE-04 step-by-step; Document any rounding points added or changed: which step truncates/rounds, what scale (8 dp for rates per the existing spec), how BigDecimal HALF_UP is applied; Confirm the same-currency short-circuit: if collection_ccy = settle_a_ccy = settle_b_ccy = payout_ccy then step skips USD pool; collection_amount = target_payout + service_charge; verify this path is documented; Add an as-built worked example for GME Remit (domestic KRW/KRW): target_payout=50000 KRW, service_charge=500 KRW, expected collection_amount=50500 KRW; Add or verify the worked example for SendMN (cross-border): target_payout=50000 KRW, cost_rate_pay=1380, m_a=0.01, m_b=0.01, service_charge=0.36 USD, expected collection_usd=36.9714, send_amount=36.9714, collection_amount=37.3314; Increment document version and add change note
**Deliverable:** Updated 04_Rate_Engine_and_Settlement_Spec.md with as-built rounding rules, confirmed short-circuit behaviour, and two verified worked examples (domestic + cross-border)
**Acceptance / logic checks:**
- The 5-step sequence in the document matches the implementation code exactly (no step reordered or missing)
- Domestic example: target_payout=50000 KRW, service_charge=500 KRW, collection_amount=50500 KRW documented and annotated as same-currency short-circuit
- Cross-border example: pool identity check (36.9714 - 0.36971 - 0.36971 = 36.2319 within 0.01 USD) is shown as a verification step
- Partner B deviation tolerance default of 1.0% is stated explicitly
- Rate quote TTL section states 60s for aggregator-bound and 300s otherwise, both configurable in range 60-1800s
**Depends on:** 1.6-T01

### 1.6-T06 — Update API-05 spec to reflect as-built endpoint contracts and export final OpenAPI file  _(55 min)_
**Context:** API-05 (05_Partner_API_Specification.md) is the normative northbound API contract. As-built updates include: confirming actual base URLs (https://api-sandbox.gmepayplus.com and https://api.gmepayplus.com are placeholders per API-05 §2.1 — replace with actual provisioned hostnames), verifying all endpoint paths and HTTP methods match the implementation, confirming error code list is complete, verifying idempotency-key behaviour (24-hour window, 422 on body mismatch), and confirming webhook event payload field names. The HMAC-SHA256 signing scheme from API-05 §3.2 must be documented with the actual canonical string format. Export the live OpenAPI 3.x JSON spec from the running application.
**Steps:** Export the OpenAPI 3.x spec from the running staging application (e.g. GET /v3/api-docs or Springdoc endpoint); Compare every path, method, request body schema, and response schema in the export against API-05; Update API-05: replace placeholder base URLs with actual DNS names, correct any endpoint or field name discrepancies, add any endpoints implemented but not originally specced; Confirm and document the HMAC canonical string format with a concrete example: method + newline + path + newline + UTC-timestamp + newline + body-sha256-hex; Save the exported OpenAPI JSON as docs/api/openapi-v1.json in the repository; Increment API-05 document version and add change note
**Deliverable:** Updated 05_Partner_API_Specification.md with actual base URLs and verified endpoint contracts; openapi-v1.json in docs/api/
**Acceptance / logic checks:**
- openapi-v1.json is valid JSON and passes openapi-spec-validator (or equivalent) with zero errors
- Every path in openapi-v1.json has a corresponding section in API-05 and vice versa (no undocumented endpoints, no documented endpoints missing from the spec)
- Base URL fields no longer contain the placeholder text 'Assumption: Exact hostnames are placeholders'
- HMAC canonical string example in API-05 §3.2 shows actual field order and a worked 5-line example
- Error code table in API-05 includes PARTNER_B_QUOTE_DEVIATION and PARTNER_B_QUOTE_UNAVAILABLE with correct HTTP status codes
**Depends on:** 1.6-T01

### 1.6-T07 — Update SCH-06 ZeroPay integration spec to reflect as-built batch file implementations  _(55 min)_
**Context:** SCH-06 (06_QR_Scheme_Integration_Spec_ZeroPay.md) covers the southbound ZeroPay SFTP batch interface: ZP00xx file types (ZP0011, ZP0012, ZP0021, ZP0022, ZP0041, ZP0043, ZP0045, ZP0047, ZP0051, ZP0053, ZP0055, ZP0061, ZP0062, ZP0063, ZP0064, ZP0065, ZP0066), SFTP paths, batch window KST times, and reconciliation logic. As-built updates: confirm actual SFTP path structure (OPS-13 §6.5: /gmepay/batch/<direction>/<file-id>/YYYY-MM-DD/<filename>), confirm file archival path in object storage (/gmepay/batch/...), record any field mapping differences discovered against 한결원 actual format, note OI-OPS-01 and OI-OPS-02 resolution status (한결원 test environment availability, production SFTP host).
**Steps:** Retrieve the as-built SFTP file generation code for each ZP00xx type from the batch-worker service; Compare each file's field layout and encoding against SCH-06 file specifications; record any deviations; Update SCH-06 to reflect any field name changes, record layout changes, or encoding differences confirmed with 한결원 during integration testing; Update the SFTP path and archival path sections to match OPS-13 §6.5 conventions; Update OI-OPS-01 and OI-OPS-02 open items with actual resolution: confirm 한결원 test environment date accessed and production SFTP host/port confirmed; Increment document version and add change note
**Deliverable:** Updated 06_QR_Scheme_Integration_Spec_ZeroPay.md with as-built file layouts, confirmed SFTP paths, and resolved open items OI-OPS-01 and OI-OPS-02
**Acceptance / logic checks:**
- SFTP path pattern in the document matches OPS-13 §6.5 pattern: /gmepay/batch/<direction>/<file-id>/YYYY-MM-DD/<filename>
- OI-OPS-01 (한결원 test environment) has a resolution date and access confirmation note
- OI-OPS-02 (production SFTP host) is either resolved with actual host/port or explicitly flagged as still OPEN with go-live blocker note
- All 18 ZP00xx file types listed in the document have a confirmed field layout with no TBD markers remaining
- Reconciliation section confirms ZP0062/ZP0064 vs internal aggregation tolerance and the exception handling path
**Depends on:** 1.6-T01

### 1.6-T08 — Update SEC-09 security spec to reflect as-built auth, secrets, and audit implementation  _(50 min)_
**Context:** SEC-09 (09_Security_and_Compliance_Spec.md) covers HMAC-SHA256 auth, secrets inventory, RBAC roles, BOK FX reporting (FX1014, FX1015), audit log fields, and data privacy. As-built updates: confirm actual bcrypt cost factor for API secret hashing (spec: cost >= 12), confirm RBAC roles as implemented in the Admin System, confirm audit log fields captured per transaction (actor, timestamp, previous_value, new_value), confirm secrets path convention (gmepay/<env>/<service>/<secret-name> per OPS-13 §4.7), and record OI-03 BOK reporting status. SEC-09 §7 incident response plan should be confirmed against the actual on-call runbook.
**Steps:** Check the authentication implementation: verify bcrypt cost factor >= 12 in the Partner credential hash; document the actual cost factor used; Review RBAC role definitions in Admin System against SEC-09 RBAC table; update any role name or permission changes; Verify audit log schema: confirm actor, timestamp, previous_value, new_value, entity_type, entity_id fields are present on config change events; Update BOK reporting section with OI-03 resolution or deferral note; if FEATURE_BOK_REPORTING flag is still false in Phase 1, state this explicitly; Confirm secrets path convention matches gmepay/<env>/<service>/<secret-name> and document one concrete example per service; Increment document version and add change note
**Deliverable:** Updated 09_Security_and_Compliance_Spec.md with confirmed bcrypt cost, as-built RBAC roles, audit field list, and OI-03 BOK status
**Acceptance / logic checks:**
- Bcrypt cost factor is documented as a concrete integer >= 12 (not 'cost >= 12' placeholder)
- RBAC table lists actual role names matching the Admin System implementation (e.g. ADMIN, OPS, FINANCE, READ_ONLY or equivalent)
- Audit log field list includes at minimum: actor_id, actor_role, entity_type, entity_id, action, previous_value, new_value, occurred_at
- Secrets path section contains at least one real example: gmepay/prod/batch-worker/zeropay-sftp-key
- OI-03 BOK reporting section explicitly states Phase 1 status: flag FEATURE_BOK_REPORTING=false, target for Phase 2 resolution
**Depends on:** 1.6-T01

### 1.6-T09 — Update OPS-13 runbook to reflect as-built environment names, service commands, and config matrix  _(55 min)_
**Context:** OPS-13 (13_DevOps_Deployment_and_Runbook.md) is the operational runbook. As-built updates target three areas: (1) §2 Environment Strategy - confirm actual environment tier names (dev/int/staging/prod) and the 한결원 test environment access date; (2) §4 CI/CD - confirm actual pipeline tool (GitHub Actions, Jenkins, etc.), branch strategy as built, and image tagging convention; (3) §5.3 Environment Configuration Matrix - replace placeholder values with actual config (ZeroPay SFTP host, DB pool sizes, rate quote TTL, webhook retry max). The batch job schedule table (§6.2, jobs JOB-ZP-01 through JOB-ZP-13) must reflect any window adjustments discovered during 한결원 integration. Runbook commands (e.g. kubectl rollout restart) must match actual cluster namespace and deployment names.
**Steps:** Confirm and update the §2 environment tiers table with actual DNS names, access dates, and ZeroPay sandbox confirmation; Update §4.1 pipeline stages table with actual CI/CD tool name and stage duration baselines from the first production build; Replace all placeholder values in §5.3 Environment Configuration Matrix with measured actuals: DB pool sizes, rate quote TTL per environment, webhook retry max; Update §6.2 batch job schedule table if any KST window was adjusted during 한결원 testing; Update all runbook CLI commands with actual Kubernetes namespace (e.g. gmepay-prod) and deployment names (e.g. hub-api, batch-worker, admin-svc); Update open items OI-OPS-03 (scaling thresholds) and OI-OPS-04 (SendMN onboarding) with Phase 1 outcomes; Increment document version and add change note
**Deliverable:** Updated 13_DevOps_Deployment_and_Runbook.md with confirmed environment config, CI/CD tool name, actual kubectl deployment names, and resolved/updated open items
**Acceptance / logic checks:**
- §5.3 config matrix has no placeholder or 'TBD' values for any prod-column entry
- At least one runbook CLI command (e.g. §8.4 SFTP key rotation or §8.7 batch reprocess) contains the actual Kubernetes namespace and deployment name
- Batch job schedule in §6.2 has confirmed KST window times (not 'per KST production windows' placeholder)
- OI-OPS-01 entry states the actual date 한결원 test environment became accessible
- §4.1 pipeline stages table names the actual CI/CD tool (e.g. GitHub Actions) in a note or tool column
**Depends on:** 1.6-T01, 1.6-T03

### 1.6-T10 — Update PM-14 project plan with as-built phase actuals, milestone dates, and RAID log close-outs  _(45 min)_
**Context:** PM-14 (14_Project_Plan_RAID_RACI.md) contains the phased delivery plan, milestone table, RAID log, and RACI. For WBS 1.6 (as-built docs), PM-14 must be updated with: (a) actual Phase 1 gate date vs planned Apr 10 - Jun 20 2026, (b) Phase 2 ZeroPay integration milestone actuals (한결원 test environment date, end-to-end test date), (c) RAID log: close out resolved risks and issues, record actual impacts, (d) Phase 3 go-live checklist status (Oct 10 2026 target), (e) RACI: confirm assignees are accurate. Phase 4 (T-Bank/SendMN overseas onboarding Oct 1 - Dec 10 2026) open items should be added if not already tracked.
**Steps:** Update §3 milestone table: fill Actual Date column for all completed milestones; mark Phase 1 gate as achieved with date; Update RAID log: close out risks and issues that were resolved during Phase 1/2 build; add new risks identified during implementation with probability, impact, and owner; Update RACI matrix: confirm all role assignments match the actual delivery team contacts; Add a Phase 1 retrospective note (1-2 sentences per major deliverable) noting any schedule variance; Confirm the Phase 3 go-live checklist in §5 is current and reflects items completed in OPS-13 §12.1; Increment document version and add change note
**Deliverable:** Updated 14_Project_Plan_RAID_RACI.md with actual milestone dates, closed RAID items, and Phase 1 retrospective notes
**Acceptance / logic checks:**
- Milestone table contains an Actual Date value (not blank) for every milestone with planned date <= 2026-06-05
- At least 3 RAID log rows have status CLOSED with a resolution description
- RACI matrix has real names or role designations (not Placeholder) for all rows with delivery team responsibility
- Phase 3 go-live checklist §5 has no items that are demonstrably incomplete based on Phase 1 scope
- Document version in META header is incremented and a change note is appended
**Depends on:** 1.6-T01

### 1.6-T11 — Update REF-15 glossary to add terms introduced during build and flag any redefined terms  _(40 min)_
**Context:** REF-15 (15_Glossary_and_Data_Dictionary.md) is the canonical terminology reference. During build, the implementation may have introduced new terms (class names, service names, error codes, config flag names) or used existing terms differently. New terms to check: job_run_id (from batch idempotency, OPS-13 §6.3), FEATURE_* flag names (OPS-13 §5.2: FEATURE_LIVE_FX_FEED, FEATURE_PARTNER_REFUND_API, FEATURE_OUTBOUND_PAYMENTS, FEATURE_BOK_REPORTING, FEATURE_MULTI_SCHEME_ROUTING), RateSource enum values (IDENTITY, LIVE, MANUAL, PARTNER from the rate engine), error codes (PARTNER_B_QUOTE_DEVIATION, PARTNER_B_QUOTE_UNAVAILABLE, RATE_UNAVAILABLE, SCHEME_UNAVAILABLE). Any term used in API-05 error responses or in the batch runbook that does not appear in REF-15 must be added.
**Steps:** Search the codebase and updated API-05 / OPS-13 documents for all domain terms, error codes, and config flag names not currently in REF-15; Add each missing term with: name, definition (1-2 sentences), document cross-reference, and type (error_code / enum_value / config_flag / entity / process); Review existing entries: if any definition was changed during build (e.g. a field name was renamed), update the definition and add a deprecated alias row; Add the 5 FEATURE_* flag names with their Phase 1 default values (all false) and a brief description of what each enables; Add all error codes present in the API-05 error table to REF-15 if not already listed; Increment document version and add change note
**Deliverable:** Updated 15_Glossary_and_Data_Dictionary.md with all new terms, error codes, and feature flags from the as-built system
**Acceptance / logic checks:**
- All 5 FEATURE_* flag names from OPS-13 §5.2 are present with Phase 1 default=false noted
- Error codes PARTNER_B_QUOTE_DEVIATION, PARTNER_B_QUOTE_UNAVAILABLE, RATE_UNAVAILABLE, and SCHEME_UNAVAILABLE are defined with HTTP status codes and trigger conditions
- RateSource enum values IDENTITY, LIVE, MANUAL, PARTNER are defined with the condition under which each applies
- job_run_id is defined as UUID, scoped to a single batch job execution, used for idempotency
- No term added during this ticket conflicts with an existing definition (check for duplicate entries)
**Depends on:** 1.6-T01, 1.6-T06

### 1.6-T12 — Produce sandbox API onboarding guide for partner integration engineers  _(55 min)_
**Context:** WBS 1.6 deliverable includes API docs for partner integration. The sandbox environment (https://api-sandbox.gmepayplus.com per API-05 §2.1, updated to actual URL in 1.6-T06) is the integration target for GME Remit and SendMN. The onboarding guide must let a partner engineer with no prior GMEPay+ knowledge authenticate, obtain a rate quote, and submit a test payment in under 30 minutes. It must cover: HMAC-SHA256 signing (canonical string: HTTP_METHOD + newline + path + newline + UTC-timestamp + newline + body-sha256-hex per API-05 §3.2), idempotency key format (UUID v4 per API-05 §2.6), rate quote TTL (default 300s per RATE-04), and the two test scenarios: domestic KRW payment (GME Remit type) and cross-border USD-funded payment (SendMN type).
**Steps:** Create docs/api/sandbox-onboarding-guide.md in the repository; Section 1 - Credentials: explain how to request sandbox credentials (X-API-Key + API Secret) from GME Ops; explain that the secret is shown once and must be stored securely; Section 2 - Authentication: provide a step-by-step HMAC signing example with a concrete request (POST /v1/rates, body {target_payout:50000, currency:KRW}), showing the canonical string construction and the X-GME-Signature header value; Section 3 - Rate Quote: show a complete GET /v1/rates request and response example including validUntil timestamp and the offer_rate field; Section 4 - Submit Payment: show POST /v1/payments request with Idempotency-Key header and the response; note that the quote must be used within TTL; Section 5 - Webhook: show the payment.approved webhook payload and instruct the partner to return HTTP 200 within 5 seconds
**Deliverable:** docs/api/sandbox-onboarding-guide.md with 5 sections covering credentials, auth, rate quote, payment submission, and webhook
**Acceptance / logic checks:**
- HMAC canonical string example in Section 2 shows all 4 lines: method, path, timestamp, body-hash (concrete values, not pseudocode)
- Section 3 shows a complete JSON response for POST /v1/rates including validUntil in ISO-8601 UTC format and offer_rate as a decimal string
- Section 4 includes the Idempotency-Key header with a UUID example and explicitly states the 24-hour deduplication window
- Section 5 webhook payload includes transaction_id, status (APPROVED), collection_amount, and payout_amount with example values
- Guide contains zero internal GME jargon that is not defined inline (every term used is either in REF-15 or defined in the guide itself)
**Depends on:** 1.6-T06, 1.6-T11

### 1.6-T13 — Produce partner webhook integration reference with retry semantics and failure scenarios  _(50 min)_
**Context:** Partners rely on webhooks for async payment results. API-05 defines webhook delivery but the as-built system's retry policy (OPS-13 §7.5.6: max 5 retries in prod, P2 alert on failure) must be documented for partner engineers. Partners need to know: (1) webhook URL must return HTTP 200-299 within 5 seconds or the delivery is retried, (2) retry schedule: exponential back-off (exact schedule from OPS-13 §6.3: 30s, 120s, 300s for batch; webhook retry schedule must be verified from implementation), (3) event types: payment.approved, payment.failed, payment.cancelled (confirm from implementation), (4) how to verify the webhook signature to prevent spoofing, (5) idempotency: webhooks for the same transaction_id may be delivered more than once on retry; partners must handle duplicates.
**Steps:** Verify the webhook retry schedule from the implementation (number of retries, back-off intervals) and confirm it matches OPS-13 §7.5.6 (max 5 retries in prod); Create docs/api/webhook-reference.md; Section 1 - Event types: list all webhook event types with their trigger conditions (payment.approved: ZeroPay confirms; payment.failed: scheme rejects or prefund insufficient; payment.cancelled: same-day cancel processed); Section 2 - Delivery semantics: document timeout (5s), retry count, back-off schedule with explicit intervals in seconds, and the at-least-once delivery guarantee; Section 3 - Signature verification: show how to verify the X-GME-Webhook-Signature header (HMAC-SHA256 of the raw body using the partner API secret); Section 4 - Idempotency: instruct partner to use transaction_id as a deduplication key; show example of duplicate delivery and correct handling (return 200, discard duplicate); Section 5 - Failure escalation: explain that after max retries, GME Ops can manually re-trigger from Admin System on partner request
**Deliverable:** docs/api/webhook-reference.md covering all event types, retry semantics, signature verification, and idempotency
**Acceptance / logic checks:**
- Section 1 lists exactly the event types implemented (must include payment.approved and payment.failed at minimum; confirm payment.cancelled if implemented)
- Section 2 states the retry count (max 5 in prod per OPS-13) and lists the explicit back-off intervals in seconds
- Section 3 includes a concrete signature verification code snippet (pseudocode or Java/Python) showing HMAC-SHA256(raw_body, api_secret)
- Section 4 explicitly states that duplicate webhook deliveries can occur and the correct handling is to check transaction_id before processing
- Retry interval values are consistent between webhook-reference.md and the OPS-13 §7.5.6 alert thresholds
**Depends on:** 1.6-T06, 1.6-T09

### 1.6-T14 — Produce ops handover: FX rate update procedure and rate engine admin guide  _(45 min)_
**Context:** GME Ops staff must be able to update FX treasury rates without engineering assistance. OPS-13 §8.11 defines the Phase 1 manual FX rate update procedure. The rate engine uses treasury.usd_{ccy} = units of {ccy} per 1 USD. Example: treasury.usd_krw = 1381.50 means 1 USD = 1381.50 KRW. The Admin System FX Rates screen applies a 4-eyes check if the rate change exceeds 2% (OPS-13 §8.11). After update, the new rate is effective for new transactions within 1 second (in-memory cache TTL per OPS-13 §8.11). Feature flag FEATURE_LIVE_FX_FEED is false in Phase 1. Ops staff must also understand the rate-lock guarantee: committed transactions are never affected by subsequent rate changes.
**Steps:** Create docs/ops/fx-rate-update-guide.md; Section 1 - Rate convention: explain treasury.usd_{ccy} format with a worked example: treasury.usd_krw = 1381.50 means 1 USD = 1381.50 KRW; note KRW has 0 decimal places; Section 2 - Update procedure: reproduce the OPS-13 §8.11 steps verbatim with actual Admin System screen navigation path (Admin System -> FX Rates -> [currency] -> Update); Section 3 - 4-eyes control: explain that a change > 2% requires second-operator approval; show what the system displays (percentage diff from previous rate); describe the approval workflow; Section 4 - Verification: explain how to verify the new rate is active using the Admin System rate calculation preview; show expected output for GME Remit domestic (50000 KRW + 500 = 50500 KRW) and SendMN cross-border; Section 5 - Rate lock guarantee: state clearly that updating a rate does NOT affect any transaction that was already committed; explain validUntil on outstanding quotes (TTL 300s default)
**Deliverable:** docs/ops/fx-rate-update-guide.md with 5 sections covering convention, procedure, 4-eyes control, verification, and rate-lock guarantee
**Acceptance / logic checks:**
- Section 1 states the treasury.usd_{ccy} convention with a concrete KRW example: 1 USD = 1381.50 KRW, field name treasury.usd_krw
- Section 3 states the 2% threshold for 4-eyes approval explicitly (not 'large changes' vaguely)
- Section 4 verification example confirms: domestic GME Remit 50000 KRW target_payout + 500 KRW service_charge = 50500 KRW collection_amount (same-currency short-circuit)
- Section 5 uses the word 'rate-lock' and states that already-committed transactions are not affected
- Guide contains no references to code, JVM, or database commands -- it is written for non-technical Ops staff
**Depends on:** 1.6-T05, 1.6-T09

### 1.6-T15 — Produce ops handover: prefunding management procedure for OVERSEAS partners  _(40 min)_
**Context:** GME Ops records and monitors USD prefunding for OVERSEAS partners (SendMN, T-Bank). Prefunding deductions are atomic (SELECT ... FOR UPDATE). Low-balance threshold: USD 10,000 (P2 alert); critical threshold: USD 2,000 (P1 alert, payments suspended automatically). OPS-13 §8.5 covers the top-up recording procedure; OPS-13 §8.6 covers the critical-balance response. Partners need to be suspended via Admin System -> Partner -> Status -> Suspend; re-enabled once top-up is confirmed. The prefunding ledger records each deduction and top-up immutably with operator_id and timestamp. Phase 1 only GME Remit is LOCAL (no prefunding); SendMN and T-Bank are OVERSEAS.
**Steps:** Create docs/ops/prefunding-guide.md; Section 1 - Which partners need prefunding: list OVERSEAS partners (SendMN, T-Bank) vs LOCAL (GME Remit); state that LOCAL partners require no prefunding; Section 2 - Top-up recording procedure: reproduce OPS-13 §8.5 steps with actual Admin System navigation; state that the balance updates immediately and the Finance reference is mandatory; Section 3 - Alert thresholds: document default thresholds (P2 at USD 10,000; P1 at USD 2,000; suspension at USD 0); note that per-partner thresholds are configurable in Admin System; Section 4 - Critical balance response: reproduce OPS-13 §8.6 steps including the Suspend action path; state that in-flight transactions are not affected by suspension; Section 5 - Ledger immutability: explain that every top-up and deduction is an immutable ledger entry visible in Admin System -> Partner -> Prefunding Ledger; entries cannot be deleted or edited
**Deliverable:** docs/ops/prefunding-guide.md covering partner types, top-up procedure, alert thresholds, critical response, and ledger audit trail
**Acceptance / logic checks:**
- Section 1 explicitly identifies GME Remit as LOCAL (no prefunding) and SendMN/T-Bank as OVERSEAS (prefunding required)
- Section 3 states both numeric thresholds: USD 10,000 (P2) and USD 2,000 (P1) as defaults
- Section 4 includes the explicit Admin System navigation path: Admin System -> Partners -> [Partner Name] -> Status -> Suspend
- Section 4 states that in-flight transactions at the moment of suspension are NOT cancelled (they complete normally)
- Section 5 states that ledger entries are immutable and include operator_id and timestamp
**Depends on:** 1.6-T09

### 1.6-T16 — Produce ops handover: ZeroPay batch job monitoring and reprocessing runbook  _(50 min)_
**Context:** GME Ops must monitor and respond to batch job failures. OPS-13 §6.2 defines 13 batch jobs (JOB-ZP-01 through JOB-ZP-13) with KST window times. Critical path: JOB-ZP-01/02 by 02:00 KST -> JOB-ZP-03/04 by 05:00 -> JOB-ZP-05 by 05:00 -> JOB-ZP-06 by 10:00. P1 alerts fire at 15 minutes before window (e.g. ZP0011 alert at 01:45 KST per OPS-13 §6.4). Reprocessing uses Admin System -> Batch Jobs -> [JOB-ID] -> Rerun for Date, or CLI: batch-cli rerun --job JOB-ZP-01 --date <YYYY-MM-DD> --force. The batch worker checks idempotency: if the file was already received by 한결원 (confirmed via ZP0012), re-run is a no-op. Never re-submit ZP0061 without 한결원 confirmation (double-submission risk per OPS-13 §8.7.2).
**Steps:** Create docs/ops/batch-monitoring-runbook.md; Section 1 - Daily job schedule: reproduce the 13-job table from OPS-13 §6.2 with Job ID, file(s), direction, window, dependency, criticality; add the alert threshold column from §6.4; Section 2 - Monitoring dashboard: describe the Batch Health dashboard panels (job timeline, last run status, file receipt by window) and where to find the job_run_id for a specific run; Section 3 - Reprocessing procedure: provide the Admin System and CLI paths for re-running a job; include the idempotency note (no-op if already received); Section 4 - ZP0061 special handling: explicitly state the double-submission risk for ZP0061 and require 한결원 confirmation before re-submitting; provide the 한결원 ops contact lookup path; Section 5 - Escalation: state that after 3 automatic retries (30s, 120s, 300s per OPS-13 §6.3) the job enters FAILED state and requires manual action; list the escalation path from OPS-13 §11.2
**Deliverable:** docs/ops/batch-monitoring-runbook.md with job schedule table, dashboard guide, reprocessing procedure, ZP0061 warning, and escalation path
**Acceptance / logic checks:**
- Section 1 table lists all 13 jobs (JOB-ZP-01 through JOB-ZP-13) with KST window times matching OPS-13 §6.2
- Section 1 includes P1 alert threshold times (01:45 KST for JOB-ZP-01/02; 05:15 KST for JOB-ZP-05; 10:30 KST for JOB-ZP-06)
- Section 3 shows both the Admin System GUI path and the CLI command with --force flag
- Section 4 contains an explicit warning that ZP0061 must not be re-submitted without 한결원 written confirmation, with reason (duplicate merchant crediting risk)
- Section 5 states the 3-retry exponential back-off intervals: 30s, 120s, 300s
**Depends on:** 1.6-T09

### 1.6-T17 — Produce ops handover: incident response quick-reference card  _(45 min)_
**Context:** On-call engineers need a quick-reference for P1/P2 incidents. OPS-13 §11 defines severity levels (P1 = 15-min response, P2 = 30-min), escalation path (engineer -> Engineering Lead -> Ops Manager -> 한결원 if scheme issue -> SEC-09 §7 if security), and postmortem requirements (all P1s, P2s that breach SLOs). OPS-13 §7.5 alert catalog has 20+ alerts across 6 categories. The quick-reference must map each P1 alert to a runbook section so the on-call engineer can navigate directly. Go-live is Oct 10, 2026; hypercare period is Oct 10-24 with lowered P2->P1 escalation.
**Steps:** Create docs/ops/incident-quickref.md; Section 1 - Severity and response times: P1 = 15 min, P2 = 30 min, P3 = next business day; hypercare note: P2 treated as P1 during Oct 10-24 2026; Section 2 - Alert-to-runbook map: create a 3-column table: Alert Name | Severity | Runbook Section. Include all P1 alerts from OPS-13 §7.5 (API error rate > 1%; payment endpoint unavailable; pool identity failure; ZP0011/ZP0021 missed; ZP0061 missed; ZP0012/ZP0022 not received; SFTP connection failure; prefunding at zero or critical) with their OPS-13 section reference; Section 3 - Escalation path: reproduce OPS-13 §11.2 5-step escalation as a numbered list with time triggers; Section 4 - First-response checklist: 5-bullet checklist covering: check alert source, check monitoring dashboard, check recent deployment (was there a deployment in last 30 min), check scheme status (ZeroPay SFTP), check transaction error rate trend; Section 5 - Postmortem trigger: state the rule: required for all P1 and any P2 that breached SLOs; document must include incident timeline, root cause, impact, action items
**Deliverable:** docs/ops/incident-quickref.md with severity table, alert-to-runbook map, escalation path, first-response checklist, and postmortem trigger rule
**Acceptance / logic checks:**
- Alert-to-runbook map table contains at least 8 P1 alert entries with correct OPS-13 section references
- Section 3 escalation path lists all 5 steps from OPS-13 §11.2 with time triggers (15 min for P1, 30 min for P2)
- Section 1 explicitly calls out the hypercare period (Oct 10-24 2026) and the P2->P1 escalation rule during hypercare
- Pool identity failure (collection_usd - collection_margin_usd - payout_margin_usd != payout_usd_cost beyond 0.01 USD) appears as a P1 alert pointing to OPS-13 §8.9
- Postmortem section states 'within 48 hours' as the deadline per OPS-13 §11.4
**Depends on:** 1.6-T09, 1.6-T16

### 1.6-T18 — Produce ops handover: go-live cutover checklist document  _(50 min)_
**Context:** WBS 1.6 includes go-live handover materials. OPS-13 §12 defines the pre-go-live checklist (8 sections: infrastructure, application, ZeroPay integration, partners, merchant data, monitoring, security, operational readiness) and the cutover procedure. The Phase 3 go-live target is October 10, 2026, after a Tuesday/Wednesday morning batch window (approx 06:00 KST). The go-live smoke tests in OPS-13 §12.2 include: rate quote for GME Remit domestic (collection_amount = target_payout + 500 KRW), rate quote for SendMN cross-border (USD pool math), webhook delivery to GME Remit test endpoint, and prefunding balance read for SendMN. Partner configs: GME Remit (LOCAL, KRW/KRW, service charge KRW 500, no prefunding); SendMN (OVERSEAS, USD prefunding, 2% combined FX margin, KRW 500 service charge).
**Steps:** Create docs/ops/go-live-checklist.md; Section 1 - Pre-go-live checklist: reproduce all 8 subsections from OPS-13 §12.1 (Infrastructure, Application, ZeroPay Integration, Partners, Merchant Data, Monitoring, Security, Operational Readiness) as a Markdown checkbox list with sign-off line per section; Section 2 - Cutover procedure: numbered steps from OPS-13 §12.2; specify the recommended window (Tuesday/Wednesday 06:00 KST after overnight batch completes); Section 3 - Smoke tests: document the 4 go-live smoke tests with expected outputs: (a) GME Remit rate quote: target_payout=50000 KRW -> collection_amount=50500 KRW; (b) SendMN rate quote: verify USD pool identity holds; (c) webhook to GME Remit test endpoint returns 200; (d) SendMN prefunding balance readable; Section 4 - Rollback trigger: state the condition for rollback (critical defect blocking payment) and the first action (suspend partner API keys in Admin System -> Partners -> API Keys -> Suspend); Section 5 - Hypercare schedule: reproduce OPS-13 §12.4 hypercare criteria (14 days, 10 consecutive successful batch days, zero P1s required to exit)
**Deliverable:** docs/ops/go-live-checklist.md with 8-section pre-go-live checklist, cutover procedure, smoke test specifications, rollback trigger, and hypercare schedule
**Acceptance / logic checks:**
- All 8 checklist sections from OPS-13 §12.1 are present with checkbox items (not just headings)
- Smoke test (a) states exact values: target_payout=50000 KRW, expected collection_amount=50500 KRW for GME Remit domestic
- Smoke test (b) instructs the operator to verify pool identity: collection_usd - collection_margin_usd - payout_margin_usd = payout_usd_cost within USD 0.01
- Section 4 rollback trigger specifies suspending API keys as the first action before any other rollback step
- Section 5 states hypercare end condition: zero P1 incidents AND at least 10 consecutive successful complete batch cycles (morning + afternoon)
**Depends on:** 1.6-T09, 1.6-T17

### 1.6-T19 — Produce partner portal user guide for read-only balance and transaction inquiry  _(45 min)_
**Context:** The Partner Portal (PRD-08) is a read-only self-service web portal. It provides: prefunding balance inquiry (OVERSEAS partners only), transaction history (filterable by date range, status, direction, partner_id, scheme), transaction detail (all 8 event trail steps), and CSV export. The portal is not a configuration interface -- GME Ops performs all config in the Admin System. Partners log in with portal credentials distinct from API credentials. The transaction event trail has 8 steps: rate_quote_issued, payment_initiated, prefund_deducted (OVERSEAS only), scheme_request_sent, scheme_response_received, transaction_committed, webhook_dispatched, webhook_delivered. Partners may see UNCERTAIN status for up to 24 hours on transactions where ZeroPay has not yet responded.
**Steps:** Create docs/partner/portal-user-guide.md; Section 1 - Login and credentials: explain that portal credentials are separate from API credentials; contact GME Ops to obtain; two-factor authentication requirement if applicable; Section 2 - Prefunding balance (OVERSEAS only): show the balance display screen with fields (available_balance USD, last_top_up_date, last_top_up_amount); explain that LOCAL partners (GME Remit) do not see this section; Section 3 - Transaction history: show filter options (date range, status: APPROVED/FAILED/CANCELLED/UNCERTAIN, direction); explain cursor pagination; note that UNCERTAIN transactions may remain in that state for up to 24 hours during ZeroPay batch reconciliation; Section 4 - Transaction detail: show the 8-step event trail with timestamps; explain each step name in plain language; note that step 3 (prefund_deducted) is OVERSEAS partners only; Section 5 - CSV export: explain the fields in the export file; note 7-year data retention per SEC-09
**Deliverable:** docs/partner/portal-user-guide.md with 5 sections covering login, balance inquiry, transaction history, event trail, and CSV export
**Acceptance / logic checks:**
- Section 2 explicitly states that LOCAL partners (e.g. GME Remit) do not have a prefunding balance section
- Section 3 explains UNCERTAIN status and the 24-hour ZeroPay batch reconciliation window in plain language
- Section 4 lists all 8 event trail step names in order (rate_quote_issued through webhook_delivered) with plain-language descriptions
- Section 5 mentions 7-year retention as the reason why historical exports are available
- Guide contains no Admin System content (no scheme config, no margin update -- those are Ops-only)
**Depends on:** 1.6-T08, 1.6-T11

### 1.6-T20 — Produce developer sandbox quick-start: end-to-end test of domestic payment flow  _(50 min)_
**Context:** A partner developer integrating with the sandbox needs a concrete working example for the domestic payment flow (GME Remit type: LOCAL, KRW/KRW/KRW, same-currency short-circuit, service_charge=500 KRW). The flow is: (1) GET /v1/rates with target_payout=50000 KRW to receive offer_rate and validUntil, (2) POST /v1/payments with quote_id and Idempotency-Key to initiate payment, (3) receive payment.approved webhook. In the same-currency short-circuit, collection_amount = target_payout + service_charge = 50500 KRW; the USD pool steps are skipped; offer_rate = 1.0 (same currency). Sandbox credentials: obtained from GME Ops per 1.6-T12 guide. HMAC signing is required on all calls per API-05 §3.2.
**Steps:** Create docs/api/quickstart-domestic.md; Section 1 - Prerequisites: sandbox credentials (X-API-Key, API Secret), curl or HTTP client, understand HMAC signing from sandbox-onboarding-guide.md §2; Section 2 - Step 1: GET /v1/rates with request body {scheme_id: 'zeropay', direction: 'domestic', target_payout: {amount: '50000.00', currency: 'KRW'}}; show full signed request headers and expected response including collection_amount=50500 KRW, validUntil; Section 3 - Step 2: POST /v1/payments with Idempotency-Key, quote_id from step 1, and merchant_qr_id; show full request and expected response (transaction_id, status=PENDING); Section 4 - Step 3: webhook receipt - show the payment.approved event payload with transaction_id matching step 2, status=APPROVED, collection_amount=50500 KRW; Section 5 - Verify: explain how to fetch transaction detail via GET /v1/payments/{transaction_id} and confirm all 8 event trail steps are visible (noting step 3 prefund_deducted will not appear for domestic LOCAL partners)
**Deliverable:** docs/api/quickstart-domestic.md with 5 sections: prerequisites, rate quote, payment initiation, webhook receipt, and verification
**Acceptance / logic checks:**
- Section 2 shows collection_amount=50500 KRW in the example response (50000 + 500 service charge, same-currency short-circuit)
- Section 2 response does not include USD pool fields (payout_usd_cost, collection_usd) -- those are absent in the same-currency path
- Section 3 shows the Idempotency-Key header with a UUID example and quotes the 24-hour deduplication window
- Section 4 webhook payload shows status=APPROVED and collection_amount matching the Section 2 quote
- Section 5 notes that event trail step 3 (prefund_deducted) will NOT appear for domestic LOCAL partner transactions
**Depends on:** 1.6-T12, 1.6-T13

### 1.6-T21 — Produce developer sandbox quick-start: end-to-end test of cross-border OVERSEAS payment flow  _(55 min)_
**Context:** The cross-border payment flow applies to OVERSEAS partners (SendMN type: prefunded USD balance, m_a=0.01, m_b=0.01, service_charge=0.36 USD, settle_a_ccy=USD IDENTITY, settle_b_ccy=KRW LIVE). The 5-step rate engine is fully exercised: (1) payout_usd_cost = target_payout / cost_rate_pay = 50000/1380 = 36.2319 USD, (2) collection_usd = 36.2319/(1-0.01-0.01) = 36.9714 USD, (3) collection_margin_usd = 0.36971, payout_margin_usd = 0.36971, (4) send_amount = 36.9714 * 1.0 = 36.9714 USD, (5) collection_amount = 36.9714 + 0.36 = 37.3314 USD. Pool identity: 36.9714 - 0.36971 - 0.36971 = 36.2319 (within 0.01 USD). The prefunding balance must cover collection_amount before the scheme is called; insufficient balance returns HTTP 402 before any ZeroPay call.
**Steps:** Create docs/api/quickstart-crossborder.md; Section 1 - Prerequisites: OVERSEAS partner sandbox credentials with prefunding enabled; note that the sandbox prefunding balance is pre-loaded by GME Ops for test use; Section 2 - Step 1: GET /v1/rates request and response; show all 5 rate engine output fields in the response: payout_usd_cost=36.2319, collection_usd=36.9714, send_amount=36.9714, collection_amount=37.3314 USD, offer_rate (cross_rate = target_payout/send_amount = 50000/36.9714 = 1352.66 KRW/USD); Section 3 - Prefunding check: explain that insufficient prefunding balance returns HTTP 402 with error code INSUFFICIENT_PREFUNDING before the scheme is called; show example 402 response; Section 4 - POST /v1/payments: show request and response; note that prefunding deduction is atomic (SELECT FOR UPDATE) and occurs before ZeroPay is called; Section 5 - Webhook and verification: show payment.approved webhook payload including payout_usd_cost and collection_amount; show event trail including step 3 prefund_deducted
**Deliverable:** docs/api/quickstart-crossborder.md with 5 sections: prerequisites, rate quote with USD pool fields, prefunding check, payment initiation, and webhook verification
**Acceptance / logic checks:**
- Section 2 rate quote response shows all 5 calculated fields with values matching the worked example: collection_amount=37.3314 USD, collection_usd=36.9714 USD
- Pool identity check is shown: 36.9714 - 0.36971 - 0.36971 = 36.2319 within 0.01 USD tolerance
- Section 3 shows HTTP 402 error code INSUFFICIENT_PREFUNDING and explicitly states ZeroPay is NOT called when prefunding is insufficient
- Section 4 states that prefunding deduction is atomic (uses SELECT ... FOR UPDATE)
- Section 5 event trail includes step 3 (prefund_deducted) with a USD amount matching collection_amount
**Depends on:** 1.6-T12, 1.6-T13, 1.6-T15

### 1.6-T22 — Verify all docs/as-built documents pass internal consistency checks and cross-reference audit  _(55 min)_
**Context:** WBS 1.6 produces multiple updated spec docs and new guides. Before handover, all documents must be internally consistent: field names in API-05 must match DAT-03, rate engine formulas in RATE-04 must match the worked examples in quickstart-crossborder.md, OPS-13 runbook commands must use the deployment names confirmed in 1.6-T09, REF-15 glossary must define every term used in the new ops guides. This ticket is a structured review and fix pass. It does not create new documents; it repairs inconsistencies found across the deliverables from 1.6-T01 through 1.6-T21.
**Steps:** Run a cross-reference check: for each field name appearing in the API-05 endpoint schemas, verify the same name appears in DAT-03 data dictionary and REF-15 glossary; list mismatches; Run a numeric consistency check: verify the rate engine worked examples in RATE-04, quickstart-crossborder.md, and go-live-checklist.md all use the same input values and produce the same outputs (50000 KRW target_payout, treasury.usd_krw=1380, m_a=m_b=0.01, service_charge=0.36 USD -> collection_amount=37.3314 USD); Run a runbook command check: for every kubectl or batch-cli command in the ops guides, verify the namespace and deployment name match the values confirmed in 1.6-T09; Fix all mismatches found in steps 1-3 with targeted edits to the affected files; Produce a consistency-check-log.md in docs/as-built/ recording what was checked, mismatches found, and corrections made
**Deliverable:** consistency-check-log.md in docs/as-built/ plus all corrected cross-document inconsistencies applied to affected files
**Acceptance / logic checks:**
- consistency-check-log.md lists at least 3 categories checked (field names, numeric examples, runbook commands) with a findings count per category
- Any field appearing in openapi-v1.json request/response schemas also appears in DAT-03 and REF-15 (spot-check 5 fields)
- The cross-border worked example (collection_amount=37.3314 USD for target_payout=50000 KRW) appears identically in RATE-04, quickstart-crossborder.md, and go-live-checklist.md smoke test
- No kubectl or batch-cli command in any ops guide references a placeholder namespace (e.g. <namespace>) -- all are replaced with actual values
- consistency-check-log.md records zero remaining unresolved inconsistencies at the time of completion
**Depends on:** 1.6-T05, 1.6-T06, 1.6-T09, 1.6-T11, 1.6-T18, 1.6-T21


## WBS 16.2 — Phase 3 go-live: GME Remit domestic
### 16.2-T01 — Define go-live environment config schema for production slot  _(45 min)_
**Context:** WBS 16.2 is the Phase 3 production launch of GME Remit (domestic) on ZeroPay, target Oct 10 2026. OPS-13 defines four environment tiers: dev, int, staging, prod. The production slot needs a validated config schema covering SFTP credentials path, DB connection strings, secrets-manager paths, feature-flag overrides, and ZeroPay production SFTP host/port/path. All secrets must be stored in the secrets manager (HashiCorp Vault / AWS Secrets Manager / Azure Key Vault); no plain-text secrets in app config. Feature flags: FEATURE_LIVE_FX_FEED=OFF, FEATURE_PARTNER_REFUND_API=OFF, FEATURE_BOK_REPORTING=OFF (pending OI-03) in Phase 3.
**Steps:** Create or update infrastructure/config/prod.env.template (or equivalent IaC values file) with all required keys, each marked as SECRET or PLAIN.; Add FEATURE_LIVE_FX_FEED, FEATURE_PARTNER_REFUND_API, FEATURE_BOK_REPORTING flags explicitly set to OFF.; Document the secrets-manager path convention (e.g. gmepay/prod/zeropay/sftp_host) matching OPS-13 §4.7.; Add a CI validation step that asserts no SECRET-marked key has a non-empty plain-text value in the template.; Peer-review the schema against OPS-13 §12.1.1 infrastructure checklist items.
**Deliverable:** prod.env.template (or IaC values file) with full key inventory, flag defaults, and secrets-manager path map for production environment.
**Acceptance / logic checks:**
- Template contains entries for zeropay_sftp_host, zeropay_sftp_port, zeropay_sftp_path, zeropay_sftp_credentials_ref, db_connection_string_ref, and partner_api_key_ref at minimum.
- FEATURE_LIVE_FX_FEED, FEATURE_PARTNER_REFUND_API, FEATURE_BOK_REPORTING are all set to OFF (not absent).
- CI step fails if any SECRET-marked key contains a literal value (non-empty string not referencing a secrets-manager path).
- Secrets-manager paths follow the hierarchical convention gmepay/{env}/{component}/{key} with no env-value collision between staging and prod.
- Schema diff is reviewable with no hard-coded production hostnames or credentials in version control.

### 16.2-T02 — Provision and harden production infrastructure per OPS-13 §12.1.1 checklist  _(60 min)_
**Context:** OPS-13 §12.1.1 specifies the pre-go-live infrastructure checklist: multi-AZ API tier and DB, load balancer + WAF + TLS, auto-scaling tested at 500 TPS synthetic load, DR environment provisioned with drill completed, and PostgreSQL backup/restore tested within 30 days. GME Remit domestic generates no prefunding or FX load; the target TPS for Phase 3 is 50 TPS sustained / 100 TPS burst (NFR-10). IaC (Terraform / Pulumi / equivalent) should be used for all provisioning.
**Steps:** Run IaC apply for prod environment, verifying multi-AZ deployment for API pods and managed PostgreSQL.; Validate TLS certificate on the prod load balancer and WAF rule set (OWASP top-10 at minimum).; Execute synthetic load test at 500 TPS for 5 minutes against staging-equivalent prod infra; confirm no auto-scaling failures.; Perform PostgreSQL backup and restore drill: take snapshot, restore to a scratch instance, verify row counts match.; Record all results in the pre-go-live checklist doc (OPS-13 §12.1 checkboxes).
**Deliverable:** IaC apply output log + completed infrastructure section of OPS-13 §12.1.1 checklist (all 6 items checked with evidence links).
**Acceptance / logic checks:**
- At least 2 availability zones confirmed in IaC output for API and DB tiers.
- TLS certificate valid, WAF active, and HTTPS-only redirect enforced on prod load balancer.
- Synthetic 500 TPS load test shows p95 latency <= 500ms and zero 5xx errors (NFR-10 threshold).
- PostgreSQL restore drill: restored row count for payments table matches source within 0 rows; RTO confirmed <= 4 hours (NFR-10).
- Backup timestamp in checklist is within 30 calendar days of go-live date Oct 10 2026.
**Depends on:** 16.2-T01

### 16.2-T03 — Load production secrets into secrets manager and rotate all staging values  _(45 min)_
**Context:** OPS-13 §12.1.7 requires all production secrets rotated from staging values before go-live. Secrets include: ZeroPay production SFTP credentials (issued by KFTC/한결원), GME Remit partner API key/secret, internal service-to-service JWTs, DB master password, and any encryption-at-rest keys. SEC-09 mandates secrets stored encrypted at rest in Vault/Secrets Manager; no secret in environment variables or config files. Rotation must be confirmed by reading back a non-secret reference (e.g. last-rotated timestamp) without exposing the value.
**Steps:** Obtain production ZeroPay SFTP credentials from KFTC/한결원 (prerequisite: OI-OPS-02 resolved).; Load all production secrets into secrets manager under gmepay/prod/* paths using the convention from 16.2-T01.; Revoke or expire all staging-value secrets that were accidentally seeded in prod paths.; Verify no staging API key or SFTP password exists in prod secrets manager by listing key metadata (not values).; Record secret rotation evidence (key name + rotated timestamp) in the security sign-off document.
**Deliverable:** Secrets manager prod namespace populated with all required secrets; rotation evidence log attached to security sign-off.
**Acceptance / logic checks:**
- Listing gmepay/prod/* in secrets manager returns entries for zeropay_sftp_credentials, gme_remit_api_secret, db_password, jwt_signing_key at minimum.
- No gmepay/staging/* value is identical to corresponding gmepay/prod/* value (verified by metadata/hash comparison, not plain-text).
- Application can start in prod without any secret injected as a plain-text env var (startup health check passes).
- Break-glass access procedure documented and tested: a designated engineer can retrieve a secret via the emergency access path within 5 minutes.
- Security lead signs off the rotation checklist before go-live window opens.
**Depends on:** 16.2-T01

### 16.2-T04 — Apply all pending DB migrations to production PostgreSQL instance  _(45 min)_
**Context:** DAT-03 defines the Hub Core schema. All migrations written in earlier WBS items must be applied to the production DB in order, idempotently, via the project migration tool (Flyway / Liquibase / equivalent). OPS-13 §12.1.2 requires migrations applied and verified before traffic is accepted. Production DB is on managed PostgreSQL, port 5433 per project memory. Migration scripts must never drop data; each must be reversible or have a documented forward-only policy.
**Steps:** Enumerate all pending migration scripts not yet applied to prod (compare migration version table).; Run migration tool in dry-run mode against prod DB; capture and review the execution plan.; Apply migrations with the migration tool in prod; capture the output log.; Verify schema by running a structural smoke query: SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public' and confirm expected table count.; Record the final applied migration version in the deployment log.
**Deliverable:** Production DB at the target schema version with all migrations applied; migration tool output log as evidence.
**Acceptance / logic checks:**
- Migration tool reports 0 pending scripts after apply.
- No ERROR lines appear in the migration apply log.
- Key tables (partners, schemes, rules, payments, rate_quotes, settlement_batches, audit_log, prefunding_ledger) exist in the public schema with correct column counts verifiable via information_schema.
- DB version entry in the flyway_schema_history (or equivalent) table matches the expected head version from VCS.
- Application health endpoint GET /health returns HTTP 200 within 30s of migration completion, indicating schema compatibility.
**Depends on:** 16.2-T01, 16.2-T02

### 16.2-T05 — Register GME Remit as a LOCAL partner in production Admin System  _(45 min)_
**Context:** Phase 3 requires GME Remit onboarded in production (not sandbox). Per PM-14 §2.2 Phase 3, GME Remit is a LOCAL type partner: settlement currencies KRW/KRW/KRW, same-currency short-circuit applies (collection = settle_A = settle_B = payout), service_charge = KRW 500 (flat, not in USD pool), no prefunding. Rule direction: Domestic. Rate engine for same-currency: collection_amount = target_payout + service_charge. Admin System partner record must include: API credentials (key + hashed secret), webhook URL, scheme association (ZeroPay), and rule. Config changes must log actor + timestamp + previous value per audit requirements.
**Steps:** Log into production Admin System as a GME Ops admin user.; Create Partner record: name=GME Remit, type=LOCAL, settle_a_ccy=KRW, settle_b_ccy=KRW, payout_ccy=KRW.; Create Rule record: partner=GME Remit, scheme=ZeroPay, direction=Domestic, m_a=0.0, m_b=0.0, service_charge=500 KRW.; Issue API credentials (key + secret) for GME Remit; store secret hash in DB; provide plain secret to GME Remit team via secure channel.; Confirm audit log entry: actor, timestamp, action=PARTNER_CREATED and action=RULE_CREATED, previous_value=null.
**Deliverable:** GME Remit partner record + Domestic rule live in production Admin System with audit trail.
**Acceptance / logic checks:**
- GET /admin/partners/{id} returns type=LOCAL, settle_a_ccy=KRW, status=ACTIVE.
- GET /admin/rules?partner=gme-remit returns direction=Domestic, m_a=0.0, m_b=0.0, service_charge=500, scheme=ZeroPay.
- Audit log shows two entries (PARTNER_CREATED, RULE_CREATED) with the operator email and a UTC timestamp within the last hour.
- A POST /v1/quotes (northbound) using GME Remit API key for target_payout=13500 KRW returns collection_amount=14000 KRW (13500+500) and offer_rate not returned (same-currency short-circuit).
- Partner API key is not stored in plain text in DB (only the hashed form exists in the partners table).
**Depends on:** 16.2-T04

### 16.2-T06 — Verify ZeroPay production SFTP connectivity and credentials  _(30 min)_
**Context:** OPS-13 §12.1.3 requires ZeroPay production SFTP host/port/IP allowlist confirmed with KFTC/한결원 (OI-OPS-02) and a successful connectivity test before go-live. Production SFTP is separate from the test environment. The SFTP client in Hub Core uses the credentials loaded in 16.2-T03. Firewall change requests to allowlist KFTC production IPs may take 2 weeks (OPS-13 §13.2 OI-OPS-02). All ZP00xx file paths use the same directory structure as test but on the production server.
**Steps:** Confirm KFTC production SFTP host, port, and IP allowlist have been received from 한결원 (prerequisite: OI-OPS-02 closed).; Submit firewall change request to allowlist KFTC production IP range if not already done (2-week lead time).; Run the application SFTP connectivity test script (or sftp -oStrictHostKeyChecking=no) using production credentials from secrets manager.; List the production SFTP root directory and confirm the expected upload/download subdirectory structure exists.; Record the successful connectivity test result (timestamp, remote banner, directory listing) in the go-live checklist OPS-13 §12.1.3.
**Deliverable:** Confirmed SFTP connectivity log to KFTC production server; OPS-13 §12.1.3 checklist items checked.
**Acceptance / logic checks:**
- SFTP connection exits with code 0 using credentials from secrets manager gmepay/prod/zeropay/sftp_credentials.
- Remote SFTP directory listing returns expected upload and download subdirectories matching the test environment structure.
- No timeout or authentication failure errors in the connection log.
- Firewall change record confirms KFTC production IP block is allowlisted on the Hub Core outbound egress.
- Connectivity test result timestamp recorded in go-live checklist is within 7 days of Oct 10 2026 go-live date.
**Depends on:** 16.2-T03

### 16.2-T07 — Run full production ZeroPay merchant sync (ZP0041/ZP0045/ZP0043/ZP0053) and validate  _(45 min)_
**Context:** OPS-13 §12.1.5 requires production ZeroPay merchant sync (ZP0041/ZP0045 incremental/full merchant list; ZP0043/ZP0053 QR sync) run and validated before go-live. ZP0041 = incremental merchant update, ZP0045 = full merchant list (ZP0051 = full), ZP0043 = incremental QR update, ZP0053 = full QR update. The Hub Core batch worker processes these files via the southbound SFTP adapter (SCH-06). At least one live ZeroPay merchant per merchant type (Individual, Franchise) must be validated in the local DB after sync.
**Steps:** Trigger the ZP0051 full merchant sync batch job against the production SFTP (download ZP0051 from KFTC prod, parse, upsert merchants table).; Trigger ZP0053 full QR sync batch job (download ZP0053, parse, upsert qr_codes table).; Query the local DB: SELECT COUNT(*) FROM merchants WHERE source=ZeroPay AND status=Active and confirm count > 0.; Spot-check at least one Individual and one Franchise merchant record for required fields (merchant_id, name, fee_rate, qr_code_id).; Record merchant count and spot-check results in the go-live checklist.
**Deliverable:** Production merchants and QR codes table populated from KFTC production sync; spot-check results recorded in checklist OPS-13 §12.1.5.
**Acceptance / logic checks:**
- Batch job log shows ZP0051 file downloaded, parsed, and upserted with 0 error rows.
- SELECT COUNT(*) FROM merchants WHERE status=Active returns >= 1 row.
- At least one merchant record has merchant_type=Individual and one has merchant_type=Franchise (or equivalent) with a non-null fee_rate.
- qr_codes table has at least one row linked to an active merchant (JOIN merchants ON merchant_id WHERE status=Active).
- Batch job completion timestamp is recorded and the job exited with code 0.
**Depends on:** 16.2-T06

### 16.2-T08 — Configure and validate ZeroPay batch scheduler on production KST windows  _(45 min)_
**Context:** OPS-13 §12.1.3 and SCH-06 specify ZeroPay batch windows at 02:00, 05:00, 10:00, 14:00, 19:00, 22:00 KST. The batch scheduler (Kubernetes CronJob per OPS-13 §13.1 A-OPS-07) must fire at the correct KST times in production. KST = UTC+9. Batch types include: payment-result registration (ZP0011/ZP0012), refund registration (ZP0021/ZP0022), merchant sync (ZP0041/ZP0043/ZP0045/ZP0047/ZP0051/ZP0053/ZP0055), and settlement (ZP0061-ZP0066). Missed-window alerts must fire via the alert catalog (OPS-13 §7.5).
**Steps:** Review and update all CronJob definitions to use the correct UTC cron expressions for 02:00/05:00/10:00/14:00/19:00/22:00 KST (= 17:00/20:00/01:00/05:00/10:00/13:00 UTC previous/same day).; Deploy CronJob manifests to production and verify their next-run timestamps in kubectl.; Configure missed-window alert: if a batch job does not start within 5 minutes of its scheduled time, fire an alert to the OPS alerting channel.; Perform a manual trigger of at least one CronJob (e.g. payment-result batch) and confirm the job completes without error.; Check that batch alerting notification reaches the configured PagerDuty / on-call channel.
**Deliverable:** Production CronJob manifests deployed with correct KST-equivalent UTC cron schedules; missed-window alert confirmed firing.
**Acceptance / logic checks:**
- kubectl get cronjob -n prod shows 6 schedule entries; spot-check: the 02:00 KST job has cron expression 0 17 * * * (UTC previous day).
- kubectl get jobs -n prod --sort-by=.metadata.creationTimestamp shows at least one manual-trigger job completed with status=Succeeded.
- Missed-window synthetic test: pause the CronJob for 6 minutes then re-enable; confirm alert fires within 5 minutes of the missed window.
- Batch job logs are shipped to the central log aggregator and queryable within 2 minutes of job completion.
- No batch CronJob definition references KST timezone offset incorrectly (all times in UTC with +9h conversion applied).
**Depends on:** 16.2-T06

### 16.2-T09 — Verify production feature flags: LIVE_FX_FEED=OFF, PARTNER_REFUND_API=OFF, BOK_REPORTING=OFF  _(30 min)_
**Context:** OPS-13 §12.1.2 requires feature flags configured before go-live. Phase 3 runs without automated live FX feed (manual GME Ops updates only, per PRD §8), without partner refund API (admin-portal-only per BRD §5.3), and without automated BOK reporting (pending OI-03 resolution). Flags are application-level config (not DB) so the application reads them at startup. The system must behave correctly with all three flags OFF: rate engine uses manually set treasury.usd_{ccy} values, refund requests via API return 501 Not Implemented, BOK report generation is skipped.
**Steps:** Confirm prod.env or secrets manager contains FEATURE_LIVE_FX_FEED=false, FEATURE_PARTNER_REFUND_API=false, FEATURE_BOK_REPORTING=false.; Restart one API pod and confirm it logs the feature flag state at startup (INFO level).; Call POST /v1/payments/{id}/refund via the partner API; expect HTTP 501 with error code REFUND_API_DISABLED.; Verify the rate engine reads treasury.usd_krw from the DB rate table (manually set by GME Ops) rather than any external feed.; Verify BOK report batch job is skipped when FEATURE_BOK_REPORTING=false (no error, just a logged skip message).
**Deliverable:** Feature flag verification test report confirming all three flags are OFF and their enforcement is observable in prod behavior.
**Acceptance / logic checks:**
- Application startup log line contains feature_flags: {live_fx_feed: false, partner_refund_api: false, bok_reporting: false}.
- POST /v1/payments/{id}/refund returns HTTP 501 and JSON body {error_code: REFUND_API_DISABLED}.
- Rate engine GET /v1/quotes call returns offer data sourced from treasury table (not a live feed endpoint); treasury rate last_updated timestamp is a manually set value.
- BOK reporting CronJob completes with log message SKIPPED: FEATURE_BOK_REPORTING=false and exit code 0.
- Changing any flag to true in the secrets manager and restarting a pod causes the corresponding behavior to activate (reversibility confirmed in staging, not prod).
**Depends on:** 16.2-T03, 16.2-T04

### 16.2-T10 — Load initial production treasury rates (KRW, USD) and verify GME Ops rate-update workflow  _(30 min)_
**Context:** Phase 3 uses manual FX rate updates by GME Ops (BRD §10.4, PM-14 A-14 A-05). The rate engine reads treasury.usd_krw (KRW per 1 USD) and treasury.usd_usd=1.0 from the rate table. For GME Remit domestic (KRW/KRW/KRW same-currency short-circuit), the USD pool is bypassed entirely, so treasury.usd_krw does not affect domestic collection_amount. However it must be set correctly for any future cross-border queries. R-08 in PM-14 RAID identifies stale rates as a medium risk; the Admin dashboard must show a stale-rate alert if a rate has not been updated in > 24 hours.
**Steps:** Log into production Admin System as GME Ops. Navigate to FX Rate Management.; Set treasury.usd_krw to the current live KRW/USD rate (e.g. 1350.00 as a placeholder; replace with real rate before go-live). Set treasury.usd_usd=1.0.; Confirm the rate table entry shows: rate_key, value, set_by (operator email), set_at (UTC timestamp), valid_from.; Verify stale-rate alert: temporarily set a rate with set_at = NOW() - 25 hours using a test script; confirm the Admin dashboard displays a stale-rate warning for that key.; Restore the rate to current value and confirm the stale-rate warning clears.
**Deliverable:** Production treasury rate table populated with KRW and USD rates; stale-rate alert behavior confirmed working.
**Acceptance / logic checks:**
- SELECT * FROM rate_table WHERE rate_key IN (treasury.usd_krw, treasury.usd_usd) returns 2 rows with non-null values and set_at within the last 24 hours.
- Audit log records the rate-set action with actor=<ops_email>, previous_value=null (or prior value), new_value, timestamp.
- GET /v1/quotes with target_payout=13500 KRW for GME Remit Domestic returns collection_amount=14000 KRW (same-currency short-circuit: 13500+500 KRW service_charge), confirming USD rate is not used in this path.
- Admin System dashboard shows a stale-rate badge when treasury.usd_krw has not been updated for > 24 hours.
- Setting treasury.usd_usd to any value other than 1.0 is rejected by the API with error IDENTITY_RATE_IMMUTABLE.
**Depends on:** 16.2-T05

### 16.2-T11 — Confirm GME Remit webhook delivery end-to-end in production  _(45 min)_
**Context:** OPS-13 §12.1.4 requires GME Remit webhook delivery confirmed end-to-end before go-live. Per API-05, GMEPay+ sends webhook events to the partner-configured webhook URL at payment lifecycle steps (8-step event trail): payment.pending, payment.processing, payment.completed, payment.failed, refund.pending, etc. GME Remit must confirm receipt of at least one webhook. The partner webhook URL must be HTTPS, authenticated with HMAC-SHA256 signature in X-GMEPay-Signature header (key = shared secret set during partner onboarding). Delivery retry policy: 3 attempts with exponential backoff.
**Steps:** Confirm GME Remit webhook URL is set in the production partner record (HTTPS endpoint owned by GME Remit tech team).; Using a test-mode payment or internal trigger, fire a test webhook event (e.g. payment.test_ping) to the GME Remit webhook URL.; Confirm GME Remit tech contact acknowledges receipt and validates the HMAC-SHA256 signature using the shared secret.; Check Hub Core webhook delivery log: event_type, partner_id, delivery_attempt=1, http_status=200, delivered_at timestamp.; Verify retry behavior: temporarily point webhook URL to a 500 endpoint; confirm 3 retry attempts are logged with exponential backoff before marking delivery_status=FAILED.
**Deliverable:** Webhook delivery confirmation from GME Remit tech team; Hub Core delivery log showing successful event delivery with correct signature.
**Acceptance / logic checks:**
- GME Remit tech team confirms receipt of the test_ping webhook event with valid HMAC-SHA256 signature (shared secret matches).
- Webhook delivery log row: partner_id=gme-remit, event_type=payment.test_ping, http_status=200, attempts=1, delivered_at non-null.
- X-GMEPay-Signature header on the outbound webhook matches HMAC-SHA256(payload, shared_secret) computed independently.
- Retry test: 3 attempts logged at t, t+30s, t+90s (exponential backoff) before delivery_status=FAILED for a 500 endpoint.
- Webhook URL in partner record is HTTPS (port 443); HTTP URLs are rejected at partner config save time with error WEBHOOK_URL_NOT_HTTPS.
**Depends on:** 16.2-T05

### 16.2-T12 — Execute production GME Remit MPM end-to-end smoke payment and verify 8-step event trail  _(45 min)_
**Context:** PM-14 §5.3 Phase 3 acceptance: at least 10 end-to-end live production transactions (MPM + CPM). This ticket covers the first MPM smoke transaction. MPM = Fixed Merchant-Presented Mode: merchant has a static QR code; customer scans it. Flow: partner app calls POST /v1/payments (northbound) with qr_code, partner_id, target_payout_amount_krw; Hub Core validates, applies same-currency short-circuit (collection_amount = target_payout + 500 KRW), calls ZeroPay southbound to register result (ZP0011); settlement queued. 8-step audit trail: RECEIVED, VALIDATED, RATE_APPLIED, SCHEME_SENT, SCHEME_CONFIRMED, SETTLED (queued), WEBHOOK_SENT, COMPLETED.
**Steps:** Coordinate with GME Remit tech team to initiate a real MPM payment in production (scan an active ZeroPay merchant QR code).; GME Remit app calls POST /v1/payments with a valid target_payout_amount in KRW and the scanned QR code value.; Confirm Hub Core response: HTTP 200 with payment_id, collection_amount = (target_payout + 500) KRW, status=COMPLETED.; In Admin System transaction detail, verify all 8 audit trail steps are present with timestamps.; Verify ZP0011 payment-result registration file is generated and queued for the next batch window.
**Deliverable:** First live MPM production transaction record with 8-step event trail and ZP0011 batch entry confirmed.
**Acceptance / logic checks:**
- POST /v1/payments returns HTTP 200 with collection_amount = target_payout_krw + 500 (e.g. target=10000 -> collection=10500 KRW).
- payments table row: partner_id=gme-remit, direction=Domestic, status=COMPLETED, scheme=ZeroPay, service_charge=500.
- Admin System transaction detail shows 8 audit trail steps in order: RECEIVED, VALIDATED, RATE_APPLIED, SCHEME_SENT, SCHEME_CONFIRMED, SETTLEMENT_QUEUED, WEBHOOK_SENT, COMPLETED.
- ZP0011 batch queue contains the new payment_id with correct merchant_id, amount, and KST transaction timestamp.
- USD pool fields (collection_usd, payout_usd_cost, collection_margin_usd, payout_margin_usd) are all null in the payments row (same-currency short-circuit: USD pool skipped for KRW/KRW/KRW).
**Depends on:** 16.2-T07, 16.2-T10, 16.2-T11

### 16.2-T13 — Execute production GME Remit CPM end-to-end smoke payment and verify flow  _(45 min)_
**Context:** CPM = Customer-Presented Mode: customer generates a dynamic QR token via POST /v1/payments/cpm/generate; merchant scans it. For LOCAL partners (GME Remit), CPM has no prefunding deduction (GME Remit is LOCAL, no prefunding). Flow: POST /v1/payments/cpm/generate returns a qr_token with TTL (default 60s for aggregator-bound, per rate-quote TTL spec). Merchant scans token; ZeroPay notifies Hub Core via southbound batch (ZP0012). Hub Core completes the payment and sends webhook. PM-14 §5.3 acceptance: both MPM and CPM working in production. OI-01 (customer approval UX) is open but does not block the Hub flow.
**Steps:** GME Remit app calls POST /v1/payments/cpm/generate with partner_id and target_payout_amount_krw; receive qr_token and validUntil timestamp.; Confirm qr_token TTL: validUntil = request_time + 60s (or configured TTL); token is single-use.; Merchant (or GME Ops acting as merchant) uses the ZeroPay merchant terminal to scan the qr_token within TTL.; Wait for ZP0012 southbound batch to process the merchant scan result; confirm Hub Core receives and completes the payment.; Verify payment status=COMPLETED in Admin System with 8-step audit trail and correct collection_amount = target_payout + 500 KRW.
**Deliverable:** First live CPM production transaction record with qr_token lifecycle confirmed (generated, scanned, completed).
**Acceptance / logic checks:**
- POST /v1/payments/cpm/generate returns HTTP 200 with non-null qr_token, validUntil = issued_at + 60000ms.
- If qr_token is presented after validUntil, Hub Core rejects with HTTP 422 and error_code=QR_TOKEN_EXPIRED.
- payments table row: payment_mode=CPM, partner_id=gme-remit, status=COMPLETED, collection_amount=target_payout+500.
- Second use of the same qr_token is rejected with error_code=QR_TOKEN_ALREADY_USED.
- 8-step audit trail present in Admin System for the CPM transaction identical in structure to MPM trail.
**Depends on:** 16.2-T12

### 16.2-T14 — Run first full ZeroPay settlement batch cycle in production and verify zero discrepancy  _(60 min)_
**Context:** PM-14 §5.3 acceptance: settlement batch runs without exception for 3 consecutive business days. The settlement cycle involves: ZP0061 (settlement request file sent to KFTC), ZP0062 (KFTC acknowledgement), ZP0063 (settlement result from KFTC), ZP0064/ZP0065/ZP0066 (net settlement, fee calculation, reconciliation). Hub Core generates ZP0061 and ingests ZP0063-0066. Reconciliation: sum of completed payment amounts in DB must equal the settlement figure in ZP0063 within 0 KRW (tolerance = 0 for domestic same-currency). Discrepancies must trigger an alert to GME Ops within 1 hour (OPS-13 §7.5).
**Steps:** Trigger or wait for the next scheduled ZP0061 settlement batch window (22:00 KST or first available window after go-live).; Confirm ZP0061 file is generated, uploaded to KFTC production SFTP, and KFTC returns ZP0062 acknowledgement.; After KFTC processes, download ZP0063 settlement result; parse and import into Hub Core settlement_batches table.; Run reconciliation check: SELECT SUM(collection_amount) FROM payments WHERE settlement_batch_id = <batch_id> and compare with ZP0063 total; difference must be 0 KRW.; Record result in Admin System settlement reconciliation screen; confirm no exception alert was raised.
**Deliverable:** First production settlement batch cycle completed end-to-end with zero reconciliation discrepancy recorded.
**Acceptance / logic checks:**
- ZP0061 file uploaded timestamp is within 5 minutes of the scheduled batch window.
- KFTC ZP0062 acknowledgement received and ingested; settlement_batches table row status=ACKNOWLEDGED.
- Reconciliation: ABS(SUM(payments.collection_amount) - zp0063.total_settlement_amount) = 0 KRW.
- No discrepancy alert fired in OPS alerting channel for this batch run.
- settlement_batches table row transitions through status sequence: GENERATED -> SENT -> ACKNOWLEDGED -> SETTLED with all timestamps populated.
**Depends on:** 16.2-T12, 16.2-T08

### 16.2-T15 — Verify pool-identity assertion is live in production for cross-border rate paths  _(30 min)_
**Context:** The rate engine pool identity invariant must hold for all non-same-currency transactions: collection_usd - collection_margin_usd - payout_margin_usd = payout_usd_cost (tolerance 0.01 USD). For GME Remit domestic (KRW/KRW/KRW), the USD pool is skipped, but the assertion logic must still be in production code for the future inbound/overseas direction. OPS-13 §12.1.2 requires pool-identity assertion enabled and tested. An assertion failure must produce a POOL_IDENTITY_BREACH alert to the OPS alerting channel and the payment must be rejected (not committed). This ticket verifies the assertion is active in production via a staging-equivalent test injected into prod monitoring.
**Steps:** Review application code or config to confirm POOL_IDENTITY_ASSERTION_ENABLED=true in production (not a feature flag, a code-level invariant check).; In the admin or test tooling, inject a synthetic quote request with manually crafted values that violate the identity (e.g. set collection_usd=100, collection_margin_usd=2, payout_margin_usd=3, payout_usd_cost=96 -- difference is 1.0 USD > 0.01 tolerance).; Confirm the API rejects the synthetic request with error_code=POOL_IDENTITY_BREACH and HTTP 500.; Confirm POOL_IDENTITY_BREACH alert fires in the OPS alerting channel within 60 seconds.; Verify no payment record is written to the DB when the assertion fails (atomic rejection).
**Deliverable:** Pool-identity assertion activation confirmed in production with alert wiring verified; test evidence recorded in go-live checklist item 12.
**Acceptance / logic checks:**
- Synthetic violation (|collection_usd - collection_margin_usd - payout_margin_usd - payout_usd_cost| = 1.0 USD > 0.01 tolerance) is rejected with error_code=POOL_IDENTITY_BREACH.
- No payments row created for the rejected synthetic request (SELECT COUNT(*) WHERE idempotency_key=<test_key> = 0).
- OPS alerting channel receives POOL_IDENTITY_BREACH alert within 60 seconds of the rejection.
- A valid cross-border quote (|pool difference| < 0.01 USD) is accepted without triggering the alert.
- Go-live checklist item 12 (Pool-identity assertion alert wired to OPS alerting channel) is marked checked.
**Depends on:** 16.2-T04, 16.2-T09

### 16.2-T16 — Verify rate-lock behaviour in production: committed transactions are immune to treasury rate changes  _(30 min)_
**Context:** Rate engine spec: on commit, all USD-pool values + derived rates are permanently recorded (rate-lock); later treasury/margin changes never affect committed transactions. This is a hard invariant. For GME Remit domestic (same-currency short-circuit), rate-lock applies to collection_amount = target_payout + service_charge (service_charge=500 KRW is config, not treasury-rate-dependent). For future cross-border rules, rate-lock must prevent retroactive repricing. Go-live checklist item 13: rate-lock verified in production environment.
**Steps:** Create a completed test payment in production (use the MPM smoke transaction from 16.2-T12 or a dedicated test).; Record the payment_id and its stored collection_amount and rate_snapshot (offer_rate, cost_rate_coll, cost_rate_pay, collection_usd if applicable).; In Admin System, update treasury.usd_krw to a significantly different value (e.g. change from 1350 to 1400).; Re-read the completed payment record via GET /admin/payments/{id} and confirm all rate fields are unchanged.; Revert treasury.usd_krw to the correct value; confirm the rate-lock test entry in the go-live checklist.
**Deliverable:** Rate-lock test result showing a committed payment record is unaffected by a subsequent treasury rate change.
**Acceptance / logic checks:**
- payment record rate_snapshot fields (offer_rate, cost_rate_coll, cost_rate_pay) are identical before and after the treasury rate update.
- collection_amount on the committed payment row is unchanged after the treasury rate change (e.g. still 10500 KRW if target was 10000).
- A NEW quote issued after the rate change reflects the updated treasury.usd_krw value in the new offer_rate.
- No background job or trigger retroactively updates committed payment rate fields when treasury rates change.
- Go-live checklist item 13 (Rate-lock behavior verified in production environment) is marked checked.
**Depends on:** 16.2-T12, 16.2-T10

### 16.2-T17 — Validate all monitoring dashboards and alert catalog in production (OPS-13 §7.5 / §12.1.6)  _(45 min)_
**Context:** OPS-13 §12.1.6 requires all alert catalog entries configured and tested with synthetic alerts, PagerDuty/on-call rotation set, dashboards reviewed by Ops, log aggregation confirmed, and distributed tracing end-to-end (8-step trail visible in Admin System). Alert catalog includes: POOL_IDENTITY_BREACH, BATCH_MISSED_WINDOW, LOW_BALANCE (USD 10000 threshold - Phase 4), PAYMENT_ERROR_RATE_HIGH (>2%), SFTP_CONNECTIVITY_FAILURE, DB_REPLICATION_LAG. OPS-13 §12.3 specifies dashboards covering: payment TPS, error rate, batch job status, prefunding balances, settlement reconciliation status.
**Steps:** Navigate to the monitoring platform (Grafana/Datadog/equivalent); confirm all 6+ required dashboards are accessible and showing live prod data.; Fire each synthetic alert in the alert catalog using test tooling; verify each fires to the correct PagerDuty integration within 60 seconds.; Confirm log aggregation: search for a known log line from a recent payment transaction and confirm it appears in the aggregator within 2 minutes.; In Admin System, open the transaction detail for a completed payment and verify all 8 audit trail steps are visible with timestamps (distributed tracing end-to-end).; Obtain Ops Lead sign-off on the monitoring dashboard review.
**Deliverable:** Monitoring validation report: all dashboards live, all alerts firing correctly, log aggregation and tracing confirmed; Ops Lead sign-off obtained.
**Acceptance / logic checks:**
- All 6 required dashboards are visible in the monitoring platform with non-empty data from the last 5 minutes.
- Synthetic PAYMENT_ERROR_RATE_HIGH alert fires to PagerDuty within 60 seconds; on-call engineer receives the notification.
- A log search for a recent payment_id in the log aggregator returns results within 120 seconds of log emission.
- Admin System transaction detail for a completed payment shows exactly 8 audit trail entries (RECEIVED through COMPLETED) in chronological order.
- PagerDuty escalation path is confirmed: P1 alert reaches primary on-call within 5 minutes, secondary within 15 minutes.
**Depends on:** 16.2-T12, 16.2-T08

### 16.2-T18 — Complete security pre-go-live sign-off (SEC-09 §9 checklist and penetration test remediation)  _(45 min)_
**Context:** OPS-13 §12.1.7 requires: security review sign-off per SEC-09 §9 (penetration test or equivalent for Phase 3), all production secrets rotated (done in 16.2-T03), break-glass access procedure documented and tested, audit logging confirmed. SEC-09 states external pen test required before Phase 3 go-live (Aug 2026); all critical/high findings must be remediated before launch. QA-12 §11.3 exit gate: 0 CRITICAL or HIGH security findings open.
**Steps:** Retrieve the penetration test report (conducted Aug 2026 per SEC-09); confirm all CRITICAL and HIGH findings are marked as REMEDIATED with PR/commit references.; Execute the break-glass access test: a designated engineer retrieves a production secret via the emergency path (break-glass IAM role or equivalent) within 5 minutes; revoke the session after.; Verify audit logging: perform 3 Admin System actions (rate update, partner view, logout) and confirm each appears in the audit_log table with actor, action, timestamp, ip_address.; Confirm all Admin System endpoints require authentication (attempt unauthenticated GET /admin/partners; expect HTTP 401).; Security Lead signs the security sign-off certificate.
**Deliverable:** Security sign-off certificate signed by Security Lead; pen test finding closure report; break-glass test log.
**Acceptance / logic checks:**
- Pen test report shows 0 CRITICAL and 0 HIGH open findings at the time of sign-off.
- Break-glass access session activated and revoked within 10 minutes; IAM access log shows the session with duration.
- audit_log table has 3 new rows with columns: actor (email), action, entity_type, entity_id, timestamp (UTC), ip_address, all non-null.
- GET /admin/partners without authentication returns HTTP 401 (not 200 or 403).
- Security sign-off certificate contains Security Lead name, signature, and date >= 7 days before go-live Oct 10 2026.
**Depends on:** 16.2-T03, 16.2-T05

### 16.2-T19 — Conduct GME Ops training walkthrough on Admin System and confirm setup-time target  _(60 min)_
**Context:** PM-14 §5.3: GME Ops team trained; Admin portal operational runbook confirmed per OPS-13. BRD §11 success metric: new scheme or partner configured in under 30 minutes. OPS-13 §12.1.8: Ops team trained on Admin System (partner setup, exception processing, FX rate update); runbook sign-off obtained from Ops Lead. Training must cover the Phase 3 go-live scope: GME Remit partner management, ZeroPay settlement batch monitoring, FX rate manual update, exception handling workflow, and hypercare procedures.
**Steps:** Schedule and conduct a 60-minute Admin System walkthrough session with GME Ops team covering: partner management, rule config, FX rate update, settlement batch monitoring, exception queue handling.; Timed exercise: Ops user creates a new test partner record with a rule from scratch; measure elapsed time from start to rule saved.; Timed exercise: Ops user updates treasury.usd_krw rate; measure elapsed time.; Walk through the exception handling screen: demonstrate resolving a simulated settlement discrepancy.; Ops Lead signs off on the operational runbook (OPS-13) and the training completion certificate.
**Deliverable:** Training completion certificate signed by GME Ops Lead; timed exercise results showing partner setup < 30 minutes.
**Acceptance / logic checks:**
- Timed partner creation exercise: partner record + rule created in <= 30 minutes by a trained Ops user working alone.
- Timed rate update exercise: treasury.usd_krw updated in Admin System in <= 5 minutes.
- Ops user can locate a transaction in Admin System by payment_id and view its 8-step audit trail without developer assistance.
- Ops Lead has signed the OPS-13 runbook sign-off page with name and date.
- Ops team can identify the on-call contact and escalation path for a P1 incident without referring to external documentation.
**Depends on:** 16.2-T17, 16.2-T10

### 16.2-T20 — Obtain UAT sign-off certificate from GME Ops and Finance (Phase 3 gate pre-condition)  _(60 min)_
**Context:** PM-14 milestone: UAT sign-off Sep 26 2026. Phase 3 acceptance criteria (PM-14 §5.3): UAT completed and signed off by GME Ops and Finance per QA-12 UAT criteria. QA-12 §12.2 go-live readiness checklist item 1: All P1 UAT scenarios signed off. UAT entry requires staging environment with production-like data, all ZP00xx batch types verified, and GME Remit staging integration test complete (milestone Sep 5 2026). UAT exit: 0 P1/P2 defects open; all rate-engine vectors RV-01 to RV-10 pass; pool identity assertion active.
**Steps:** Confirm UAT entry criteria met in staging: staging integration test complete (Sep 5 milestone), all P1/P2 defects from E2E testing closed, rate-engine vectors RV-01 to RV-10 all passing.; Execute UAT test scripts with GME Ops users: domestic MPM and CPM happy path, settlement batch, exception handling, FX rate update, partner config.; Execute UAT test scripts with GME Finance: settlement reconciliation, revenue ledger accuracy, BOK reporting data capture (even if reporting itself is pending OI-03).; Triage any defects found in UAT; confirm all P1 and P2 defects are resolved before sign-off.; GME Product Owner signs the UAT sign-off certificate; GME Finance Lead countersigns.
**Deliverable:** Signed UAT sign-off certificate from GME Product Owner and Finance Lead; UAT defect log showing 0 open P1/P2.
**Acceptance / logic checks:**
- UAT sign-off certificate bears signatures of both GME Product Owner and Finance Lead with date >= Sep 26 2026.
- UAT defect log shows 0 open P1 and 0 open P2 defects at the time of sign-off.
- All rate-engine test vectors RV-01 to RV-10 confirmed passing in the staging environment per QA-12 §4.2.
- Domestic MPM happy path (target_payout=13500 KRW -> collection_amount=14000 KRW) executed and signed off by Ops tester.
- BOK reporting data fields (FX1014/FX1015 relevant fields) are captured in bok_report_record table rows even while FEATURE_BOK_REPORTING=OFF.
**Depends on:** 16.2-T13, 16.2-T14, 16.2-T18, 16.2-T19

### 16.2-T21 — Complete go-live readiness checklist (QA-12 §12.2, OPS-13 §12.1) and obtain Release Manager sign-off  _(45 min)_
**Context:** QA-12 §12.2 defines a 14-item go-live readiness checklist; OPS-13 §12.1 has 8 sub-sections of infrastructure/application/partner/monitoring/security/ops checklists. Both must be fully checked before the cutover window. The checklist deliverable from QA-12 is due the day before go-live (Oct 9 2026). It must be signed by Release Manager, Engineering Lead, Ops Lead, and Security Lead. Any unchecked item must be documented with a risk acceptance by GME Product Owner.
**Steps:** Aggregate the status of all 14 QA-12 §12.2 items and all OPS-13 §12.1 sub-sections into a single go-live readiness document.; For each unchecked item, document: item description, reason not completed, risk level, risk acceptance owner.; Circulate the document to Release Manager, Engineering Lead, Ops Lead, and Security Lead for review.; Resolve any last-minute blockers; obtain the four signatures.; File the signed checklist in the project change management system as the official go-live authorisation.
**Deliverable:** Fully completed and signed go-live readiness checklist document (PDF or equivalent) with four sign-offs.
**Acceptance / logic checks:**
- All 14 QA-12 §12.2 checklist items are checked OR have a documented risk acceptance signed by GME Product Owner.
- All OPS-13 §12.1 sub-sections (12.1.1 through 12.1.8) have at least one checkbox checked per item.
- Document carries four signatures: Release Manager, Engineering Lead, Ops Lead, Security Lead.
- Document completion timestamp is on or before Oct 9 2026 (day before go-live).
- Any unchecked item has a named risk-acceptance owner (not anonymous) and a risk severity of LOW (no HIGH or CRITICAL items left unchecked without escalation).
**Depends on:** 16.2-T15, 16.2-T16, 16.2-T17, 16.2-T18, 16.2-T19, 16.2-T20

### 16.2-T22 — Execute production cutover procedure (go-live window Oct 10 2026) per OPS-13 §12.2  _(30 min)_
**Context:** OPS-13 §12.2 cutover procedure: confirm all checklist items checked; set go-live window (recommend Tuesday/Wednesday 06:00 KST after overnight batch); announce to GME Remit and SendMN tech contacts >= 48 hours in advance; at T-1h engineering and ops on live call; at T-0 flip load balancer to accept traffic and enable partner API keys; run 5-minute smoke test suite. Go-live window: Oct 10 2026, recommend 06:00 KST (21:00 UTC Oct 9). GME Remit is the only active partner at Phase 3 go-live.
**Steps:** Send go-live window announcement to GME Remit tech contacts at least 48 hours before Oct 10 06:00 KST.; At T-1h (05:00 KST Oct 10): engineering lead and ops lead join the live call channel; open all monitoring dashboards; confirm overnight batch completed successfully.; At T-0 (06:00 KST Oct 10): enable GME Remit API key in Admin System (status ACTIVE); flip prod load balancer to accept external traffic.; Run the 5-minute go-live smoke test suite (see 16.2-T23 for the detailed smoke test steps).; If smoke tests pass: declare go-live confirmed in team channel. If any smoke test fails: execute rollback per OPS-13 §12.3.
**Deliverable:** Go-live confirmed declaration in team channel; monitoring dashboards showing live traffic from GME Remit; smoke test pass record.
**Acceptance / logic checks:**
- GME Remit API key status in Admin System transitions from INACTIVE/STAGING to ACTIVE at T-0 timestamp.
- Monitoring dashboard shows first real payment request arriving within 30 minutes of T-0.
- Smoke test suite (16.2-T23) completes within 5 minutes with all tests passing.
- No P1 alerts fire in the 15 minutes immediately following T-0.
- Go-live confirmed message posted to team channel by Engineering Lead includes: timestamp, smoke test result summary, and first real payment_id observed.
**Depends on:** 16.2-T21

### 16.2-T23 — Execute and document the 5-minute go-live smoke test suite (OPS-13 §12.2)  _(20 min)_
**Context:** OPS-13 §12.2 specifies the go-live smoke tests (5 minutes total): (1) Issue rate quote for GME Remit domestic and confirm collection_amount = target_payout + 500 KRW; (2) Verify webhook delivery to GME Remit test endpoint; (3) Verify /health endpoint returns 200. Additionally, QA-12 §12.2 items 3 (rate-engine vectors), 12 (pool-identity alert), and 13 (rate-lock) should be spot-checked. These tests must be scripted and runnable by the ops team without developer intervention. A failed smoke test triggers the rollback procedure (OPS-13 §12.3).
**Steps:** Run smoke test 1: POST /v1/quotes with partner=GME Remit, target_payout=10000 KRW; assert response collection_amount=10500 KRW and status 200.; Run smoke test 2: GET /health; assert HTTP 200 and body {status: UP, db: UP, sftp: UP, queue: UP}.; Run smoke test 3: send a test webhook ping to GME Remit webhook URL; assert HTTP 200 acknowledgement received within 10 seconds.; Run smoke test 4: GET /admin/prefunding/gme-remit (if applicable for LOCAL partner balance view) or verify Admin System loads the GME Remit partner page without error.; Record PASS/FAIL for each test with timestamp; if all PASS declare go-live confirmed; if any FAIL trigger rollback (suspend GME Remit API key, redeploy previous image, notify GME Remit).
**Deliverable:** Smoke test results document (4 tests, each PASS/FAIL with timestamp and actual response value) completed within 5 minutes of T-0.
**Acceptance / logic checks:**
- Smoke test 1 result: collection_amount = 10500 KRW (10000 + 500 service_charge), HTTP 200.
- Smoke test 2 result: GET /health returns HTTP 200 with all component statuses = UP within 2 seconds.
- Smoke test 3 result: GME Remit webhook endpoint acknowledges the test ping with HTTP 200 within 10 seconds.
- All 4 smoke tests complete within 5 minutes of starting.
- If any test returns a non-passing result, rollback is triggered within 2 minutes (API key suspended; rollback initiated per OPS-13 §12.3).
**Depends on:** 16.2-T22

### 16.2-T24 — Confirm production settlement batch runs for 3 consecutive business days without exception  _(30 min)_
**Context:** PM-14 §5.3 Phase 3 acceptance criterion: settlement batch runs without exception for 3 consecutive business days. This ticket covers monitoring and confirming the batch results on days 2 and 3 post-go-live (day 1 covered by 16.2-T14). Each day: ZP0061 sent, ZP0063 received, reconciliation = 0 KRW discrepancy, no exception alert fired. GME Ops performs the daily check (09:00 and 18:00 KST per OPS-13 §12.4 hypercare schedule). Failure on any of the 3 days requires an incident report and the 3-day counter resets.
**Steps:** On each of the 3 business days (Oct 10-14 2026, skipping weekends): at 09:00 KST Ops check-in, review the settlement_batches table for the previous day batch status=SETTLED.; For each batch, run the reconciliation query: ABS(SUM(payments.collection_amount WHERE batch_id=X) - zp0063.total) = 0 KRW.; Confirm no BATCH_MISSED_WINDOW or SETTLEMENT_DISCREPANCY alerts fired overnight.; Log the result in the hypercare daily postmortem review document.; After 3 clean consecutive days, mark PM-14 §5.3 settlement batch criterion as PASSED.
**Deliverable:** Hypercare batch monitoring log for 3 consecutive clean business days; PM-14 §5.3 settlement criterion signed off.
**Acceptance / logic checks:**
- 3 settlement_batches rows (one per business day) with status=SETTLED and reconciliation_discrepancy_krw=0.
- Zero BATCH_MISSED_WINDOW or SETTLEMENT_DISCREPANCY alerts in the OPS alerting channel during the 3-day window.
- Each ZP0061 file upload timestamp is within 5 minutes of the scheduled KST batch window for that day.
- ZP0062 acknowledgement received from KFTC within 2 hours of ZP0061 upload for each of the 3 days.
- GME Finance Lead countersigns the 3-day settlement confirmation log as acceptance evidence for PM-14 §5.3.
**Depends on:** 16.2-T14, 16.2-T22

### 16.2-T25 — Confirm 10+ end-to-end production transactions (mixed MPM and CPM) as PM-14 §5.3 acceptance  _(30 min)_
**Context:** PM-14 §5.3 acceptance: at least 10 end-to-end live production transactions processed successfully (MPM and CPM). Tickets 16.2-T12 (MPM) and 16.2-T13 (CPM) cover the first MPM and CPM transactions. This ticket confirms the full count of 10+ across both modes with a variety of target_payout amounts, verifying each has status=COMPLETED, correct collection_amount (target_payout + 500 KRW), and a full 8-step audit trail. Success rate >= 98% must be confirmed (BRD §11 metric).
**Steps:** Query production DB: SELECT COUNT(*) FROM payments WHERE partner_id=gme-remit AND status=COMPLETED AND created_at >= go-live timestamp; confirm >= 10 rows.; Confirm at least 1 row with payment_mode=MPM and at least 1 with payment_mode=CPM.; For each of the 10+ rows, verify collection_amount = target_payout_amount + 500 (service_charge invariant).; Calculate success rate: completed / (completed + failed) for the same time window; confirm >= 98%.; Record the transaction IDs and summary in the Phase 3 gate confirmation report for GME Product Owner.
**Deliverable:** Phase 3 gate confirmation report listing 10+ production payment IDs (mixed MPM/CPM) with status=COMPLETED and success rate >= 98%.
**Acceptance / logic checks:**
- SELECT COUNT(*) FROM payments WHERE partner_id=gme-remit AND status=COMPLETED >= 10.
- At least 1 payment with payment_mode=MPM and at least 1 with payment_mode=CPM in the COMPLETED set.
- For all 10+ rows: ABS(collection_amount - (target_payout_amount + 500)) = 0 KRW.
- Payment success rate = COMPLETED / (COMPLETED + FAILED) >= 0.98 for the go-live day window.
- Phase 3 gate confirmation report signed by GME Product Owner with go-live date Oct 10 2026.
**Depends on:** 16.2-T13, 16.2-T24

### 16.2-T26 — Initiate and manage hypercare period (Oct 10-24 2026): dual on-call, daily check-ins, postmortems  _(45 min)_
**Context:** OPS-13 §12.4 hypercare period: Oct 10-24 2026 (14 days). Two engineers on call 24/7 (primary + secondary). Twice-daily Ops check-in at 09:00 and 18:00 KST reviewing overnight batch results and transaction metrics. Daily postmortem review even with zero incidents. Partner check-in: daily call with GME Remit tech for first 5 business days. Escalation threshold: any P2 alert treated as P1 during hypercare. Hypercare ends at day 14 if: zero P1 incidents, SLOs met, at least one full batch cycle completed on each of 10 consecutive days. This ticket covers the setup and daily cadence; resolution is at day 14.
**Steps:** Activate dual on-call rotation in PagerDuty for Oct 10-24: assign primary and secondary engineers; verify escalation path reaches both within 5 minutes.; Lower the P2-to-P1 escalation threshold in PagerDuty alert config for the hypercare window.; Schedule recurring daily check-ins: 09:00 KST and 18:00 KST in the team calendar with the hypercare checklist template.; Schedule daily partner check-in calls with GME Remit tech contact for Oct 10-16 (first 5 business days).; At day 14 (Oct 24): assess hypercare exit criteria; if met, declare end of hypercare and restore normal on-call thresholds.
**Deliverable:** Hypercare exit declaration (Oct 24 2026 or later) with evidence: zero P1 incidents, SLOs met for 10 consecutive batch days, on-call rotation log.
**Acceptance / logic checks:**
- PagerDuty on-call schedule for Oct 10-24 shows two engineers assigned (primary + secondary) for every 24-hour window.
- P2 alert routing escalates to primary on-call within 5 minutes during the hypercare window (test synthetic P2 alert at go-live +1h).
- 10 consecutive batch days (Oct 10-23 excl. weekends) all show batch status=SETTLED with 0 discrepancy.
- Zero P1 incidents recorded in the incident log for the Oct 10-24 window at the time of hypercare exit.
- Hypercare exit declaration signed by Engineering Lead and Ops Lead on or after Oct 24 2026.
**Depends on:** 16.2-T22, 16.2-T24

### 16.2-T27 — Unit tests: same-currency short-circuit rate engine for GME Remit Domestic (KRW/KRW/KRW)  _(30 min)_
**Context:** Rate engine canonical rule: if collection = settle_A = settle_B = payout (same currency), skip the USD pool entirely; collection_amount = target_payout + service_charge. For GME Remit domestic: m_a=0.0, m_b=0.0, service_charge=500 KRW. This is rate-engine test vector RV-04 from QA-12 §4.2: target_payout=13500 KRW, service_charge=500 KRW -> collection_amount=14000 KRW. USD-pool fields (collection_usd, payout_usd_cost, collection_margin_usd, payout_margin_usd) must all be null/zero. offer_rate_coll and cross_rate must not be set (N/A for same-currency). service_charge must NEVER enter the USD pool calculation.
**Steps:** Write unit test class RateEngineShortCircuitTest (or equivalent) in the rate engine module.; Test case RV-04-a: input target_payout=13500 KRW, ccy=KRW/KRW, service_charge=500 KRW -> assert collection_amount=14000 KRW.; Test case RV-04-b: input target_payout=0 KRW -> assert collection_amount=500 KRW (service charge still applies).; Test case RV-04-c: input target_payout=1000000 KRW -> assert collection_amount=1000500 KRW.; Test case RV-04-d: assert that payout_usd_cost, collection_usd, collection_margin_usd, payout_margin_usd are all null or 0 in the result object for any same-currency input.
**Deliverable:** RateEngineShortCircuitTest class with 4 test cases; all pass in CI.
**Acceptance / logic checks:**
- RV-04-a: collection_amount == 14000 KRW exactly (no floating-point deviation).
- RV-04-b: collection_amount == 500 KRW when target_payout == 0.
- RV-04-c: collection_amount == 1000500 KRW; no USD pool fields populated.
- RV-04-d: payout_usd_cost is null and collection_usd is null in the RateResult object for KRW/KRW input.
- All 4 tests pass in CI with 0 failures; test coverage for the short-circuit branch >= 100%.
**Depends on:** 16.2-T05

### 16.2-T28 — Unit tests: rate-lock immutability for committed transactions  _(30 min)_
**Context:** Rate-lock invariant: once a payment transitions to COMPLETED status, its stored rate_snapshot (offer_rate_coll, cost_rate_coll, cost_rate_pay, collection_usd, payout_usd_cost) must be immutable. No treasury rate change, margin change, or admin action may retroactively alter committed rate fields. This must be enforced at the service/repository layer, not just by convention. Test must cover: (1) direct DB update attempt, (2) service-layer rate-refresh call on a committed payment, (3) admin rate change does not affect existing COMPLETED rows.
**Steps:** Write unit test RateLockImmutabilityTest with a mock payment repository.; Test RLL-01: attempt to call rateEngine.refreshRate(payment) where payment.status=COMPLETED; assert RateLockViolationException is thrown.; Test RLL-02: simulate a treasury rate change event; verify that the payment repository does NOT issue an UPDATE on rate_snapshot fields for rows where status=COMPLETED.; Test RLL-03: verify that for a PENDING payment, refreshRate() succeeds and updates the rate_snapshot.; Integration test RLL-04: create a COMPLETED payment in test DB, update treasury.usd_krw, query the payment row, assert offer_rate_coll unchanged.
**Deliverable:** RateLockImmutabilityTest class with 4 test cases; all pass in CI.
**Acceptance / logic checks:**
- RLL-01: RateLockViolationException thrown when refreshRate called on COMPLETED payment.
- RLL-02: mock payment repository UPDATE method is never called with rate_snapshot column when event is a treasury rate change and target row status=COMPLETED.
- RLL-03: refreshRate() on a PENDING payment calls repository UPDATE and returns updated RateResult without exception.
- RLL-04 integration: treasury.usd_krw updated from 1350 to 1400; COMPLETED payment row offer_rate_coll unchanged (still reflects 1350-derived value).
- All 4 tests pass in CI; RateLockViolationException is a checked exception with a meaningful message referencing the payment_id.
**Depends on:** 16.2-T04

### 16.2-T29 — Unit tests: 8-step audit trail completeness for domestic MPM payment lifecycle  _(35 min)_
**Context:** Every payment must carry an 8-step event trail: RECEIVED, VALIDATED, RATE_APPLIED, SCHEME_SENT, SCHEME_CONFIRMED, SETTLEMENT_QUEUED, WEBHOOK_SENT, COMPLETED. For GME Remit domestic MPM the trail must be populated in this exact order; no step may be skipped. Audit trail is persisted in payment_events table (or equivalent). RATE_APPLIED step must record rate_snapshot fields; for same-currency short-circuit, rate_snapshot = {mode: SAME_CURRENCY_SHORT_CIRCUIT, collection_amount, service_charge}. Tests must verify step ordering, completeness, and that RATE_APPLIED captures the correct snapshot.
**Steps:** Write unit/integration test PaymentAuditTrailTest.; Test PAT-01: process a complete domestic MPM payment through the service layer using test doubles; assert payment_events table has exactly 8 rows in sequence order.; Test PAT-02: assert payment_events row for RATE_APPLIED contains rate_snapshot with mode=SAME_CURRENCY_SHORT_CIRCUIT and collection_amount=target_payout+500.; Test PAT-03: simulate SCHEME_CONFIRMED timing out; assert trail stops at SCHEME_SENT and payment status=FAILED (no subsequent steps written).; Test PAT-04: assert that each payment_events row has a non-null created_at timestamp and that timestamps are in non-decreasing order.
**Deliverable:** PaymentAuditTrailTest class with 4 test cases; all pass in CI.
**Acceptance / logic checks:**
- PAT-01: payment_events count = 8 for a completed domestic MPM; event_type values in order match the 8-step sequence exactly.
- PAT-02: RATE_APPLIED event row has meta.mode=SAME_CURRENCY_SHORT_CIRCUIT and meta.collection_amount=target_payout+service_charge.
- PAT-03: after scheme timeout, payment_events count = 4 (RECEIVED, VALIDATED, RATE_APPLIED, SCHEME_SENT); payment status=FAILED; no SCHEME_CONFIRMED row exists.
- PAT-04: all 8 event timestamps are non-null and in non-decreasing chronological order.
- All 4 tests pass in CI with 0 failures.
**Depends on:** 16.2-T12

### 16.2-T30 — Unit tests: webhook HMAC-SHA256 signature generation and retry logic  _(35 min)_
**Context:** Webhooks to GME Remit are signed with HMAC-SHA256 using the partner shared secret (stored hashed in DB; signing uses the raw secret from secrets manager). X-GMEPay-Signature = HMAC-SHA256(request_body_bytes, raw_shared_secret). Delivery retry policy: 3 attempts, exponential backoff (t, t+30s, t+90s), then delivery_status=FAILED. These unit tests must cover: correct signature for a known payload, signature mismatch detection, and the retry state machine.
**Steps:** Write unit test WebhookSignatureTest: given payload=testbody and secret=abc123, assert X-GMEPay-Signature = HMAC-SHA256(testbody, abc123) in hex.; Write unit test WebhookRetryTest: mock the HTTP client to return 500 three times; assert deliveries table has 3 attempts at intervals approx 0s, 30s, 90s; final status=FAILED.; Write test WebhookRetrySuccessTest: mock HTTP client returns 500 twice then 200; assert delivery_status=DELIVERED after 3rd attempt, attempts=3.; Write test WebhookSecretIsolationTest: confirm the hashed secret in DB is never passed as the signing key (service must fetch raw secret from secrets manager, not DB hash).; All 4 tests pass in CI.
**Deliverable:** WebhookSignatureTest and WebhookRetryTest classes with 4 test cases; all pass in CI.
**Acceptance / logic checks:**
- WebhookSignatureTest: computed signature matches independently calculated HMAC-SHA256(testbody, abc123) in lowercase hex.
- WebhookRetryTest: 3 HTTP calls logged; delivery_attempts=3; delivery_status=FAILED; timestamps approximate 0s, 30s, 90s intervals.
- WebhookRetrySuccessTest: delivery_status=DELIVERED after third attempt (first two return 500, third returns 200); attempts=3.
- WebhookSecretIsolationTest: the signing key passed to HMAC function is sourced from secrets manager, not from the hashed DB column (verified via mock assertion).
- All 4 tests pass in CI; test execution time < 5 seconds total.
**Depends on:** 16.2-T11

### 16.2-T31 — Integration test: GME Remit Domestic end-to-end MPM flow in staging environment  _(45 min)_
**Context:** This integration test validates the full northbound-to-southbound flow in the staging environment (E3): POST /v1/payments (MPM) -> rate engine (same-currency short-circuit) -> ZP0011 batch registration -> ZP0063 settlement confirmation mock -> webhook delivery -> COMPLETED. Uses synthetic partner P-TEST-001 (TestRemit, LOCAL, KRW). Treasury rate not needed for same-currency. Service charge = 500 KRW. Must pass before UAT entry (QA-12 exit gate: all integration tests green, 0 P1/P2).
**Steps:** Seed staging DB with P-TEST-001 (TestRemit, LOCAL, KRW/KRW/KRW, service_charge=500 KRW, ZeroPay Domestic rule, m_a=0.0, m_b=0.0).; Call POST /v1/payments on staging API with partner key for P-TEST-001, qr_code=QR-TEST-0001, target_payout=13500 KRW.; Assert response: HTTP 200, collection_amount=14000 KRW, payment_id non-null, status=COMPLETED or PROCESSING.; Mock the ZP0011 confirmation callback from ZeroPay stub; assert payment transitions to COMPLETED.; Assert 8-step audit trail in payment_events; assert webhook delivered to test webhook receiver with correct HMAC signature.
**Deliverable:** Integration test E2E_DOMESTIC_MPM_001 in the automated test suite; passing in CI staging pipeline.
**Acceptance / logic checks:**
- POST /v1/payments returns HTTP 200 with collection_amount=14000 KRW (13500+500).
- payment row: status=COMPLETED, direction=Domestic, payment_mode=MPM, scheme=ZeroPay, partner_id=P-TEST-001.
- ZP0011 batch queue entry exists with correct payment_id, merchant_id=M-TEST-0001, amount=13500 KRW.
- Webhook receiver captured 1 delivery with event_type=payment.completed and valid HMAC signature.
- Test E2E_DOMESTIC_MPM_001 is GREEN in the CI staging pipeline and runtime <= 10 seconds.
**Depends on:** 16.2-T27, 16.2-T29, 16.2-T30

### 16.2-T32 — Integration test: GME Remit Domestic CPM token lifecycle in staging environment  _(45 min)_
**Context:** CPM flow in staging: POST /v1/payments/cpm/generate (P-TEST-001) -> qr_token issued (TTL=60s) -> merchant scan via ZeroPay stub (ZP0012) -> COMPLETED. Token expiry and double-spend rejection must be tested. Same collection_amount = target_payout + 500 KRW rule applies. Staging uses the 한결원 test SFTP environment (E2/E3 per QA-12 §2.3).
**Steps:** Call POST /v1/payments/cpm/generate for P-TEST-001 with target_payout=10000 KRW; receive qr_token and validUntil.; Simulate merchant scan via ZeroPay stub sending ZP0012 response; confirm payment completes with collection_amount=10500 KRW.; Test token expiry: freeze time past validUntil; attempt to use the same qr_token via a simulated ZP0012; assert Hub Core rejects with QR_TOKEN_EXPIRED and payment status=FAILED.; Test double-spend: use the same qr_token twice before expiry; assert second use rejected with QR_TOKEN_ALREADY_USED.; Assert 8-step audit trail for the successful CPM payment.
**Deliverable:** Integration test E2E_DOMESTIC_CPM_001 in the automated test suite; passing in CI staging pipeline.
**Acceptance / logic checks:**
- POST /v1/payments/cpm/generate returns qr_token and validUntil = issued_at + 60000ms; HTTP 200.
- Successful CPM payment: collection_amount=10500 KRW, status=COMPLETED, payment_mode=CPM.
- Token expiry test: Hub Core returns error_code=QR_TOKEN_EXPIRED for a post-TTL ZP0012; payment status=FAILED.
- Double-spend test: second ZP0012 for same qr_token returns error_code=QR_TOKEN_ALREADY_USED.
- Test E2E_DOMESTIC_CPM_001 is GREEN in CI staging pipeline.
**Depends on:** 16.2-T31

### 16.2-T33 — Integration test: settlement reconciliation zero-discrepancy and discrepancy alert path  _(40 min)_
**Context:** Reconciliation must produce 0 KRW discrepancy for domestic payments (same-currency, no FX rounding). Two tests: (1) happy path: multiple completed payments, ZP0063 settlement total matches DB sum exactly; (2) discrepancy path: inject a tampered ZP0063 with a 1 KRW difference; assert SETTLEMENT_DISCREPANCY alert fires and batch status=EXCEPTION. Discrepancy tolerance = 0 KRW for domestic (no rounding). Alert must fire within 1 hour per OPS-13 (for this test: within the test timeout of 5 seconds using mock alerting).
**Steps:** Seed test DB with 5 completed payments for P-TEST-001 totalling 67500 KRW collection_amount (5 x 13500 KRW).; Ingest a mock ZP0063 file with total_settlement_amount=67500 KRW; assert reconciliation passes with discrepancy=0 and batch status=SETTLED.; Ingest a tampered ZP0063 with total_settlement_amount=67499 KRW (1 KRW short); assert reconciliation detects discrepancy=1 KRW and batch status=EXCEPTION.; Assert SETTLEMENT_DISCREPANCY alert is published to the mock alerting channel within the test.; Assert batch status=EXCEPTION row in settlement_batches table with discrepancy_amount=1 and discrepancy_ccy=KRW.
**Deliverable:** Integration tests RECON_001 (happy path) and RECON_002 (discrepancy) in the automated test suite; both passing in CI.
**Acceptance / logic checks:**
- RECON_001: reconciliation result discrepancy_amount=0 KRW; settlement_batches status=SETTLED.
- RECON_002: reconciliation detects discrepancy=1 KRW; settlement_batches status=EXCEPTION; discrepancy_amount=1, discrepancy_ccy=KRW.
- RECON_002: mock alerting channel receives exactly 1 SETTLEMENT_DISCREPANCY event with batch_id and discrepancy_amount in the payload.
- Both tests are GREEN in CI with runtime <= 5 seconds each.
- RECON_001 with 5 payments of 13500 KRW each: DB sum = 67500 KRW = ZP0063 total exactly (no floating-point drift).
**Depends on:** 16.2-T14, 16.2-T31

### 16.2-T34 — Performance smoke test: production API p95 latency and TPS targets at Phase 3 volumes  _(60 min)_
**Context:** NFR-10 targets for Phase 3: 50 TPS sustained, 100 TPS burst, p95 latency <= 500ms for POST /v1/payments. Pre-go-live checklist OPS-13 §12.1.1: auto-scaling tested at synthetic 500 TPS. OPS-13 §12.3 open item OI-OPS-03: exact thresholds validated during load testing in staging Aug-Sep 2026. This ticket covers the final load test run in staging-equivalent production infra before go-live, using the GME Remit domestic MPM flow (computationally cheapest path: same-currency short-circuit).
**Steps:** Configure load test tool (k6, Locust, JMeter, or equivalent) with the domestic MPM POST /v1/payments scenario for P-TEST-001.; Ramp to 50 TPS sustained for 10 minutes; collect p50, p95, p99 latency and error rate.; Spike to 100 TPS burst for 2 minutes; confirm no 5xx errors and auto-scaling triggers.; Collect memory and CPU metrics from the API pod during the test; confirm no memory leak (steady-state RSS stable).; Record results in the performance test report; compare against NFR-10 thresholds.
**Deliverable:** Performance test report for the production-equivalent staging environment; p95 latency and TPS results vs. NFR-10 thresholds.
**Acceptance / logic checks:**
- 50 TPS sustained for 10 minutes: p95 latency <= 500ms; error rate = 0%.
- 100 TPS burst for 2 minutes: 0 HTTP 5xx errors; at least one auto-scaling event observed (new pod launched).
- Memory RSS of API pods is stable (< 10% growth) over the 10-minute sustained test.
- p99 latency at 50 TPS <= 1000ms (secondary NFR-10 threshold).
- Performance test report is attached to the go-live readiness checklist as evidence for OPS-13 §12.1.1 item (auto-scaling tested under synthetic load).
**Depends on:** 16.2-T02, 16.2-T31

### 16.2-T35 — Write Phase 3 go-live runbook addendum (OPS-13 §12 supplement for GME Remit domestic)  _(45 min)_
**Context:** OPS-13 §12.1.8 requires the runbook reviewed and signed off by Ops Lead before go-live. The existing OPS-13 runbook covers generic procedures; this addendum must provide GME Remit domestic-specific operational procedures: (1) how to update treasury.usd_krw manually, (2) how to investigate a settlement discrepancy for KRW domestic payments, (3) how to disable/re-enable GME Remit API key in an incident, (4) daily batch window check procedure, (5) hypercare escalation contacts. The addendum must be self-contained so an Ops team member with no developer present can execute each procedure.
**Steps:** Draft addendum section 1: FX Rate Update procedure (step-by-step Admin System navigation, what to enter, how to verify, stale-rate alert explanation).; Draft addendum section 2: Settlement Discrepancy Investigation (how to run reconciliation query, how to compare ZP0063 total vs DB sum, escalation path).; Draft addendum section 3: API Key Emergency Disable/Enable (Admin System steps, whom to notify, re-enable checklist).; Draft addendum section 4: Daily Batch Window Monitoring checklist (which jobs to check, pass criteria, escalation threshold).; Draft addendum section 5: Hypercare contact list and escalation matrix; submit for Ops Lead review and sign-off.
**Deliverable:** OPS-13 Phase 3 Runbook Addendum document (5 sections, <= 4 pages) signed off by Ops Lead before Oct 9 2026.
**Acceptance / logic checks:**
- Section 1 (FX Rate Update): a new Ops hire can complete the rate update procedure in <= 5 minutes following the steps alone.
- Section 2 (Discrepancy Investigation): includes the exact SQL reconciliation query: SELECT SUM(collection_amount) FROM payments WHERE settlement_batch_id=? and comparison logic.
- Section 3 (API Key Disable): Admin System steps are numbered and match the actual UI path (verified by Ops tester).
- Section 5 (Hypercare Contacts): lists at least 3 named contacts (Engineering Lead, Ops Lead, GME Remit tech contact) with phone/email.
- Ops Lead sign-off present on the document with name, date, and signature before Oct 9 2026.
**Depends on:** 16.2-T19, 16.2-T17


## WBS 16.3 — Phase 4 onboarding: overseas partners
### 16.3-T01 — Register T-Bank partner record in Admin System (OVERSEAS type, USD settle)  _(20 min)_
**Context:** GMEPay+ partner onboarding is pure config - no code change. An OVERSEAS partner requires: Partner Name, Partner Code (e.g. TBANK), Type=OVERSEAS, Contact Email (for low-balance alerts), Webhook URL (HTTPS), Rate Quote TTL (default 300 s), Collection Currency (MNT or per-agreement), Settlement Currency Settle-A = USD. Status starts as Draft. A prefunding_account row (currency=USD, balance=0) is auto-created when partner type is OVERSEAS. Source: PRD-07 §5.3, DAT-03 §6.1.
**Steps:** Log in to Admin System as OPS_OPERATOR or higher.; Navigate to Partners > Create Partner.; Fill Identity Section: Name=T-Bank, Code=TBANK, Type=OVERSEAS, Contact Email=ops@tbank.example, Webhook URL=https://webhook.tbank.example/gmepayplus, Rate Quote TTL=300.; Fill Settlement Configuration: Collection Currency=MNT, Settlement Currency (Settle A)=USD; verify Settlement Model auto-sets to Prefunding.; Save; confirm status=Draft and that a prefunding_account row exists in DB for TBANK with balance=0.00 USD.
**Deliverable:** Active partner record for T-Bank in the partner table with partner_code=TBANK, type=OVERSEAS, settle_a_ccy=USD, and a corresponding prefunding_account row.
**Acceptance / logic checks:**
- Partner record exists with partner_code=TBANK, type=OVERSEAS, status=Draft, settlement_currency=USD.
- prefunding_account row exists with partner_id=TBANK-id, currency=USD, balance=0.0000.
- LOW type (LOCAL) partner GME Remit does NOT have a prefunding_account row (control check).
- Audit log contains a CREATE event for TBANK with actor and timestamp.
- Attempting to activate T-Bank without a prefunding balance > 0 returns a validation error.

### 16.3-T02 — Register SendMN partner record in Admin System (OVERSEAS type, USD settle)  _(20 min)_
**Context:** SendMN is the first OVERSEAS inbound partner (PM-14 Phase 4). Fields match T-Bank pattern: Code=SENDMN, Type=OVERSEAS, Collection Currency=MNT, Settle A=USD. SendMN is listed alongside T-Bank; both must be onboarded simultaneously once confirmed by GME BD. Same schema applies: prefunding_account auto-created on save. Source: PRD-07 §5.3, BRD-01 partner table.
**Steps:** Navigate to Admin System > Partners > Create Partner.; Fill: Name=SendMN, Code=SENDMN, Type=OVERSEAS, Contact Email=ops@sendmn.example, Webhook URL=https://webhook.sendmn.example/gmepayplus, Rate Quote TTL=300.; Set Collection Currency=MNT, Settlement Currency=USD; confirm Settlement Model=Prefunding.; Save; confirm status=Draft.; Verify prefunding_account row created for SENDMN in DB.
**Deliverable:** Active partner record for SendMN with partner_code=SENDMN, type=OVERSEAS, and a prefunding_account row with balance=0.00 USD.
**Acceptance / logic checks:**
- DB row: partner_code=SENDMN, type=OVERSEAS, settle_a_ccy=USD, status=Draft.
- prefunding_account row: partner_id=SENDMN-id, currency=USD, balance=0.0000.
- Audit log CREATE event for SENDMN with actor and timestamp.
- Two OVERSEAS partners (TBANK, SENDMN) now coexist without conflict.
- Editing either partner's Contact Email triggers a separate audit log UPDATE event.
**Depends on:** 16.3-T01

### 16.3-T03 — Configure SendMN x ZeroPay x INBOUND rule - currency quadruple and rate slots  _(25 min)_
**Context:** A Rule links one Partner to one Scheme for one Direction. For SendMN x ZeroPay x INBOUND the currency quadruple is: collection_ccy=MNT, settle_a_ccy=USD, usd_intermediary=USD (always), settle_b_ccy=KRW, payout_ccy=KRW. Section 1 of the Mapping Page is read-only and auto-derived. Section 2 rate slots: Collection Leg (USD->USD) = IDENTITY (rate=1.0, no override); Payout Leg (USD->KRW) = LIVE (from treasury.usd_krw). Source: PRD-07 §6.4.1-6.4.2, RATE-04 identity-leg rule.
**Steps:** Navigate to Rules > Create Rule; select Partner=SendMN, Scheme=ZeroPay, Direction=INBOUND.; Verify Section 1 displays: Collection=MNT, Settle A=USD, USD Intermediary=USD, Settle B=KRW, Payout=KRW.; Verify Collection Leg badge=IDENTITY (USD->USD, rate locked at 1.0, override toggle disabled).; Verify Payout Leg badge=LIVE (USD->KRW, current treasury.usd_krw shown).; Save rule in Draft; do not yet set margins (next ticket).
**Deliverable:** SendMN x ZeroPay x INBOUND rule record (status=Draft) with correct currency quadruple stored in the rule table.
**Acceptance / logic checks:**
- Rule record: partner=SENDMN, scheme=zeropay, direction=INBOUND, collection_ccy=MNT, settle_a_ccy=USD, settle_b_ccy=KRW, payout_ccy=KRW.
- Collection-leg source=IDENTITY, cost_rate_coll=1.0 (no override possible).
- Payout-leg source=LIVE; changing treasury.usd_krw causes the displayed rate to update within 1 minute.
- Same-currency short-circuit notice is NOT shown (currencies differ).
- Audit log CREATE event for rule (SENDMN, zeropay, INBOUND).
**Depends on:** 16.3-T02

### 16.3-T04 — Configure T-Bank x ZeroPay x INBOUND rule - currency quadruple and rate slots  _(25 min)_
**Context:** T-Bank follows the same INBOUND OVERSEAS pattern as SendMN. Currency quadruple: collection_ccy=MNT (or per-agreement - same interim assumption), settle_a_ccy=USD, usd_intermediary=USD, settle_b_ccy=KRW, payout_ccy=KRW. Collection Leg=IDENTITY, Payout Leg=LIVE. Both partners are separate config entries; no shared rule. Source: PRD-07 §6, BRD-01 Assumption A2.
**Steps:** Navigate to Rules > Create Rule; select Partner=T-Bank, Scheme=ZeroPay, Direction=INBOUND.; Verify Section 1 displays same quadruple as SendMN (MNT/USD/USD/KRW/KRW).; Confirm Collection Leg=IDENTITY and Payout Leg=LIVE badges.; Save rule in Draft status.; Verify two independent rule rows exist for SENDMN and TBANK (same scheme+direction, different partner).
**Deliverable:** T-Bank x ZeroPay x INBOUND rule record (status=Draft) with correct currency quadruple.
**Acceptance / logic checks:**
- Rule record: partner=TBANK, scheme=zeropay, direction=INBOUND, settle_a_ccy=USD, settle_b_ccy=KRW.
- Two rules now exist for zeropay/INBOUND (one per partner) with no uniqueness conflict.
- cost_rate_coll=1.0 (IDENTITY) and payout leg shows live treasury rate.
- Audit log CREATE event for (TBANK, zeropay, INBOUND).
- Rule status=Draft; no payments can be processed until Activated.
**Depends on:** 16.3-T01, 16.3-T03

### 16.3-T05 — Set FX margins m_a=1.00% and m_b=1.00% on SendMN INBOUND rule  _(20 min)_
**Context:** RATE-04 mandates m_a + m_b >= 2.00% for all cross-border rules (settle_a != settle_b). SendMN inbound: m_a=1.00% (collection-side), m_b=1.00% (payout-side), combined=2.00%. These are stored on the rule record and used in the 5-step rate engine: step 2 collection_usd = payout_usd_cost / (1 - 0.01 - 0.01) = payout_usd_cost / 0.98. Validation: Save button disabled if m_a+m_b < 2.00%. Source: PRD-07 §6.4.3, RATE-04 step 2.
**Steps:** Open SendMN x ZeroPay x INBOUND rule (Draft).; In Section 3 - Margin Configuration, enter m_a=1.00 and m_b=1.00.; Confirm combined margin display shows 2.00%.; Attempt to save with m_b=0.90 (combined=1.90%) and verify save is blocked with error.; Restore m_b=1.00 and save successfully.
**Deliverable:** SendMN INBOUND rule with m_a=0.0100 and m_b=0.0100 saved in rule table.
**Acceptance / logic checks:**
- Rule record: m_a=0.0100, m_b=0.0100, confirmed in DB.
- Setting m_a=1.0, m_b=0.9 (combined=1.9%) is rejected: error reads combined margin must be at least 2.00%.
- Setting m_a=0.0, m_b=0.0 on a cross-border rule is rejected.
- m_a=1.5%, m_b=0.5% (combined=2.0%) is accepted (boundary check).
- Audit log records previous m_a and new m_a values on every save.
**Depends on:** 16.3-T03

### 16.3-T06 — Set FX margins m_a=1.00% and m_b=1.00% on T-Bank INBOUND rule  _(20 min)_
**Context:** T-Bank uses the same 2% combined margin as SendMN (PM-14 §2: 2% FX margin for all Phase 4 OVERSEAS partners). m_a=1.00%, m_b=1.00%. Rule must be cross-border (MNT/USD/KRW) so the 2% floor constraint applies. Source: PRD-07 §6.4.3, RATE-04.
**Steps:** Open T-Bank x ZeroPay x INBOUND rule (Draft).; Enter m_a=1.00 and m_b=1.00 in Section 3.; Confirm combined=2.00%, save.; Verify rule record in DB: m_a=0.0100, m_b=0.0100.; Confirm audit log entry with previous value (0) and new value (0.0100) for m_a.
**Deliverable:** T-Bank INBOUND rule with m_a=0.0100 and m_b=0.0100 persisted.
**Acceptance / logic checks:**
- DB: rule row for TBANK/zeropay/INBOUND has m_a=0.0100, m_b=0.0100.
- Audit log shows actor, timestamp, and previous m_a=0.0000 -> 0.0100.
- Saving m_a=0.0, m_b=1.99% is blocked.
- T-Bank and SendMN rule margins are independent; changing one does not affect the other.
- Rule status remains Draft after margin save (activation is a separate step).
**Depends on:** 16.3-T04

### 16.3-T07 — Set service charge USD 0.35 on SendMN INBOUND rule (Settle A = USD)  _(20 min)_
**Context:** The service_charge for OVERSEAS inbound rules where Settle A = USD is denominated in USD (not KRW). For SendMN the Phase 4 configured value is USD 0.35 per transaction. The spec notes that KRW 500 applies to domestic (Settle A=KRW) rules; for USD settle-A the amount is operator-entered in USD. service_charge is added at rate-engine Step 5: collection_amount = send_amount + service_charge; it never enters the USD pool math. Source: PRD-07 §6.4.4, RATE-04 step 5, spec note at line 5217.
**Steps:** Open SendMN x ZeroPay x INBOUND rule (Draft).; Navigate to Section 4 - Service Charge.; Verify the Currency label shows USD (auto-set from Settle A ccy).; Enter service_charge_amount=0.35; confirm currency label=USD.; Save and verify rule record: service_charge_amount=0.3500, service_charge_ccy=USD.
**Deliverable:** SendMN INBOUND rule with service_charge_amount=0.3500 USD persisted.
**Acceptance / logic checks:**
- DB rule row: service_charge_amount=0.3500, service_charge_ccy=USD.
- Currency field is read-only and auto-set to USD (operator cannot change it).
- service_charge_amount=0 is valid (save succeeds).
- Saving a negative service_charge is rejected.
- Audit log records previous (0) and new (0.3500) service_charge values.
**Depends on:** 16.3-T05

### 16.3-T08 — Set service charge USD 0.35 on T-Bank INBOUND rule (Settle A = USD)  _(20 min)_
**Context:** T-Bank uses the same service charge structure as SendMN: Settle A = USD, so service_charge is denominated in USD. Configured value: USD 0.35. The charge is added at rate-engine Step 5: collection_amount = send_amount + 0.35 USD. Source: PRD-07 §6.4.4, RATE-04 step 5.
**Steps:** Open T-Bank x ZeroPay x INBOUND rule (Draft).; In Section 4, enter service_charge_amount=0.35; confirm currency=USD.; Save and verify DB record.; Cross-check that changing T-Bank service charge to 0.50 does not affect SendMN rule.; Restore to 0.35 and save.
**Deliverable:** T-Bank INBOUND rule with service_charge_amount=0.3500 USD persisted.
**Acceptance / logic checks:**
- DB rule row TBANK/zeropay/INBOUND: service_charge_amount=0.3500, service_charge_ccy=USD.
- Currency label cannot be changed by operator.
- T-Bank and SendMN service_charge values are stored independently.
- Audit log entry for service_charge change on T-Bank rule.
- Rule still in Draft status after save.
**Depends on:** 16.3-T06, 16.3-T07

### 16.3-T09 — Record initial prefunding top-up for SendMN (prefunding_account and ledger entry)  _(30 min)_
**Context:** OVERSEAS partners require a prefunding_account in USD. Before activation, an initial balance must be recorded. The Admin System Prefunding Management module allows Ops to record a CREDIT_TOPUP entry in prefunding_ledger_entry (immutable, append-only). Fields: account_id (FK to prefunding_account for SENDMN), entry_type=CREDIT_TOPUP, amount=initial USD amount agreed with SendMN finance team, txn_ref=bank transfer reference, note=optional. The prefunding_account.balance is updated atomically. Default low_balance_threshold=10000.00 USD. Source: PRD-07 §9.2, DAT-03 §6.1-6.2.
**Steps:** Navigate to Admin System > Prefunding > SendMN > Record Top-Up.; Enter: amount=50000.00, currency=USD, bank_reference=WIRE-SENDMN-001, note=Phase 4 initial prefunding.; Submit and confirm success dialog.; Verify prefunding_account.balance for SENDMN = 50000.0000 USD.; Verify prefunding_ledger_entry row: account_id=SENDMN-account-id, entry_type=CREDIT_TOPUP, amount=50000.0000, balance_after=50000.0000.
**Deliverable:** prefunding_ledger_entry row (entry_type=CREDIT_TOPUP, amount=50000.00 USD) and updated prefunding_account.balance=50000.00 for SendMN.
**Acceptance / logic checks:**
- prefunding_account.balance=50000.0000 USD immediately after save (not cached).
- prefunding_ledger_entry is immutable: UPDATE or DELETE via any UI returns 403/error.
- balance_after in ledger entry matches prefunding_account.balance.
- Audit log entry with actor, timestamp, previous balance (0), new balance (50000.00).
- Partner Portal for SendMN reflects updated balance within 1 second of save (G-02 from PRD-08).
**Depends on:** 16.3-T02

### 16.3-T10 — Record initial prefunding top-up for T-Bank and configure low-balance alert  _(25 min)_
**Context:** Same prefunding setup as SendMN. T-Bank also requires initial USD balance and a low-balance alert threshold. Default threshold=10000.00 USD per PRD-07 §5.3.3. The low_balance_alert_config table stores: partner_id, threshold_usd=10000.00, alert_email (T-Bank contact email), is_active=true. Alert fires when balance_after < threshold after any DEBIT_PAYMENT entry; the transaction is NOT blocked. Source: PRD-07 §9.2, DAT-03 §6.3, SAD-02 prefunding model.
**Steps:** Navigate to Admin System > Prefunding > T-Bank > Record Top-Up.; Enter amount=50000.00, currency=USD, bank_reference=WIRE-TBANK-001.; Submit and confirm balance = 50000.0000.; Navigate to T-Bank partner profile > Prefunding Setup.; Confirm low_balance_threshold=10000.00 USD and alert_email is set to T-Bank contact; save.
**Deliverable:** T-Bank prefunding_account.balance=50000.00 USD, and low_balance_alert_config row with threshold_usd=10000.00 and is_active=true.
**Acceptance / logic checks:**
- DB: prefunding_account for TBANK, balance=50000.0000 USD.
- DB: low_balance_alert_config for TBANK, threshold_usd=10000.0000, is_active=true.
- Changing threshold to 5000.00 saves successfully and audit-logs previous value.
- Alert threshold is independent per partner: SENDMN threshold unchanged after TBANK threshold edit.
- Ledger entry created: entry_type=CREDIT_TOPUP, amount=50000.0000, balance_after=50000.0000.
**Depends on:** 16.3-T01

### 16.3-T11 — Generate production API credentials for SendMN and transmit securely  _(20 min)_
**Context:** API credentials are generated by GME Ops in Admin System. Format: X-API-Key=pk_live_<32-char-hex>, API Secret=sk_live_<64-char-hex>. Secret is shown once at generation and stored as bcrypt hash (cost >= 12) - never retrievable again. Ops must copy and transmit to SendMN out-of-band. Credential generation is audit-logged with actor and timestamp. Source: API-05 §3.1, PRD-07 §5.3.4, SEC-09 §2.3.
**Steps:** Open SendMN partner in Admin System > API Credentials > Generate Credentials.; Record the displayed X-API-Key (pk_live_...) and API Secret (sk_live_...) - this is the only time the secret is visible.; Confirm dialog acknowledging secret must be stored now.; Transmit credentials to SendMN tech team via agreed secure channel (encrypted email or OTP link).; Verify Admin System shows only the API Key (not secret) and creation timestamp on subsequent views.
**Deliverable:** SendMN production API credentials (X-API-Key + hashed secret) stored in DB; plaintext secret delivered to partner out-of-band.
**Acceptance / logic checks:**
- DB: partner_credential row for SENDMN has api_key=pk_live_..., secret stored as bcrypt hash (not plaintext).
- Second view of SendMN credentials page shows key but not secret (secret field absent or masked).
- Audit log: credential GENERATE event for SENDMN with actor and timestamp.
- Using the generated key+secret against GET /v1/balance returns HTTP 200 with SENDMN balance.
- Using an incorrect secret returns HTTP 401 code=INVALID_SIGNATURE.
**Depends on:** 16.3-T09

### 16.3-T12 — Generate production API credentials for T-Bank and transmit securely  _(20 min)_
**Context:** Identical credential generation process to SendMN. X-API-Key=pk_live_<32-char-hex>, API Secret=sk_live_<64-char-hex>. Bcrypt hash stored; plaintext shown once. Source: API-05 §3.1, PRD-07 §5.3.4, SEC-09 §2.3.
**Steps:** Open T-Bank partner in Admin System > API Credentials > Generate Credentials.; Record X-API-Key and API Secret in credential-passing template.; Confirm dialog; transmit to T-Bank tech team via secure channel.; Verify only key (no secret) shown on subsequent view.; Test credentials via sandbox: GET /v1/balance with TBANK credentials returns HTTP 200.
**Deliverable:** T-Bank production API credentials stored (key + hashed secret); plaintext secret transmitted out-of-band.
**Acceptance / logic checks:**
- DB: credential row for TBANK with bcrypt-hashed secret.
- T-Bank credentials cannot authenticate as SENDMN (partner_id isolation: GET /v1/balance with TBANK key returns TBANK balance, not SENDMN balance).
- Audit log GENERATE event for TBANK with actor and timestamp.
- Revoking T-Bank credentials immediately returns HTTP 401 on subsequent requests (no grace period).
- Sandbox test: GET /v1/balance with TBANK key+secret returns HTTP 200 with balance_usd field.
**Depends on:** 16.3-T10, 16.3-T11

### 16.3-T13 — Activate SendMN and T-Bank partner records and rules (status Draft -> Active)  _(20 min)_
**Context:** Activation prerequisite checklist (PRD-07 §5.3.5): settlement currency set, API credentials generated, prefunding account configured with balance > 0. For OVERSEAS: prefunding account must have balance > 0. Rule activation is separate: rule.status=Active means new transactions can be processed against it. Changing an Active rule applies only to NEW transactions - committed transactions retain their locked values. Source: PRD-07 §5.3.5, §6.
**Steps:** Navigate to SendMN partner; click Activate. Verify all prerequisites satisfied (credentials, prefunding>0, webhook URL).; Confirm SendMN partner.status=Active in DB.; Navigate to SendMN x ZeroPay x INBOUND rule; click Activate.; Confirm rule.status=Active in DB.; Repeat steps 1-4 for T-Bank (TBANK partner + TBANK rule).
**Deliverable:** Both SENDMN and TBANK partner records and their INBOUND rules set to status=Active.
**Acceptance / logic checks:**
- DB: partner.status=Active for both SENDMN and TBANK.
- DB: rule.status=Active for both SENDMN/zeropay/INBOUND and TBANK/zeropay/INBOUND.
- Attempting to activate a partner without prefunding balance > 0 is blocked with clear error.
- Attempting to activate a partner without API credentials is blocked.
- Audit log: ACTIVATE events for both partners and both rules with actor, timestamp.
**Depends on:** 16.3-T11, 16.3-T12, 16.3-T07, 16.3-T08

### 16.3-T14 — Create Partner Portal user accounts for SendMN and T-Bank finance staff  _(25 min)_
**Context:** The Partner Portal (PRD-08) is a read-only self-service portal. GME Ops creates the first administrator account for each partner during onboarding. Portal users are separate from Admin System users. Each partner sees ONLY their own data (strict data isolation - PRD-08 G-05). Portal capabilities: prefunding balance inquiry, transaction history, CSV export, settlement statements. Source: PRD-08 §1.3, §1.5.
**Steps:** Navigate to Admin System > Partner Portal Users > Create User.; For SendMN: create user with email=finance@sendmn.example, role=PARTNER_ADMIN, linked to partner=SENDMN.; Send password-reset email; user must set password on first login.; Repeat for T-Bank: email=finance@tbank.example, role=PARTNER_ADMIN, linked to partner=TBANK.; Verify SendMN portal user can see SENDMN balance but receives 403 when accessing TBANK data.
**Deliverable:** Portal user accounts created for SendMN and T-Bank; first-login password reset flow confirmed.
**Acceptance / logic checks:**
- Portal login as SendMN user shows SENDMN prefunding balance (USD 50000) on dashboard.
- Portal login as T-Bank user shows TBANK prefunding balance (USD 50000) on dashboard.
- SendMN portal user GET /portal/v1/balance for TBANK partner_id returns HTTP 403 (data isolation).
- New portal user is forced to change password on first login.
- Audit log: CREATE_PORTAL_USER events for both accounts with actor and timestamp.
**Depends on:** 16.3-T13

### 16.3-T15 — Verify rate engine calculation for SendMN INBOUND payment (RECEIVE mode, numeric check)  _(30 min)_
**Context:** Rate engine 5-step RECEIVE mode for SendMN INBOUND (collection=USD, settle_a=USD, settle_b=KRW, payout=KRW): cost_rate_pay=treasury.usd_krw=1380.00, cost_rate_coll=1.0 (IDENTITY). m_a=0.01, m_b=0.01, service_charge=0.35 USD. Example: target_payout=50000 KRW. Step1: payout_usd_cost=50000/1380.00=36.2319 USD. Step2: collection_usd=36.2319/(1-0.01-0.01)=36.9714 USD. Step3: collection_margin_usd=36.9714*0.01=0.3697 USD; payout_margin_usd=36.9714*0.01=0.3697 USD. Step4: send_amount=36.9714*1.0=36.9714 USD. Step5: collection_amount=36.9714+0.35=37.3214 USD. Pool identity: 36.9714-0.3697-0.3697=36.2320 vs payout_usd_cost=36.2319 (within 0.01 USD). Source: RATE-04 §3, TICKET_BRIEF canonical facts.
**Steps:** Set treasury.usd_krw=1380.00 in Admin System FX Rates.; Call POST /v1/rates with target_payout=50000, payout_currency=KRW, scheme_id=zeropay, direction=inbound using SENDMN credentials.; Record quote_id, offer_rate, send_amount, collection_usd, payout_usd_cost, collection_margin_usd, payout_margin_usd, service_charge.; Verify all values match the expected calculation within 0.01 USD tolerance.; Verify pool identity: collection_usd - collection_margin_usd - payout_margin_usd = payout_usd_cost within 0.01 USD.
**Deliverable:** Verified POST /v1/rates response for SendMN INBOUND with treasury.usd_krw=1380.00, target_payout=50000 KRW showing correct USD pool values.
**Acceptance / logic checks:**
- payout_usd_cost=36.23 USD (within 0.01 of 50000/1380.00).
- collection_usd=36.97 USD (within 0.01 of payout_usd_cost/0.98).
- collection_margin_usd=payout_margin_usd=0.37 USD each (within 0.01 of collection_usd*0.01).
- send_amount=36.97 USD (equals collection_usd because cost_rate_coll=1.0 IDENTITY).
- Pool identity holds: collection_usd - collection_margin_usd - payout_margin_usd = payout_usd_cost within 0.01 USD.
**Depends on:** 16.3-T13

### 16.3-T16 — Verify rate engine calculation for T-Bank INBOUND payment (different treasury rate)  _(30 min)_
**Context:** Same 5-step RECEIVE mode as SendMN. Uses treasury.usd_krw; cost_rate_coll=1.0 (IDENTITY for USD settle-A). Test with treasury.usd_krw=1400.00 to verify the engine uses the live rate. Example: target_payout=100000 KRW. payout_usd_cost=100000/1400.00=71.4286 USD. collection_usd=71.4286/0.98=72.8863 USD. collection_margin_usd=payout_margin_usd=72.8863*0.01=0.7289 USD each. send_amount=72.8863 USD. collection_amount=72.8863+0.35=73.2363 USD. Pool: 72.8863-0.7289-0.7289=71.4285 vs 71.4286 (within 0.01). Source: RATE-04 §3.
**Steps:** Set treasury.usd_krw=1400.00 in Admin System FX Rates.; Call POST /v1/rates with target_payout=100000, payout_currency=KRW, scheme_id=zeropay, direction=inbound using TBANK credentials.; Verify response fields against expected values.; Verify pool identity holds.; Change treasury.usd_krw to 1380.00 and call again; confirm payout_usd_cost changes proportionally (100000/1380=72.46 vs 100000/1400=71.43).
**Deliverable:** POST /v1/rates response for T-Bank INBOUND with target_payout=100000 KRW at two distinct treasury rates, both producing correct pool values.
**Acceptance / logic checks:**
- At treasury.usd_krw=1400.00: payout_usd_cost=71.43 USD (within 0.01).
- At treasury.usd_krw=1400.00: collection_usd=72.89 USD (within 0.01).
- Pool identity: collection_usd - collection_margin_usd - payout_margin_usd = payout_usd_cost within 0.01 USD at both rates.
- Changing treasury rate affects quote in real-time (second call reflects new rate).
- T-Bank quote uses TBANK rule margins (m_a=0.01, m_b=0.01), not SendMN margins.
**Depends on:** 16.3-T15

### 16.3-T17 — Unit test: rate engine 5-step RECEIVE mode for OVERSEAS INBOUND with IDENTITY collect leg  _(40 min)_
**Context:** Explicit unit test (not integration) verifying the RateEngine.compute() function with OVERSEAS INBOUND inputs. Inputs: target_payout=50000, cost_rate_pay=1380.00, cost_rate_coll=1.0 (IDENTITY), m_a=0.01, m_b=0.01, service_charge=0.35. Expected outputs as per RATE-04: payout_usd_cost=36.2319, collection_usd=36.9714, collection_margin_usd=0.3697, payout_margin_usd=0.3697, send_amount=36.9714, collection_amount=37.3214. Pool identity must hold within 0.01 USD. Use Decimal type throughout (no float). Source: RATE-04 §3, TICKET_BRIEF §Canonical facts.
**Steps:** In the rate engine unit test class, add test method testOverseasInboundIdentityCollectLeg().; Instantiate RateEngine with: cost_rate_pay=1380.00, cost_rate_coll=1.0, m_a=0.01, m_b=0.01, service_charge=0.35, service_charge_ccy=USD.; Call compute(target_payout=50000, payout_ccy=KRW).; Assert each output field within 0.01 USD tolerance using Decimal comparison.; Assert pool identity: collection_usd - collection_margin_usd - payout_margin_usd = payout_usd_cost within 0.0001 (internal tolerance tighter than API tolerance).
**Deliverable:** Passing unit test testOverseasInboundIdentityCollectLeg() in the rate engine test suite.
**Acceptance / logic checks:**
- payout_usd_cost=36.2319 USD (4dp), assertion passes.
- collection_usd=36.9714 USD (4dp), assertion passes.
- send_amount=collection_usd*cost_rate_coll=36.9714*1.0=36.9714, assertion passes.
- collection_amount=36.9714+0.35=37.3214, assertion passes.
- Pool identity assertion: abs(collection_usd - collection_margin_usd - payout_margin_usd - payout_usd_cost) < 0.0001, passes.
**Depends on:** 16.3-T15

### 16.3-T18 — Unit test: prefunding deduction atomic SELECT FOR UPDATE under concurrent INBOUND payments  _(45 min)_
**Context:** RATE-04 and SAD-02 mandate that prefunding deduction uses SELECT FOR UPDATE on the prefunding_account row. The scheme is never called without a prior committed deduction. Test: two concurrent goroutines/threads each attempt to deduct USD 40000 from a prefunding balance of USD 50000. Only one must succeed; the other must receive INSUFFICIENT_PREFUNDING. No double-spend allowed. Source: SAD-02 §prefunding model, RATE-04 prefunding section, TICKET_BRIEF canonical facts.
**Steps:** In prefunding service unit test, create a test prefunding_account with balance=50000.00 USD.; Launch two concurrent threads T1 and T2, each calling prefundingService.deduct(account_id, 40000.00) simultaneously.; After both threads complete, assert exactly one returned success and one returned INSUFFICIENT_PREFUNDING error.; Assert final prefunding_account.balance=10000.00 (only one deduction committed).; Assert prefunding_ledger_entry contains exactly one DEBIT_PAYMENT row for this test account.
**Deliverable:** Passing concurrency unit test in prefunding service test suite confirming atomicity under concurrent deduction.
**Acceptance / logic checks:**
- Exactly one of T1/T2 succeeds; the other throws/returns INSUFFICIENT_PREFUNDING.
- Final balance=10000.00 USD (not 0 or negative, not 50000).
- Exactly one DEBIT_PAYMENT ledger entry created (not two).
- No deadlock or timeout occurs in the test (completes within 5 seconds).
- balance_after in the ledger entry matches the final balance in prefunding_account.
**Depends on:** 16.3-T17

### 16.3-T19 — Unit test: INSUFFICIENT_PREFUNDING rejection before scheme call for OVERSEAS MPM  _(40 min)_
**Context:** Rule: for OVERSEAS partners, the scheme is NEVER called without a prior successful prefunding deduction. If deduction fails (balance insufficient), the payment is rejected at HTTP 402 before any ZeroPay API call is made. Test this via a unit test with a mocked scheme adapter: verify the scheme mock is never invoked when prefunding deduction fails. Source: SAD-02, RATE-04, API-05 §4.3 HTTP 402.
**Steps:** Create a test with prefunding_account.balance=10.00 USD and a payment requiring collection_usd=36.97 USD.; Mock the scheme adapter (ZeroPayAdapter) to record whether it was called.; Call paymentOrchestrator.execute(payment) with the above inputs.; Assert HTTP 402 / INSUFFICIENT_PREFUNDING error is returned.; Assert schemeAdapter.wasInvoked()==false (scheme never called).
**Deliverable:** Passing unit test confirming scheme adapter is never called when prefunding deduction returns INSUFFICIENT_PREFUNDING.
**Acceptance / logic checks:**
- HTTP 402 / error code INSUFFICIENT_PREFUNDING returned.
- Scheme adapter mock records zero invocations.
- No prefunding_ledger_entry row created (no partial deduction).
- prefunding_account.balance remains 10.00 USD (unchanged).
- Test passes for both MPM (POST /v1/payments) and CPM (POST /v1/payments/cpm/generate) code paths.
**Depends on:** 16.3-T18

### 16.3-T20 — Unit test: CPM prefunding deduction at token generation (not at scheme approval)  _(40 min)_
**Context:** For CPM payments, prefunding is deducted at POST /v1/payments/cpm/generate (token issuance), not at the final scheme approval. This is the canonical rule per API-05 §4.4 and PRD-08 (PRD wins over BS-02 which says deduction at completion). Test: call generate() -> verify deduction committed -> simulate scheme response -> verify no second deduction. Source: API-05 §4.4, TICKET_BRIEF canonical facts.
**Steps:** Set up prefunding_account.balance=100.00 USD.; Call cpmService.generateToken(partner_id=SENDMN, prefund_reserve_usd=36.97).; Assert prefunding_account.balance=63.03 USD immediately after generate (deduction happened).; Assert one DEBIT_PAYMENT ledger entry exists.; Simulate scheme approval callback; assert no second ledger entry is created and balance remains 63.03.
**Deliverable:** Passing unit test confirming CPM deduction occurs at generate() and is not repeated at scheme approval.
**Acceptance / logic checks:**
- Balance=63.03 USD after generateToken() call (deducted 36.97).
- Exactly one DEBIT_PAYMENT ledger entry after generate, before scheme callback.
- After scheme approval callback, balance unchanged at 63.03 (no second deduction).
- After scheme approval callback, total ledger entries for this payment = 1 (not 2).
- If generate() fails (e.g. scheme unavailable), DEBIT_PAYMENT is NOT created and balance = 100.00.
**Depends on:** 16.3-T18

### 16.3-T21 — Unit test: prefunding DEBIT_REVERSAL on cancellation of OVERSEAS MPM payment  _(40 min)_
**Context:** When an OVERSEAS MPM payment is cancelled (POST /v1/payments/{id}/cancel), the prefunding deduction must be reversed: a CREDIT_REVERSAL ledger entry is created and prefunding_account.balance is restored. The cancel response includes prefund_returned_usd. Only same-day cancellation in approved or pending status is allowed. Source: API-05 §4.6, DAT-03 §6.2 entry_type=CREDIT_REVERSAL.
**Steps:** Create a payment in approved status with prefunding deducted: account balance went from 50000 to 49963.03 (deducted 36.97).; Call cancelService.cancel(payment_id, reason=customer_request).; Assert response contains prefund_returned_usd=36.97 USD.; Assert prefunding_account.balance=50000.00 restored.; Assert a CREDIT_REVERSAL ledger entry exists with amount=36.97 and balance_after=50000.00.
**Deliverable:** Passing unit test confirming CREDIT_REVERSAL ledger entry and balance restoration on same-day MPM cancellation.
**Acceptance / logic checks:**
- prefunding_account.balance=50000.00 after cancel (restored).
- CREDIT_REVERSAL ledger entry: amount=36.97, balance_after=50000.00.
- Response: prefund_returned_usd=36.97, status=cancelled.
- Attempting to cancel a next-day payment returns HTTP 400 code=CANCEL_NOT_PERMITTED (no reversal).
- Attempting to cancel an already-cancelled payment returns HTTP 409.
**Depends on:** 16.3-T19

### 16.3-T22 — Unit test: low-balance alert email fired after deduction drops balance below threshold  _(35 min)_
**Context:** The low_balance_alert_config table stores threshold_usd=10000.00 per partner. After every DEBIT_PAYMENT, if balance_after < threshold_usd, an alert email is sent to alert_email. The transaction is NOT blocked. Test by deducting to just below 10000 and asserting email notification fired. Source: PRD-07 §5.3.3, DAT-03 §6.3.
**Steps:** Set prefunding_account.balance=10100.00 and low_balance_alert_config.threshold_usd=10000.00 for test partner.; Mock email notification service to capture outgoing emails.; Call prefundingService.deduct(account_id, 200.00) (balance drops to 9900.00, below 10000).; Assert email notification fired with subject containing low balance warning.; Assert deduction succeeded (not blocked): balance=9900.00, DEBIT_PAYMENT ledger entry exists.
**Deliverable:** Passing unit test confirming low-balance alert email is sent when balance drops below threshold but payment is not blocked.
**Acceptance / logic checks:**
- DEBIT_PAYMENT succeeds: balance=9900.00.
- Email notification mock called exactly once after deduction.
- Deducting from 15000 to 10001 (above threshold) fires no alert.
- Deducting from 10001 to 9999 (crossing threshold) fires alert.
- Deducting from 9999 to 9500 (already below threshold) fires another alert (every sub-threshold deduction alerts).
**Depends on:** 16.3-T19

### 16.3-T23 — Integration test: SendMN MPM INBOUND payment end-to-end (rate quote -> payment -> webhook)  _(45 min)_
**Context:** Full end-to-end flow in the integration test environment: (1) POST /v1/rates returns valid quote_id with correct USD pool fields; (2) POST /v1/payments deducts prefunding atomically and calls ZeroPay sandbox (ZPQR_TEST_APPROVED); (3) payment.approved webhook is delivered to SendMN webhook endpoint. Treasury.usd_krw=1380.00. target_payout=50000 KRW. Expected: prefund_deducted_usd=36.97, status=approved, scheme_txn_id present. Source: API-05 §4.2-4.3, §6.
**Steps:** Set treasury.usd_krw=1380.00; confirm SendMN prefunding_account.balance >= 500 USD.; POST /v1/rates with SENDMN credentials: target_payout=50000, payout_currency=KRW, scheme_id=zeropay, direction=inbound, merchant_qr=ZPQR_TEST_APPROVED. Record quote_id and valid_until.; POST /v1/payments with quote_id, merchant_qr=ZPQR_TEST_APPROVED, direction=inbound, scheme_id=zeropay, partner_txn_ref=IT-SENDMN-001, collection_amount=37.32, collection_currency=USD. Record payment_id.; Poll GET /v1/payments/{payment_id} until status=approved.; Verify webhook.tbank.example/gmepayplus received payment.approved event with correct payment_id and signature.
**Deliverable:** Passing integration test record showing SendMN MPM INBOUND payment approved, prefunding deducted, and webhook delivered.
**Acceptance / logic checks:**
- POST /v1/payments returns HTTP 201 and status=approved.
- prefund_deducted_usd in response is 36.97 USD (within 0.01 of collection_usd).
- prefunding_account.balance reduced by exactly 36.97 USD (atomic, no partial deduction).
- payment.approved webhook delivered to SendMN webhook URL; X-Signature header verifiable with SENDMN API secret.
- Idempotent retry (same Idempotency-Key, same body) returns the stored response without creating a duplicate payment.
**Depends on:** 16.3-T17, 16.3-T13

### 16.3-T24 — Integration test: T-Bank CPM INBOUND payment end-to-end (generate -> webhook pending_debit -> approved)  _(50 min)_
**Context:** CPM flow for T-Bank: (1) POST /v1/payments/cpm/generate issues prepare_token and deducts prefunding; (2) ZeroPay POS scan triggers payment.pending_debit webhook to T-Bank with offer_rate and estimated collection_amount; (3) final payment.approved webhook delivered. Prefunding deducted at generate step (not at approval). Source: API-05 §4.4, §6, TICKET_BRIEF CPM rule.
**Steps:** Confirm T-Bank prefunding_account.balance >= 500 USD.; POST /v1/payments/cpm/generate with TBANK credentials: scheme_id=zeropay, direction=inbound, partner_txn_ref=IT-TBANK-CPM-001, country_code=KR. Record cpm_token_id, prepare_token, prefund_reserved_usd, payment_id.; Verify prefunding_account.balance reduced by prefund_reserved_usd immediately after generate.; Simulate POS scan by submitting cpm_token_id via sandbox control; verify payment.pending_debit webhook received.; Wait for payment.approved webhook; verify payment_id matches and scheme_txn_id present.
**Deliverable:** Passing integration test showing T-Bank CPM INBOUND payment with prefunding deducted at generate and both webhooks delivered.
**Acceptance / logic checks:**
- POST /v1/payments/cpm/generate returns HTTP 201 with prepare_token and prefund_reserved_usd.
- Prefunding balance drops by prefund_reserved_usd immediately after generate (before scheme interaction).
- payment.pending_debit webhook contains offer_rate and estimated collection_amount.
- payment.approved webhook contains scheme_txn_id and status=approved.
- GET /v1/payments/{payment_id} returns final status=approved after webhooks.
**Depends on:** 16.3-T23, 16.3-T20

### 16.3-T25 — Certification test C-01 and C-02: Rate quote and MPM payment success for SendMN  _(30 min)_
**Context:** API-05 §10.4 defines 16 certification tests (C-01 to C-16) partners must pass before production credentials are issued. C-01: POST /v1/rates returns HTTP 200 with valid quote_id and valid_until. C-02: POST /v1/payments with that quote_id returns HTTP 201, status=approved. Run against the sandbox environment with SENDMN sandbox credentials (pk_test_ prefix).
**Steps:** Issue SENDMN sandbox credentials (pk_test_<32hex> / sk_test_<64hex>) via Admin System.; C-01: POST /v1/rates, target_payout=10000, payout_currency=KRW, scheme_id=zeropay, direction=inbound. Assert HTTP 200, quote_id present, valid_until in future.; C-02: POST /v1/payments with quote_id from C-01, merchant_qr=ZPQR_TEST_APPROVED. Assert HTTP 201, status=approved.; Record pass/fail in certification checklist.; Confirm rate quote TTL enforced: repeat C-02 with an expired quote_id and assert HTTP 422 RATE_QUOTE_EXPIRED.
**Deliverable:** C-01 and C-02 certification results documented as PASS for SendMN.
**Acceptance / logic checks:**
- C-01: HTTP 200, quote_id non-null, valid_until > current UTC time.
- C-02: HTTP 201, status=approved, scheme_txn_id present.
- Using expired quote_id in payment returns HTTP 422 code=RATE_QUOTE_EXPIRED (C-03 coverage).
- Sandbox credentials (pk_test_ prefix) work against sandbox environment and return sandbox scheme responses.
- Certification result logged in QA-12 traceability matrix.
**Depends on:** 16.3-T13

### 16.3-T26 — Certification test C-04 and C-05: CPM token generation and status polling for SendMN  _(30 min)_
**Context:** C-04: POST /v1/payments/cpm/generate returns HTTP 201 with prepare_token present. C-05: GET /v1/payments/{id} returns HTTP 200 with correct status progression (pending -> approved). Run in SendMN sandbox. Source: API-05 §10.4.
**Steps:** C-04: POST /v1/payments/cpm/generate with SENDMN sandbox credentials. Assert HTTP 201, prepare_token non-null, cpm_token_id non-null, prefund_reserved_usd present.; Record payment_id from C-04 response.; C-05: Poll GET /v1/payments/{payment_id} every 2 seconds until status transitions from pending to approved (max 60 s in sandbox).; Assert status progression includes at least pending state before approved.; Record pass/fail in certification checklist.
**Deliverable:** C-04 and C-05 certification results documented as PASS for SendMN.
**Acceptance / logic checks:**
- C-04: HTTP 201, prepare_token present, expires_at in future (within 60 s of generation).
- C-05: GET /v1/payments/{id} returns HTTP 200 at all status stages.
- Status transitions pending -> approved without skipping (CPM sandbox flow).
- prefund_reserved_usd in generate response matches deduction in prefunding_account.
- Polling with a payment_id owned by a different partner returns HTTP 404 (isolation check).
**Depends on:** 16.3-T25

### 16.3-T27 — Certification test C-06 and C-07: same-day cancel and next-day cancel rejection for SendMN  _(30 min)_
**Context:** C-06: POST /v1/payments/{id}/cancel on an approved same-day payment returns HTTP 200 status=cancelled. C-07: Attempt cancel on a next-day payment returns HTTP 400 code=CANCEL_NOT_PERMITTED. For OVERSEAS partners, cancellation must also return prefund_returned_usd confirming the reversal. Source: API-05 §4.6, §10.4.
**Steps:** Create and approve a SendMN MPM payment in sandbox.; C-06: POST /v1/payments/{id}/cancel with reason=customer_request. Assert HTTP 200, status=cancelled, prefund_returned_usd > 0.; Verify prefunding balance restored by the returned USD amount.; C-07: Use a payment created on a prior calendar day (or simulate via test flag). POST /v1/payments/{id}/cancel. Assert HTTP 400 code=CANCEL_NOT_PERMITTED.; Record pass/fail in certification checklist.
**Deliverable:** C-06 and C-07 certification results documented as PASS for SendMN.
**Acceptance / logic checks:**
- C-06: HTTP 200, status=cancelled, prefund_returned_usd equals the original prefund_deducted_usd.
- C-07: HTTP 400, code=CANCEL_NOT_PERMITTED.
- prefunding_account.balance restored after C-06 cancellation.
- CREDIT_REVERSAL ledger entry created matching the cancelled payment.
- Attempting C-06 again on the same payment returns HTTP 409 (already cancelled).
**Depends on:** 16.3-T25

### 16.3-T28 — Certification test C-08, C-09, C-10: merchant resolve, inactive merchant, balance inquiry for SendMN  _(30 min)_
**Context:** C-08: GET /v1/merchants/{qr} with valid QR returns HTTP 200 with merchant detail. C-09: GET /v1/merchants/{qr} with inactive QR returns HTTP 422. C-10: GET /v1/balance returns HTTP 200 for OVERSEAS partner (SendMN) and HTTP 403 for LOCAL partner (GME Remit). Source: API-05 §4.7, §4.8, §10.4.
**Steps:** C-08: GET /v1/merchants/ZPQR_TEST_APPROVED with SENDMN sandbox credentials. Assert HTTP 200 with merchant_name, status=active.; C-09: GET /v1/merchants/ZPQR_TEST_INACTIVE. Assert HTTP 422 with appropriate error code.; C-10a: GET /v1/balance with SENDMN credentials. Assert HTTP 200, balance_usd present, as_of present.; C-10b: GET /v1/balance with GME_REMIT credentials (LOCAL partner). Assert HTTP 403.; Record all pass/fail in certification checklist.
**Deliverable:** C-08, C-09, and C-10 certification results documented as PASS for SendMN.
**Acceptance / logic checks:**
- C-08: HTTP 200, merchant_name non-null, payout_currency=KRW, status=active.
- C-09: HTTP 422, error code present (e.g. MERCHANT_INACTIVE).
- C-10a: HTTP 200, balance_usd and low_balance_threshold_usd both present.
- C-10b: HTTP 403 for LOCAL partner GET /v1/balance.
- C-10a response balance_usd matches the Admin System displayed balance for SENDMN.
**Depends on:** 16.3-T26

### 16.3-T29 — Certification test C-11 and C-12: webhook delivery and idempotent re-delivery for SendMN  _(40 min)_
**Context:** C-11: payment.approved webhook must be received by SendMN webhook URL; X-Signature header must be verifiable; SendMN endpoint must return HTTP 2xx within 10 seconds. C-12: payment.failed webhook must be received and re-delivery must be idempotent (partner endpoint handles duplicate delivery without double-processing). Delivery is at-least-once per API-05 §6. Source: API-05 §6, §10.4.
**Steps:** Stand up a test HTTP server at SendMN webhook URL capturing events and responding HTTP 200.; Execute a payment that results in approved; assert payment.approved webhook received within 10 s.; Verify X-Signature = HMAC-SHA256 of webhook payload using SENDMN API secret; assert signature valid.; Trigger a payment using ZPQR_TEST_DECLINED; assert payment.failed webhook received.; Replay the payment.failed webhook to the same endpoint (simulate re-delivery); assert endpoint handles duplicate gracefully (no double-processing based on idempotency key in payload).
**Deliverable:** C-11 and C-12 certification results documented as PASS for SendMN.
**Acceptance / logic checks:**
- C-11: payment.approved webhook delivered within 10 s; X-Signature validates correctly with API secret.
- C-12: payment.failed webhook delivered; duplicate delivery with same event_id returns HTTP 200 without creating duplicate records.
- Webhook payload contains payment_id, status, scheme_txn_id (for approved), partner_txn_ref.
- Webhook delivery failures are retried (simulate 500 response on first attempt; assert retry within retry window).
- SendMN endpoint returning non-2xx causes at-least-once re-delivery.
**Depends on:** 16.3-T25

### 16.3-T30 — Certification test C-13, C-14, C-15, C-16: error scenarios for SendMN (prefunding, dup ref, sig, idempotent)  _(40 min)_
**Context:** C-13: POST /v1/payments with balance below required collection_usd returns HTTP 402 INSUFFICIENT_PREFUNDING (scheme never called). C-14: Submitting duplicate partner_txn_ref returns HTTP 409 DUPLICATE_PARTNER_TXN_REF. C-15: Invalid X-Signature returns HTTP 401 INVALID_SIGNATURE. C-16: Idempotent retry (same Idempotency-Key, same body) returns stored response without double-processing. Source: API-05 §10.4.
**Steps:** C-13: Set SendMN sandbox balance to 0.01 USD; POST /v1/payments for 50000 KRW (requires ~37 USD). Assert HTTP 402 code=INSUFFICIENT_PREFUNDING.; C-14: Execute a successful payment with partner_txn_ref=CERT-014. Repeat the same request with different body but same partner_txn_ref. Assert HTTP 409 DUPLICATE_PARTNER_TXN_REF.; C-15: Send POST /v1/rates with a tampered X-Signature. Assert HTTP 401 code=INVALID_SIGNATURE.; C-16: Send POST /v1/payments twice with identical Idempotency-Key and identical body. Assert second call returns stored first response (HTTP 201, same payment_id) without creating a duplicate payment.; Record all pass/fail.
**Deliverable:** C-13, C-14, C-15, and C-16 certification results documented as PASS for SendMN.
**Acceptance / logic checks:**
- C-13: HTTP 402, code=INSUFFICIENT_PREFUNDING; prefunding_account.balance unchanged; no scheme call made.
- C-14: HTTP 409, code=DUPLICATE_PARTNER_TXN_REF when partner_txn_ref reused with different body.
- C-15: HTTP 401, code=INVALID_SIGNATURE; request rejected before any business logic.
- C-16: Second request with same Idempotency-Key returns same payment_id as first request; only one payment row in DB.
- All 4 tests pass; certification checklist items C-13 through C-16 marked PASS.
**Depends on:** 16.3-T29

### 16.3-T31 — Repeat certification tests C-01 to C-16 for T-Bank sandbox credentials  _(50 min)_
**Context:** The certification suite (API-05 §10.4, C-01 to C-16) must be passed by EACH partner independently before production credentials are issued. T-Bank must pass the same 16 tests using TBANK sandbox credentials. No shared certification; each partner is isolated. Tests cover: rate quote, MPM, CPM, cancel, merchant resolve, balance inquiry, webhooks, error scenarios. Source: API-05 §10.4.
**Steps:** Issue TBANK sandbox credentials (pk_test_ / sk_test_) via Admin System.; Execute certification tests C-01 through C-16 using TBANK credentials against the sandbox environment following the same procedure as SendMN certification.; For C-10, verify TBANK GET /v1/balance returns TBANK balance (not SENDMN balance) - data isolation.; Record pass/fail for each of the 16 tests in the certification checklist.; Confirm all 16 tests pass before marking T-Bank as certified.
**Deliverable:** T-Bank certification checklist with all 16 tests (C-01 to C-16) marked PASS.
**Acceptance / logic checks:**
- All 16 certification items show PASS for T-Bank.
- C-10: T-Bank balance endpoint returns T-Bank balance, not SendMN balance (strict isolation).
- C-11: T-Bank webhook signature validated with TBANK API secret (not SENDMN secret).
- C-13: INSUFFICIENT_PREFUNDING uses T-Bank prefunding_account (not shared with SendMN).
- Certification result logged in QA-12 traceability matrix under T-Bank partner ID.
**Depends on:** 16.3-T30, 16.3-T12

### 16.3-T32 — Configure low-balance email alert for SendMN and verify alert fires at USD 10,000 threshold  _(25 min)_
**Context:** Low-balance alerting: when prefunding_account.balance drops below low_balance_alert_config.threshold_usd after any DEBIT_PAYMENT, an alert email is sent to the configured recipients. The transaction is NOT blocked. Default threshold=10000.00 USD per partner. For SendMN: alert_email must be set to agreed recipient list before go-live. Source: PRD-07 §5.3.3, SAD-02 alert model, OPS-13 hypercare checklist.
**Steps:** Navigate to SendMN > Prefunding Setup > configure Low-Balance Alert Recipients = [ops@sendmn.example, gme-ops@gme.example].; Set threshold to 10000.00 USD (confirm default already set).; In staging: set SendMN prefunding balance to 10100.00 USD via Admin System adjustment.; Execute a payment that deducts 200.00 USD (balance drops to 9900 < 10000).; Verify alert email received at both configured recipients within 60 seconds.
**Deliverable:** Low-balance alert email confirmed delivered when SendMN balance crosses below USD 10,000; alert config verified in DB.
**Acceptance / logic checks:**
- Email delivered to all configured recipients (ops@sendmn.example and gme-ops@gme.example) within 60 s.
- Email subject or body identifies partner=SendMN and current balance=9900.00 USD.
- Payment was NOT blocked; DEBIT_PAYMENT ledger entry exists and balance=9900.00.
- Deduction that keeps balance above 10000 fires NO alert.
- Alert configuration change is audit-logged with actor and timestamp.
**Depends on:** 16.3-T22, 16.3-T13

### 16.3-T33 — Configure low-balance email alert for T-Bank and verify alert fires at USD 10,000 threshold  _(25 min)_
**Context:** Same low-balance alert setup as SendMN but for T-Bank. Separate alert_email configuration, independent threshold. Default threshold=10000.00 USD. Source: PRD-07 §5.3.3, DAT-03 §6.3.
**Steps:** Navigate to T-Bank > Prefunding Setup > set Low-Balance Alert Recipients = [ops@tbank.example, gme-ops@gme.example].; Confirm threshold=10000.00 USD.; In staging: set T-Bank balance to 10200.00 USD; execute a payment deducting 300.00 USD (balance drops to 9900).; Verify alert email received at T-Bank recipients.; Confirm altering T-Bank threshold to 5000 does not affect SendMN threshold.
**Deliverable:** T-Bank low-balance alert confirmed working; recipients and threshold configured in DB.
**Acceptance / logic checks:**
- Alert email delivered to T-Bank recipients when balance crosses below 10000 USD.
- T-Bank alert does not trigger SendMN alert and vice versa.
- low_balance_alert_config row for TBANK: threshold_usd=10000.00, is_active=true.
- Audit log entry for T-Bank alert config save.
- payment.failed due to INSUFFICIENT_PREFUNDING (balance=0) also fires urgent alert.
**Depends on:** 16.3-T32, 16.3-T10

### 16.3-T34 — Verify rate quote TTL enforcement and RATE_QUOTE_EXPIRED error for OVERSEAS partners  _(30 min)_
**Context:** Rate quote TTL for non-aggregator-bound partners defaults to 300 seconds (5 minutes). validUntil = quote_issued_at + TTL. On commit (POST /v1/payments), if UTC now > valid_until, the request is rejected with HTTP 422 code=RATE_QUOTE_EXPIRED. The rate lock (all USD pool values + derived rates) is only written on successful commit; expired quotes cannot be committed. Source: API-05 §4.2, §4.3, TICKET_BRIEF rate quote TTL section.
**Steps:** Configure SendMN rate_quote_ttl_seconds=60 (minimum, for testability) in Admin System.; POST /v1/rates to obtain quote_id with valid_until approximately 60 s in future.; Wait 65 seconds (past valid_until).; POST /v1/payments with the expired quote_id. Assert HTTP 422 code=RATE_QUOTE_EXPIRED.; POST /v1/rates again for a fresh quote; immediately POST /v1/payments. Assert HTTP 201 approved.
**Deliverable:** Verified RATE_QUOTE_EXPIRED rejection for SendMN and confirmed fresh quote succeeds.
**Acceptance / logic checks:**
- Expired quote returns HTTP 422 code=RATE_QUOTE_EXPIRED with no prefunding deduction.
- Fresh quote submitted within TTL returns HTTP 201 approved.
- prefunding_account.balance unchanged after expired-quote rejection (no partial deduction).
- rate_quote_ttl_seconds is configurable per partner (60-1800 range); value outside range is rejected.
- Committed payment records quote_issued_at and all locked USD pool values permanently.
**Depends on:** 16.3-T25

### 16.3-T35 — Verify idempotency key semantics for SendMN MPM INBOUND payments  _(30 min)_
**Context:** POST /v1/payments requires an Idempotency-Key header (partner-generated UUID v4). GMEPay+ stores results keyed by (partner_id, idempotency_key) for 24 hours. Repeat with same key + same body: stored response returned, no new payment. Repeat with same key + different body: HTTP 422. Source: API-05 §2.6, §7.
**Steps:** POST /v1/payments with Idempotency-Key=UUID-A and a valid payment body. Record payment_id and prefunding balance.; POST /v1/payments again with Idempotency-Key=UUID-A and IDENTICAL body. Assert same payment_id returned; prefunding_account.balance unchanged (no second deduction).; POST /v1/payments with Idempotency-Key=UUID-A and a DIFFERENT body (different partner_txn_ref). Assert HTTP 422.; POST /v1/payments with Idempotency-Key=UUID-B (new key, same body). Assert new payment_id; prefunding deducted again.; Verify exactly two payments exist in DB (from UUID-A and UUID-B), not three.
**Deliverable:** Idempotency semantics verified: duplicate key+body returns cached response; duplicate key+different body returns 422; new key creates new payment.
**Acceptance / logic checks:**
- Idempotent retry (UUID-A, same body) returns identical payment_id and HTTP 201; no second DEBIT_PAYMENT ledger entry.
- Conflicting retry (UUID-A, different body) returns HTTP 422.
- New key (UUID-B) creates a new payment and new DEBIT_PAYMENT ledger entry.
- Total DEBIT_PAYMENT ledger entries = 2 (one per unique idempotency key).
- Idempotency key expires after 24 hours: same key after 24 h can be reused (new payment created).
**Depends on:** 16.3-T23

### 16.3-T36 — Load test: concurrent MPM INBOUND payments for SendMN confirming SELECT FOR UPDATE atomicity  _(50 min)_
**Context:** Phase 4 acceptance criterion (PM-14 §5.4): prefunding deduction atomicity verified under concurrent test load. Use JMeter, k6, or equivalent to send 50 concurrent POST /v1/payments requests for SendMN with prefunding_account.balance=500.00 USD where each payment requires ~37 USD (collection_usd). Only ~13 payments can succeed before balance exhausts; rest must receive INSUFFICIENT_PREFUNDING. No negative balance allowed. Source: PM-14 §5.4, SAD-02 SELECT FOR UPDATE mandate.
**Steps:** Set SendMN staging prefunding_account.balance=500.00 USD.; Launch 50 concurrent POST /v1/payments threads with unique partner_txn_ref and Idempotency-Key each.; Collect HTTP response codes and final balance after all threads complete.; Assert final balance >= 0 (never negative).; Assert sum of prefund_deducted_usd across all HTTP 201 responses + final balance = 500.00 (pool identity).
**Deliverable:** Load test report confirming prefunding atomicity under 50 concurrent requests; no negative balance and no double-spend.
**Acceptance / logic checks:**
- Final prefunding_account.balance >= 0 (never goes negative).
- All HTTP 402 (INSUFFICIENT_PREFUNDING) responses have zero deduction (balance unchanged for those).
- Total deducted (sum of prefund_deducted_usd from HTTP 201 responses) + final balance = 500.00 USD within 0.01 USD.
- No deadlocks or timeouts in DB during test (all requests resolve within 5 seconds).
- Exactly N payments succeeded where N = floor(500 / collection_usd_per_payment).
**Depends on:** 16.3-T18, 16.3-T23

### 16.3-T37 — Verify Partner Portal balance view for SendMN and T-Bank (real-time, data isolation)  _(30 min)_
**Context:** The Partner Portal (PRD-08) must show OVERSEAS partners their current prefunding balance within 1 second of page load (G-02). Partners can only see their own data (G-05). Portal balance view shows: balance_usd, low_balance_threshold_usd, is_below_threshold, as_of timestamp. Portal is read-only; no config changes allowed. Source: PRD-08 §1.2, API-05 §4.8.
**Steps:** Log in to Partner Portal as SendMN portal user.; Navigate to Balance / Dashboard; record displayed balance_usd.; Verify displayed balance matches GET /v1/balance response balance_usd within 1 USD.; Verify as_of timestamp is within 2 seconds of current UTC time.; Log in as T-Bank portal user; attempt to access SendMN balance page URL directly. Assert 403 or redirect to T-Bank balance only.
**Deliverable:** Verified Partner Portal balance display for both SendMN and T-Bank with correct real-time balance and data isolation.
**Acceptance / logic checks:**
- SendMN portal user sees SendMN balance_usd within 1 s of page load.
- T-Bank portal user sees T-Bank balance_usd (not SendMN balance).
- Direct URL access to another partner balance page returns HTTP 403 (data isolation G-05).
- is_below_threshold=true when balance < 10000; badge or warning displayed.
- CSV export of transaction history for SendMN contains only SendMN transactions.
**Depends on:** 16.3-T14, 16.3-T32

### 16.3-T38 — Verify 8-step transaction event trail for OVERSEAS INBOUND payment in Admin System  _(30 min)_
**Context:** Every transaction carries an 8-step event trail stored from the first event. Steps for OVERSEAS INBOUND MPM: 1=QUOTED, 2=COMMITTED, 3=PENDING_DEBIT, 4=DEBITED, 5=SCHEME_SUBMITTED, 6=SCHEME_CONFIRMED, 7=SETTLEMENT_PENDING, 8=SETTLED. Step 2 is present only for OVERSEAS partners (deduction step absent for LOCAL). Admin System transaction detail must display all populated steps with timestamps. Source: PRD-07 §8.5.3, DAT-03 transaction event trail, Admin System A-10.
**Steps:** Execute a SendMN MPM INBOUND payment that reaches approved+settled state.; In Admin System > Transactions > search by partner=SendMN and find the transaction.; Open transaction detail; verify the event trail section shows all 8 step labels.; Confirm steps 1-7 have timestamps; step 8 timestamp populated after settlement batch runs.; Verify locked rate/pool values (collection_usd, payout_usd_cost, both margins, offer_rate_coll) are displayed in the detail view.
**Deliverable:** Admin System transaction detail for a SendMN INBOUND payment displaying all 8 event trail steps with timestamps and locked rate fields.
**Acceptance / logic checks:**
- All 8 event trail steps are labeled and displayed; steps 1-7 have timestamps after payment approval.
- Step 3 (PENDING_DEBIT) and step 4 (DEBITED) are present for OVERSEAS partner (absent for LOCAL GME Remit).
- Locked rate fields (collection_usd, payout_usd_cost, collection_margin_usd, payout_margin_usd) visible and match the original quote.
- Changing treasury.usd_krw after payment commit does NOT change the displayed locked rates.
- Pool identity holds in the displayed locked values: collection_usd - collection_margin_usd - payout_margin_usd = payout_usd_cost within 0.01.
**Depends on:** 16.3-T23, 16.3-T36

### 16.3-T39 — Verify audit log completeness for Phase 4 onboarding actions (partner, rule, prefunding, credentials)  _(35 min)_
**Context:** Every config change in the Admin System must be audit-logged with actor, timestamp, and previous value. This is required by SEC-09 and the Phase 4 acceptance checklist. Audit-log entries are immutable (no delete via UI). Verify completeness across: partner create/activate, rule create/activate, margin set, service charge set, prefunding top-up, credentials generate, alert threshold set. Source: PRD-07 §10, SEC-09 §5, TICKET_BRIEF §Audit.
**Steps:** Navigate to Admin System > Audit Log; filter by entity=SendMN (partner_id).; Verify audit log contains entries for: CREATE_PARTNER, ACTIVATE_PARTNER, CREATE_RULE, ACTIVATE_RULE, SET_MARGIN (m_a), SET_MARGIN (m_b), SET_SERVICE_CHARGE, CREDIT_TOPUP, GENERATE_CREDENTIALS, SET_ALERT_THRESHOLD.; For each entry confirm: actor (username), timestamp (UTC), previous value, new value.; Attempt to delete an audit log entry via Admin System UI. Assert delete button absent or returns error.; Export 7-day audit log to CSV; verify all expected columns and that it downloads within 30 seconds.
**Deliverable:** Audit log completeness verification report confirming all Phase 4 onboarding actions for SendMN are logged and immutable.
**Acceptance / logic checks:**
- At least 10 distinct audit event types present for SENDMN (CREATE_PARTNER, ACTIVATE_PARTNER, CREATE_RULE, SET_MARGIN x2, SET_SERVICE_CHARGE, CREDIT_TOPUP, GENERATE_CREDENTIALS, ACTIVATE_RULE, SET_ALERT_THRESHOLD).
- Every entry has actor, timestamp, and previous_value populated.
- Attempting to delete audit log entry via any UI action returns 403 or is unavailable.
- CSV export completes within 30 seconds for 7-day window.
- Same audit completeness verified for TBANK onboarding actions.
**Depends on:** 16.3-T38, 16.3-T33

### 16.3-T40 — Phase 4 go-live smoke test: SendMN live rate quote and pool math validation (production)  _(20 min)_
**Context:** OPS-13 §12.2 go-live smoke tests include: issue rate quote for SendMN (cross-border) and confirm USD pool math. This is a production smoke test after cutover. treasury.usd_krw is the live value set by GME Ops. test: target_payout=10000 KRW, direction=inbound, scheme_id=zeropay. Verify collection_usd, payout_usd_cost, margins, and pool identity hold. Prefunding balance must be readable. Source: OPS-13 §12.2.
**Steps:** Confirm GME Ops has set a live treasury.usd_krw value in production Admin System.; POST /v1/rates with SendMN production credentials: target_payout=10000, payout_currency=KRW, scheme_id=zeropay, direction=inbound.; Verify HTTP 200 and valid_until in future (> 60 s from now).; Calculate expected payout_usd_cost = 10000 / treasury.usd_krw; verify response payout_usd_cost within 0.01 USD.; Verify pool identity: collection_usd - collection_margin_usd - payout_margin_usd = payout_usd_cost within 0.01 USD.
**Deliverable:** Production smoke test result: SendMN rate quote with correct USD pool math using live treasury rate.
**Acceptance / logic checks:**
- HTTP 200 with quote_id and valid_until from production endpoint.
- payout_usd_cost matches 10000 / live treasury.usd_krw within 0.01 USD.
- Pool identity holds in production response.
- GET /v1/balance with SendMN production credentials returns HTTP 200 with balance_usd > 0.
- Smoke test result documented in go-live log with timestamp and actor.
**Depends on:** 16.3-T36, 16.3-T38

### 16.3-T41 — Phase 4 go-live smoke test: T-Bank live rate quote and prefunding balance readable (production)  _(20 min)_
**Context:** Same go-live smoke test as SendMN but for T-Bank. Confirm T-Bank production credentials work, rate engine returns correct USD pool values, and prefunding balance is readable. Source: OPS-13 §12.2, PM-14 §3.1 milestone Dec 10 2026.
**Steps:** POST /v1/rates with T-Bank production credentials: target_payout=10000, payout_currency=KRW, scheme_id=zeropay, direction=inbound.; Verify HTTP 200 with quote_id, valid_until, payout_usd_cost, collection_usd.; Verify pool identity holds.; GET /v1/balance with T-Bank production credentials. Assert HTTP 200, balance_usd > 0.; Document smoke test result in go-live log.
**Deliverable:** Production smoke test result: T-Bank rate quote with correct pool math and balance readable.
**Acceptance / logic checks:**
- HTTP 200 for POST /v1/rates with T-Bank production credentials.
- Pool identity holds in response values.
- GET /v1/balance returns T-Bank balance only (not SendMN balance).
- Smoke test logged with timestamp, actor, and both HTTP response codes.
- Both partners confirmed live before declaring Phase 4 go-live.
**Depends on:** 16.3-T40

### 16.3-T42 — Document Phase 4 partner onboarding runbook for SendMN and T-Bank  _(45 min)_
**Context:** OPS-13 §12.1.8 requires an operational runbook reviewed and signed off by Ops Lead. The Phase 4 onboarding runbook covers: partner record creation, rule configuration (currency quadruple, IDENTITY collect leg, 2% margins, USD service charge), prefunding top-up procedure, credential generation and secure transmission, low-balance alert setup, certification test execution, and go-live checklist sign-off. Source: OPS-13 §12.1, §12.2, PM-14 §5.4.
**Steps:** Create Phase 4 Onboarding Runbook document covering: partner record fields, rule configuration (quadruple + margins + service charge), prefunding top-up steps, credential generation procedure, low-balance alert configuration, certification test list (C-01 to C-16), and go-live smoke test steps.; Include numeric examples: m_a=1%, m_b=1%, service_charge=0.35 USD, threshold=10000 USD.; Add contact register: SendMN tech contact, T-Bank tech contact, GME BD, GME Ops lead.; Obtain review and sign-off from Ops Lead.; Store runbook in agreed ops documentation location and link from Admin System help.
**Deliverable:** Phase 4 Onboarding Runbook document (partner setup, rule config, prefunding, certs, go-live) signed off by Ops Lead.
**Acceptance / logic checks:**
- Runbook covers all 16 certification test IDs (C-01 to C-16) with pass criteria.
- Numeric examples for USD pool math and 2% margin constraint are present.
- Contact register lists SendMN and T-Bank technical contacts with phone/email.
- Runbook is accessible by Ops team without developer assistance.
- Ops Lead sign-off obtained and recorded with date.
**Depends on:** 16.3-T41


## WBS 16.5 — Hypercare (14-day) & stabilization
### 16.5-T01 — Define hypercare_day DB table and daily-status enum  _(30 min)_
**Context:** WBS 16.5 covers the 14-day hypercare window (Oct 10-24 2026) after GMEPay+ go-live. A persistent record of each hypercare day is needed so the final hypercare report can be generated. The table must record: day number (1-14), calendar date (UTC), ops_checkin_09_completed (bool), ops_checkin_18_completed (bool), batch_morning_ok (bool), batch_afternoon_ok (bool), p1_incident_count (int), p2_incident_count (int), slo_met (bool), daily_review_completed (bool), notes (text). A separate enum hypercare_status = {ACTIVE, PASSED, EXTENDED, FAILED} is needed on a hypercare_run table that tracks the overall run.
**Steps:** Create migration file db/migrations/V9001__hypercare_run.sql with table hypercare_run (id UUID PK, go_live_date DATE NOT NULL, status hypercare_status NOT NULL DEFAULT ACTIVE, end_date DATE, closed_by VARCHAR(100), created_at TIMESTAMPTZ DEFAULT now()).; Create migration V9002__hypercare_day.sql with table hypercare_day (id UUID PK, run_id UUID NOT NULL REFERENCES hypercare_run(id), day_number SMALLINT NOT NULL CHECK(day_number BETWEEN 1 AND 14), calendar_date DATE NOT NULL, ops_checkin_09_completed BOOLEAN NOT NULL DEFAULT false, ops_checkin_18_completed BOOLEAN NOT NULL DEFAULT false, batch_morning_ok BOOLEAN, batch_afternoon_ok BOOLEAN, p1_incident_count SMALLINT NOT NULL DEFAULT 0, p2_incident_count SMALLINT NOT NULL DEFAULT 0, slo_met BOOLEAN, daily_review_completed BOOLEAN NOT NULL DEFAULT false, notes TEXT, created_at TIMESTAMPTZ DEFAULT now(), UNIQUE(run_id, day_number)).; Add index on hypercare_day(run_id, calendar_date).; Run flyway validate in int environment; confirm both migrations apply cleanly with zero errors.
**Deliverable:** Two Flyway migration scripts V9001 and V9002 in db/migrations/ creating hypercare_run and hypercare_day tables with correct constraints.
**Acceptance / logic checks:**
- Inserting a hypercare_run row with status=ACTIVE and a hypercare_day row for day_number=1 succeeds; inserting a second row with day_number=1 for the same run_id violates the UNIQUE constraint.
- Inserting day_number=0 or day_number=15 violates the CHECK constraint.
- Foreign key from hypercare_day.run_id to hypercare_run.id is enforced (orphan insert rejected).
- The enum hypercare_status accepts only ACTIVE, PASSED, EXTENDED, FAILED; any other value is rejected by the DB.

### 16.5-T02 — Define HypercareRun and HypercareDay JPA entities and repository interfaces  _(40 min)_
**Context:** WBS 16.5. The platform is built in Java with Spring Boot and PostgreSQL. After the DB migration (16.5-T01), Java entity classes and Spring Data JPA repositories are needed for HypercareRun and HypercareDay so services can read and write hypercare state. HypercareRun fields: id (UUID), goLiveDate (LocalDate), status (HypercareStatus enum: ACTIVE/PASSED/EXTENDED/FAILED), endDate (LocalDate nullable), closedBy (String nullable), createdAt (OffsetDateTime). HypercareDay fields map 1:1 to the hypercare_day table columns defined in 16.5-T01.
**Steps:** Create src/main/java/com/gmepay/hypercare/domain/HypercareStatus.java enum with values ACTIVE, PASSED, EXTENDED, FAILED.; Create HypercareRun.java @Entity mapped to hypercare_run table with all fields; annotate status with @Enumerated(EnumType.STRING).; Create HypercareDay.java @Entity mapped to hypercare_day table with @ManyToOne to HypercareRun.; Create HypercareRunRepository extends JpaRepository<HypercareRun,UUID> with findByStatus(HypercareStatus).; Create HypercareDayRepository extends JpaRepository<HypercareDay,UUID> with findByRunIdOrderByDayNumber(UUID runId) and findByRunIdAndDayNumber(UUID runId, int dayNumber).; Confirm @DataJpaTest passes: save a HypercareRun and three HypercareDay rows, reload via repository, assert all fields round-trip correctly.
**Deliverable:** HypercareRun.java, HypercareDay.java entities, HypercareStatus.java enum, HypercareRunRepository.java, HypercareDayRepository.java in the hypercare domain package.
**Acceptance / logic checks:**
- @DataJpaTest: findByRunIdOrderByDayNumber returns days sorted ascending by day_number.
- @DataJpaTest: findByStatus(ACTIVE) returns only ACTIVE runs.
- Inserting a HypercareDay with dayNumber=0 throws a ConstraintViolationException (maps to DB check).
- The entity field batchMorningOk is nullable (Boolean, not boolean) and maps to batch_morning_ok; persisting null does not throw.
**Depends on:** 16.5-T01

### 16.5-T03 — Implement HypercareService: start run and record daily check-in  _(50 min)_
**Context:** WBS 16.5. The HypercareService orchestrates the 14-day hypercare run. Key business rules from OPS-13 sect 12.4: (1) A run starts at go-live (Oct 10 2026); it creates a HypercareRun with status=ACTIVE and pre-creates 14 HypercareDay rows (day_number 1-14, calendar_date = goLiveDate + (dayNumber-1) days). (2) Ops does two daily check-ins: 09:00 KST and 18:00 KST. recordCheckin(runId, date, slot=MORNING|EVENING) marks the correct boolean on the HypercareDay row. (3) Only one ACTIVE run may exist at a time; startRun() throws HypercareAlreadyActiveException if one exists.
**Steps:** Create HypercareService.java in the hypercare service package with @Transactional methods startRun(LocalDate goLiveDate) and recordCheckin(UUID runId, LocalDate date, CheckinSlot slot).; startRun: assert no existing ACTIVE run; create HypercareRun(status=ACTIVE, goLiveDate); bulk-insert 14 HypercareDay rows with calendar_date = goLiveDate.plusDays(dayNumber-1).; recordCheckin: load HypercareDay by runId + calendar_date; set opsCheckin09Completed=true (MORNING) or opsCheckin18Completed=true (EVENING); save.; Create HypercareAlreadyActiveException (RuntimeException) and CheckinSlot enum (MORNING, EVENING).; Write unit tests: startRun happy path creates 14 days; startRun when run exists throws; recordCheckin MORNING sets correct field only.
**Deliverable:** HypercareService.java with startRun and recordCheckin; HypercareAlreadyActiveException.java; CheckinSlot.java enum; unit tests HypercareServiceTest.java.
**Acceptance / logic checks:**
- startRun(2026-10-10) inserts 14 HypercareDay rows; day_number=1 has calendar_date=2026-10-10; day_number=14 has calendar_date=2026-10-23.
- Calling startRun twice throws HypercareAlreadyActiveException on the second call.
- recordCheckin with slot=MORNING sets opsCheckin09Completed=true and leaves opsCheckin18Completed unchanged (false).
- recordCheckin with slot=EVENING sets opsCheckin18Completed=true and leaves opsCheckin09Completed unchanged.
- recordCheckin for a date not in the run (e.g. day 15) throws HypercareDayNotFoundException.
**Depends on:** 16.5-T02

### 16.5-T04 — Implement HypercareService: record batch cycle result for a given day  _(30 min)_
**Context:** WBS 16.5. OPS-13 sect 12.4 states hypercare ends at day 14 only if at least one full batch cycle (morning + afternoon) completed successfully on each of 10 consecutive days. The morning batch corresponds to ZP0011/ZP0061 submission and ZP0012/ZP0062 receipt; the afternoon batch to ZP0063/ZP0065/ZP0066 and ZP0064 receipt. HypercareService needs a method recordBatchResult(runId, date, cycle=MORNING|AFTERNOON, success=bool) that writes to batch_morning_ok or batch_afternoon_ok on the correct HypercareDay row.
**Steps:** Add BatchCycle enum (MORNING, AFTERNOON) to the hypercare package.; Add recordBatchResult(UUID runId, LocalDate date, BatchCycle cycle, boolean success) to HypercareService.; Method loads the HypercareDay by (runId, date); throws HypercareDayNotFoundException if absent; sets batchMorningOk or batchAfternoonOk; saves.; Add unit tests: happy path sets correct field; wrong cycle does not affect the other field; calling twice with conflicting values (first true then false) stores the latest value.; Confirm the method is idempotent if called with the same cycle and success=true twice.
**Deliverable:** BatchCycle.java enum; recordBatchResult method in HypercareService.java; test cases in HypercareServiceTest.java covering batch recording logic.
**Acceptance / logic checks:**
- recordBatchResult(runId, 2026-10-10, MORNING, true) sets batchMorningOk=true; batchAfternoonOk remains null.
- recordBatchResult(runId, 2026-10-10, AFTERNOON, false) sets batchAfternoonOk=false; batchMorningOk unchanged.
- recordBatchResult for a date outside the run throws HypercareDayNotFoundException.
- Calling recordBatchResult twice with the same args is idempotent (no exception, final value is the last-written value).
**Depends on:** 16.5-T03

### 16.5-T05 — Implement HypercareService: record daily review and SLO compliance  _(30 min)_
**Context:** WBS 16.5. OPS-13 sect 12.4: even when no incidents occur, a daily review of the previous 24 hours for anomalies is mandatory. SLO compliance (payment success rate >= 98% per NFR-10 N-20, p95 latency < 1500ms per N-02, API uptime 99.9% per N-05) must be assessed per day. HypercareService needs recordDailyReview(runId, date, sloMet, notes) that marks dailyReviewCompleted=true, sloMet=bool, and stores the notes string on the HypercareDay row.
**Steps:** Add recordDailyReview(UUID runId, LocalDate date, boolean sloMet, String notes) to HypercareService.; Load HypercareDay by (runId, date); set dailyReviewCompleted=true, sloMet=sloMet, notes=notes (max 2000 chars; truncate with warning if longer); save.; Validate that notes is not null; throw IllegalArgumentException if null is passed (empty string is permitted).; Add unit tests: happy path; null notes throws; notes > 2000 chars are truncated to 2000.; Confirm that recording a daily review for an already-reviewed day overwrites the previous notes (upsert semantics).
**Deliverable:** recordDailyReview method in HypercareService.java; test cases in HypercareServiceTest.java.
**Acceptance / logic checks:**
- recordDailyReview with sloMet=false stores false in DB; subsequent read confirms sloMet=false.
- recordDailyReview sets dailyReviewCompleted=true regardless of sloMet value.
- Passing null for notes throws IllegalArgumentException with message indicating notes is required.
- A notes string of 2001 chars is truncated to exactly 2000 chars in the persisted value.
- Calling twice for the same date with different notes stores the second notes value (last-write wins).
**Depends on:** 16.5-T03

### 16.5-T06 — Implement HypercareService: record incident count for a given day  _(30 min)_
**Context:** WBS 16.5. Each HypercareDay tracks p1_incident_count and p2_incident_count. OPS-13 sect 11.1 defines P1 (15 min response, e.g. API down, pool identity failure) and P2 (30 min, e.g. latency breach, low prefunding). OPS-13 sect 12.4: hypercare ends only with zero P1 incidents over 14 days. P2 incidents during hypercare are treated as P1 for response purposes but the exit criterion only checks P1 count. HypercareService needs incrementIncidentCount(runId, date, severity=P1|P2) that atomically increments the correct counter on the HypercareDay.
**Steps:** Add IncidentSeverity enum (P1, P2) to the hypercare package.; Add incrementIncidentCount(UUID runId, LocalDate date, IncidentSeverity severity) to HypercareService.; Method loads HypercareDay; increments p1IncidentCount or p2IncidentCount by 1; saves; returns updated counts as an IncidentCountResult record.; Add unit test: increment P1 twice and P2 once; verify counts are 2 and 1 respectively.; Add edge case test: incrementing on day 14 (last day) works correctly.
**Deliverable:** IncidentSeverity.java enum; IncidentCountResult.java record; incrementIncidentCount method in HypercareService.java; test cases.
**Acceptance / logic checks:**
- Two calls to incrementIncidentCount(P1) for the same day produce p1IncidentCount=2.
- incrementIncidentCount(P2) does not affect p1IncidentCount.
- incrementIncidentCount for day_number=14 succeeds.
- incrementIncidentCount for a date outside the run throws HypercareDayNotFoundException.
- IncidentCountResult contains both p1IncidentCount and p2IncidentCount after each increment.
**Depends on:** 16.5-T03

### 16.5-T07 — Implement HypercareService: evaluate exit criteria and close run  _(50 min)_
**Context:** WBS 16.5. OPS-13 sect 12.4: hypercare exits at day 14 IF all three criteria are met: (a) zero P1 incidents across all 14 days, (b) SLOs met (sloMet=true) on all days that have a dailyReview recorded, (c) at least one full batch cycle (batchMorningOk=true AND batchAfternoonOk=true) on each of 10 consecutive days. If criteria not met on day 14, the run must be extended (status=EXTENDED) rather than closed as PASSED. A closeRun(runId, closedBy) method applies the criteria and sets status accordingly. A helper evaluateExitCriteria(runId) returns an ExitCriteriaResult with per-criterion booleans.
**Steps:** Add evaluateExitCriteria(UUID runId) to HypercareService: load all 14 HypercareDay rows sorted by day_number; compute noPi1Incidents = all days have p1IncidentCount=0; computeSlosMet = all days with dailyReviewCompleted=true have sloMet=true; computeConsecutiveBatch = find longest run of days where both batchMorningOk=true and batchAfternoonOk=true, check >= 10.; Return ExitCriteriaResult(boolean noPi1Incidents, boolean slosMet, boolean tenConsecutiveBatchDays, boolean allPassed).; Add closeRun(UUID runId, String closedBy): call evaluateExitCriteria; set status=PASSED if allPassed, else EXTENDED; set endDate=today, closedBy; save.; Unit tests: all criteria met -> PASSED; any P1 incident -> EXTENDED; only 9 consecutive batch days -> EXTENDED; non-consecutive 10 batch days -> EXTENDED.
**Deliverable:** ExitCriteriaResult.java record; evaluateExitCriteria and closeRun methods in HypercareService.java; unit tests in HypercareServiceTest.java covering all criterion branches.
**Acceptance / logic checks:**
- 14 days with p1=0, sloMet=true, consecutive batch days 1-10 -> allPassed=true and closeRun sets status=PASSED.
- Any day with p1IncidentCount=1 -> noPi1Incidents=false and closeRun sets status=EXTENDED.
- Only days 1-9 have full batch + days 11-14 have full batch (gap on day 10) -> tenConsecutiveBatchDays=false -> EXTENDED.
- Days 5-14 all have full batch (10 consecutive) -> tenConsecutiveBatchDays=true even though days 1-4 do not.
- closeRun on a run that is not ACTIVE throws IllegalStateException.
**Depends on:** 16.5-T06

### 16.5-T08 — Unit tests: hypercare exit-criteria edge cases (10-consecutive-batch algorithm)  _(40 min)_
**Context:** WBS 16.5. The 10-consecutive-batch-days criterion in HypercareService.evaluateExitCriteria is the most complex logic (longest-consecutive-run algorithm over 14 boolean pairs). A dedicated test class with parametrised vectors validates all boundary cases. The criterion: find the longest unbroken sequence of days where batchMorningOk=true AND batchAfternoonOk=true; that sequence length must be >= 10 to pass.
**Steps:** Create HypercareExitCriteriaTest.java with @ParameterizedTest(MethodSource).; Define test vectors: (1) all 14 days full batch -> pass; (2) days 1-10 full, 11-14 missing afternoon -> pass (days 1-10 is 10 consecutive); (3) days 1-9 full + day 10 morning only + days 11-14 full -> fail (max run = 9, then 4; neither >= 10); (4) days 2-11 full, rest missing -> pass; (5) alternating full/missing -> fail; (6) exactly 10 consecutive ending on day 14 -> pass.; For each vector, build a list of 14 HypercareDay objects with the specified booleans and call evaluateExitCriteria using a mock repository.; Assert tenConsecutiveBatchDays matches expected boolean for each vector.; Confirm no vector produces an exception for valid inputs.
**Deliverable:** HypercareExitCriteriaTest.java with at least 6 parametrised test vectors fully covering the consecutive-batch algorithm.
**Acceptance / logic checks:**
- Vector (1): all 14 full -> tenConsecutiveBatchDays=true.
- Vector (3): max run=9 due to day-10 gap -> tenConsecutiveBatchDays=false.
- Vector (5): alternating -> max run=1 -> tenConsecutiveBatchDays=false.
- Vector (6): days 5-14 consecutive (10) -> tenConsecutiveBatchDays=true.
- All 6 vectors pass without exceptions.
**Depends on:** 16.5-T07

### 16.5-T09 — Implement daily partner check-in recording (first 5 business days)  _(40 min)_
**Context:** WBS 16.5. OPS-13 sect 12.4: daily calls with GME Remit and SendMN technical contacts are required for the first 5 business days post-go-live. These need to be tracked. Add a hypercare_partner_checkin table (via migration): id UUID PK, run_id UUID FK hypercare_run, checkin_date DATE, partner_id VARCHAR(50), contact_name VARCHAR(100), outcome VARCHAR(20) CHECK(outcome IN (COMPLETED, MISSED, RESCHEDULED)), issues_raised TEXT, recorded_by VARCHAR(100), recorded_at TIMESTAMPTZ. HypercareService gets recordPartnerCheckin(runId, date, partnerId, contactName, outcome, issuesRaised, recordedBy).
**Steps:** Create migration V9003__hypercare_partner_checkin.sql with the table definition above.; Add HypercarePartnerCheckin.java @Entity and HypercarePartnerCheckinRepository.java (findByRunIdOrderByCheckinDateAscPartnerIdAsc).; Add recordPartnerCheckin method to HypercareService; validate that the checkin_date is within the first 5 business days of goLiveDate (Mon-Fri, skipping Korean public holidays is out of scope; just check <= goLiveDate + 6 calendar days within which 5 business days fall); log a warning but do not reject if date is after day 5.; Add unit test: record two partner check-ins on day 1 (GME Remit + SendMN); verify both persisted with correct outcome.
**Deliverable:** V9003 migration; HypercarePartnerCheckin.java entity and repository; recordPartnerCheckin method in HypercareService.java; unit test.
**Acceptance / logic checks:**
- Insert two check-ins with partnerId=GME_REMIT and partnerId=SENDMN for 2026-10-10; both rows persisted with run_id FK intact.
- Outcome value INVALID is rejected by the DB CHECK constraint.
- findByRunIdOrderByCheckinDateAscPartnerIdAsc returns rows sorted by date then partner alphabetically.
- recordPartnerCheckin with issuesRaised=null stores NULL (not an empty string).
**Depends on:** 16.5-T02

### 16.5-T10 — Admin API: POST /internal/hypercare/runs - start hypercare run  _(45 min)_
**Context:** WBS 16.5. GME Ops starts the hypercare run at go-live via an internal Admin API endpoint. The endpoint is POST /internal/hypercare/runs with JSON body {goLiveDate: ISO-8601 date string}. It calls HypercareService.startRun and returns 201 Created with the HypercareRunDto (id, goLiveDate, status, createdAt). The endpoint is guarded by the ROLE_OPS_ADMIN RBAC role (internal Admin Service; no partner-facing). Returns 409 Conflict if a run is already ACTIVE (HypercareAlreadyActiveException). All actions are audit-logged with actor identity and timestamp.
**Steps:** Create HypercareController.java in the admin API package with @PostMapping(/internal/hypercare/runs).; Validate the request body: goLiveDate must be present and a valid ISO date; return 400 if malformed.; Call HypercareService.startRun(goLiveDate); return 201 with HypercareRunDto.; Catch HypercareAlreadyActiveException and return 409 with error body {code: HYPERCARE_ALREADY_ACTIVE, message: ...}.; Write an audit log entry via the AuditLogService with action=HYPERCARE_RUN_STARTED, actor=authenticated user, payload=goLiveDate.; Add a MockMvc integration test verifying 201 on success and 409 on duplicate.
**Deliverable:** HypercareController.java (POST runs endpoint); HypercareRunDto.java; integration test HypercareControllerTest.java covering success and duplicate cases.
**Acceptance / logic checks:**
- POST /internal/hypercare/runs {goLiveDate: 2026-10-10} returns 201 with status=ACTIVE and 14 days implied.
- Second POST with same date returns 409 with code=HYPERCARE_ALREADY_ACTIVE.
- POST with goLiveDate=invalid-string returns 400.
- Audit log entry exists with action=HYPERCARE_RUN_STARTED after successful call.
- Unauthenticated request (no token) returns 401.
**Depends on:** 16.5-T03

### 16.5-T11 — Admin API: POST /internal/hypercare/runs/{id}/checkins - record ops check-in  _(35 min)_
**Context:** WBS 16.5. OPS-13 sect 12.4: twice daily check-ins at 09:00 and 18:00 KST. Ops records these via the Admin portal which calls POST /internal/hypercare/runs/{id}/checkins with body {date: ISO date, slot: MORNING|EVENING}. Returns 200 OK with the updated HypercareDayDto. Returns 404 if run not found, 400 if slot is invalid, 422 if the date is outside the run window. All actions audit-logged.
**Steps:** Add POST /internal/hypercare/runs/{id}/checkins to HypercareController.; Validate path param id (UUID) and request body; return 400 for bad slot value.; Call HypercareService.recordCheckin(runId, date, slot); return 200 with HypercareDayDto for that day.; Map HypercareDayNotFoundException to 422 Unprocessable Entity with body {code: DATE_OUT_OF_RANGE}.; Add audit log entry: action=HYPERCARE_CHECKIN_RECORDED, actor, runId, date, slot.; Add MockMvc tests: success MORNING, success EVENING, unknown slot returns 400, date not in run returns 422.
**Deliverable:** Updated HypercareController.java (checkin endpoint); HypercareDayDto.java; test cases in HypercareControllerTest.java.
**Acceptance / logic checks:**
- POST checkin with slot=MORNING on day 1 returns 200 with opsCheckin09Completed=true.
- POST checkin with slot=INVALID returns 400.
- POST checkin with date outside 14-day window returns 422 with code DATE_OUT_OF_RANGE.
- Audit log entry is present after successful check-in.
- POST checkin on a non-existent runId returns 404.
**Depends on:** 16.5-T10

### 16.5-T12 — Admin API: POST /internal/hypercare/runs/{id}/batch-results - record batch cycle result  _(35 min)_
**Context:** WBS 16.5. Ops records whether the morning (ZP0011/ZP0061 cycle) or afternoon (ZP0063/ZP0065 cycle) batch completed successfully each day. Endpoint: POST /internal/hypercare/runs/{id}/batch-results with body {date, cycle: MORNING|AFTERNOON, success: bool}. Returns 200 with HypercareDayDto. Returns 404 run not found, 422 date out of range. Action audit-logged. The batch monitoring system (16.5-T20) also calls this endpoint automatically; Ops can call it manually as an override.
**Steps:** Add POST /internal/hypercare/runs/{id}/batch-results to HypercareController.; Validate body: date (ISO date), cycle (MORNING|AFTERNOON), success (boolean required).; Call HypercareService.recordBatchResult; return 200 with HypercareDayDto.; Map HypercareDayNotFoundException to 422; unknown run to 404.; Audit log: action=HYPERCARE_BATCH_RESULT_RECORDED, actor, runId, date, cycle, success.; Add MockMvc tests: MORNING success=true, AFTERNOON success=false, invalid cycle 400, out-of-range date 422.
**Deliverable:** Updated HypercareController.java (batch-results endpoint); test cases in HypercareControllerTest.java.
**Acceptance / logic checks:**
- POST batch-results cycle=MORNING success=true returns 200 with batchMorningOk=true in response.
- POST batch-results cycle=AFTERNOON success=false returns 200 with batchAfternoonOk=false.
- POST with cycle=WEEKLY returns 400.
- Audit log entry exists after successful POST.
- POST with date=2026-10-25 (day 15) returns 422.
**Depends on:** 16.5-T11

### 16.5-T13 — Admin API: POST /internal/hypercare/runs/{id}/daily-reviews - record daily review  _(30 min)_
**Context:** WBS 16.5. OPS-13 sect 12.4 requires a daily review of the previous 24 hours even with no incidents. Endpoint: POST /internal/hypercare/runs/{id}/daily-reviews with body {date, sloMet: bool, notes: string (required, 1-2000 chars)}. Returns 200 with HypercareDayDto. SLO baseline from NFR-10: payment success rate >= 98%, API uptime 99.9%, p95 latency < 1500ms. Notes must record whether SLOs were met and any anomalies observed. Action audit-logged.
**Steps:** Add POST /internal/hypercare/runs/{id}/daily-reviews to HypercareController.; Validate body: date required, sloMet required boolean, notes required 1-2000 chars; return 400 on violation.; Call HypercareService.recordDailyReview; return 200 with HypercareDayDto.; Audit log: action=HYPERCARE_DAILY_REVIEW_RECORDED, actor, runId, date, sloMet.; Add MockMvc tests: success with sloMet=false and notes present; missing notes returns 400; notes > 2000 chars returns 400.
**Deliverable:** Updated HypercareController.java (daily-reviews endpoint); test cases in HypercareControllerTest.java.
**Acceptance / logic checks:**
- POST daily-reviews sloMet=false with 50-char notes returns 200 with dailyReviewCompleted=true and sloMet=false in response.
- POST with notes omitted returns 400 with field-level validation error.
- POST with notes of 2001 characters returns 400.
- POST with notes of exactly 2000 characters returns 200 and stores all 2000 chars.
- Audit log entry contains sloMet value.
**Depends on:** 16.5-T11

### 16.5-T14 — Admin API: POST /internal/hypercare/runs/{id}/incidents - record incident  _(30 min)_
**Context:** WBS 16.5. During hypercare, each P1 or P2 incident must be logged against the day. OPS-13 sect 12.4: P2 alerts are treated as P1 for response but only P1 counts affect exit criteria. Endpoint: POST /internal/hypercare/runs/{id}/incidents with body {date, severity: P1|P2, incidentRef: string (reference to the incident management system, e.g. PD-20261012-001)}. Increments the counter on HypercareDay. Returns 200 with IncidentCountResult. Audit-logged.
**Steps:** Add POST /internal/hypercare/runs/{id}/incidents to HypercareController.; Validate body: date required, severity (P1|P2) required, incidentRef required non-blank string.; Call HypercareService.incrementIncidentCount(runId, date, severity); return 200 with IncidentCountResult.; Audit log: action=HYPERCARE_INCIDENT_RECORDED, actor, runId, date, severity, incidentRef.; MockMvc tests: P1 increments p1Count; P2 increments p2Count; invalid severity returns 400; missing incidentRef returns 400.
**Deliverable:** Updated HypercareController.java (incidents endpoint); test cases in HypercareControllerTest.java.
**Acceptance / logic checks:**
- POST incidents severity=P1 returns 200 with p1IncidentCount=1 (assuming start from 0).
- POST incidents severity=P2 returns 200 with p2IncidentCount incremented; p1IncidentCount unchanged.
- POST incidents severity=P3 returns 400.
- POST incidents with blank incidentRef returns 400.
- Audit log entry includes incidentRef value.
**Depends on:** 16.5-T11

### 16.5-T15 — Admin API: POST /internal/hypercare/runs/{id}/partner-checkins - record partner check-in  _(35 min)_
**Context:** WBS 16.5. OPS-13 sect 12.4: daily partner calls with GME Remit and SendMN for first 5 business days. Endpoint: POST /internal/hypercare/runs/{id}/partner-checkins with body {date, partnerId: string, contactName: string, outcome: COMPLETED|MISSED|RESCHEDULED, issuesRaised: string (optional)}. Returns 201 Created with the new record. Audit-logged. Also returns a warning header X-Hypercare-Warning: PAST_DAY5 if the checkin date is after the first 5 business days of the run.
**Steps:** Add POST /internal/hypercare/runs/{id}/partner-checkins to HypercareController.; Validate body: date, partnerId, contactName, outcome (COMPLETED|MISSED|RESCHEDULED) all required; issuesRaised optional.; Call HypercareService.recordPartnerCheckin; return 201 with HypercarePartnerCheckinDto.; If date > goLiveDate + 6 calendar days, add response header X-Hypercare-Warning: PAST_DAY5 but still return 201.; Audit log: action=HYPERCARE_PARTNER_CHECKIN_RECORDED, actor, runId, partnerId, outcome.; MockMvc tests: success for day 1 GME Remit; outcome=INVALID returns 400; date after day 6 returns 201 with warning header.
**Deliverable:** Updated HypercareController.java (partner-checkins endpoint); HypercarePartnerCheckinDto.java; test cases in HypercareControllerTest.java.
**Acceptance / logic checks:**
- POST partner-checkins for 2026-10-10 outcome=COMPLETED returns 201 with no warning header.
- POST for 2026-10-17 (day 8) returns 201 with X-Hypercare-Warning: PAST_DAY5 header present.
- POST with outcome=INVALID returns 400.
- POST with missing partnerId returns 400.
- Audit log entry contains partnerId and outcome.
**Depends on:** 16.5-T09, 16.5-T10

### 16.5-T16 — Admin API: GET /internal/hypercare/runs/{id}/status - current hypercare status  _(45 min)_
**Context:** WBS 16.5. Ops needs a dashboard-ready endpoint that returns the current state of the active hypercare run at a glance. Endpoint: GET /internal/hypercare/runs/{id}/status. Response: HypercareStatusDto containing runId, goLiveDate, status (ACTIVE/PASSED/EXTENDED/FAILED), currentDay (1-14 based on today's date), days array (14 HypercareDaySummaryDto each with: dayNumber, date, bothCheckinsComplete, batchFullCycleComplete, dailyReviewComplete, sloMet, p1IncidentCount, p2IncidentCount), exitCriteria (from evaluateExitCriteria), consecutiveBatchDaysCount (max consecutive so far).
**Steps:** Add GET /internal/hypercare/runs/{id}/status to HypercareController.; Load HypercareRun by id; return 404 if not found.; Build HypercareStatusDto: for each of the 14 HypercareDay rows compute bothCheckinsComplete=(opsCheckin09Completed AND opsCheckin18Completed), batchFullCycleComplete=(batchMorningOk==true AND batchAfternoonOk==true).; Compute currentDay = ChronoUnit.DAYS.between(goLiveDate, LocalDate.now()) + 1, clamped to 1-14.; Call evaluateExitCriteria and include result in response.; Add MockMvc test: seeded run with 3 days of data returns correct summary.
**Deliverable:** GET status endpoint in HypercareController.java; HypercareStatusDto.java; HypercareDaySummaryDto.java; test in HypercareControllerTest.java.
**Acceptance / logic checks:**
- A day where opsCheckin09Completed=true but opsCheckin18Completed=false has bothCheckinsComplete=false in the response.
- A day where batchMorningOk=true and batchAfternoonOk=null has batchFullCycleComplete=false.
- consecutiveBatchDaysCount equals the current maximum consecutive full-batch days in the seeded data.
- exitCriteria.allPassed is false if any p1IncidentCount > 0 in any day.
- GET on non-existent runId returns 404.
**Depends on:** 16.5-T07, 16.5-T10

### 16.5-T17 — Admin API: POST /internal/hypercare/runs/{id}/close - evaluate exit and close run  _(40 min)_
**Context:** WBS 16.5. OPS-13 sect 12.4: hypercare ends at day 14 if criteria met (zero P1 incidents, SLOs met, 10 consecutive full batch days). This endpoint is called by Ops on day 14 (or later for extended runs). POST /internal/hypercare/runs/{id}/close with body {closedBy: string}. Internally calls HypercareService.closeRun; returns 200 with HypercareRunDto showing the new status (PASSED or EXTENDED) and the ExitCriteriaResult. Returns 409 if run is not ACTIVE.
**Steps:** Add POST /internal/hypercare/runs/{id}/close to HypercareController.; Validate body: closedBy required non-blank.; Call HypercareService.closeRun(runId, closedBy); return 200 with HypercareRunDto including exitCriteria field.; Map IllegalStateException (run not ACTIVE) to 409 with code=RUN_NOT_ACTIVE.; Audit log: action=HYPERCARE_RUN_CLOSED, actor, runId, closedBy, newStatus.; MockMvc tests: criteria met -> status=PASSED; any P1 incident -> status=EXTENDED; closing already-PASSED run -> 409.
**Deliverable:** POST close endpoint in HypercareController.java; test cases in HypercareControllerTest.java covering PASSED and EXTENDED outcomes.
**Acceptance / logic checks:**
- Closing a run with zero P1s, all SLOs met, 10 consecutive batch days returns 200 with status=PASSED.
- Closing a run with one P1 incident on day 3 returns 200 with status=EXTENDED and exitCriteria.noPi1Incidents=false.
- Closing an already-PASSED run returns 409 with code=RUN_NOT_ACTIVE.
- Missing closedBy body field returns 400.
- Audit log entry contains newStatus value after close.
**Depends on:** 16.5-T16

### 16.5-T18 — Implement hypercare issue triage log: DB schema and entity  _(40 min)_
**Context:** WBS 16.5. The parent deliverable for WBS 16.5 is a Hypercare Report, which must include an issue triage log. Each anomaly, near-miss, or P3/P4 observation during hypercare should be recorded even if it does not rise to a formal incident. DB table hypercare_issue (id UUID PK, run_id UUID FK hypercare_run, day_number SMALLINT, reported_at TIMESTAMPTZ, severity VARCHAR(10) CHECK(severity IN (P1,P2,P3,P4)), title VARCHAR(200), description TEXT, component VARCHAR(100), status VARCHAR(20) CHECK(status IN (OPEN,INVESTIGATING,RESOLVED,WONTFIX)), resolution TEXT, resolved_at TIMESTAMPTZ, reported_by VARCHAR(100)).
**Steps:** Create migration V9004__hypercare_issue.sql with the table definition above.; Create HypercareIssue.java @Entity and HypercareIssueRepository with findByRunIdOrderByReportedAtDesc and findByRunIdAndStatus.; Create HypercareIssueService.java with reportIssue(runId, severity, title, description, component, reportedBy) and resolveIssue(issueId, resolution, resolvedBy).; Unit tests: reportIssue creates with status=OPEN; resolveIssue sets status=RESOLVED and resolvedAt=now().
**Deliverable:** V9004 migration; HypercareIssue.java entity and repository; HypercareIssueService.java; unit tests.
**Acceptance / logic checks:**
- reportIssue persists with status=OPEN and resolvedAt=null.
- resolveIssue sets status=RESOLVED, resolution text, and resolvedAt timestamp (not null) in DB.
- severity value P5 is rejected by the DB CHECK constraint.
- status value DEFERRED is rejected by the DB CHECK constraint.
- findByRunIdAndStatus(OPEN) returns only OPEN issues.
**Depends on:** 16.5-T01

### 16.5-T19 — Admin API: CRUD endpoints for hypercare issue triage log  _(40 min)_
**Context:** WBS 16.5. Ops needs to log and update triage issues during the hypercare window. Endpoints: POST /internal/hypercare/runs/{runId}/issues (report new issue); GET /internal/hypercare/runs/{runId}/issues (list, filterable by status and severity); PATCH /internal/hypercare/runs/{runId}/issues/{issueId}/resolve (resolve with resolution text). All audit-logged. HypercareIssueDto fields: id, runId, dayNumber, reportedAt, severity, title, description, component, status, resolution, resolvedAt, reportedBy.
**Steps:** Add POST /internal/hypercare/runs/{runId}/issues: validate body (severity, title 1-200 chars, description required, component required); call HypercareIssueService.reportIssue; return 201.; Add GET endpoint with optional query params status and severity; return list of HypercareIssueDtos.; Add PATCH .../resolve: validate body (resolution required 1-1000 chars, resolvedBy required); call resolveIssue; return 200.; Audit log each operation with action names HYPERCARE_ISSUE_REPORTED and HYPERCARE_ISSUE_RESOLVED.; MockMvc tests covering each endpoint, including filtering by status=OPEN and severity=P2.
**Deliverable:** Three endpoints in HypercareController.java (or a separate HypercareIssueController); test cases covering CRUD and filtering.
**Acceptance / logic checks:**
- POST issue with severity=P3 title=Merchant sync delay returns 201 with status=OPEN.
- GET issues?status=OPEN returns only open issues; GET issues?severity=P2 returns only P2 issues.
- PATCH resolve with missing resolution returns 400.
- PATCH resolve sets status=RESOLVED and resolvedAt non-null in the response.
- GET issues on unknown runId returns 404.
**Depends on:** 16.5-T18, 16.5-T10

### 16.5-T20 — Batch monitoring auto-integration: hook ZeroPay batch job completion into hypercare  _(50 min)_
**Context:** WBS 16.5. During hypercare, the ZeroPay batch jobs (JOB-ZP-01 through JOB-ZP-08) must automatically update the hypercare day record so Ops does not have to record batch results manually. The existing BatchJobEventPublisher (or equivalent) should call the HypercareService after each batch job completes. Job-to-cycle mapping: JOB-ZP-01 (ZP0011 submission) and JOB-ZP-06 (ZP0062 receipt) together constitute the MORNING cycle; JOB-ZP-07 (ZP0063 submission) and JOB-ZP-08 (ZP0064 receipt) constitute the AFTERNOON cycle. The MORNING cycle is complete (success=true) only when both jobs succeed; any failure sets success=false.
**Steps:** Create HypercareBatchObserver.java as a Spring @Component that listens to BatchJobCompletedEvent (existing event type).; On each event, check if there is an ACTIVE hypercare run (HypercareRunRepository.findByStatus(ACTIVE)).; Maintain an in-memory (or DB-backed) state tracking whether both jobs for a cycle have completed; when both have: call HypercareService.recordBatchResult for the correct cycle and date.; Handle the case where no ACTIVE hypercare run exists (log at DEBUG, no-op).; Add integration test: publish two BatchJobCompletedEvents for JOB-ZP-01 and JOB-ZP-06 on 2026-10-10; assert batchMorningOk=true on day 1.
**Deliverable:** HypercareBatchObserver.java; integration test HypercareBatchObserverTest.java verifying auto-recording for both MORNING and AFTERNOON cycles.
**Acceptance / logic checks:**
- After JOB-ZP-01 completes (success), batchMorningOk is still null (morning cycle not yet complete).
- After both JOB-ZP-01 and JOB-ZP-06 complete successfully, batchMorningOk=true.
- If JOB-ZP-01 succeeds but JOB-ZP-06 fails, batchMorningOk=false.
- When no ACTIVE hypercare run exists, the observer does nothing and logs at DEBUG.
- After JOB-ZP-07 and JOB-ZP-08 both succeed, batchAfternoonOk=true for the same calendar date.
**Depends on:** 16.5-T04, 16.5-T03

### 16.5-T21 — SLO compliance auto-evaluation job: nightly check and hypercare day update  _(50 min)_
**Context:** WBS 16.5. OPS-13 sect 12.4 requires SLOs to be met each day. SLO definitions from NFR-10: payment success rate >= 98% (N-20), Payment API p95 latency < 1500ms (N-02), API uptime 99.9% per month (N-05). A nightly job runs at 01:00 KST (16:00 UTC) and evaluates the previous calendar day's SLO compliance using metrics already stored in the monitoring system (Prometheus). It calls HypercareService.recordDailyReview for the previous day with sloMet=true/false and a generated notes string. This job runs only when a hypercare run is ACTIVE.
**Steps:** Create HypercareSloEvaluationJob.java as a Spring scheduled task (@Scheduled(cron=0 0 16 * * *, zone=UTC) == 01:00 KST).; Inject MetricsQueryClient (existing); query: payment_success_rate_1d (must be >= 0.98), payment_p95_latency_ms (must be < 1500), api_uptime_pct_24h (must be >= 99.9). Use yesterday's date range.; Determine sloMet: all three metrics must pass.; Build notes string: SLO auto-evaluation: success_rate={value}, p95_latency={value}ms, uptime={value}%. SLO MET / BREACHED.; Call HypercareService.recordDailyReview(runId, yesterday, sloMet, notes) only if there is an ACTIVE run.; Unit test: mock metrics passing -> sloMet=true; mock p95=1501ms -> sloMet=false; no active run -> no-op.
**Deliverable:** HypercareSloEvaluationJob.java with scheduled method; unit tests HypercareSloEvaluationJobTest.java.
**Acceptance / logic checks:**
- payment_success_rate=0.985, p95_latency=1200ms, uptime=99.95% -> sloMet=true; notes contain all three metric values.
- payment_success_rate=0.975 (below 0.98) -> sloMet=false.
- p95_latency=1501ms (above 1500) -> sloMet=false even if other metrics pass.
- api_uptime=99.85% (below 99.9%) -> sloMet=false.
- If no ACTIVE run exists, recordDailyReview is never called (verify with mock assertion).
**Depends on:** 16.5-T05, 16.5-T03

### 16.5-T22 — Hypercare report generator: compile full 14-day report as structured DTO  _(50 min)_
**Context:** WBS 16.5. The parent deliverable for WBS 16.5 is a Hypercare Report. HypercareReportService.generateReport(runId) compiles the full report into a HypercareReportDto. The report must include: (1) run summary (goLiveDate, endDate, status, closedBy), (2) exit criteria result, (3) per-day table (all 14 days with all HypercareDay fields + partner check-ins for that day), (4) incident summary (total P1 count, total P2 count, per-day breakdown), (5) issue triage log (all issues sorted by reportedAt), (6) SLO compliance summary (days sloMet / total reviewed days), (7) batch performance summary (total full-cycle days, max consecutive).
**Steps:** Create HypercareReportService.java with generateReport(UUID runId) returning HypercareReportDto.; Build all seven sections by querying HypercareRunRepository, HypercareDayRepository, HypercarePartnerCheckinRepository, HypercareIssueRepository.; Compute incidentSummary: sum p1IncidentCount and p2IncidentCount across all days.; Compute sloComplianceSummary: days with dailyReviewCompleted=true and sloMet=true / total days with dailyReviewCompleted=true.; Compute batchPerformanceSummary: count days with batchFullCycle=true; compute maxConsecutiveBatchDays.; Unit test with seeded data covering all fields.
**Deliverable:** HypercareReportService.java; HypercareReportDto.java and all nested DTO classes; unit tests HypercareReportServiceTest.java.
**Acceptance / logic checks:**
- Report for a run with 2 P1 incidents across two days shows incidentSummary.totalP1=2.
- SLO compliance: 10 of 12 reviewed days met SLO -> sloComplianceSummary={met:10, reviewed:12, percentage:83.3}.
- batchPerformanceSummary.maxConsecutiveBatchDays matches the same algorithm tested in 16.5-T08.
- The per-day table has exactly 14 entries, ordered by day_number ascending.
- generateReport on non-existent runId throws HypercareRunNotFoundException.
**Depends on:** 16.5-T07, 16.5-T09, 16.5-T18

### 16.5-T23 — Admin API: GET /internal/hypercare/runs/{id}/report - serve hypercare report  _(40 min)_
**Context:** WBS 16.5. The hypercare report must be accessible via the Admin portal. Endpoint: GET /internal/hypercare/runs/{id}/report. Calls HypercareReportService.generateReport and returns the full HypercareReportDto as JSON. Supports Accept: application/json (default) and Accept: text/csv (returns a simplified CSV of the per-day table for Finance/Ops export). Returns 404 if run not found. Requires ROLE_OPS_ADMIN or ROLE_FINANCE_READ.
**Steps:** Add GET /internal/hypercare/runs/{id}/report to HypercareController (or HypercareReportController).; Call HypercareReportService.generateReport(runId); return 200 with JSON body.; For Accept: text/csv, generate a CSV using a simple CsvFormatter: columns day_number, date, both_checkins_complete, batch_full_cycle, daily_review_complete, slo_met, p1_incidents, p2_incidents.; Set Content-Disposition: attachment; filename=hypercare-report-{runId}.csv for CSV response.; MockMvc tests: JSON response has all top-level sections; CSV response has correct header row; unknown runId returns 404.
**Deliverable:** Report GET endpoint in HypercareController.java; CsvFormatter.java (or similar); test cases in HypercareControllerTest.java.
**Acceptance / logic checks:**
- GET report returns JSON with all 7 sections (runSummary, exitCriteria, days, incidentSummary, issueTriageLog, sloComplianceSummary, batchPerformanceSummary).
- GET report with Accept: text/csv returns 200 with Content-Type: text/csv and the correct 8-column header row.
- GET report for unknown runId returns 404.
- JSON response days array has exactly 14 elements.
- CSV Content-Disposition header contains filename hypercare-report-{id}.csv.
**Depends on:** 16.5-T22, 16.5-T16

### 16.5-T24 — Unit tests: HypercareReportService with full 14-day seeded dataset  _(55 min)_
**Context:** WBS 16.5. A comprehensive test class seeds a complete 14-day hypercare dataset and validates every field of HypercareReportDto. The test should cover a representative real scenario: days 1-5 have partner check-ins; days 1-11 have batch MORNING success, days 3-14 have batch AFTERNOON success (morning fully consecutive); 1 P2 incident on day 4; 1 P3 issue in triage log; all days have daily review with sloMet=true except day 6 (sloMet=false); no P1 incidents. Exit criteria: PASSED (zero P1, 10+ consecutive batch, all SLOs met on reviewed days except day 6 -> slosMet=false -> EXTENDED).
**Steps:** Create HypercareReportServiceIntegrationTest.java using @SpringBootTest + TestContainers (PostgreSQL).; Seed the DB with the described 14-day scenario using repository calls.; Call HypercareReportService.generateReport and capture HypercareReportDto.; Assert each section: runSummary.status=ACTIVE (not yet closed), incidentSummary.totalP1=0, incidentSummary.totalP2=1, sloComplianceSummary.met=13/14 reviewed, issueTriageLog.size=1, batchPerformanceSummary.maxConsecutiveBatchDays=9 (days 3-11 have both), exitCriteria.slosMet=false.; Confirm closeRun on this seeded data returns status=EXTENDED.
**Deliverable:** HypercareReportServiceIntegrationTest.java with a full-scenario seed and assertion of all seven report sections.
**Acceptance / logic checks:**
- incidentSummary.totalP2=1 (day 4 P2 incident).
- sloComplianceSummary: 13 of 14 reviewed days met -> percentage approx 92.9%.
- issueTriageLog has 1 entry with severity=P3.
- batchPerformanceSummary.maxConsecutiveBatchDays: days 3-11 afternoon + days 1-11 morning; full cycle (both) = days 3-11 = 9 consecutive -> maxConsecutiveBatchDays=9.
- exitCriteria.slosMet=false (day 6 sloMet=false) -> allPassed=false.
**Depends on:** 16.5-T22

### 16.5-T25 — Alert rule: P2-treated-as-P1 threshold override during hypercare  _(40 min)_
**Context:** WBS 16.5. OPS-13 sect 12.4: during hypercare, any P2 alert is treated as P1 for response purposes (15 min acknowledgement, not 30 min). This must be implemented as a feature in the alerting system. When a hypercare run is ACTIVE, the AlertRouter (existing component) must downgrade response thresholds: any P2 alert notification includes an additional tag hypercare_mode=true and the PagerDuty urgency is set to high (same as P1). This is achieved by a HypercareAlertEnricher that wraps the outbound alert payload.
**Steps:** Create HypercareAlertEnricher.java as a Spring @Component with method enrichAlert(AlertPayload payload): if HypercareRunRepository.findByStatus(ACTIVE) is non-empty, add tag hypercare_mode=true to the payload; if payload.severity==P2, set payload.pdUrgency=high.; Wire HypercareAlertEnricher into the existing AlertRouter.dispatch() method call chain.; Confirm that when no ACTIVE run exists, enrichAlert is a no-op and pdUrgency is unchanged for P2 alerts.; Unit tests: P2 alert during ACTIVE run -> pdUrgency=high + tag present; P2 alert with no active run -> pdUrgency=low; P1 alert during active run -> pdUrgency=high (unchanged).; Add an integration test that publishes a P2 alert event and verifies the PagerDuty mock receives urgency=high.
**Deliverable:** HypercareAlertEnricher.java; wiring in AlertRouter.java; unit tests HypercareAlertEnricherTest.java.
**Acceptance / logic checks:**
- P2 alert with ACTIVE run: PagerDuty payload urgency=high and tags include hypercare_mode=true.
- P2 alert with no active run: PagerDuty payload urgency=low (standard P2) and no hypercare_mode tag.
- P1 alert with ACTIVE run: urgency=high and hypercare_mode=true (P1 stays high regardless).
- P3 alert with ACTIVE run: urgency unchanged (P3 is not elevated during hypercare; only P2 is).
- enrichAlert never throws an exception regardless of the alert payload content.
**Depends on:** 16.5-T03

### 16.5-T26 — Ops dashboard panel: hypercare status widget for Admin portal  _(55 min)_
**Context:** WBS 16.5. The Admin portal (PRD-07) must display a hypercare status widget during the 14-day window. The widget shows: current day (1-14), a traffic-light RAG status (RED if any P1 today, AMBER if any P2 or SLO breach today, GREEN otherwise), batch cycle status icons for morning and afternoon, the two check-in status indicators, and a count of open triage issues. The widget calls GET /internal/hypercare/runs/{id}/status (16.5-T16) and renders the data. It is only visible when a run is ACTIVE.
**Steps:** Add a HypercareStatusWidget React component (or equivalent frontend framework) in the Admin portal frontend.; The widget polls GET /internal/hypercare/runs/active/status every 60 seconds (add a GET /internal/hypercare/runs/active/status convenience endpoint that returns the current ACTIVE run or 204 No Content if none).; Compute RAG: RED if current day has p1IncidentCount > 0; AMBER if p2IncidentCount > 0 or sloMet=false; GREEN otherwise.; Render batch icons: green check if batchFullCycleComplete, amber clock if batchMorningOk=true but afternoonOk pending, red X if morning failed.; Add Cypress (or similar) test: seeded API returns day 3 with p1=1; widget shows RED status.
**Deliverable:** HypercareStatusWidget component; GET /internal/hypercare/runs/active/status endpoint in HypercareController.java; Cypress test verifying RAG logic.
**Acceptance / logic checks:**
- Day with p1IncidentCount=1 renders RED badge.
- Day with p1IncidentCount=0, p2IncidentCount=1 renders AMBER badge.
- Day with p1=0, p2=0, sloMet=true renders GREEN badge.
- Widget is not rendered (or shows a no-op placeholder) when no ACTIVE run exists (204 response).
- Batch icon shows green check when batchMorningOk=true AND batchAfternoonOk=true.
**Depends on:** 16.5-T16

### 16.5-T27 — Add GET /internal/hypercare/runs/active/status convenience endpoint  _(25 min)_
**Context:** WBS 16.5. The Admin portal dashboard widget (16.5-T26) needs a stable endpoint that always returns the current ACTIVE hypercare run without requiring the caller to know the run ID. GET /internal/hypercare/runs/active/status returns 200 with HypercareStatusDto when a run is ACTIVE; returns 204 No Content when no run is ACTIVE. This is a thin wrapper over the existing GET /internal/hypercare/runs/{id}/status endpoint.
**Steps:** Add GET /internal/hypercare/runs/active/status to HypercareController.; Query HypercareRunRepository.findByStatus(ACTIVE); if empty return 204; if present, delegate to existing status-building logic and return 200.; Add MockMvc test: ACTIVE run exists -> 200 with correct body; no active run -> 204 with empty body.; Confirm the endpoint is accessible to ROLE_OPS_ADMIN without a run ID parameter.
**Deliverable:** GET /internal/hypercare/runs/active/status endpoint in HypercareController.java; test in HypercareControllerTest.java.
**Acceptance / logic checks:**
- ACTIVE run present: returns 200 with status=ACTIVE and correct currentDay.
- No ACTIVE run present: returns 204 with no body.
- Response body structure matches HypercareStatusDto (same as the /runs/{id}/status endpoint).
- Unauthenticated request returns 401.
**Depends on:** 16.5-T16

### 16.5-T28 — Integration test: full 14-day hypercare lifecycle end-to-end  _(55 min)_
**Context:** WBS 16.5. A full end-to-end integration test drives the complete hypercare lifecycle: start run, record check-ins and batch results for all 14 days (via API), record one P2 incident on day 3, complete all daily reviews, call close, retrieve the report, and assert the final status. Uses TestContainers PostgreSQL and MockMvc. This validates the interaction between all hypercare service methods and the DB.
**Steps:** Create HypercareLifecycleIntegrationTest.java with @SpringBootTest and TestContainers.; POST /internal/hypercare/runs with goLiveDate=2026-10-10; capture runId.; For each of 14 days: POST checkin MORNING, POST checkin EVENING, POST batch-results MORNING success=true, POST batch-results AFTERNOON success=true, POST daily-reviews sloMet=true notes=All good.; POST incident on day 3 with severity=P2 incidentRef=PD-TEST-001.; POST /internal/hypercare/runs/{id}/close with closedBy=TestOps.; GET /internal/hypercare/runs/{id}/report; assert status=PASSED (zero P1, 14 consecutive batch days, all SLOs met).; GET /internal/hypercare/runs/{id}/report Accept: text/csv; assert CSV has 14 data rows.
**Deliverable:** HypercareLifecycleIntegrationTest.java covering the full happy-path lifecycle including close and report retrieval.
**Acceptance / logic checks:**
- POST runs returns 201; runId is a valid UUID.
- After recording 14 days of data, close returns status=PASSED.
- Report JSON shows incidentSummary.totalP1=0, totalP2=1, batchPerformanceSummary.maxConsecutiveBatchDays=14.
- Report CSV has exactly 14 data rows plus 1 header row (15 lines total).
- GET /internal/hypercare/runs/active/status returns 204 after close (run is no longer ACTIVE).
**Depends on:** 16.5-T23, 16.5-T17, 16.5-T27

### 16.5-T29 — Runbook doc: hypercare procedures section in OPS-13 runbook  _(55 min)_
**Context:** WBS 16.5. OPS-13 sect 8 (Operational Runbook) must include a Hypercare Procedures section so any Ops engineer can execute the 14-day process without prior knowledge. The section must cover: (1) starting the run at go-live via the Admin portal, (2) twice-daily check-in procedure (09:00 and 18:00 KST), (3) daily partner check-in procedure for days 1-5, (4) recording batch results (auto vs manual override), (5) recording daily reviews including SLO assessment, (6) recording incidents and triage issues, (7) evaluating and closing the run on day 14, (8) generating and distributing the hypercare report, (9) what to do if criteria not met (EXTENDED status). Audience: GME Ops team with zero prior hypercare context.
**Steps:** Open docs/runbooks/OPS-13-hypercare.md (create if not exists).; Write section 8.12 Hypercare Procedures with numbered sub-sections matching the 9 topics above.; For each sub-section, include the exact Admin portal navigation path (e.g. Admin Portal > Hypercare > Start Run) and the corresponding API endpoint as a reference.; Include the exit criteria verbatim: zero P1 incidents, SLOs met on all reviewed days, at least one full batch cycle on each of 10 consecutive days.; Include a table showing the hypercare schedule: check-in times (09:00 KST, 18:00 KST), partner call schedule (days 1-5 business days), batch windows (ZP0011 by 01:45 KST, ZP0061 by 05:15 KST, ZP0063 by afternoon window).; Peer-review the doc with one other team member; record reviewer name and date in the doc header.
**Deliverable:** docs/runbooks/OPS-13-hypercare.md with all 9 sub-sections, complete Admin portal navigation paths, API endpoint references, and the exit criteria table.
**Acceptance / logic checks:**
- Document contains exit criteria verbatim: zero P1 incidents, SLOs met on all reviewed days, at least one full batch cycle on each of 10 consecutive days.
- Document lists both check-in times: 09:00 KST and 18:00 KST.
- Document references the Admin portal navigation path for starting a run and closing a run.
- Document explains EXTENDED status: what it means, what actions Ops must take, and how to eventually close as PASSED.
- Document includes a reviewer name and review date in the header.
**Depends on:** 16.5-T17, 16.5-T23

### 16.5-T30 — Unit tests: HypercareService edge cases (null batch fields, re-opening extended run)  _(40 min)_
**Context:** WBS 16.5. Additional unit tests for edge cases not covered by previous test tickets. Edge case 1: evaluateExitCriteria when some days have batchMorningOk=null (batch job not yet run for that day) - null must count as not-successful, not as false. Edge case 2: a run in EXTENDED status can be re-evaluated and set to PASSED via a second closeRun call if criteria are now met (an extended run may be rechecked daily until passed). Edge case 3: recordDailyReview on a day that already has dailyReviewCompleted=true updates notes but does not clear other fields. Edge case 4: incrementIncidentCount on day 14 boundary.
**Steps:** Add test NullBatchFieldsCountsAsFailure: create 14 HypercareDays with days 1-9 full batch and day 10 batchMorningOk=null; assert tenConsecutiveBatchDays=false.; Add test ExtendedRunCanBeClosedAsPassed: set run status=EXTENDED; satisfy all criteria; call closeRun; assert status=PASSED. (Requires changing IllegalStateException guard to allow EXTENDED as well as ACTIVE.); Add test RecordDailyReviewUpdatesExistingReview: call recordDailyReview twice; second call with different notes; assert notes is the second value and p1IncidentCount is unchanged.; Add test IncrementOnDay14: increment P1 on day_number=14; assert p1IncidentCount=1.; Update HypercareService.closeRun to accept ACTIVE or EXTENDED status (not just ACTIVE).
**Deliverable:** Additional test methods in HypercareServiceTest.java; updated closeRun guard in HypercareService.java to accept EXTENDED status.
**Acceptance / logic checks:**
- NullBatchFieldsCountsAsFailure: null batchMorningOk on day 10 breaks the consecutive run at day 9 -> tenConsecutiveBatchDays=false.
- ExtendedRunCanBeClosedAsPassed: EXTENDED run with all criteria met transitions to PASSED.
- RecordDailyReviewUpdatesExistingReview: second call updates notes; p1IncidentCount remains 0.
- IncrementOnDay14: p1IncidentCount=1 after one increment on day 14.
- closeRun on a FAILED run still throws IllegalStateException.
**Depends on:** 16.5-T07, 16.5-T05

### 16.5-T31 — Security: verify RBAC enforcement on all hypercare endpoints  _(45 min)_
**Context:** WBS 16.5. All hypercare Admin API endpoints must be protected by RBAC. From PRD-07 and SEC-09, the Admin portal uses roles: ROLE_OPS_ADMIN (full access), ROLE_FINANCE_READ (read-only reports), ROLE_SUPPORT (read-only transaction view). Hypercare write endpoints (start run, record check-ins, record batch, record review, record incident, close run, report issue) require ROLE_OPS_ADMIN. Hypercare read endpoints (GET status, GET report) require ROLE_OPS_ADMIN or ROLE_FINANCE_READ. ROLE_SUPPORT must receive 403 on all hypercare endpoints.
**Steps:** Create HypercareSecurityTest.java with @WithMockUser tests for each of the 12 hypercare endpoints.; For each write endpoint: assert ROLE_OPS_ADMIN returns the expected 2xx; ROLE_FINANCE_READ returns 403; unauthenticated returns 401.; For GET /status and GET /report: assert ROLE_OPS_ADMIN and ROLE_FINANCE_READ both return 200; ROLE_SUPPORT returns 403.; Verify Spring Security @PreAuthorize annotations on HypercareController match the above matrix.; Run the full security test suite and confirm zero failures.
**Deliverable:** HypercareSecurityTest.java covering all 12 hypercare endpoints with three role scenarios each; @PreAuthorize annotations on all HypercareController methods.
**Acceptance / logic checks:**
- POST /runs with ROLE_FINANCE_READ returns 403.
- GET /runs/{id}/status with ROLE_FINANCE_READ returns 200.
- GET /runs/{id}/report with ROLE_FINANCE_READ returns 200.
- POST /runs/{id}/close with ROLE_OPS_ADMIN returns 200; with ROLE_SUPPORT returns 403.
- All 12 endpoints return 401 for unauthenticated requests.
**Depends on:** 16.5-T23

### 16.5-T32 — Audit log verification: all hypercare write operations produce audit entries  _(45 min)_
**Context:** WBS 16.5. GMEPay+ requires audit logging for all Admin System actions (SEC-09 sect 6, TICKET_BRIEF canonical facts). Each hypercare write operation must produce an immutable audit log entry via AuditLogService with fields: actor (authenticated user), action (named constant), timestamp (UTC), payload (JSON with key fields). The 9 write operations are: HYPERCARE_RUN_STARTED, HYPERCARE_CHECKIN_RECORDED, HYPERCARE_BATCH_RESULT_RECORDED, HYPERCARE_DAILY_REVIEW_RECORDED, HYPERCARE_INCIDENT_RECORDED, HYPERCARE_PARTNER_CHECKIN_RECORDED, HYPERCARE_ISSUE_REPORTED, HYPERCARE_ISSUE_RESOLVED, HYPERCARE_RUN_CLOSED.
**Steps:** Create HypercareAuditIntegrationTest.java with @SpringBootTest and TestContainers.; For each of the 9 write operations, call the endpoint and then query the audit_log table to confirm an entry was written.; Assert each audit entry has non-null actor, correct action string, timestamp within 5 seconds of the call, and the payload JSON contains the key field (e.g. runId for HYPERCARE_RUN_STARTED).; Confirm audit entries are append-only: calling the same operation twice creates two entries.; Confirm no audit entry is created for GET (read-only) operations.
**Deliverable:** HypercareAuditIntegrationTest.java verifying audit log entries for all 9 write operations.
**Acceptance / logic checks:**
- HYPERCARE_RUN_STARTED entry exists in audit_log after POST /runs, payload contains goLiveDate.
- HYPERCARE_INCIDENT_RECORDED entry exists after POST /incidents, payload contains severity and incidentRef.
- HYPERCARE_RUN_CLOSED entry exists after POST /close, payload contains newStatus (PASSED or EXTENDED).
- No audit entry is created by GET /status or GET /report calls.
- Two calls to POST /checkins produce two separate audit entries (append-only, no upsert on audit).
**Depends on:** 16.5-T28


<!-- wbs-v3-gap-closure -->

---

## WBS v3 gap-closure tickets (re-baseline, 2026-06-10)

These tickets convert this service's PARTIAL audit findings into DONE and add work discovered during the build. Statuses live on the `Backlog` sheet of `GMEPay+_Task_Backlog.xlsx`; phase sequencing on the `Completion Plan v3` sheet of `GMEPay+_WBS.xlsx`.

### 18.7-G01 — ADR-001..005: resolve stack contradictions
*Completion phase:* **R0** · *Est:* 120 min · *Role:* Architect

**Context.** User's two source images conflict: Kafka (architecture diagram) vs RabbitMQ (tile board); Spring Cloud Gateway (built) vs Nginx (tile board); MongoDB keep/drop; Rocky Linux base; Elasticsearch role. NEEDS USER DECISION.

**Steps.**
- Draft 5 one-page ADRs with recommendation each
- Present to user; record decision
- File alignment tickets for any code swap decided

**Deliverable.** 5 signed ADRs in docs/adr/

**Acceptance.**
- Each ADR has Decision + Consequences; MASTER_PLAN references them

