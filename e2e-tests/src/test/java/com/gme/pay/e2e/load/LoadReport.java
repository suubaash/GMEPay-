package com.gme.pay.e2e.load;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The two artefacts a run leaves behind: a machine-readable {@code result.json} and a short human
 * {@code summary.md} (also printed to stdout).
 *
 * <p>Both are written, not one, because they answer different questions. The JSON is what gets diffed
 * between a baseline run and a 10x run, or committed next to a capacity decision. The Markdown is what
 * goes in a ticket. Deriving the second from the first at read time would mean re-doing the percentile
 * maths in a spreadsheet, and that is how two numbers for the same run start circulating.
 *
 * <p><b>Every "not measured" stays "not measured".</b> A NaN percentile renders as {@code n/a} in the
 * text and {@code null} in the JSON — never 0, which would read as "instantaneous", and never omitted,
 * which would read as "fine". The same rule governs the SLO block: an empty target file produces
 * {@code "verdict": "NO_TARGETS_DECLARED"}, not {@code "verdict": "PASS"}.
 */
final class LoadReport {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ISO_INSTANT;

    private final LoadOptions opts;
    private final String scenarioName;
    private final Instant startedAt;
    private final Instant endedAt;
    private final double elapsedSeconds;
    private final LoadHarness.Results results;
    private final PrometheusSnapshot before;
    private final PrometheusSnapshot after;
    private final SloTargets targets;

    private final long attempted;
    private final double successRate;
    private final double declineRate;
    private final double errorRate;
    private final double throughputTps;
    private final double p50;
    private final double p95;
    private final double p99;
    private final SloTargets.Evaluation evaluation;

    LoadReport(LoadOptions opts, String scenarioName, Instant startedAt, Instant endedAt,
               long elapsedNs, LoadHarness.Results results,
               PrometheusSnapshot before, PrometheusSnapshot after, SloTargets targets) {
        this.opts = opts;
        this.scenarioName = scenarioName;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.elapsedSeconds = elapsedNs / 1_000_000_000.0;
        this.results = results;
        this.before = before;
        this.after = after;
        this.targets = targets;

        this.attempted = results.attempted.get();
        long okCount = results.ok.get();
        this.successRate = attempted == 0 ? Double.NaN : (double) okCount / attempted;
        this.declineRate = attempted == 0 ? Double.NaN : (double) results.declined.get() / attempted;
        this.errorRate = attempted == 0 ? Double.NaN : (double) results.errored.get() / attempted;
        // Throughput counts COMPLETED payments only. Counting attempts would let a run that shed half its
        // arrivals report the rate it was asked for rather than the rate it achieved.
        this.throughputTps = elapsedSeconds <= 0 ? Double.NaN : okCount / elapsedSeconds;
        this.p50 = results.overall.percentileMs(50);
        this.p95 = results.overall.percentileMs(95);
        this.p99 = results.overall.percentileMs(99);
        this.evaluation = targets.evaluate(successRate, errorRate, throughputTps, p50, p95, p99);
    }

    SloTargets.Evaluation evaluation() {
        return evaluation;
    }

    // -------------------------------------------------------------------------
    // Machine-readable
    // -------------------------------------------------------------------------

    void write() throws IOException {
        Files.createDirectories(opts.outDir);
        Path json = opts.outDir.resolve("result.json");
        Files.writeString(json, JSON.writeValueAsString(toJson()), StandardCharsets.UTF_8);
        Path md = opts.outDir.resolve("summary.md");
        Files.writeString(md, humanSummary(), StandardCharsets.UTF_8);
        System.out.println("[load] wrote " + json.toAbsolutePath());
        System.out.println("[load] wrote " + md.toAbsolutePath());
    }

