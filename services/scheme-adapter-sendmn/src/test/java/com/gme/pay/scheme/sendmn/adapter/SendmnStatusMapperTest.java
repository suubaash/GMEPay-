package com.gme.pay.scheme.sendmn.adapter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link SendmnStatusMapper} contract: Decrypted/Processing → PENDING, Approved →
 * APPROVED, and — because SendMN documents NO failed/declined terminal status (open
 * issue O4) — everything else maps to UNKNOWN, never to a failure (ADR-016).
 */
class SendmnStatusMapperTest {

    @Test
    @DisplayName("Decrypted and Processing map to PENDING")
    void pendingStatuses() {
        assertEquals("PENDING", SendmnStatusMapper.toCanonical("Decrypted"));
        assertEquals("PENDING", SendmnStatusMapper.toCanonical("Processing"));
    }

    @Test
    @DisplayName("Approved maps to APPROVED")
    void approved() {
        assertEquals("APPROVED", SendmnStatusMapper.toCanonical("Approved"));
    }

    @Test
    @DisplayName("mapping is case-insensitive and trims whitespace")
    void caseInsensitive() {
        assertEquals("APPROVED", SendmnStatusMapper.toCanonical("APPROVED"));
        assertEquals("APPROVED", SendmnStatusMapper.toCanonical(" approved "));
        assertEquals("PENDING", SendmnStatusMapper.toCanonical("PROCESSING"));
        assertEquals("PENDING", SendmnStatusMapper.toCanonical("decrypted"));
    }

    @Test
    @DisplayName("unknown, undocumented and null statuses map to UNKNOWN — never auto-fail")
    void unknownNeverFails() {
        assertEquals("UNKNOWN", SendmnStatusMapper.toCanonical(null));
        assertEquals("UNKNOWN", SendmnStatusMapper.toCanonical(""));
        assertEquals("UNKNOWN", SendmnStatusMapper.toCanonical("Declined"));
        assertEquals("UNKNOWN", SendmnStatusMapper.toCanonical("Failed"));
        assertEquals("UNKNOWN", SendmnStatusMapper.toCanonical("Cancelled"));
        assertEquals("UNKNOWN", SendmnStatusMapper.toCanonical("some-future-status"));
    }
}
