package com.gme.pay.payment.sandbox;

import com.gme.pay.payment.domain.QrSchemeClassifier;
import com.gme.pay.payment.persistence.SandboxE2eRunEntity;
import com.gme.pay.payment.persistence.SandboxE2eRunRepository;
import com.gme.pay.payment.sandbox.dto.E2eRunDetail;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link E2eRunner} using a mocked {@link SelfPayClient} (no real HTTP) and an
 * in-memory-ish mocked repository that assigns ids and echoes the saved entity back.
 *
 * <p>Covers:
 * <ol>
 *   <li>classify + pay-approved sequence → status PASS with 4 PASS steps.
 *   <li>pay-declined → status FAIL, failedStep "Pay", Verify step SKIP.
 *   <li>DYNAMIC CRC round-trip: the built tag-63 is accepted by the real {@link QrSchemeClassifier}.
 * </ol>
 */
class E2eRunnerTest {

    /** A repository stub that assigns incrementing ids on first save and returns the same entity. */
    private static SandboxE2eRunRepository repoStub() {
        SandboxE2eRunRepository repo = mock(SandboxE2eRunRepository.class);
        AtomicLong seq = new AtomicLong(0);
        when(repo.save(any(SandboxE2eRunEntity.class))).thenAnswer((Answer<SandboxE2eRunEntity>) inv -> {
            SandboxE2eRunEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                long id = seq.incrementAndGet();
                // set the generated id via reflection (JPA would normally assign it)
                var f = SandboxE2eRunEntity.class.getDeclaredField("id");
                f.setAccessible(true);
                f.set(e, id);
            }
            return e;
        });
        return repo;
    }

    @Test
    @DisplayName("classify + pay APPROVED → status PASS, 4 PASS steps, schemeTxnRef verified")
    void approvedSequence_allPass() {
        SelfPayClient client = mock(SelfPayClient.class);
        when(client.classify(anyString())).thenReturn(new SelfPayClient.Result<>(
                200,
                new SelfPayClient.ClassifyResponse(true, "com.zeropay", "KR", "KRW", "MPM", "ZEROPAY"),
                "{\"supported\":true}"));
        when(client.pay(any())).thenReturn(new SelfPayClient.Result<>(
                201,
                new SelfPayClient.PayResponse("APPROVED", "ZP-TXN-123", "GMEREMIT-1", null),
                "{\"status\":\"APPROVED\"}"));

        E2eRunner runner = new E2eRunner(client, repoStub());
        E2eRunDetail detail = runner.run("KR", "GMEREMIT", "5000", "STATIC");

        assertThat(detail.status()).isEqualTo("PASS");
        assertThat(detail.failedStep()).isNull();
        assertThat(detail.stepCount()).isEqualTo(4);
        assertThat(detail.currency()).isEqualTo("KRW");
        assertThat(detail.steps()).extracting(E2eRunDetail.Step::status)
                .containsExactly("PASS", "PASS", "PASS", "PASS");
        assertThat(detail.steps().get(3).detail()).contains("ZP-TXN-123");
    }

    @Test
    @DisplayName("pay DECLINED → status FAIL, failedStep=Pay, Verify step SKIP")
    void payDeclined_failStopsAtPay() {
        SelfPayClient client = mock(SelfPayClient.class);
        when(client.classify(anyString())).thenReturn(new SelfPayClient.Result<>(
                200,
                new SelfPayClient.ClassifyResponse(true, "fonepay.com", "NP", "NPR", "MPM", "NEPAL"),
                "{\"supported\":true}"));
        when(client.pay(any())).thenReturn(new SelfPayClient.Result<>(
                422,
                new SelfPayClient.PayResponse("DECLINED", null, null, "receiver_not_found"),
                "{\"status\":\"DECLINED\",\"declineReason\":\"receiver_not_found\"}"));

        E2eRunner runner = new E2eRunner(client, repoStub());
        E2eRunDetail detail = runner.run("NP", "GMEREMIT", "1300", "STATIC");

        assertThat(detail.status()).isEqualTo("FAIL");
        assertThat(detail.failedStep()).isEqualTo("Pay");
        assertThat(detail.stepCount()).isEqualTo(4);
        assertThat(detail.steps()).extracting(E2eRunDetail.Step::status)
                .containsExactly("PASS", "PASS", "FAIL", "SKIP");
        assertThat(detail.steps().get(2).detail()).contains("receiver_not_found");
        // Verify step is SKIP, not evaluated.
        assertThat(detail.steps().get(3).name()).isEqualTo("Verify receipt");
        assertThat(detail.steps().get(3).status()).isEqualTo("SKIP");
    }

    @Test
    @DisplayName("classify currency mismatch → Classify FAIL, Pay + Verify SKIP")
    void classifyCurrencyMismatch_failStopsAtClassify() {
        SelfPayClient client = mock(SelfPayClient.class);
        when(client.classify(anyString())).thenReturn(new SelfPayClient.Result<>(
                200,
                // GMEPay+ resolved KRW but we asked for NP (expected NPR) → mismatch.
                new SelfPayClient.ClassifyResponse(true, "com.zeropay", "KR", "KRW", "MPM", "ZEROPAY"),
                "{\"currency\":\"KRW\"}"));

        E2eRunner runner = new E2eRunner(client, repoStub());
        E2eRunDetail detail = runner.run("NP", "GMEREMIT", "1300", "STATIC");

        assertThat(detail.status()).isEqualTo("FAIL");
        assertThat(detail.failedStep()).isEqualTo("Classify");
        assertThat(detail.steps()).extracting(E2eRunDetail.Step::status)
                .containsExactly("PASS", "FAIL", "SKIP", "SKIP");
    }

    @Test
    @DisplayName("DYNAMIC CRC: built tag-63 is accepted by the real classifier (round-trip)")
    void dynamicCrc_roundTripsThroughClassifier() {
        // Build a DYNAMIC KR QR with an embedded amount, then classify it with the REAL classifier.
        String dynamic = SandboxQrCatalog.resolve("KR", "DYNAMIC", "5000");

        // It must carry the amount field (tag 54 = len 04 + "5000") and the ZeroPay network id.
        assertThat(dynamic).contains("54045000");
        assertThat(dynamic).contains("com.zeropay");

        QrSchemeClassifier.Classification c = QrSchemeClassifier.classify(dynamic);
        assertThat(c.isKnown()).isTrue();
        assertThat(c.networkIdentifier()).isEqualTo("com.zeropay");
        assertThat(c.country()).isEqualTo("KR");

        // The recomputed CRC must match a fresh computation over everything up to "6304".
        int idx = dynamic.lastIndexOf("6304");
        String beforeCrc = dynamic.substring(0, idx + 4);
        String crc = dynamic.substring(idx + 4);
        assertThat(crc).hasSize(4);
        assertThat(Crc16Ccitt.compute(beforeCrc)).isEqualTo(crc);
    }
}
