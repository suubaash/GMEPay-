package com.gme.pay.e2e.load;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parsed command line for {@link LoadHarness}. Plain {@code --key=value} pairs — no CLI library, no
 * new dependency (see {@code Documentation/RUNBOOK_LOAD_AND_CAPACITY.md} §2 for the justification).
 *
 * <p><b>Defaults target the {@code run-fleet.ps1} 18xxx band</b>, which is the fleet an engineer
 * actually has running on this machine (payment-executor :18084, rate-fx :18101 —
 * {@code run-fleet.ps1} lines 152 / 198). They are defaults, not assumptions: every URL is still put
 * through {@link LoadTargetGuard}, and the harness still refuses to run without
 * {@value LoadTargetGuard#ACK_FLAG}.
 */
public final class LoadOptions {

    /** Money path driven by this run. */
    public enum Scenario {
        /** Wallet scans a merchant QR and pays: {@code POST /v1/pay} on payment-executor. */
        WALLET_PAY,
        /** Two-phase orchestrated flow: rate-fx quote → {@code /v1/payments/authorize} → {@code /confirm}. */
        AUTHORIZE_CONFIRM
    }

    public final Scenario scenario;
    /** Requested open-model arrival rate, requests/second. Not a guarantee — see {@code sheddedCount}. */
    public final double rate;
    /** Hard cap on in-flight requests. Arrivals past the cap are SHED and reported, never queued. */
    public final int concurrency;
    /** Measured window. */
    public final Duration duration;
    /** Un-measured window before {@link #duration} (JIT + pool warm-up). Results are discarded. */
    public final Duration warmup;
    /** Per-request timeout. A timeout counts as an ERROR, never as a decline. */
    public final Duration requestTimeout;

    public final String paymentExecutorBaseUrl;
    public final String rateFxBaseUrl;
    /** Value presented as {@code X-Gme-Internal} (SchemeFleet's requirement, T0-2/T0-5). */
    public final String internalSecret;
    /** EMVCo QR payload paid in the WALLET_PAY scenario. */
    public final String qrPayload;
    /** Amount per payment, minor-unit-free decimal string (money convention). */
    public final String amount;
    public final String currency;
    public final String partnerCode;
    public final String schemeId;
    public final String direction;

    /** name → base URL of every service whose {@code /actuator/prometheus} is scraped before/after. */
    public final Map<String, String> scrapeTargets;

    /** Declared-SLO file. Empty/absent ⇒ the report says "no targets declared" (never invents them). */
    public final Path targetsFile;
    /** Where {@code result.json} and {@code summary.md} are written. */
    public final Path outDir;

    public final boolean acknowledged;

    private LoadOptions(Builder b) {
        this.scenario = b.scenario;
        this.rate = b.rate;
        this.concurrency = b.concurrency;
        this.duration = b.duration;
        this.warmup = b.warmup;
        this.requestTimeout = b.requestTimeout;
        this.paymentExecutorBaseUrl = strip(b.paymentExecutorBaseUrl);
        this.rateFxBaseUrl = strip(b.rateFxBaseUrl);
        this.internalSecret = b.internalSecret;
        this.qrPayload = b.qrPayload;
        this.amount = b.amount;
        this.currency = b.currency;
        this.partnerCode = b.partnerCode;
        this.schemeId = b.schemeId;
        this.direction = b.direction;
        this.scrapeTargets = Map.copyOf(b.scrapeTargets);
        this.targetsFile = b.targetsFile;
        this.outDir = b.outDir;
        this.acknowledged = b.acknowledged;
    }

    /** Every URL the run will touch — exactly the list handed to {@link LoadTargetGuard}. */
    public List<String> allTargets() {
        List<String> all = new ArrayList<>();
        all.add(paymentExecutorBaseUrl);
        if (scenario == Scenario.AUTHORIZE_CONFIRM) {
            all.add(rateFxBaseUrl);
        }
        all.addAll(scrapeTargets.values());
        return all;
    }

    private static String strip(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    // -------------------------------------------------------------------------
    // Parsing
    // -------------------------------------------------------------------------

    public static LoadOptions parse(String[] args) {
        Builder b = new Builder();
        for (String raw : args) {
            String arg = raw.trim();
            if (arg.isEmpty()) {
                continue;
            }
            if (arg.equals(LoadTargetGuard.ACK_FLAG)) {
                b.acknowledged = true;
                continue;
            }
            if (!arg.startsWith("--") || !arg.contains("=")) {
                throw new IllegalArgumentException("unrecognised argument '" + arg
                        + "' — expected --key=value or " + LoadTargetGuard.ACK_FLAG);
            }
            int eq = arg.indexOf('=');
            String key = arg.substring(2, eq);
            String value = arg.substring(eq + 1);
            switch (key) {
                case "scenario" -> b.scenario = parseScenario(value);
                case "rate" -> b.rate = positiveDouble("rate", value);
                case "concurrency" -> b.concurrency = (int) positiveDouble("concurrency", value);
                case "duration" -> b.duration = parseDuration("duration", value);
                case "warmup" -> b.warmup = parseDuration("warmup", value);
                case "timeout" -> b.requestTimeout = parseDuration("timeout", value);
                case "payment-executor-url" -> b.paymentExecutorBaseUrl = value;
                case "rate-fx-url" -> b.rateFxBaseUrl = value;
                case "internal-secret" -> b.internalSecret = value;
                case "qr" -> b.qrPayload = value;
                case "amount" -> b.amount = value;
                case "currency" -> b.currency = value;
                case "partner" -> b.partnerCode = value;
                case "scheme" -> b.schemeId = value;
                case "direction" -> b.direction = value;
                case "scrape" -> b.scrapeTargets = parseScrape(value);
                case "targets" -> b.targetsFile = Paths.get(value);
                case "out" -> b.outDir = Paths.get(value);
                default -> throw new IllegalArgumentException("unknown option --" + key
                        + " (see Documentation/RUNBOOK_LOAD_AND_CAPACITY.md §2)");
            }
        }
        return new LoadOptions(b);
    }

    private static Scenario parseScenario(String value) {
        String normalised = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            return Scenario.valueOf(normalised);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown --scenario '" + value
                    + "' — expected wallet-pay or authorize-confirm");
        }
    }

    /** {@code name=url,name=url} — empty string means "scrape nothing" (explicitly allowed). */
    static Map<String, String> parseScrape(String value) {
        Map<String, String> out = new LinkedHashMap<>();
        if (value.isBlank()) {
            return out;
        }
        for (String entry : value.split(",")) {
            String e = entry.trim();
            if (e.isEmpty()) {
                continue;
            }
            int eq = e.indexOf('=');
            if (eq <= 0 || eq == e.length() - 1) {
                throw new IllegalArgumentException("--scrape entry '" + e
                        + "' must be name=url (e.g. payment-executor=http://localhost:18084)");
            }
            out.put(e.substring(0, eq).trim(), strip(e.substring(eq + 1).trim()));
        }
        return out;
    }

    /** Accepts {@code 90s}, {@code 5m}, {@code 500ms}, or a bare number of seconds. */
    static Duration parseDuration(String key, String value) {
        String v = value.trim().toLowerCase(Locale.ROOT);
        try {
            if (v.endsWith("ms")) {
                return Duration.ofMillis(Long.parseLong(v.substring(0, v.length() - 2)));
            }
            if (v.endsWith("s")) {
                return Duration.ofSeconds(Long.parseLong(v.substring(0, v.length() - 1)));
            }
            if (v.endsWith("m")) {
                return Duration.ofMinutes(Long.parseLong(v.substring(0, v.length() - 1)));
            }
            if (v.endsWith("h")) {
                return Duration.ofHours(Long.parseLong(v.substring(0, v.length() - 1)));
            }
            return Duration.ofSeconds(Long.parseLong(v));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + key + " '" + value
                    + "' is not a duration (examples: 500ms, 90s, 5m, 1h)");
        }
    }

    private static double positiveDouble(String key, String value) {
        double parsed;
        try {
            parsed = Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + key + " '" + value + "' is not a number");
        }
        if (!(parsed > 0)) {
            throw new IllegalArgumentException("--" + key + " must be > 0, got " + value);
        }
        return parsed;
    }

    // -------------------------------------------------------------------------
    // Defaults
    // -------------------------------------------------------------------------

    private static final class Builder {
        Scenario scenario = Scenario.WALLET_PAY;
        double rate = 5;
        int concurrency = 16;
        Duration duration = Duration.ofSeconds(60);
        Duration warmup = Duration.ofSeconds(10);
        Duration requestTimeout = Duration.ofSeconds(20);

        // run-fleet.ps1 fleet ports (lines 152 / 198).
        String paymentExecutorBaseUrl = "http://localhost:18084";
        String rateFxBaseUrl = "http://localhost:18101";

        // run-fleet.ps1's dev default (line ~109). Overridable with --internal-secret; it is a
        // documented non-production marker, not a credential (gap register T0-6).
        String internalSecret = envOrDefault("GMEPAY_INTERNAL_AUTH_SECRET",
                "dev-internal-svc-secret-not-for-prod");

        // The same full EMVCo static QR (valid tag-63 CRC) WalletScanPayE2ETest pays, so the load run
        // exercises a payload already proven to round-trip through sim-scheme's /qr/decode.
        String qrPayload =
                "00020101021129260011com.zeropay0107ZP-M0015204581253034105802KR5918Seoul Noodle House6005Seoul63040B08";
        String amount = "50000";
        String currency = "KRW";
        String partnerCode = "GMEREMIT";
        String schemeId = "zeropay";
        String direction = "INBOUND";

        Map<String, String> scrapeTargets = defaultScrapeTargets();

        Path targetsFile = Paths.get("Documentation/SLO_TARGETS.properties");
        Path outDir = Paths.get("e2e-tests/build/load-results");

        boolean acknowledged = false;
    }

    /**
     * The money-path services worth a before/after scrape. Deliberately NOT the whole fleet: these are
     * the four that carry every payment, so a pool or heap ceiling shows up here first.
     */
    private static Map<String, String> defaultScrapeTargets() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("payment-executor", "http://localhost:18084");
        m.put("transaction-mgmt", "http://localhost:18082");
        m.put("merchant-qr-data", "http://localhost:18083");
        m.put("scheme-adapter-zeropay", "http://localhost:18090");
        return m;
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return (value == null || value.isBlank()) ? fallback : value;
    }

    public static String usage() {
        return """
               Usage: gradlew :e2e-tests:loadTest --args="<options>"

                 %s        REQUIRED. Confirms this machine is not wired to production.

                 --scenario=wallet-pay|authorize-confirm   (default wallet-pay)
                 --rate=<req/s>            open-model arrival rate           (default 5)
                 --concurrency=<n>         in-flight cap; excess is SHED     (default 16)
                 --duration=<90s|5m>       measured window                   (default 60s)
                 --warmup=<10s>            discarded window before it        (default 10s)
                 --timeout=<20s>           per-request timeout               (default 20s)

                 --payment-executor-url=<url>   (default http://localhost:18084)
                 --rate-fx-url=<url>            (default http://localhost:18101)
                 --internal-secret=<value>      (default $GMEPAY_INTERNAL_AUTH_SECRET, else the
                                                 documented run-fleet.ps1 dev marker)
                 --scrape=name=url,name=url     /actuator/prometheus targets; --scrape= disables
                 --targets=<file>               declared SLOs (default Documentation/SLO_TARGETS.properties)
                 --out=<dir>                    (default e2e-tests/build/load-results)

                 --qr / --amount / --currency / --partner / --scheme / --direction
                                                payload knobs; defaults match WalletScanPayE2ETest

               The harness does NOT start the fleet. Start it first (run-fleet.ps1) — the preflight
               fails with the exact instruction if a target is not answering.
               See Documentation/RUNBOOK_LOAD_AND_CAPACITY.md.
               """.formatted(LoadTargetGuard.ACK_FLAG);
    }
}
