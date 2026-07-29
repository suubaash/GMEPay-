package com.gme.pay.reporting.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link FilingArtifactValidator} — the local format check that gates the
 * {@code VALIDATED} state (GAP T5-2). A BOK artifact still containing {@code TODO_OI03} in
 * its mandatory code columns must fail, so it can never be reported as validated.
 */
class FilingArtifactValidatorTest {

    private final FilingArtifactValidator validator = new FilingArtifactValidator();

    @Test
    @DisplayName("a clean, non-empty artifact passes")
    void cleanArtifact_passes(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("KOFIU_20260615.dat"),
                "1014|20260615|GME_KR|42|TXN-1|9001|KRW|1000.00\n");

        FilingArtifactValidator.Outcome outcome = validator.validate(file);

        assertTrue(outcome.valid());
        assertNull(outcome.reason());
    }

    @Test
    @DisplayName("an artifact carrying TODO_OI03 fails — mandatory codes are still gated")
    void placeholderArtifact_fails(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("BOK_FX1015_20260615.csv"),
                "1015|20260615|GME_KR|42|TXN-1|9001|KRW|1000.00|TODO_OI03|TODO_OI03\n");

        FilingArtifactValidator.Outcome outcome = validator.validate(file);

        assertFalse(outcome.valid(), "a file with unresolved mandatory codes is not valid");
        assertNotNull(outcome.reason());
        assertTrue(outcome.reason().contains(FilingArtifactValidator.PLACEHOLDER_TOKEN));
    }

    @Test
    @DisplayName("empty, missing and null artifacts all fail")
    void degenerateArtifacts_fail(@TempDir Path dir) throws Exception {
        assertFalse(validator.validate(null).valid());
        assertFalse(validator.validate(dir.resolve("does-not-exist.dat")).valid());
        assertFalse(validator.validate(Files.createFile(dir.resolve("empty.dat"))).valid());
    }
}
