package com.gme.pay.e2e.load;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A parsed {@code /actuator/prometheus} scrape, reduced to the saturation series a load run cares about.
 *
 * <p><b>This harness measures nothing itself.</b> T3-2 put a real Micrometer registry on all 20
 * deployables (root {@code build.gradle}) and exposed {@code /actuator/prometheus} fleet-wide
 * ({@code MetricsExposureEnvironmentPostProcessor} in {@code libs/lib-errors}) — so the JVM heap, the
 * HikariCP pool and the Tomcat thread pool are already instrumented by the platform. Re-instrumenting
 * them from the client side would produce a second, disagreeing set of numbers; scraping the platform's
 * own endpoint before and after the run means the load report and the production dashboard are reading
 * the same series. See {@code Documentation/RUNBOOK_MONITORING.md} §1.2.
 *
 * <p>Only the families in {@link #FAMILIES} are retained. A full scrape is hundreds of series per
 * service and most of them cannot saturate; the retained set is exactly the ceilings the capacity
 * analysis names (pool exhaustion, heap, threads, GC) plus the request counter needed to confirm the
 * server saw the load the client thinks it sent.
 */
public final class PrometheusSnapshot {

    /**
     * Retained metric families.
     *
     * <ul>
     *   <li>{@code hikaricp_connections_*} — the pool exhaustion the COO audit predicted as first-break.
     *       {@code _pending > 0} means threads are queued waiting for a DB connection;
     *       {@code _timeout_total} rising means some gave up.</li>
     *   <li>{@code tomcat_threads_*} — busy vs config-max. {@code run-fleet.ps1} caps this at 20
     *       (line 337), so a local run can hit the HTTP ceiling before the DB one.</li>
     *   <li>{@code jvm_memory_used_bytes} / {@code jvm_gc_*} — every fleet JVM runs a small heap
     *       ({@code -Xmx256m} in the E2E launcher, {@code -Xmx320m} in compose), so GC pressure is a
     *       realistic first ceiling and not a theoretical one.</li>
     *   <li>{@code http_server_requests_seconds_count} — server-side request count, to cross-check the
     *       client's own attempt count (a large gap means requests never arrived).</li>
     *   <li>{@code executor_*} — {@code @Async}/task-executor queue depth, if the service has one.</li>
     *   <li>{@code kafka_consumer_fetch_manager_records_lag_max} — consumer lag, where spring-kafka is
     *       on the classpath.</li>
     * </ul>
     */
    public static final Set<String> FAMILIES = Set.of(
            "hikaricp_connections",
            "hikaricp_connections_active",
            "hikaricp_connections_idle",
            "hikaricp_connections_pending",
            "hikaricp_connections_max",
            "hikaricp_connections_min",
            "hikaricp_connections_timeout_total",
            "hikaricp_connections_acquire_seconds_count",
            "hikaricp_connections_acquire_seconds_sum",
            "hikaricp_connections_usage_seconds_count",
            "tomcat_threads_busy_threads",
            "tomcat_threads_current_threads",
            "tomcat_threads_config_max_threads",
            "jvm_memory_used_bytes",
            "jvm_memory_max_bytes",
            "jvm_threads_live_threads",
            "jvm_threads_peak_threads",
            "jvm_gc_pause_seconds_count",
            "jvm_gc_pause_seconds_sum",
            "process_cpu_usage",
            "system_cpu_usage",
            "http_server_requests_seconds_count",
            "executor_active_threads",
            "executor_queued_tasks",
            "executor_pool_max_threads",
            "kafka_consumer_fetch_manager_records_lag_max");

    /** service name → (series key → value). Series key is {@code name{sorted,labels}}. */
    private final Map<String, Map<String, Double>> byService;
    /** service name → why the scrape failed (401, connection refused, …). Reported, never thrown. */
    private final Map<String, String> failures;

    private PrometheusSnapshot(Map<String, Map<String, Double>> byService, Map<String, String> failures) {
        this.byService = byService;
        this.failures = failures;
    }

    public static PrometheusSnapshot empty() {
        return new PrometheusSnapshot(new LinkedHashMap<>(), new LinkedHashMap<>());
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final Map<String, Map<String, Double>> byService = new LinkedHashMap<>();
        private final Map<String, String> failures = new LinkedHashMap<>();

        public Builder service(String name, String exposition) {
            byService.put(name, parse(exposition));
            return this;
        }

        public Builder failed(String name, String reason) {
            failures.put(name, reason);
            return this;
        }

        public PrometheusSnapshot build() {
            return new PrometheusSnapshot(byService, failures);
        }
    }

    /**
     * Parses Prometheus text exposition, keeping only {@link #FAMILIES}.
     *
     * <p>Tolerant on purpose: {@code #} lines are skipped, {@code NaN}/{@code +Inf} values are dropped
     * rather than aborting, and an unparsable line is ignored. A malformed byte in a metrics endpoint
     * must never fail a load run whose real subject is the money path.
     */
    static Map<String, Double> parse(String exposition) {
        Map<String, Double> series = new LinkedHashMap<>();
        if (exposition == null) {
            return series;
        }
        for (String line : exposition.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.charAt(0) == '#') {
                continue;
            }
            int brace = trimmed.indexOf('{');
            int firstSpace = trimmed.indexOf(' ');
            if (firstSpace < 0) {
                continue;
            }
            String family = (brace >= 0 && brace < firstSpace)
                    ? trimmed.substring(0, brace)
                    : trimmed.substring(0, firstSpace);
            if (!FAMILIES.contains(family)) {
                continue;
            }
            // The value is the last whitespace-separated token (an optional timestamp would follow it,
            // but Micrometer's exposition does not emit one).
            int lastSpace = trimmed.lastIndexOf(' ');
            String key = trimmed.substring(0, lastSpace).trim();
            double value;
            try {
                value = Double.parseDouble(trimmed.substring(lastSpace + 1).trim());
            } catch (NumberFormatException e) {
                continue;
            }
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                continue;
            }
            // Summed rather than overwritten: http_server_requests_seconds_count has one series per
            // (uri, status), and the useful number here is the total the server handled.
            series.merge(key, value, Double::sum);
        }
        return series;
    }

    public Map<String, String> failures() {
        return Map.copyOf(failures);
    }

    public Set<String> services() {
        return byService.keySet();
    }

    /** Sum of every series in a family for one service (labels collapsed). */
    public Optional<Double> familySum(String service, String family) {
        Map<String, Double> series = byService.get(service);
        if (series == null) {
            return Optional.empty();
        }
        double total = 0;
        boolean found = false;
        for (Map.Entry<String, Double> e : series.entrySet()) {
            String key = e.getKey();
            if (key.equals(family) || key.startsWith(family + "{")) {
                total += e.getValue();
                found = true;
            }
        }
        return found ? Optional.of(total) : Optional.empty();
    }

    /** Max of every series in a family for one service — the right reduction for a gauge like {@code _pending}. */
    public Optional<Double> familyMax(String service, String family) {
        Map<String, Double> series = byService.get(service);
        if (series == null) {
            return Optional.empty();
        }
        double max = Double.NEGATIVE_INFINITY;
        for (Map.Entry<String, Double> e : series.entrySet()) {
            String key = e.getKey();
            if (key.equals(family) || key.startsWith(family + "{")) {
                max = Math.max(max, e.getValue());
            }
        }
        return max == Double.NEGATIVE_INFINITY ? Optional.empty() : Optional.of(max);
    }

    /** One before/after row for the report. */
    public record Delta(String service, String metric, Double before, Double after, Double change) {}

    /**
     * The saturation rows, computed as (after − before) for the two counters that only mean anything as
     * a rate, and as the raw pair for everything else.
     */
    public static List<Delta> compare(PrometheusSnapshot before, PrometheusSnapshot after) {
        List<Delta> rows = new ArrayList<>();
        List<String> reported = List.of(
                "hikaricp_connections_active",
                "hikaricp_connections_pending",
                "hikaricp_connections_max",
                "hikaricp_connections_timeout_total",
                "tomcat_threads_busy_threads",
                "tomcat_threads_config_max_threads",
                "jvm_memory_used_bytes",
                "jvm_threads_live_threads",
                "jvm_gc_pause_seconds_count",
                "jvm_gc_pause_seconds_sum",
                "process_cpu_usage",
                "http_server_requests_seconds_count",
                "executor_queued_tasks",
                "kafka_consumer_fetch_manager_records_lag_max");

        // Union of both sides so a service that only answered one of the two scrapes still appears.
        List<String> services = new ArrayList<>(before.services());
        after.services().stream().filter(s -> !services.contains(s)).forEach(services::add);

        for (String service : services) {
            for (String metric : reported) {
                boolean gauge = !metric.endsWith("_total") && !metric.endsWith("_count")
                        && !metric.endsWith("_sum");
                Optional<Double> b = gauge ? before.familyMax(service, metric) : before.familySum(service, metric);
                Optional<Double> a = gauge ? after.familyMax(service, metric) : after.familySum(service, metric);
                if (b.isEmpty() && a.isEmpty()) {
                    continue; // the service does not have this family (no DB, no Kafka, …)
                }
                Double change = (b.isPresent() && a.isPresent()) ? a.get() - b.get() : null;
                rows.add(new Delta(service, metric, b.orElse(null), a.orElse(null), change));
            }
        }
        return rows;
    }

    /** Formats a value compactly for the human summary (bytes → MiB, ratios → 3dp). */
    public static String format(Double value, String metric) {
        if (value == null) {
            return "n/a";
        }
        if (metric.endsWith("_bytes")) {
            return String.format(Locale.ROOT, "%.1f MiB", value / (1024 * 1024));
        }
        if (metric.endsWith("_usage")) {
            return String.format(Locale.ROOT, "%.3f", value);
        }
        double raw = value;
        if (raw == Math.rint(raw) && Math.abs(raw) < 1e12) {
            return String.valueOf((long) raw);
        }
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
