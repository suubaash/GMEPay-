package com.gme.pay.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Pins the fix for the {@code MinioVaultClient} version race with <b>real
 * concurrent threads</b> against the {@link FakeS3MinioClient} in-process S3.
 *
 * <h2>What the defect was, precisely</h2>
 *
 * <p>{@code store} derived {@code v<n>} by listing the
 * {@code (partnerCode, docType)} prefix and adding one, then wrote. Two writers
 * that listed before either wrote both computed the same {@code n}. Because the
 * {@code docId} path segment is a per-call UUID the two objects landed on
 * different keys — so no S3 object was overwritten and no bytes were lost, which
 * is worth stating exactly because the original report said one write "silently
 * overwrites the other". What actually collided is the <b>version number</b>:
 * two immutable, undeletable KYB documents both labelled {@code vN}, and two
 * {@code partner_document} rows both claiming to supersede the same predecessor.
 *
 * <h2>What is asserted</h2>
 *
 * <p>Both server behaviours the fleet can meet, because the fix must hold on
 * both: an endpoint that <b>enforces</b> {@code If-None-Match: *} (AWS S3 since
 * Aug 2024, recent MinIO) and one that <b>ignores</b> it (the MinIO release
 * pinned in {@code docker-compose.yml}). In neither case may two writers both
 * come away believing they own a version.
 */
class MinioVaultClientVersionRaceTest {

    private static final String BUCKET = "gmepay-partner-vault";

    private static InputStream stream(String body) {
        return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
    }

    /** Outcome of one racing writer: exactly one of ref/failure is non-null. */
    private record Outcome(VaultObjectRef ref, RuntimeException failure) {
    }

    /**
     * Run {@code n} stores concurrently, all released from the same barrier the
     * fake trips inside the "highest version so far" listing — so every writer
     * has read the same state before any of them writes.
     */
    private static List<Outcome> race(MinioVaultClient vault, FakeS3MinioClient s3, int writers,
                                      String partner, String docType) throws Exception {
        return race(vault, s3, writers, partner, docType, true);
    }

    private static List<Outcome> race(MinioVaultClient vault, FakeS3MinioClient s3, int writers,
                                      String partner, String docType, boolean lockstep)
            throws Exception {
        if (lockstep) {
            s3.parkPlainListingsOn(new CyclicBarrier(writers));
        }
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        try {
            List<Callable<Outcome>> tasks = new ArrayList<>();
            for (int i = 0; i < writers; i++) {
                String body = "DOCUMENT-BYTES-" + i;
                tasks.add(() -> {
                    try {
                        return new Outcome(vault.store(partner, docType, "doc" + body + ".pdf",
                                "application/pdf", stream(body)), null);
                    } catch (RuntimeException e) {
                        return new Outcome(null, e);
                    }
                });
            }
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : pool.invokeAll(tasks, 30, TimeUnit.SECONDS)) {
                outcomes.add(future.get());
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
            s3.parkPlainListingsOn(null);
        }
    }

    private static List<VaultObjectRef> successes(List<Outcome> outcomes) {
        return outcomes.stream().map(Outcome::ref).filter(java.util.Objects::nonNull).toList();
    }

    private static List<RuntimeException> failures(List<Outcome> outcomes) {
        return outcomes.stream().map(Outcome::failure).filter(java.util.Objects::nonNull).toList();
    }

    // ------------------------------------------------------------------ tests

    @Test
    void twoConcurrentUploads_conditionalWritesEnforced_oneWinsOneFailsLoudly() throws Exception {
        FakeS3MinioClient s3 = new FakeS3MinioClient(true);
        MinioVaultClient vault = new MinioVaultClient(s3, BUCKET);

        List<Outcome> outcomes = race(vault, s3, 2, "RACECO", "LICENSE");

        assertThat(successes(outcomes))
                .as("exactly one writer may own v1")
                .hasSize(1);
        assertThat(failures(outcomes))
                .hasSize(1)
                .allSatisfy(e -> assertThat(e)
                        .isInstanceOf(VaultVersionConflictException.class)
                        .hasMessageContaining("v1")
                        .hasMessageContaining("nothing was stored"));

        VaultObjectRef winner = successes(outcomes).get(0);
        assertThat(winner.version()).isEqualTo(1);

        // The loser stored NOTHING: one claim marker, one document object.
        assertThat(s3.versionCount("RACECO/LICENSE/" + MinioVaultClient.VERSION_LEDGER_SEGMENT
                + "/v1")).isEqualTo(1);
        assertThat(s3.keys().stream().filter(k -> k.startsWith("RACECO/LICENSE/")
                && !k.contains(MinioVaultClient.VERSION_LEDGER_SEGMENT)))
                .as("only the winner's document object exists")
                .hasSize(1);

        // Read path: the winner's bytes come back, and the digest matches.
        VaultObject stored = vault.retrieve(winner.uri());
        try (InputStream in = stored.content()) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .startsWith("DOCUMENT-BYTES-");
        }
        assertThat(stored.sha256()).isEqualTo(winner.sha256());

