package com.gme.pay.events.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fleet-wide guard: <b>a hand-built {@code ConcurrentKafkaListenerContainerFactory} must set its own
 * concurrency.</b>
 *
 * <h2>The defect this exists to prevent</h2>
 *
 * <p>Spring Boot binds {@code spring.kafka.listener.concurrency} onto its <em>auto-configured</em>
 * container factory only. Every service in this fleet hand-builds its factory instead (to pin MANUAL
 * ack mode and a DLT error handler), and a hand-built factory that never calls
 * {@code setConcurrency(..)} silently runs one consumer thread <b>whatever the property says</b>.
 *
 * <p>That is worse than a bad default. The property resolves, it appears in {@code /actuator/env}, an
 * operator raising it sees a value change and no behaviour change, and the Helm ABI advertises
 * {@code SPRING_KAFKA_LISTENER_CONCURRENCY} as a lever that does nothing on three quarters of the
 * fleet. T3-11 fixed notification-webhook's factory and the same latent defect was still present in
 * three others; this guard is here so a <em>fifth</em> cannot be added without someone noticing.
 *
 * <h2>Why a source scan and not a Spring test</h2>
 *
 * <p>Each factory lives in its own service module, and no module can see another's classes. A guard
 * that only covered the module it lives in would have to be copied into every service — which is
 * exactly the copying that let three factories drift. This test reads the fleet's Java sources
 * instead, from the one module every Kafka-using service already depends on.
 *
 * <p>The check is deliberately crude (does the file that constructs a factory also call
 * {@code setConcurrency}?). It cannot prove the value is bound to the right property — that is what
 * each service's own {@code *KafkaConcurrencyTest} does, by reading the concurrency back off a factory
 * built from a real property source. This one proves nobody skipped writing that wiring at all.
 */
class KafkaListenerConcurrencyWiringGuardTest {

    /** The construction this guard cares about. */
    private static final String FACTORY_CONSTRUCTION = "new ConcurrentKafkaListenerContainerFactory";

    /** The call that makes the property readable. */
    private static final String CONCURRENCY_CALL = "setConcurrency(";

    /**
     * Factories known to still ignore the property, with the reason they are not fixed here.
     *
     * <p>This is a shrinking baseline, not an exemption list: the test below fails BOTH when a file
     * outside it ignores the property AND when a file inside it has been fixed without being removed.
     * An entry can therefore only ever be deleted, never quietly accumulate.
     *
     * <p><b>It is now EMPTY, and that is the point.</b> The last entry —
     * {@code ops-partner-bff}'s {@code OpsAlertKafkaConsumerConfig} — was deleted together with its
     * fix. A guard that keeps an allowlist for the one file it exists to protect is a guard that
     * excuses the defect instead of closing it, so the entry and the defect had to go in one change.
     * Nothing may be added back here: a new offender is a fix to write, not a line to append.
     */
    private static final Set<String> KNOWN_UNFIXED = Set.of();

    @Test
    @DisplayName("every hand-built Kafka listener container factory in the fleet sets its concurrency")
    void everyHandBuiltFactorySetsConcurrency() {
        Path root = repositoryRoot();
        List<String> factories = new ArrayList<>();
        List<String> ignoringTheProperty = new ArrayList<>();

        for (Path file : javaSourcesUnder(root.resolve("services"))) {
            String source = read(file);
            if (!source.contains(FACTORY_CONSTRUCTION)) {
                continue;
            }
            String relative = relativise(root, file);
            factories.add(relative);
            if (!source.contains(CONCURRENCY_CALL)) {
                ignoringTheProperty.add(relative);
            }
        }

        assertTrue(factories.size() >= 4,
                "expected to find the fleet's Kafka consumer configurations, found " + factories
                        + " — has the scan root or the class name moved?");

        Set<String> unexpected = new LinkedHashSet<>(ignoringTheProperty);
        unexpected.removeAll(KNOWN_UNFIXED);
        if (!unexpected.isEmpty()) {
            fail("""
                    These hand-built Kafka listener container factories never call setConcurrency(..), \
                    so spring.kafka.listener.concurrency is UNREADABLE for them: they run one consumer \
                    thread whatever an operator sets, and the property looks like a working lever. \
                    Add `@Value("${spring.kafka.listener.concurrency:3}") int concurrency` to the \
                    @Bean method and `factory.setConcurrency(Math.max(1, concurrency))` before the \
                    return, then add a *KafkaConcurrencyTest that reads the value back off the built \
                    factory. Offenders: """ + unexpected);
        }

        Set<String> staleBaseline = new LinkedHashSet<>(KNOWN_UNFIXED);
        staleBaseline.removeAll(ignoringTheProperty);
        assertTrue(staleBaseline.isEmpty(),
                "KNOWN_UNFIXED in " + getClass().getSimpleName() + " is stale: " + staleBaseline
                        + " now sets its concurrency. Delete the entry (and add that service's own "
                        + "*KafkaConcurrencyTest) so the baseline keeps shrinking.");
    }

