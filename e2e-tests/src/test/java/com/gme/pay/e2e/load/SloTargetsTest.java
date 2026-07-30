package com.gme.pay.e2e.load;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the one property that makes this harness honest: <b>an undeclared SLO never passes.</b>
 *
 * <p>Also pins the shipped placeholder file itself, so nobody can "helpfully" fill in plausible
 * defaults — the file must stay empty of numbers until a named owner declares them, and the register
 * entry for T3-5 says so.
 */
@DisplayName("SloTargets: measures, never invents")
class SloTargetsTest {

    private static SloTargets of(String... keyValues) {
        Properties props = new Properties();
        for (int i = 0; i < keyValues.length; i += 2) {
            props.setProperty(keyValues[i], keyValues[i + 1]);
        }
        return SloTargets.from(Paths.get("test.properties"), true, props);
    }

    @Test
    @DisplayName("an empty file yields NO_TARGETS_DECLARED — not PASS")
    void emptyFileIsNotAPass() {
        SloTargets targets = of();
        assertFalse(targets.anyDeclared());
        SloTargets.Evaluation eval = targets.evaluate(1.0, 0.0, 42.0, 10, 20, 30);
        assertEquals(SloTargets.Verdict.NO_TARGETS_DECLARED, eval.verdict());
        assertTrue(eval.checks().isEmpty(), "nothing to check");
        assertEquals(SloTargets.NUMERIC_KEYS, eval.undeclared());
    }

    @Test
    @DisplayName("a missing file yields NO_TARGETS_DECLARED, not an exception")
    void missingFileIsNotAnError() {
        SloTargets targets = SloTargets.load(Paths.get("does", "not", "exist.properties"));
        assertFalse(targets.fileExists());
        assertEquals(SloTargets.Verdict.NO_TARGETS_DECLARED,
                targets.evaluate(1.0, 0.0, 1.0, 1, 1, 1).verdict());
        assertTrue(targets.describe().contains("NO TARGETS DECLARED"));
    }

    @Test
    @DisplayName("a key present but BLANK counts as undeclared (a half-finished edit fails nothing)")
    void blankValueIsUndeclared() {
        SloTargets targets = of(SloTargets.KEY_P99_MAX_MS, "   ");
        assertFalse(targets.anyDeclared());
        assertEquals(SloTargets.Verdict.NO_TARGETS_DECLARED,
                targets.evaluate(1.0, 0.0, 1.0, 1, 1, 5_000).verdict());
    }

    @Test
    @DisplayName("a non-numeric value is a loud failure, not a silent zero")
    void nonNumericValueThrows() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> of(SloTargets.KEY_P99_MAX_MS, "fast"));
        assertTrue(e.getMessage().contains(SloTargets.KEY_P99_MAX_MS));
    }

    @Test
    @DisplayName("declared and met → PASS")
    void declaredAndMet() {
        SloTargets targets = of(
                SloTargets.KEY_P99_MAX_MS, "500",
                SloTargets.KEY_MAX_ERROR_RATE, "0.01",
                SloTargets.KEY_MIN_SUCCESS_RATE, "0.95",
                SloTargets.KEY_MIN_THROUGHPUT_TPS, "10");
        SloTargets.Evaluation eval = targets.evaluate(0.99, 0.002, 25.0, 40, 120, 420);
        assertEquals(SloTargets.Verdict.PASS, eval.verdict());
        assertEquals(4, eval.checks().size());
        assertTrue(eval.checks().stream().allMatch(c -> Boolean.TRUE.equals(c.met())));
    }

    @Test
    @DisplayName("declared and missed → FAIL, naming the missed target")
    void declaredAndMissed() {
        SloTargets targets = of(SloTargets.KEY_P99_MAX_MS, "500");
        SloTargets.Evaluation eval = targets.evaluate(0.99, 0.0, 25.0, 40, 120, 900);
        assertEquals(SloTargets.Verdict.FAIL, eval.verdict());
        assertEquals(SloTargets.KEY_P99_MAX_MS, eval.checks().get(0).key());
        assertEquals(Boolean.FALSE, eval.checks().get(0).met());
    }

    @Test
    @DisplayName("a declared target the run could not measure is UNDECIDABLE and FAILS — never a quiet pass")
    void unmeasuredDeclaredTargetFails() {
        SloTargets targets = of(SloTargets.KEY_P99_MAX_MS, "500");
        SloTargets.Evaluation eval = targets.evaluate(1.0, 0.0, 1.0, Double.NaN, Double.NaN, Double.NaN);
        assertEquals(SloTargets.Verdict.FAIL, eval.verdict());
        assertNull(eval.checks().get(0).met());
        assertNull(eval.checks().get(0).actual());
    }

    @Test
    @DisplayName("a partially-filled file evaluates only what was declared")
    void partiallyDeclared() {
        SloTargets targets = of(SloTargets.KEY_P95_MAX_MS, "1000");
        SloTargets.Evaluation eval = targets.evaluate(0.5, 0.4, 1.0, 10, 20, 30);
        assertEquals(SloTargets.Verdict.PASS, eval.verdict(),
                "a dreadful error rate must NOT fail a run that never declared an error-rate target");
        assertEquals(1, eval.checks().size());
        assertEquals(5, eval.undeclared().size());
    }

    @Test
    @DisplayName("describe() names the missing owner, because an unowned number is not a commitment")
    void describeNamesMissingOwner() {
        assertTrue(of(SloTargets.KEY_P99_MAX_MS, "500").describe().contains("slo.owner NOT set"));
        assertTrue(of(SloTargets.KEY_P99_MAX_MS, "500", SloTargets.KEY_OWNER, "COO")
                .describe().contains("owner: COO"));
    }

    // -------------------------------------------------------------------------
    // The shipped placeholder
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("the SHIPPED Documentation/SLO_TARGETS.properties declares nothing (T3-5 is half business)")
    void shippedPlaceholderDeclaresNothing() throws IOException {
        Path file = repoRoot().resolve("Documentation/SLO_TARGETS.properties");
        assertTrue(Files.isRegularFile(file), "the placeholder must exist so an owner has somewhere to "
                + "declare targets: " + file);
        SloTargets shipped = SloTargets.load(file);
        assertFalse(shipped.anyDeclared(),
                "SLO targets are a commercial commitment, not a default. If this test fails, someone "
                        + "put numbers in the shipped placeholder — either they are a real, owned "
                        + "declaration (then update this test and the T3-5 register entry) or they are "
                        + "invented (then remove them).");
        assertEquals(SloTargets.Verdict.NO_TARGETS_DECLARED,
                shipped.evaluate(1.0, 0.0, 1.0, 1, 1, 1).verdict());
    }

    private static Path repoRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        for (Path p = dir; p != null; p = p.getParent()) {
            if (Files.exists(p.resolve("settings.gradle"))) {
                return p;
            }
        }
        throw new IllegalStateException("could not locate repo root above " + dir);
    }
}
