package com.gme.pay.metrics;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fleet-wide guard: <b>a service with {@code @Scheduled} jobs must size its scheduler pool above its
 * job count</b> (gap <b>T3-11</b> defect 2).
 *
 * <h2>The defect this exists to prevent</h2>
 *
 * <p>{@code spring.task.scheduling.pool.size} defaults to <b>one thread</b>. Every {@code @Scheduled}
 * method in a service shares it, so a job that blocks does not make its siblings late — it stops them
 * entirely. {@code RUNBOOK_LOAD_AND_CAPACITY.md} §4.1 #5 named the consequence: transaction-mgmt's
 * 1-second outbox poller lengthens under load and, holding the only thread, silences the 10-second
 * expiry sweeper and the 60-second stuck-transaction alerter — the T3-3 safety nets go quiet at exactly
 * the moment volume makes them matter.
 *
 * <h2>Why a guard and not just the right numbers</h2>
 *
 * <p>Because the numbers had already drifted once. T3-11 sized five services by hand; by the time this
 * guard was written payment-executor had grown from 4 scheduled jobs to <b>7</b> against a pool of
 * <b>6</b>, so two jobs could queue behind their siblings again — reintroducing the exact defect, in
 * the service that owns the money path, with the original fix still sitting in the config file above
 * it. Sizing is not a one-time act; job counts only ever grow.
 *
 * <p>The pool must exceed the job count rather than equal it, because the
 * {@link SchedulerLagProbe} heartbeat rides on this same pool. A probe that cannot get a thread reports
 * <em>no lag</em> — so at pool == jobs the one metric that makes starvation visible is the first thing
 * starvation hides. "The pool is fine" and "the pool is too busy to measure" must not look identical.
 *
 * <h2>Why a source scan and not a Spring test</h2>
 *
 * <p>Each service's pool size lives in its own classpath resource and its jobs in its own module; no
 * module can see another's. A per-service test has to be copied thirteen times, and the copy nobody
 * wrote is the service that runs one thread. This reads the fleet's sources and config files from the
 * module every service already depends on. The behavioural proof that a sized pool actually prevents
 * starvation is {@link SchedulerLagProbeTest}, which runs two real jobs on a real 1-thread and
 * 2-thread pool; this test proves the sizing exists and keeps up with the job count.
 */
class SchedulerPoolSizeWiringGuardTest {