    @Test
    @DisplayName("all four fixed factories are covered by a service-level concurrency test")
    void eachFixedFactoryHasItsOwnBehaviouralTest() {
        Path root = repositoryRoot();
        // The source scan above cannot tell setConcurrency(3) from setConcurrency(configuredValue).
        // Only a test that builds the factory from a property source can, so require one to exist
        // beside each fixed factory — a fix without that test is a fix nothing pins.
        List<String> expected = List.of(
                "services/notification-webhook/src/test/java/com/gme/pay/notify/consumer/"
                        + "WebhookKafkaConcurrencyTest.java",
                "services/revenue-ledger/src/test/java/com/gme/pay/ledger/consumer/"
                        + "RevenueLedgerKafkaConcurrencyTest.java",
                "services/prefunding/src/test/java/com/gme/pay/prefunding/consumer/"
                        + "PrefundingKafkaConcurrencyTest.java",
                "services/ops-partner-bff/src/test/java/com/gme/pay/bff/alert/"
                        + "OpsAlertKafkaConcurrencyTest.java");
        List<String> missing = expected.stream().filter(p -> !Files.exists(root.resolve(p))).toList();
        assertEquals(List.of(), missing,
                "a Kafka consumer factory was fixed without the test that proves the property is "
                        + "actually read back: " + missing);
    }

    @Test
    @DisplayName("every consumer's default concurrency equals the broker's KAFKA_NUM_PARTITIONS")
    void defaultConcurrencyMatchesTheBrokersPartitionCount() {
        Path root = repositoryRoot();
        int partitions = composePartitionCount(root);

        // Kafka assigns WHOLE PARTITIONS to consumers, so the two numbers are not independent knobs:
        // concurrency above the partition count leaves threads permanently idle (and hides the fact
        // that adding replicas achieves nothing), while concurrency below it leaves partitions
        // unconsumed by this replica. Reading the partition count from compose rather than restating
        // it means re-partitioning the broker without re-aligning the consumers fails HERE, instead of
        // becoming a silently under- or over-threaded fleet nobody re-checks.
        List<String> misaligned = new ArrayList<>();
        for (Path file : javaSourcesUnder(root.resolve("services"))) {
            String source = read(file);
            if (!source.contains(FACTORY_CONSTRUCTION)) {
                continue;
            }
            Matcher matcher = Pattern
                    .compile("\\$\\{spring\\.kafka\\.listener\\.concurrency:(\\d+)}")
                    .matcher(source);
            if (!matcher.find()) {
                // Covered by the test above, which reports it with the right message.
                continue;
            }
            int declared = Integer.parseInt(matcher.group(1));
            if (declared != partitions) {
                misaligned.add(relativise(root, file) + " defaults to " + declared);
            }
        }

        assertEquals(List.of(), misaligned,
                "these consumers default to a concurrency that does not match docker-compose.yml's "
                        + "KAFKA_NUM_PARTITIONS=" + partitions + ", so threads sit idle or partitions "
                        + "go unread: " + misaligned);
    }

    /**
     * The broker's default partition count for auto-created topics. Compose sets
     * {@code KAFKA_AUTO_CREATE_TOPICS_ENABLE: true}, so this value — not any explicit topic
     * declaration — is what the {@code gmepay.*} topics are actually created with.
     */
    private static int composePartitionCount(Path root) {
        String compose = read(root.resolve("docker-compose.yml"));
        Matcher matcher = Pattern.compile("KAFKA_NUM_PARTITIONS:\\s*\"?(\\d+)\"?").matcher(compose);
        assertTrue(matcher.find(),
                "docker-compose.yml no longer sets KAFKA_NUM_PARTITIONS. Unset means the BROKER "
                        + "DEFAULT OF 1, under which every added consumer thread and every added "
                        + "replica is idle — the T3-11 defect 4 finding.");
        return Integer.parseInt(matcher.group(1));
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
                    // build/ holds generated + copied sources; scanning it would double-count.
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

    private static String relativise(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }
}
