package com.gme.pay.settlement.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plain JUnit 5 unit tests for {@link PartnerTypeSettlementClassifier}.
 * No Spring context, no Docker, no Testcontainers.
 *
 * Spec reference: 7.1-T03 acceptance checks.
 */
class PartnerTypeSettlementClassifierTest {

    private PartnerTypeSettlementClassifier classifier;

    @BeforeEach
    void setUp() {
        classifier = new PartnerTypeSettlementClassifier();
    }

    @Test
    @DisplayName("LOCAL partner -> SettlementType.NET")
    void localPartner_returnsNet() {
        Partner local = new Partner(1L, "GME Remit", PartnerType.LOCAL);
        assertEquals(SettlementType.NET, classifier.classify(local));
    }

    @Test
    @DisplayName("OVERSEAS partner -> SettlementType.GROSS")
    void overseasPartner_returnsGross() {
        Partner overseas = new Partner(2L, "SendMN", PartnerType.OVERSEAS);
        assertEquals(SettlementType.GROSS, classifier.classify(overseas));
    }

    @Test
    @DisplayName("Null partner -> IllegalArgumentException")
    void nullPartner_throws() {
        assertThrows(IllegalArgumentException.class, () -> classifier.classify(null));
    }

    @Test
    @DisplayName("No partner names are hardcoded — classification is driven solely by PartnerType")
    void noHardcodedPartnerNames() {
        // Different partner names, same type — result must be identical
        Partner p1 = new Partner(10L, "AnyLocal", PartnerType.LOCAL);
        Partner p2 = new Partner(11L, "AnotherLocal", PartnerType.LOCAL);
        assertEquals(classifier.classify(p1), classifier.classify(p2));

        Partner p3 = new Partner(20L, "AnyOverseas", PartnerType.OVERSEAS);
        Partner p4 = new Partner(21L, "AnotherOverseas", PartnerType.OVERSEAS);
        assertEquals(classifier.classify(p3), classifier.classify(p4));
    }
}
