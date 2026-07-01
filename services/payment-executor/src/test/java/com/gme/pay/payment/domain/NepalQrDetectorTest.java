package com.gme.pay.payment.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link NepalQrDetector}. Verifies each Nepal marker is detected and that
 * ZeroPay domestic QRs never misfire as Nepal.
 */
class NepalQrDetectorTest {

    /** Real-shaped Fonepay QR sample from the bug report. */
    private static final String FONEPAY_QR =
            "00020101021102164271420013285741263500011fonepay.com"
                    + "...5802NP5923kinaun shopping pvt.ltd6013ANAMNAGAR...";

    /** ZeroPay domestic QR — must NOT be classified as Nepal. */
    private static final String ZEROPAY_QR =
            "00020101021102...com.zeropay...5802KR5910COFFEE HUT6304ABCD";

    @Test
    @DisplayName("Fonepay sample QR is detected as Nepal")
    void detectsFonepaySample() {
        assertThat(NepalQrDetector.isNepal(FONEPAY_QR)).isTrue();
    }

    @Test
    @DisplayName("ZeroPay QR is NOT classified as Nepal")
    void doesNotMisfireOnZeroPay() {
        assertThat(NepalQrDetector.isNepal(ZEROPAY_QR)).isFalse();
    }

    @Test
    @DisplayName("Each Nepal marker (fonepay.com, 5802NP, khalti, nepalpay, npqr) matches")
    void detectsEachMarker() {
        assertThat(NepalQrDetector.isNepal("abc fonepay.com xyz")).isTrue();
        assertThat(NepalQrDetector.isNepal("0002...5802NP5910X")).isTrue();
        assertThat(NepalQrDetector.isNepal("pay via khalti wallet")).isTrue();
        assertThat(NepalQrDetector.isNepal("nepalpay merchant")).isTrue();
        assertThat(NepalQrDetector.isNepal("scheme=npqr")).isTrue();
    }

    @Test
    @DisplayName("Marker matching is case-insensitive")
    void caseInsensitive() {
        assertThat(NepalQrDetector.isNepal("FONEPAY.COM")).isTrue();
        assertThat(NepalQrDetector.isNepal("5802np")).isTrue();
        assertThat(NepalQrDetector.isNepal("KHALTI")).isTrue();
    }

    @Test
    @DisplayName("null / blank / non-Nepal payloads return false")
    void nullBlankAndOther() {
        assertThat(NepalQrDetector.isNepal(null)).isFalse();
        assertThat(NepalQrDetector.isNepal("")).isFalse();
        assertThat(NepalQrDetector.isNepal("   ")).isFalse();
        assertThat(NepalQrDetector.isNepal("00020101...5802KR...")).isFalse();
    }
}