        // And the next upload takes v2, not a second v1.
        VaultObjectRef next = vault.store("RACECO", "LICENSE", "renewed.pdf", "application/pdf",
                stream("renewed"));
        assertThat(next.version()).isEqualTo(2);
        assertThat(next.uri()).endsWith("/v2.pdf");
    }

    @Test
    void twoConcurrentUploads_serverIgnoresPrecondition_bothFailClosed_noDuplicateVersion()
            throws Exception {
        // The MinIO release pinned in docker-compose.yml predates conditional
        // writes: it accepts If-None-Match and does nothing with it. The
        // post-PUT version listing is what has to catch the collision then.
        FakeS3MinioClient s3 = new FakeS3MinioClient(false);
        MinioVaultClient vault = new MinioVaultClient(s3, BUCKET);

        List<Outcome> outcomes = race(vault, s3, 2, "RACECO", "UBO_DECLARATION");

        assertThat(successes(outcomes))
                .as("no writer may silently win a version the server let both claim")
                .isEmpty();
        assertThat(failures(outcomes))
                .hasSize(2)
                .allSatisfy(e -> assertThat(e).isInstanceOf(VaultVersionConflictException.class));
        assertThat(s3.keys())
                .as("neither document was written")
                .noneMatch(k -> k.startsWith("RACECO/UBO_DECLARATION/")
                        && !k.contains(MinioVaultClient.VERSION_LEDGER_SEGMENT));

        // v1 is burned — the ledger marker cannot be deleted (object-lock
        // COMPLIANCE), so the next upload takes v2 and the numbering is SPARSE.
        // Sparse is the intended trade: never reused, therefore never ambiguous.
        VaultObjectRef retry = vault.store("RACECO", "UBO_DECLARATION", "ubo.pdf",
                "application/pdf", stream("ubo bytes"));
        assertThat(retry.version()).isEqualTo(2);

        try (InputStream in = vault.retrieve(retry.uri()).content()) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("ubo bytes");
        }
    }

    @Test
    void eightConcurrentUploads_neverProduceTwoDocumentsAtTheSameVersion() throws Exception {
        FakeS3MinioClient s3 = new FakeS3MinioClient(true);
        MinioVaultClient vault = new MinioVaultClient(s3, BUCKET);

        // Deliberately NOT lockstepped: eight writers left to interleave however
        // the scheduler pleases, so the claims land at whatever depths they land.
        List<Outcome> outcomes = race(vault, s3, 8, "STORMCO", "FINANCIALS", false);

        List<VaultObjectRef> won = successes(outcomes);
        assertThat(won).as("at least one writer must get through").isNotEmpty();
        assertThat(won).extracting(VaultObjectRef::version).doesNotHaveDuplicates();
        assertThat(won).extracting(VaultObjectRef::uri).doesNotHaveDuplicates();
        assertThat(failures(outcomes))
                .allSatisfy(e -> assertThat(e).isInstanceOf(VaultVersionConflictException.class));
        assertThat(won.size() + failures(outcomes).size()).isEqualTo(8);
        // No claim key was ever taken twice, whatever the interleaving was.
        for (VaultObjectRef ref : won) {
            assertThat(s3.versionCount("STORMCO/FINANCIALS/"
                    + MinioVaultClient.VERSION_LEDGER_SEGMENT + "/v" + ref.version()))
                    .isEqualTo(1);
        }

        // Every winner's object is intact and distinct — no silent overwrite.
        for (VaultObjectRef ref : won) {
            try (InputStream in = vault.retrieve(ref.uri()).content()) {
                assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                        .startsWith("DOCUMENT-BYTES-");
            }
        }
    }

    @Test
    void sequentialUploads_stillNumberContiguously_andPriorVersionsStayReadable() {
        FakeS3MinioClient s3 = new FakeS3MinioClient(true);
        MinioVaultClient vault = new MinioVaultClient(s3, BUCKET);

        VaultObjectRef v1 = vault.store("SEQCO", "LICENSE", "lic.pdf", "application/pdf",
                stream("license v1"));
        VaultObjectRef v2 = vault.store("SEQCO", "LICENSE", "lic.pdf", "application/pdf",
                stream("license v2"));
        VaultObjectRef otherType = vault.store("SEQCO", "AOA", "aoa.pdf", "application/pdf",
                stream("aoa"));

        assertThat(v1.version()).isEqualTo(1);
        assertThat(v2.version()).isEqualTo(2);
        assertThat(otherType.version()).as("doc types claim independently").isEqualTo(1);

        // The ledger did not leak into the caller-visible locator.
        assertThat(v2.uri()).doesNotContain(MinioVaultClient.VERSION_LEDGER_SEGMENT);

        // Read path unchanged: superseded versions stay addressable.
        assertThat(vault.retrieve(v1.uri()).sha256()).isEqualTo(v1.sha256());
        assertThat(vault.retrieve(v2.uri()).sha256()).isEqualTo(v2.sha256());
    }

    @Test
    void bucketWrittenByTheOldClient_continuesNumbering_ratherThanRestartingAtOne() {
        FakeS3MinioClient s3 = new FakeS3MinioClient(true);
        // Three documents written by the pre-fix count-then-write client: no
        // version ledger exists under the prefix at all.
        s3.seedLegacyObject("LEGACYCO/LICENSE/11111111-1111-1111-1111-111111111111/v1.pdf", "old1");
        s3.seedLegacyObject("LEGACYCO/LICENSE/22222222-2222-2222-2222-222222222222/v2.pdf", "old2");
        s3.seedLegacyObject("LEGACYCO/LICENSE/33333333-3333-3333-3333-333333333333/v3", "old3");
        MinioVaultClient vault = new MinioVaultClient(s3, BUCKET);

        VaultObjectRef next = vault.store("LEGACYCO", "LICENSE", "lic.pdf", "application/pdf",
                stream("new"));

        assertThat(next.version()).isEqualTo(4);
        assertThat(next.uri()).endsWith("/v4.pdf");
    }

    @Test
    void versionOfKey_readsBothDocumentAndLedgerKeys() {
        String prefix = "P/T/";
        assertThat(MinioVaultClient.versionOfKey(prefix, prefix + "abc-uuid/v7.pdf")).isEqualTo(7);
        assertThat(MinioVaultClient.versionOfKey(prefix, prefix + "abc-uuid/v7")).isEqualTo(7);
        assertThat(MinioVaultClient.versionOfKey(prefix,
                prefix + MinioVaultClient.VERSION_LEDGER_SEGMENT + "/v12")).isEqualTo(12);
        // Anything that is not a v<n> leaf contributes nothing rather than
        // corrupting the maximum.
        assertThat(MinioVaultClient.versionOfKey(prefix, prefix + "abc-uuid/notes.txt")).isZero();
        assertThat(MinioVaultClient.versionOfKey(prefix, prefix + "loose-file")).isZero();
        assertThat(MinioVaultClient.versionOfKey(prefix, prefix + "abc/v")).isZero();
        assertThat(MinioVaultClient.versionOfKey(prefix, prefix + "abc/v12345678901")).isZero();
        assertThat(MinioVaultClient.versionOfKey(prefix, "OTHER/T/abc/v9.pdf")).isZero();
    }

    @Test
    void storeStillValidatesItsInputs() {
        MinioVaultClient vault = new MinioVaultClient(new FakeS3MinioClient(true), BUCKET);
        assertThatThrownBy(() -> vault.store("a/b", "LICENSE", "f.pdf", null, stream("x")))
                .isInstanceOf(VaultException.class);
        assertThatThrownBy(() -> vault.store("GMEREMIT", "LICENSE", " ", null, stream("x")))
                .isInstanceOf(VaultException.class);
        assertThatThrownBy(() -> vault.retrieve("mem://elsewhere/x"))
                .isInstanceOf(VaultException.class);
    }
}