    private static final Pattern SCHEDULED = Pattern.compile("@Scheduled\\s*\\(");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n]*");

    /** {@code spring.task.scheduling.pool.size=N} in a .properties file, ignoring commented lines. */
    private static final Pattern PROPERTIES_FORM = Pattern.compile(
            "^[ \\t]*spring\\.task\\.scheduling\\.pool\\.size[ \\t]*=[ \\t]*(\\d+)", Pattern.MULTILINE);

    /** The same setting in the nested YAML form settlement-reconciliation uses. */
    private static final Pattern YAML_FORM = Pattern.compile(
            "scheduling:\\s*\\R\\s*pool:\\s*\\R\\s*size:\\s*(\\d+)");

    /**
     * Services that still run an under-sized (or unset, i.e. ONE-thread) scheduler pool, each outside
     * the T3-11 change scope, with what is wrong.
     *
     * <p>A shrinking baseline, not an exemption list: the test fails BOTH when a service outside it is
     * under-sized AND when a service inside it has been fixed without being removed.
     *
     * <p>The fix is one property. Set {@code spring.task.scheduling.pool.size} to
     * (number of {@code @Scheduled} methods + 1), and say in a comment beside it what the jobs are and
     * which of them is the monitor — five services already read that way.
     */
    private static final Map<String, String> KNOWN_UNDERSIZED = Map.of(
            // Both unset entirely → Spring's default of ONE thread for every job in the service. Both
            // were concurrently owned by another agent when this baseline was last shortened.
            "config-registry", "1 job, pool UNSET (one thread)",
            "ops-partner-bff",
            "2 jobs, pool UNSET (one thread) — one of them is the ops paging dispatcher's sweep, so the "
                    + "alerting path is the thing sharing a single thread");

    @Test
    @DisplayName("every service's scheduler pool exceeds its @Scheduled job count")
    void everyScheduledServiceSizesItsPoolAboveItsJobCount() {
        Path root = repositoryRoot();
        Map<String, Integer> jobs = new TreeMap<>();
        Map<String, Integer> pools = new LinkedHashMap<>();

        for (Path service : servicesUnder(root)) {
            int count = scheduledJobCount(service);
            if (count == 0) {
                continue;
            }
            String name = service.getFileName().toString();
            jobs.put(name, count);
            Integer pool = configuredPoolSize(service);
            if (pool != null) {
                pools.put(name, pool);
            }
        }

        assertTrue(jobs.size() >= 10,
                "expected to find the fleet's scheduled services, found " + jobs.keySet()
                        + " — has the scan root moved?");

        List<String> undersized = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : jobs.entrySet()) {
            Integer pool = pools.get(entry.getKey());
            // > and not >=: the SchedulerLagProbe heartbeat shares this pool, and a probe that cannot
            // get a thread reports no lag.
            if (pool == null || pool <= entry.getValue()) {
                undersized.add(entry.getKey() + " (jobs=" + entry.getValue() + ", pool="
                        + (pool == null ? "UNSET→1" : pool) + ")");
            }
        }

        Set<String> unexpected = new LinkedHashSet<>();
        for (String finding : undersized) {
            String name = finding.substring(0, finding.indexOf(' '));
            if (!KNOWN_UNDERSIZED.containsKey(name)) {
                unexpected.add(finding);
            }
        }
        if (!unexpected.isEmpty()) {
            fail("""
                    These services run fewer scheduler threads than they have @Scheduled jobs, so a \
                    blocked job does not make its siblings late — it stops them, and the \
                    SchedulerLagProbe heartbeat that would report it competes for the same thread. \
                    Set spring.task.scheduling.pool.size to (job count + 1) and say beside it what the \
                    jobs are and which one is the monitor. Offenders: """ + unexpected);
        }

        Set<String> stale = new LinkedHashSet<>(KNOWN_UNDERSIZED.keySet());
        undersized.forEach(f -> stale.remove(f.substring(0, f.indexOf(' '))));
        // A baseline entry for a service that no longer has any @Scheduled job is stale too.
        stale.removeIf(name -> !jobs.containsKey(name));
        assertTrue(stale.isEmpty(),
                "KNOWN_UNDERSIZED in " + getClass().getSimpleName() + " is stale: " + stale
                        + " is now sized above its job count. Delete the entry so the baseline keeps "
                        + "shrinking.");
    }

    private static int scheduledJobCount(Path service) {
        Path src = service.resolve("src/main/java");
        int count = 0;
        for (Path file : javaSourcesUnder(src)) {
            Matcher matcher = SCHEDULED.matcher(stripComments(read(file)));
            while (matcher.find()) {
                count++;
            }
        }
        return count;
    }

    private static Integer configuredPoolSize(Path service) {
        // All three names, and both syntaxes within each: several services ship application.properties
        // AND application.yml, and the setting may legitimately be in either. A lookup that stopped at
        // the first file it found would report a correctly-sized service as UNSET, and a guard that
        // cries wolf is a guard whose failures get baselined.
        for (String name : List.of("application.properties", "application.yml", "application.yaml")) {
            Path file = service.resolve("src/main/resources").resolve(name);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            String text = read(file);
            Matcher properties = PROPERTIES_FORM.matcher(text);
            if (properties.find()) {
                return Integer.valueOf(properties.group(1));
            }
            Matcher yaml = YAML_FORM.matcher(text);
            if (yaml.find()) {
                return Integer.valueOf(yaml.group(1));
            }
        }
        return null;
    }

    private static String stripComments(String source) {
        return LINE_COMMENT.matcher(BLOCK_COMMENT.matcher(source).replaceAll("")).replaceAll("");
    }

    private static List<Path> servicesUnder(Path root) {
        try (Stream<Path> dirs = Files.list(root.resolve("services"))) {
            return dirs.filter(Files::isDirectory)
                    .filter(p -> Files.isDirectory(p.resolve("src/main/java")))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Walks up from the module directory to the directory holding {@code settings.gradle}. */
    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.exists(candidate.resolve("settings.gradle"))
                    && Files.isDirectory(candidate.resolve("services"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException(
                "could not locate the repository root (settings.gradle + services/) above "
                        + Path.of("").toAbsolutePath());
    }

    private static List<Path> javaSourcesUnder(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .filter(p -> !p.toString().replace('\\', '/').contains("/build/"))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
