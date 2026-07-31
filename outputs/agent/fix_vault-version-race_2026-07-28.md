> 작업: MinioVaultClient version race / 출처: agent

# The document vault stopped guessing which version it was writing

`libs/lib-vault` only. Nothing outside that module was touched; the caller changes this implies are
listed in §6 rather than made, because `services/config-registry` is being written by another agent.

---

## 1. What the defect actually was — the reported description is not quite right

The report I was handed says two concurrent uploads "both compute the same `vN` and both write it.
One silently overwrites the other." The first half is exactly true. **The second half is not, and the
difference matters for choosing the fix.**

The old sequence in `MinioVaultClient.store`:

```java
String prefix = partnerCode + "/" + docType + "/";
int version = countObjects(prefix) + 1;                     // LIST, then +1
String key = prefix + UUID.randomUUID() + "/v" + version    // <-- fresh UUID per call
          + extensionOf(filename);
minio.putObject(... key ...);
```

`countObjects` lists the prefix recursively and counts. Two writers that both LIST before either PUTs
both get the same count and both compute the same `version`. But the key they write to embeds a
**per-call `UUID.randomUUID()`** as the `docId` segment, so the two keys differ:

```
GMEREMIT/LICENSE/2f1c…-a/v2.pdf     <- writer A
GMEREMIT/LICENSE/9b40…-e/v2.pdf     <- writer B
```

**No S3 object is overwritten and no bytes are lost.** What collides is the *version label*. And that
is the worse failure for this bucket, not the milder one:

- The bucket is object-lock **COMPLIANCE**, 10-year retention. Two documents labelled `v2` cannot be
  deleted and redone — the ambiguity is permanent.
- `version` is exactly the field a reviewer or an examiner uses to tell the superseded KYB document
  (business registration, licence, UBO evidence) from the current one. A missing object is an obvious
  error; two different `v2`s is not obviously anything.
- The caller then writes two `partner_document` rows that each claim to supersede the same
  predecessor. Which of the two is "the licence we activated this partner on" is unanswerable
  afterwards.

I also confirmed the second-order behaviour of the old code: `countObjects` counted **objects**, not
versions, and worked only because each `store` wrote exactly one object. Any stray object under the
prefix would have shifted every subsequent version number.

There is no "latest version" resolution inside the vault to break — the port is only `store` +
`retrieve`, `retrieve` addresses an exact key, and config-registry resolves *current* from
`superseded_at IS NULL`, never from `MAX(version)`. See §5.

---

## 2. What the bucket actually supports — checked before choosing

| Fact | Where | Consequence |
|---|---|---|
| bucket created `objectLock(true)` → **versioning implied** | `VaultBucketInitializer.ensureBucket` | object-version listing is available as a CAS *detector* |
| retention **COMPLIANCE**, 10 years, not GOVERNANCE | same | nothing written can be unwritten. A wrong write is forever |
| endpoint = MinIO `RELEASE.2024-10-13T13-34-11Z` | `docker-compose.yml:333` | **predates MinIO's conditional-write support**; it accepts `If-None-Match` and does nothing with it |
| endpoint = real AWS S3 `s3.ap-northeast-2` | `values-aws.yaml:43` | conditional writes **are** enforced (AWS, Aug 2024+) |
| endpoint = an S3 *gateway* | `values-azure.yaml:43` | support unknown, must not be assumed |
| `version INT NOT NULL DEFAULT 1`, no UNIQUE, no contiguity constraint | `V010__partner_document.sql:70` | sparse version numbers are legal in the caller's schema |

The fleet therefore spans endpoints that enforce the precondition and endpoints that ignore it. **A
fix that only sends `If-None-Match` would be a no-op on the deployed dev/on-prem MinIO — and worse
than a no-op, because it would read as safe.**

---

## 3. The fix: claim the version, then prove the claim

Version numbers are now reserved before the document is written, in an append-only ledger inside the
same prefix:

```
<partnerCode>/<docType>/_versions/v<n>      <- zero-cost marker, body = claiming writer's token
<partnerCode>/<docType>/<docId>/v<n>[.ext]  <- the document, unchanged layout (ADR-006)
```

`_versions` sits where a `docId` would and can never collide with one (`docId` is always a UUID).

`store` now does:

1. **`highestVersion(prefix)`** — one LIST, parsing the `v<n>` leaf of **both** ledger keys and
   document keys. Reading documents too is what keeps a bucket written by the old client numbering
   forwards instead of restarting at 1; reading the ledger too is what makes burned numbers stick.
2. **Conditional claim** — PUT `_versions/v<n+1>` with `If-None-Match: *`.
   - `412 PreconditionFailed` → `VaultVersionConflictException`, nothing stored.
   - `NotImplemented` / `InvalidArgument` (a server that rejects the header itself) → retry the PUT
     unconditionally and lean entirely on step 3.