    private ObjectNode toJson() {
        ObjectNode root = JSON.createObjectNode();
        root.put("schemaVersion", 1);
        root.put("gap", "T3-5");

        ObjectNode run = root.putObject("run");
        run.put("scenario", scenarioName);
        run.put("startedAt", STAMP.format(startedAt));
        run.put("endedAt", STAMP.format(endedAt));
        run.put("elapsedSeconds", round(elapsedSeconds, 3));
        run.put("requestedRatePerSecond", opts.rate);
        run.put("concurrencyCap", opts.concurrency);
        run.put("warmupSeconds", opts.warmup.toSeconds());
        run.put("requestTimeoutMs", opts.requestTimeout.toMillis());
        run.put("paymentExecutorBaseUrl", opts.paymentExecutorBaseUrl);
        if (opts.scenario == LoadOptions.Scenario.AUTHORIZE_CONFIRM) {
            run.put("rateFxBaseUrl", opts.rateFxBaseUrl);
        }

        ObjectNode counts = root.putObject("counts");
        counts.put("attempted", attempted);
        counts.put("ok", results.ok.get());
        counts.put("declined", results.declined.get());
        counts.put("errored", results.errored.get());
        counts.put("shedByHarness", results.shed.get());

        ObjectNode rates = root.putObject("rates");
        putNullable(rates, "successRate", successRate, 5);
        putNullable(rates, "declineRate", declineRate, 5);
        putNullable(rates, "errorRate", errorRate, 5);
        putNullable(rates, "achievedThroughputPerSecond", throughputTps, 3);

        ObjectNode latency = root.putObject("latencyMs");
        latency.put("samples", results.overall.count());
        putNullable(latency, "min", results.overall.minMs(), 3);
        putNullable(latency, "mean", results.overall.meanMs(), 3);
        putNullable(latency, "p50", p50, 3);
        putNullable(latency, "p95", p95, 3);
        putNullable(latency, "p99", p99, 3);
        putNullable(latency, "max", results.overall.maxMs(), 3);

        if (!results.perStep.isEmpty()) {
            ObjectNode steps = root.putObject("stepLatencyMs");
            results.perStep.forEach((step, lat) -> {
                ObjectNode node = steps.putObject(step);
                node.put("samples", lat.count());
                putNullable(node, "p50", lat.percentileMs(50), 3);
                putNullable(node, "p95", lat.percentileMs(95), 3);
                putNullable(node, "p99", lat.percentileMs(99), 3);
            });
        }

        ObjectNode codes = root.putObject("outcomeCodes");
        results.codeTally().forEach(codes::put);

        ObjectNode slo = root.putObject("slo");
        slo.put("targetsFile", String.valueOf(opts.targetsFile));
        slo.put("targetsFileExists", targets.fileExists());
        slo.put("verdict", evaluation.verdict().name());
        slo.put("description", targets.describe());
        evaluation.owner().ifPresentOrElse(o -> slo.put("owner", o), () -> slo.putNull("owner"));
        evaluation.reviewedOn().ifPresentOrElse(r -> slo.put("reviewedOn", r), () -> slo.putNull("reviewedOn"));
        ArrayNode checks = slo.putArray("checks");
        for (SloTargets.Check check : evaluation.checks()) {
            ObjectNode node = checks.addObject();
            node.put("key", check.key());
            node.put("target", check.target());
            if (check.actual() == null) {
                node.putNull("actual");
            } else {
                node.put("actual", round(check.actual(), 5));
            }
            if (check.met() == null) {
                node.putNull("met");
            } else {
                node.put("met", check.met());
            }
            node.put("note", check.note());
        }
        ArrayNode undeclared = slo.putArray("undeclared");
        evaluation.undeclared().forEach(undeclared::add);

        ObjectNode metrics = root.putObject("prometheus");
        ArrayNode deltas = metrics.putArray("beforeAfter");
        for (PrometheusSnapshot.Delta d : PrometheusSnapshot.compare(before, after)) {
            ObjectNode node = deltas.addObject();
            node.put("service", d.service());
            node.put("metric", d.metric());
            putNullable(node, "before", d.before() == null ? Double.NaN : d.before(), 5);
            putNullable(node, "after", d.after() == null ? Double.NaN : d.after(), 5);
            putNullable(node, "change", d.change() == null ? Double.NaN : d.change(), 5);
        }
        ObjectNode scrapeFailures = metrics.putObject("scrapeFailures");
        before.failures().forEach((svc, why) -> scrapeFailures.put(svc + " (before)", why));
        after.failures().forEach((svc, why) -> scrapeFailures.put(svc + " (after)", why));

        ArrayNode caveats = root.putArray("caveats");
        caveats.add("The harness did NOT start the fleet; whatever was running was measured as-is.");
        caveats.add("Scheme calls go to the local simulators, not a real scheme endpoint — scheme-side "
                + "latency and rate limits are NOT represented.");
        caveats.add("A single-host fleet shares one CPU, one disk and one page cache with this harness, "
                + "so absolute numbers are not a production forecast; use them for relative comparison "
                + "(baseline vs 10x) only.");
        if (results.shed.get() > 0) {
            caveats.add("The harness SHED " + results.shed.get() + " arrival(s) at its --concurrency cap: "
                    + "the offered rate was not achieved, so these percentiles describe a lighter load "
                    + "than requested.");
        }
        return root;
    }

