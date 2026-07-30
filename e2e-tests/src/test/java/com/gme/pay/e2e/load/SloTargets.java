package com.gme.pay.e2e.load;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * The declared-SLO file, and the pass/fail verdict against it.
 *
 * <p><b>The whole design of this class is "do not invent a number".</b> What latency and availability
 * GMEPay+ promises a partner is a commercial commitment, not something a test harness gets to decide;
 * the COO audit's §8 finding is precisely that partner contracts would otherwise promise availability
 * the platform cannot measure. So the shipped file
 * ({@code Documentation/SLO_TARGETS.properties}) has <b>every key commented out</b>, and this class
 * reports {@link Verdict#NO_TARGETS_DECLARED} — a distinct third state, not a pass and not a
 * failure — until a named owner fills it in.
 *
 * <p>Consequences of that choice, on purpose:
 * <ul>
 *   <li>An empty file can never make a run look green. The verdict is not "PASS".</li>
 *   <li>A partially-filled file is honoured exactly as far as it goes: only the keys with values are
 *       evaluated, and the report lists which ones were skipped. Declaring a p99 without declaring an
 *       availability target is a legitimate intermediate state.</li>
 *   <li>A key present but blank counts as undeclared, so {@code slo.latency.p99.max-ms=} left over
 *       from an edit does not silently become 0 and fail every run.</li>
 * </ul>
 *
 * <p>Format is {@code java.util.Properties} rather than YAML/JSON: it needs {@code #} comments (the
 * placeholder file is mostly explanation), and it needs zero dependencies — e2e-tests carries only
 * {@code jackson-databind}, and neither SnakeYAML nor a JSON-with-comments parser is worth adding for
 * six numbers.
 */
public final class SloTargets {

    public static final String KEY_MIN_SUCCESS_RATE = "slo.availability.min-success-rate";
    public static final String KEY_MAX_ERROR_RATE = "slo.reliability.max-error-rate";
    public static final String KEY_P50_MAX_MS = "slo.latency.p50.max-ms";
    public static final String KEY_P95_MAX_MS = "slo.latency.p95.max-ms";
    public static final String KEY_P99_MAX_MS = "slo.latency.p99.max-ms";
    public static final String KEY_MIN_THROUGHPUT_TPS = "slo.throughput.min-tps";
    public static final String KEY_OWNER = "slo.owner";
    public static final String KEY_REVIEWED_ON = "slo.reviewed-on";

    /** Every numeric key this harness knows how to evaluate, in report order. */
    public static final List<String> NUMERIC_KEYS = List.of(
            KEY_MIN_SUCCESS_RATE, KEY_MAX_ERROR_RATE,
            KEY_P50_MAX_MS, KEY_P95_MAX_MS, KEY_P99_MAX_MS,
            KEY_MIN_THROUGHPUT_TPS);

    public enum Verdict {
        /** No numeric target had a value. Neither pass nor fail — nobody has declared anything yet. */
        NO_TARGETS_DECLARED,
        /** Every declared target was met. Says nothing about the undeclared ones. */
        PASS,
        /** At least one declared target was missed. */
        FAIL
    }

    private final Map<String, Double> declared;
    private final String owner;
    private final String reviewedOn;
    private final Path source;
    private final boolean fileExists;

    private SloTargets(Path source, boolean fileExists, Map<String, Double> declared,
                       String owner, String reviewedOn) {
        this.source = source;
        this.fileExists = fileExists;
        this.declared = Map.copyOf(declared);
        this.owner = owner;
        this.reviewedOn = reviewedOn;
    }

    // -------------------------------------------------------------------------
    // Loading
    // -------------------------------------------------------------------------

    /** Loads the file. A missing file is NOT an error — it is simply "nothing declared". */
    public static SloTargets load(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return new SloTargets(file, false, Map.of(), null, null);
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("could not read SLO target file " + file + ": " + e.getMessage(), e);
        }
        return from(file, true, props);
    }

    static SloTargets from(Path source, boolean fileExists, Properties props) {
        Map<String, Double> declared = new LinkedHashMap<>();
        for (String key : NUMERIC_KEYS) {
            String raw = props.getProperty(key);
            if (raw == null || raw.isBlank()) {
                continue; // left blank on purpose, or never filled in — undeclared either way
            }
            double parsed;
            try {
                parsed = Double.parseDouble(raw.trim());
            } catch (NumberFormatException e) {
                throw new IllegalStateException(source + ": " + key + " is not a number ('" + raw.trim()
                        + "'). Leave it blank/commented to declare no target for it.");
            }
            declared.put(key, parsed);
        }
        return new SloTargets(source, fileExists, declared,
                trimOrNull(props.getProperty(KEY_OWNER)), trimOrNull(props.getProperty(KEY_REVIEWED_ON)));
    }

    private static String trimOrNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    // -------------------------------------------------------------------------
    // Evaluation
    // -------------------------------------------------------------------------

    /** One evaluated target line. {@code met} is null when the run produced no comparable measurement. */
    public record Check(String key, double target, Double actual, Boolean met, String note) {}

    public record Evaluation(Verdict verdict, List<Check> checks, List<String> undeclared,
                             Optional<String> owner, Optional<String> reviewedOn) {}

    /**
     * Compares a finished run against whatever was declared.
     *
     * @param successRate OK / attempted (declines counted as NOT successful — a declined payment did not
     *                    complete, even though the platform behaved correctly; whether an availability
     *                    SLO should exclude declines is itself an owner decision, noted in the runbook)
     * @param errorRate   ERROR / attempted
     * @param p50Ms       NaN when unmeasured
     */
    public Evaluation evaluate(double successRate, double errorRate, double throughputTps,
                               double p50Ms, double p95Ms, double p99Ms) {
        if (declared.isEmpty()) {
            return new Evaluation(Verdict.NO_TARGETS_DECLARED, List.of(), NUMERIC_KEYS,
                    Optional.ofNullable(owner), Optional.ofNullable(reviewedOn));
        }

        List<Check> checks = new ArrayList<>();
        addLowerBound(checks, KEY_MIN_SUCCESS_RATE, successRate);
        addUpperBound(checks, KEY_MAX_ERROR_RATE, errorRate);
        addUpperBound(checks, KEY_P50_MAX_MS, p50Ms);
        addUpperBound(checks, KEY_P95_MAX_MS, p95Ms);
        addUpperBound(checks, KEY_P99_MAX_MS, p99Ms);
        addLowerBound(checks, KEY_MIN_THROUGHPUT_TPS, throughputTps);

        List<String> undeclared = NUMERIC_KEYS.stream().filter(k -> !declared.containsKey(k)).toList();
        // A check with met == null (no comparable measurement) is NOT a pass. It cannot be a silent one
        // either: it is rendered as "n/a" and forces the verdict to FAIL, because an undecidable target
        // is exactly the situation this whole gap is about.
        boolean anyMissed = checks.stream().anyMatch(c -> !Boolean.TRUE.equals(c.met()));
        return new Evaluation(anyMissed ? Verdict.FAIL : Verdict.PASS, checks, undeclared,
                Optional.ofNullable(owner), Optional.ofNullable(reviewedOn));
    }

    private void addLowerBound(List<Check> checks, String key, double actual) {
        Double target = declared.get(key);
        if (target == null) {
            return;
        }
        if (Double.isNaN(actual)) {
            checks.add(new Check(key, target, null, null, "not measured by this run"));
            return;
        }
        checks.add(new Check(key, target, actual, actual >= target, "actual must be >= target"));
    }

    private void addUpperBound(List<Check> checks, String key, double actual) {
        Double target = declared.get(key);
        if (target == null) {
            return;
        }
        if (Double.isNaN(actual)) {
            checks.add(new Check(key, target, null, null, "not measured by this run"));
            return;
        }
        checks.add(new Check(key, target, actual, actual <= target, "actual must be <= target"));
    }

    // -------------------------------------------------------------------------

    public boolean anyDeclared() {
        return !declared.isEmpty();
    }

    public boolean fileExists() {
        return fileExists;
    }

    public Path source() {
        return source;
    }

    /** Human line for the summary header — deliberately blunt when nothing is declared. */
    public String describe() {
        if (!fileExists) {
            return "NO TARGETS DECLARED — " + source + " does not exist";
        }
        if (declared.isEmpty()) {
            return "NO TARGETS DECLARED — every key in " + source + " is blank or commented out. "
                    + "Latency and availability commitments are a business decision; this harness "
                    + "measures, it does not choose. See RUNBOOK_LOAD_AND_CAPACITY §6.";
        }
        return String.format(Locale.ROOT, "%d of %d targets declared in %s%s",
                declared.size(), NUMERIC_KEYS.size(), source,
                owner == null ? " (slo.owner NOT set — nobody owns these numbers)" : " (owner: " + owner + ")");
    }
}
