package com.gme.pay.kybadapter.kyb.registration;

import static org.assertj.core.api.Assertions.assertThat;

import com.gme.pay.kyb.KybSubject;
import com.gme.pay.kybadapter.kyb.BusinessRegistrationVerifier.BizRegResult;
import com.gme.pay.kybadapter.kyb.BusinessRegistrationVerifier.BizRegStatus;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Pure-function tests of the deterministic business-registration verifier. */
class StubBusinessRegistrationVerifierTest {

    private final StubBusinessRegistrationVerifier verifier = new StubBusinessRegistrationVerifier();

    private static KybSubject subject(String taxId) {
        return new KybSubject("P1", "회사", "Co Ltd", "KR", taxId, List.of());
    }

    @Test
    @DisplayName("a present tax id with no trigger word verifies")
    void verified() {
        BizRegResult r = verifier.verify(subject("123-45-67890"));
        assertThat(r.status()).isEqualTo(BizRegStatus.VERIFIED);
        assertThat(r.ref()).startsWith("stub-bizreg-");
        assertThat(r.verifiedAt()).isNotNull();
    }

    @Test
    @DisplayName("blank tax id is SKIPPED with no ref")
    void skipped() {
        BizRegResult r = verifier.verify(subject("  "));
        assertThat(r.status()).isEqualTo(BizRegStatus.SKIPPED);
        assertThat(r.ref()).isNull();
    }

    @Test
    @DisplayName("NOTFOUND trigger token yields NOT_FOUND")
    void notFound() {
        assertThat(verifier.verify(subject("BIZREG_NOTFOUND-1")).status())
                .isEqualTo(BizRegStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("MISMATCH trigger token yields MISMATCH")
    void mismatch() {
        assertThat(verifier.verify(subject("BIZREG_MISMATCH-1")).status())
                .isEqualTo(BizRegStatus.MISMATCH);
    }

    @Test
    @DisplayName("same tax id yields the same ref (deterministic)")
    void deterministicRef() {
        String a = verifier.verify(subject("123-45-67890")).ref();
        String b = verifier.verify(subject("123-45-67890")).ref();
        assertThat(a).isEqualTo(b);
    }
}
