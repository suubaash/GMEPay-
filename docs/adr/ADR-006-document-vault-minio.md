# ADR-006 — Partner document vault: MinIO with object-lock + versioning

**Status:** Accepted (user decision, 2026-06-11)
**Slice:** Partner Setup Slice 3 (KYB) + later regulatory artefacts

## Context
Partner onboarding requires durable storage of: KYB documents (certificate of incorporation, AoA, board resolution, license scans, UBO declarations, signatory cards), regulator-evidence artefacts (sanctions screening decisions, KFTC 계좌실명조회 verifications), signed contracts, and Wolfsberg CBDDQ packs. KoFIU/FSS retention is 5 years minimum; bank-side examiners expect 10. Storage must be tamper-evident across that horizon — silent overwrite of an expired license is the failure mode regulators discover during examination.

## Decision
**MinIO** (already in the stack per WS17 Real-Stack Closure) hosting a `gmepay-partner-vault` bucket with **object lock in compliance mode** (10-year retention; not governance — even bucket admins cannot delete during retention), **versioning enabled**, server-side encryption with keys retrieved from **HashiCorp Vault** (lands in R3). Path layout: `s3://gmepay-partner-vault/<partner_code>/<doc_type>/<doc_id>/v<n>.<ext>`.

## Consequences
- No new infrastructure component (MinIO is in ADR-stack already).
- Cross-border PII transfers: `<partner_code>` paths must be encrypted at rest; Vault holds keys per-partner so a partner offboarding lets us crypto-shred their vault entries (PIPA Art. 21 disposal obligation) while satisfying the 10-year retention requirement — the row keeps existing but is unreadable.
- Document upload flow is via the BFF, never direct-to-MinIO from the browser (preserves audit + virus scan hook).
- 10-year object-lock means an accidental delete is impossible — operational discipline change.

## Addendum (2026-06-30) — cloud-agnostic storage seam
The vault wrapper (`lib-vault`) talks the **S3 API over HTTP via the `io.minio` client — not a cloud-provider SDK** — so the same code runs against self-hosted MinIO (on-prem, the default), AWS S3, or an Azure-fronting S3 gateway. Everything is env-injected; nothing is baked:
- `GMEPAY_VAULT_ENDPOINT` — S3 API URL (also the master switch; unset ⇒ in-memory dev vault).
- `GMEPAY_VAULT_REGION` (default `us-east-1`) — Sig-V4 signing region; MinIO ignores it, AWS/Azure-gateway buckets set their real region.
- `GMEPAY_VAULT_PATH_STYLE` (default `true`) — `true` = path-style (MinIO/on-prem), `false` = virtual-host (AWS S3). The `io.minio` client auto-selects the wire style from the endpoint host (AWS hosts → virtual-host, all others → path-style); the flag is the declared contract and is asserted against that detection at boot.
- `GMEPAY_VAULT_ACCESS_KEY` / `GMEPAY_VAULT_SECRET_KEY`.

A build-time portability guard (`./gradlew check` → `portabilityGuard`) fails if any AWS/Azure/GCP SDK ever lands on a runtime classpath, so the open-protocol seam cannot silently regress.
