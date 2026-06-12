# sftp-gateway — service backlog bundle

NEW service (architecture diagram): brokers all KFTC SFTP file exchange so scheme adapters never touch raw SFTP. Module `services/sftp-gateway` (settings.gradle change is coordinator-only). Bridges SFTP <-> MinIO with PGP + checksum ledger.


<!-- wbs-v3-gap-closure -->

---

## WBS v3 gap-closure tickets (re-baseline, 2026-06-10)

These tickets convert this service's PARTIAL audit findings into DONE and add work discovered during the build. Statuses live on the `Backlog` sheet of `GMEPay+_Task_Backlog.xlsx`; phase sequencing on the `Completion Plan v3` sheet of `GMEPay+_WBS.xlsx`.

### 18.3-G01 — Scaffold sftp-gateway service
*Completion phase:* **R2** · *Est:* 140 min · *Role:* Backend · *Deps:* 17.1-G03

**Context.** New Spring Boot module brokering KFTC SFTP exchange so scheme-adapter never touches raw SFTP (per architecture diagram).

**Steps.**
- services:sftp-gateway module (NOTE: settings.gradle change is coordinator-only)
- Apache Mina SSHD client; host-key pinning
- Health endpoint + connection check

**Deliverable.** Module green in build

**Acceptance.**
- Connects to dev SFTP container in compose; health UP

### 18.3-G02 — Inbound/outbound transfer jobs + MinIO bridge
*Completion phase:* **R2** · *Est:* 180 min · *Role:* Backend · *Deps:* 18.3-G01,17.8-G01

**Context.** Poll remote KFTC dirs → MinIO inbound bucket; push outbound files from MinIO → remote; checksum + dedupe ledger.

**Steps.**
- Scheduled poll with window config
- SHA-256 manifest per transfer; transfer ledger table
- Retry with backoff; alert on miss window

**Deliverable.** File bridge SFTP↔MinIO

**Acceptance.**
- Round-trip file lands with matching checksum; duplicate skip logged

### 18.3-G03 — PGP encrypt/decrypt + key management
*Completion phase:* **R2** · *Est:* 160 min · *Role:* Backend · *Deps:* 18.3-G02

**Context.** KFTC batch files are PGP-protected. Encrypt outbound, decrypt inbound; keys via env now, Vault later (R3).

**Steps.**
- BouncyCastle PGP streams
- Key fingerprints pinned in config
- Bad-signature file → quarantine bucket

**Deliverable.** PGP layer on transfers

**Acceptance.**
- Tampered file quarantined + alerted; clean file decrypts

