package com.gme.pay.bff.compliance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GAP T5-2: a placeholder is not a credential. Pins the exact literals that ship in
 * configuration today — {@code stub-cert-id} (Hometax NTS cert) and {@code TODO_OI03} (BOK
 * transaction/category code) — as NOT configured, and keeps real-looking values passing.
 */
class ConfiguredValuesTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "stub-cert-id",   // the literal shipped in reporting-compliance application.yml
            "STUB-CERT-ID",   // case must not be an escape hatch
            "  stub-cert-id ",
            "stub",
            "TODO_OI03",      // the literal shipped in the BOK code columns
            "todo",
            "TBD",
            "changeme",
            "placeholder",
            "dummy-value",
            "n/a",
            "none",
            "-",
            "xxx",
            "   "})
    @DisplayName("placeholder markers and blanks are NOT configured")
    void placeholdersAreNotConfigured(String value) {
        assertThat(ConfiguredValues.isConfigured(value))
                .as("'%s' must not count as configured", value).isFalse();
        assertThat(ConfiguredValues.isPlaceholder(value)).isTrue();
    }

    @Test
    @DisplayName("null and empty are NOT configured")
    void nullAndEmptyAreNotConfigured() {
        assertThat(ConfiguredValues.isConfigured(null)).isFalse();
        assertThat(ConfiguredValues.isConfigured("")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "HT-9981",              // real-looking Hometax cert id
            "1234567890123456",     // NTS-shaped id
            "101",                  // real BOK txn code
            "KOFIU-GME-001",
            "gme-prod-cert-2026"})  // contains 'cert' but is not a placeholder marker
    @DisplayName("real-looking credentials ARE configured")
    void realValuesAreConfigured(String value) {
        assertThat(ConfiguredValues.isConfigured(value))
                .as("'%s' must count as configured", value).isTrue();
        assertThat(ConfiguredValues.isPlaceholder(value)).isFalse();
    }
}