3. **Verify sole ownership** — LIST every object version of that exact claim key
   (`includeVersions(true)`).
   - `> 1 version` → two writers claimed it, the server did not enforce anything → **both fail**.
   - `1 version, not ours` → **fail**.
   - `1 version, ours` → the version is owned; write the document.
4. Write the document exactly as before (streamed SHA-256, user metadata, sha256 tag).

The two mechanisms cover the two worlds: the header makes it single-winner and cheap where it is
supported; the listing makes it *safe* where it is not.

**Failing both racers is deliberate, not a shortcut.** It is the only outcome on a
non-enforcing endpoint that never lets a colliding write win silently, and it costs one number in a
ledger. §4 explains why the alternative was rejected.

### Options rejected

| Option | Why not |
|---|---|
| **Rely on native S3 object versioning** (drop `v<n>`, carry the provider's `versionId`) | Cheapest in principle and genuinely correct, but `VaultObjectRef.version` is an `int` that lands in `partner_document.version INT`, and the URI layout `…/v<n>` is ADR-006 and is what the document viewer's history walks. Opaque provider version ids mean a contract change + DDL change in `services/config-registry` — the module I was told not to touch. Recorded as a follow-up worth considering on its own merits, not as this fix. |
| **`If-None-Match` conditional PUT alone** | Silently does nothing on the MinIO release the fleet actually runs. A guard that is inert on the main deploy target is the failure mode this branch keeps removing. Kept as *half* the fix. |
| **Serialise through a store that can express uniqueness** (a `partner_document` unique index, or a DB sequence) | The right long-term source of truth, and it also fixes the DB half of the race (§6.1) — but it is a migration plus a service change in `config-registry`. Out of bounds, and it would leave the vault trusting a caller to be correct. |
| **Make the document key itself deterministic and conditionally PUT it** | The key ends in the uploaded file's extension, so two racers uploading `a.pdf` and `b.png` at `v2` write different keys and the precondition never fires. Only an extension-free key can be the CAS token — which is what the ledger marker is. |
| **Retry with the next free number** | Explicitly rejected. Renumbering is *arguably* correct for the bytes (two genuine documents deserve two versions) but it is not correct for the caller: the two `partner_document` transactions would still both supersede the same row, and now with no error anywhere to show it happened. Fail-closed matches the rest of this branch, and a caller that wants a retry can do it with its eyes open. |

---

## 4. Consequences a reader needs to know

- **Version numbers are strictly increasing but may be sparse.** A claim whose document PUT then
  fails, or a lost race, burns its number permanently — object-lock forbids reclaiming it. Documented
  on `VaultClient`, asserted in the tests. Nothing reads versions as contiguous.
- **The ledger is retained for 10 years like everything else in the bucket.** That is a feature: it
  is an audit trail of which writer claimed which version of which partner's document type, and it
  costs one ~36-byte object per version.
- **One extra LIST and one extra small PUT per upload.** KYB uploads are a handful per partner
  onboarding; correctness of compliance evidence is worth two round trips.
- **New exception type**, `VaultVersionConflictException extends VaultException`, so existing callers
  compile unchanged and keep failing closed.

---

## 5. The read path

Checked, and unchanged on purpose:

- `retrieve(uri)` addresses the **exact object key** from the ref the caller persisted. Sparse or
  skipped versions are invisible to it.
- The vault has no listing/latest API to break — `VaultClient` is `store` + `retrieve` and a test
  asserts it stays that way.
- "Latest" is resolved by the caller in SQL (`findCurrentByPartnerIdAndDocType`, i.e.
  `superseded_at IS NULL`), **not** by `MAX(version)`. Grep-verified across `config-registry`,
  `ops-partner-bff` and `lib-api-contracts`: nothing computes a next version, and nothing assumes
  `version` is dense.
- The ledger prefix never appears in a caller-visible URI (asserted).

---

## 6. Not done — precise follow-ups, all outside `libs/lib-vault`

1. **`PartnerDocumentService.upload` maps every `VaultException` to `502 BAD_GATEWAY`**
   (`services/config-registry/.../document/PartnerDocumentService.java:149-152`). A
   `VaultVersionConflictException` is a `409 CONFLICT` — "someone else is uploading this document type
   right now, retry" — and it is retryable, which 502 does not communicate. One `catch` clause above
   the existing one.
2. **The DB half of the same race is untouched.** Two concurrent `upload` calls each read the current
   `partner_document` row, each set `superseded_at`, each insert a fresh row → two current rows for
   one `(partner, docType)`. The vault now prevents the *version* collision, which in practice makes
   this much harder to hit, but the invariant is not enforced: it wants a partial unique index on
   `(partner_id, doc_type) WHERE superseded_at IS NULL`, or `SELECT … FOR UPDATE` on the partner row.
   That is a migration in `config-registry`.
3. **Native-versioning option** (§3 table, row 1) remains open as the simpler end state if the
   `partner_document.version` contract is ever revisited.
4. `MinioVaultClientIT`'s existing `reStore_mintsNextVersion` test writes to `VERSIONCO/LICENSE`;
   the new race test uses `RACECO/CBDDQ` so the two do not interact.

---

## 7. Verification

| Check | Result |
|---|---|
| `gradlew :libs:lib-vault:test` | **14/14 pass** |
| `gradlew testClasses` (repo-wide) | **BUILD SUCCESSFUL** — including `services/config-registry`, which compiles against the changed port with no caller edit |
| `check_internal_auth_wiring.py` | 121/121 |
| `check_monitoring_wiring.py` | 37/37 |
| `check_helm_chart_wiring.py` | 299/299 |
| `check_gitleaks_config.py` | findings=0, missed=0, false-positives=0 |
| `check_load_harness_wiring.py` | OK |
| `node docker/keycloak/check-topology.mjs` | 101/101 |

### How the race is tested without Docker

**Docker/MinIO was not started, and a real S3 is genuinely needed to answer one question this test
cannot: whether the endpoint enforces `If-None-Match`.** So the fix was built not to depend on the
answer, and the test asserts *both* answers.

`FakeS3MinioClient` is a real `MinioClient` subclass (via its protected copy constructor) serving the
S3 verbs from a concurrent map. It is faithful in the three respects the fix depends on: it keeps
**object versions** and returns them from `listObjects(includeVersions)`; it models the conditional
PUT **both ways** behind a flag (atomic `putIfAbsent` + `412` when enforcing, plain append when not);
and it drains the real byte stream so the production `DigestInputStream` SHA-256 is computed for real
and GET/STAT serve what was written. A `CyclicBarrier` tripped inside the plain listing parks every
writer at the "what is the highest version?" read and releases them together, so the race is
deterministic rather than hopeful.

`MinioVaultClientVersionRaceTest`, with real threads:

- 2 writers, **enforcing** endpoint → exactly **one** success at `v1`, exactly **one**
  `VaultVersionConflictException`; the loser wrote no document object; the winner's bytes and digest
  round-trip; the next upload takes `v2`.
- 2 writers, **ignoring** endpoint → **zero** successes, two `VaultVersionConflictException`s, no
  document object written, and `v1` burned so the retry legitimately gets `v2` and reads back.
- 8 writers, free-running → winners' versions and URIs all distinct, every failure is a version
  conflict, every winner's object intact, and no ledger key ever holds two versions.
- Legacy bucket (three documents, no ledger) → next store is `v4`, not `v1`.
- Sequential stores still number `v1, v2` per doc type, prior versions stay readable, ledger never
  leaks into the URI.
- `versionOfKey` parses document *and* ledger leaves and ignores everything else.

Two bugs surfaced **in the test double** while doing this and are worth recording, because a weaker
double would have passed a broken fix: its first conditional-PUT implementation was
check-then-put — a read-modify-write, the very thing under test — which made the enforcing case
behave like the ignoring one; and its `Last-Modified` header used `Z` rather than a named zone, which
the MinIO response parser rejects.

`MinioVaultClientIT` (docker-tagged, CI-only) also gained
`concurrentStores_neverMintTheSameVersionTwice`: six real threads against real MinIO, asserting no two
winners share a version and every loser gets `VaultVersionConflictException`. **That test has not been
executed here** — it runs on a CI runner with a Docker engine, and it is the one that will tell us
which half of the fix the pinned MinIO image actually exercises.

---

## 8. Files

```
libs/lib-vault/src/main/java/com/gme/pay/vault/MinioVaultClient.java              (claim + verify)
libs/lib-vault/src/main/java/com/gme/pay/vault/VaultVersionConflictException.java (new)
libs/lib-vault/src/main/java/com/gme/pay/vault/VaultClient.java                   (port contract)
libs/lib-vault/src/main/java/com/gme/pay/vault/InMemoryVaultClient.java           (divergence stated)
libs/lib-vault/src/main/java/com/gme/pay/vault/InMemoryVaultAutoConfiguration.java (WARN)
libs/lib-vault/src/test/java/com/gme/pay/vault/FakeS3MinioClient.java             (new)
libs/lib-vault/src/test/java/com/gme/pay/vault/MinioVaultClientVersionRaceTest.java (new)
libs/lib-vault/src/test/java/com/gme/pay/vault/MinioVaultClientIT.java            (real-MinIO race)
```

`InMemoryVaultClient` keeps its per-JVM counter — it is `synchronized`, so it cannot race inside one
JVM, and across JVMs it has nothing to compare-and-set against. The two clients therefore **no longer
share a concurrency contract**, and both the class javadoc and the startup WARN now say so outright:
the fallback "can NEVER raise the port's `VaultVersionConflictException`". That divergence is only
acceptable because `GMEPAY_VAULT_ENDPOINT` is set in `docker-compose.yml` and in all four Helm values
files, which the N>1 sweep verified and I re-verified.
