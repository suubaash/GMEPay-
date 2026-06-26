package com.gme.pay.payment.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gme.pay.payment.domain.client.PartnerConfigClient;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The pure per-transaction limit rule: enforce min/max USD bounds, with null caps/limits/amount treated
 * as unconstrained (fail-open). Boundary values (== cap) are allowed; only strictly over/under breaches.
 */
class TransactionLimitPolicyTest {

    private static PartnerConfigClient.TxnLimits limits(String min, String max) {
        return new PartnerConfigClient.TxnLimits(
                min == null ? null : new BigDecimal(min),
                max == null ? null : new BigDecimal(max),
                null, null, null, null);
    }

    private static void enforce(String amount, PartnerConfigClient.TxnLimits limits) {
        TransactionLimitPolicy.enforcePerTransaction("P_TEST",
                amount == null ? null : new BigDecimal(amount), limits);
    }

    @Test
    @DisplayName("within [min,max] passes")
    void withinLimitsPasses() {
        assertDoesNotThrow(() -> enforce("1000", limits("100", "5000")));
    }

    @Test
    @DisplayName("strictly over max is rejected (MAX)")
    void overMaxRejected() {
        TransactionLimitExceededException ex = assertThrows(TransactionLimitExceededException.class,
                () -> enforce("5000.01", limits(null, "5000")));
        assertTrue(ex.getMessage().contains("MAX"));
    }

    @Test
    @DisplayName("exactly at max passes (boundary inclusive)")
    void atMaxPasses() {
        assertDoesNotThrow(() -> enforce("5000", limits(null, "5000")));
    }

    @Test
    @DisplayName("strictly below min is rejected (MIN)")
    void belowMinRejected() {
        TransactionLimitExceededException ex = assertThrows(TransactionLimitExceededException.class,
                () -> enforce("99.99", limits("100", null)));
        assertTrue(ex.getMessage().contains("MIN"));
    }

    @Test
    @DisplayName("exactly at min passes (boundary inclusive)")
    void atMinPasses() {
        assertDoesNotThrow(() -> enforce("100", limits("100", null)));
    }

    @Test
    @DisplayName("statutory 소액해외송금업 ceiling: $5000 passes, $5000.01 rejected")
    void soaekCeiling() {
        assertDoesNotThrow(() -> enforce("5000", limits(null, "5000")));
        assertThrows(TransactionLimitExceededException.class, () -> enforce("5000.01", limits(null, "5000")));
    }

    @Test
    @DisplayName("null limits / null caps / null amount are unconstrained (fail-open)")
    void nullsAreUnconstrained() {
        assertDoesNotThrow(() -> enforce("999999", null));                 // no limits configured
        assertDoesNotThrow(() -> enforce("999999", limits(null, null)));   // both caps null
        assertDoesNotThrow(() -> enforce(null, limits("100", "5000")));    // no amount to check
    }
}