    private static void putNullable(ObjectNode node, String field, double value, int scale) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            node.putNull(field);
        } else {
            node.put(field, round(value, scale));
        }
    }

    private static double round(double value, int scale) {
        double factor = Math.pow(10, scale);
        return Math.round(value * factor) / factor;
    }

    // -------------------------------------------------------------------------
    // Human
    // -------------------------------------------------------------------------

    String humanSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Load run — ").append(scenarioName).append(" — ").append(STAMP.format(startedAt))
                .append("\n\n");
        sb.append("Gap **T3-5**. Target: `").append(opts.paymentExecutorBaseUrl).append("` (local only).\n");
        sb.append("Offered ").append(fmt(opts.rate)).append(" req/s, concurrency cap ")
                .append(opts.concurrency).append(", window ")
                .append(Duration.ofSeconds((long) elapsedSeconds)).append(".\n\n");

        sb.append("## Throughput and outcomes\n\n");
        sb.append("| | |\n|---|---|\n");
        sb.append("| attempted | ").append(attempted).append(" |\n");
        sb.append("| completed (OK) | ").append(results.ok.get()).append(" |\n");
        sb.append("| declined (structured, working as designed) | ").append(results.declined.get()).append(" |\n");
        sb.append("| **errored** | ").append(results.errored.get()).append(" |\n");
        sb.append("| shed by the harness (concurrency cap) | ").append(results.shed.get()).append(" |\n");
        sb.append("| achieved throughput | ").append(pct(throughputTps, "%.2f")).append(" /s |\n");
        sb.append("| success rate | ").append(percent(successRate)).append(" |\n");
        sb.append("| decline rate | ").append(percent(declineRate)).append(" |\n");
        sb.append("| **error rate** | ").append(percent(errorRate)).append(" |\n\n");

        if (results.shed.get() > 0) {
            sb.append("> **The offered rate was not achieved.** ").append(results.shed.get())
                    .append(" arrival(s) were shed at the harness's own concurrency cap, so every ")
                    .append("percentile below describes a lighter load than was requested. Raise ")
                    .append("`--concurrency`, or read this as the platform's saturation point.\n\n");
        }

        sb.append("## End-to-end latency (completed + declined round trips)\n\n");
        sb.append("| metric | ms |\n|---|---|\n");
        sb.append("| samples | ").append(results.overall.count()).append(" |\n");
        sb.append("| min | ").append(pct(results.overall.minMs(), "%.1f")).append(" |\n");
        sb.append("| p50 | ").append(pct(p50, "%.1f")).append(" |\n");
        sb.append("| p95 | ").append(pct(p95, "%.1f")).append(" |\n");
        sb.append("| p99 | ").append(pct(p99, "%.1f")).append(" |\n");
        sb.append("| max | ").append(pct(results.overall.maxMs(), "%.1f")).append(" |\n\n");
        sb.append("Errors are **counted, not timed** — a 3 ms connection-refused would otherwise pull p50 ")
                .append("down and make a broken run look fast.\n\n");

        if (!results.perStep.isEmpty() && results.perStep.size() > 1) {
            sb.append("### Per step\n\n| step | p50 | p95 | p99 | samples |\n|---|---|---|---|---|\n");
            results.perStep.forEach((step, lat) -> sb.append("| ").append(step)
                    .append(" | ").append(pct(lat.percentileMs(50), "%.1f"))
                    .append(" | ").append(pct(lat.percentileMs(95), "%.1f"))
                    .append(" | ").append(pct(lat.percentileMs(99), "%.1f"))
                    .append(" | ").append(lat.count()).append(" |\n"));
            sb.append('\n');
        }

        Map<String, Long> tally = results.codeTally();
        sb.append("## Outcome codes\n\n");
        if (tally.isEmpty()) {
            sb.append("Every attempt completed 2xx — no declines, no errors.\n\n");
        } else {
            sb.append("| code | count |\n|---|---|\n");
            tally.forEach((code, count) -> sb.append("| `").append(code).append("` | ").append(count)
                    .append(" |\n"));
            sb.append("\nA structured 4xx code (`TRANSACTION_LIMIT_EXCEEDED`, `SCHEME_CLOSED`, ")
                    .append("`SCHEME_OPERATION_UNSUPPORTED`, `MERCHANT_INACTIVE`, …) is a **decline**, not ")
                    .append("a failure. `HTTP_5xx`, `TIMEOUT`, `CONNECTION_REFUSED` and `RATE_LIMITED` are ")
                    .append("errors.\n\n");
        }

        sb.append("## SLO verdict\n\n");
        sb.append("**").append(evaluation.verdict()).append("** — ").append(targets.describe()).append("\n\n");
        if (evaluation.verdict() == SloTargets.Verdict.NO_TARGETS_DECLARED) {
            sb.append("This is **not a pass**. What latency and availability GMEPay+ promises a partner is ")
                    .append("a commercial commitment; the harness measures it and refuses to invent it. An ")
                    .append("owner must fill in `").append(opts.targetsFile)
                    .append("` (and put their name in `slo.owner`) before any run can pass or fail.\n\n");
        } else {
            sb.append("| target | declared | actual | met |\n|---|---|---|---|\n");
            for (SloTargets.Check c : evaluation.checks()) {
                sb.append("| `").append(c.key()).append("` | ").append(fmt(c.target()))
                        .append(" | ").append(c.actual() == null ? "n/a" : fmt(c.actual()))
                        .append(" | ").append(c.met() == null ? "**undecidable**" : (c.met() ? "yes" : "**NO**"))
                        .append(" |\n");
            }
            if (!evaluation.undeclared().isEmpty()) {
                sb.append("\nStill undeclared (not evaluated): ");
                sb.append(String.join(", ", evaluation.undeclared().stream().map(k -> "`" + k + "`").toList()));
                sb.append('\n');
            }
            sb.append('\n');
        }

        List<PrometheusSnapshot.Delta> deltas = PrometheusSnapshot.compare(before, after);
        sb.append("## Platform metrics, before → after (`/actuator/prometheus`)\n\n");
        if (deltas.isEmpty()) {
            sb.append("No scrape data. ");
            if (opts.scrapeTargets.isEmpty()) {
                sb.append("`--scrape=` was empty, so none was requested.\n");
            } else {
                sb.append("See the scrape failures below.\n");
            }
        } else {
            sb.append("| service | metric | before | after | change |\n|---|---|---|---|---|\n");
            for (PrometheusSnapshot.Delta d : deltas) {
                sb.append("| ").append(d.service()).append(" | `").append(d.metric()).append("` | ")
                        .append(PrometheusSnapshot.format(d.before(), d.metric())).append(" | ")
                        .append(PrometheusSnapshot.format(d.after(), d.metric())).append(" | ")
                        .append(PrometheusSnapshot.format(d.change(), d.metric())).append(" |\n");
            }
            sb.append("\n`hikaricp_connections_pending > 0` is the DB-pool exhaustion the COO audit ")
                    .append("predicted as first-break; `tomcat_threads_busy_threads` at ")
                    .append("`config_max_threads` is the HTTP ceiling instead. Both are the platform's own ")
                    .append("series (T3-2), not the harness's.\n");
        }
        if (!before.failures().isEmpty() || !after.failures().isEmpty()) {
            sb.append("\n**Scrape failures** (recorded, never fatal):\n\n");
            before.failures().forEach((s, why) -> sb.append("- ").append(s).append(" (before): ")
                    .append(why).append('\n'));
            after.failures().forEach((s, why) -> sb.append("- ").append(s).append(" (after): ")
                    .append(why).append('\n'));
        }

        sb.append("\n## What this run does NOT tell you\n\n");
        sb.append("- The fleet was **not started by this harness**; whatever state it was in is what got ")
                .append("measured.\n");
        sb.append("- Scheme calls hit the local **simulators**. Real ZeroPay / SendMN / 9Pay latency, ")
                .append("rate limits and outages are absent.\n");
        sb.append("- The harness shares one host's CPU, disk and page cache with the fleet, so these are ")
                .append("**relative** numbers (baseline vs 10x), not a production forecast.\n");
        sb.append("- Ceilings that only a *scaled* deployment reveals (per-replica nonce/rate-limit ")
                .append("stores, Kafka partition count, ShedLock-less schedulers) are analysed by reading ")
                .append("in `Documentation/RUNBOOK_LOAD_AND_CAPACITY.md` §4, not measured here.\n");
        return sb.toString();
    }

    private static String percent(double ratio) {
        return Double.isNaN(ratio) ? "n/a" : String.format(Locale.ROOT, "%.2f%%", ratio * 100);
    }

    private static String pct(double value, String format) {
        return Double.isNaN(value) ? "n/a" : String.format(Locale.ROOT, format, value);
    }

    private static String fmt(double value) {
        return value == Math.rint(value) && Math.abs(value) < 1e9
                ? String.valueOf((long) value)
                : String.format(Locale.ROOT, "%.4f", value);
    }
}
