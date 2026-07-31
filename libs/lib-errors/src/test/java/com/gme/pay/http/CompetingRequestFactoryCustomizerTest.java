package com.gme.pay.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * <b>Two {@code RestClientCustomizer} beans that both call {@code builder.requestFactory(..)} are a race,
 * and the loser is discarded silently</b> — the failure mode is not an error but an unbounded money-path
 * client that reads as configured (gap <b>T3-11</b> defect 1).
 *
 * <h2>Which one actually wins, and why that is not good enough</h2>
 *
 * <p>{@link HttpClientTimeoutAutoConfiguration}'s customizer declares
 * {@link org.springframework.core.Ordered#LOWEST_PRECEDENCE} so it runs last and its factory wins. The
 * problem is that a competing bean which declares <b>no</b> order — a bare
 * {@code return builder -> builder.requestFactory(new JdkClientHttpRequestFactory())} lambda, which is
 * exactly the shape both services wrote — <b>also</b> sorts as {@code LOWEST_PRECEDENCE}, because that is
 * the value {@code AnnotationAwareOrderComparator} assigns to an unordered element.
 *
 * <p>So the two are <b>tied</b>, and the tie is broken by nothing more than bean-registration order:
 * {@code Ordered} sorting is stable, and auto-configuration bean definitions are registered <em>after</em>
 * user {@code @Configuration} ones, so the timeout customizer happens to come later in the stream and
 * happens to win. {@code LOWEST_PRECEDENCE} is the maximum value in the framework, so <b>no ordering
 * change can make this deterministic</b> while the competing bean exists — the only fix is that the
 * competing bean does not exist.
 *
 * <p>{@link #theTimeoutFloorWinsWhenAnUnorderedCompetitorIsPresent()} pins the current outcome against a
 * real socket, so if the tie ever resolves the other way the platform finds out from a test rather than
 * from a hung payment. {@link #tiedOrderIsWhyThisCannotBeFixedByOrderingAlone()} pins the reason.
 * {@link #noServiceRegistersAnUnorderedRequestFactoryCustomizer()} stops new instances appearing.
 *
 * <h2>The finding this documents</h2>
 *
 * <p>payment-executor's twin was <b>deleted</b> when the floor shipped, so there is one mechanism there
 * rather than two. {@code ops-partner-bff}'s {@code ClientBeans.patchCapableRequestFactoryCustomizer}
 * still exists and is still the second bean in the tie. It is redundant as well as hazardous: the factory
 * it installs is a bare {@code JdkClientHttpRequestFactory} whose sole purpose is PATCH support, and
 * {@link HttpClientTimeouts#requestFactory} installs a {@code JdkClientHttpRequestFactory} too — so
 * deleting the competitor keeps PATCH working <em>and</em> gains the timeouts. That file was owned by
 * another agent during this change and is deliberately untouched; it is recorded in the baseline below
 * with the one-line fix.
 */
class CompetingRequestFactoryCustomizerTest {

    // ------------------------------------------------------------------------
    // An unresponsive peer, so "is the timeout applied?" is answered by behaviour
    // ------------------------------------------------------------------------

    private ServerSocket blackHole;
    private Thread acceptor;
    private final AtomicBoolean stopped = new AtomicBoolean();

    @BeforeEach
    void startUnresponsivePeer() throws IOException {
        blackHole = new ServerSocket(0);
        acceptor = new Thread(() -> {
            while (!stopped.get()) {
                try (Socket socket = blackHole.accept()) {
                    InputStream in = socket.getInputStream();
                    //noinspection ResultOfMethodCallIgnored
                    in.read();
                    Thread.sleep(30_000);
                } catch (Exception e) {
                    return;
                }
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();
    }

    @AfterEach
    void stopUnresponsivePeer() throws IOException {
        stopped.set(true);
        blackHole.close();
        acceptor.interrupt();
    }

    /** The exact shape ops-partner-bff (and formerly payment-executor) registers. */
    @Configuration
    static class CompetingPatchCustomizerConfig {
        @Bean
        RestClientCustomizer patchCapableRequestFactoryCustomizer() {
            return builder -> builder.requestFactory(new JdkClientHttpRequestFactory());
        }
    }

    @Test
    @DisplayName("the timeout floor wins over an unordered competing request-factory customizer")
    void theTimeoutFloorWinsWhenAnUnorderedCompetitorIsPresent() {
        new ApplicationContextRunner()
                // The competitor is registered as a USER configuration and the floor as an
                // AUTO-configuration, which is the real relative registration order in every service
                // that has both. Reproducing that order is the point: it is the only thing deciding the
                // outcome.
                .withUserConfiguration(CompetingPatchCustomizerConfig.class)
                .withConfiguration(AutoConfigurations.of(HttpClientTimeoutAutoConfiguration.class))
                .withPropertyValues("gmepay.http.client.connect-timeout=400ms",
                        "gmepay.http.client.read-timeout=300ms")
                .run(context -> {
                    List<RestClientCustomizer> customizers = new ArrayList<>(
                            context.getBeansOfType(RestClientCustomizer.class).values());
                    assertThat(customizers).hasSize(2);
                    // Boot applies these through ObjectProvider.orderedStream(); this is the same
                    // comparator, so the order asserted here is the order production uses.
                    customizers.sort(AnnotationAwareOrderComparator.INSTANCE);

                    RestClient.Builder builder = RestClient.builder();
                    customizers.forEach(c -> c.customize(builder));
                    RestClient client = builder.build();

                    long start = System.nanoTime();
                    // If the bare factory had won, this call would not return: a
                    // JdkClientHttpRequestFactory with no read timeout is exactly the unbounded client
                    // this gap is about. The assertion is therefore that the TIMEOUT survived the race,
                    // proved by the socket rather than by inspecting a bean.
                    try {
                        client.get().uri("http://127.0.0.1:" + blackHole.getLocalPort() + "/hang")
                                .retrieve().body(String.class);
                        fail("expected the read timeout to fire");
                    } catch (ResourceAccessException expected) {
                        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(5_000L);
                    }
                });
    }

    @Test
    @DisplayName("the two customizers sort as TIED, which is why ordering alone cannot fix this")
    void tiedOrderIsWhyThisCannotBeFixedByOrderingAlone() {
        RestClientCustomizer unordered = builder ->
                builder.requestFactory(new JdkClientHttpRequestFactory());
        RestClientCustomizer floor = new HttpClientTimeoutAutoConfiguration
                .TimeoutRestClientCustomizer(new HttpClientTimeoutProperties());

        // Sorting is how the tie is demonstrated, because the comparator's own getOrder(..) is
        // protected. Spring's sort is STABLE, so if the two compared unequal the order would be the same
        // whichever way round the input went. It is not: each input order survives its own sort, which
        // is only possible when the comparator considers them equal.
        List<RestClientCustomizer> floorLast = new ArrayList<>(List.of(unordered, floor));
        List<RestClientCustomizer> floorFirst = new ArrayList<>(List.of(floor, unordered));
        floorLast.sort(AnnotationAwareOrderComparator.INSTANCE);
        floorFirst.sort(AnnotationAwareOrderComparator.INSTANCE);

        // Both LOWEST_PRECEDENCE. The floor cannot out-rank the competitor because there is no value
        // beyond LOWEST_PRECEDENCE to move to — so "the floor runs last" is true today only because
        // stable sorting preserves registration order and auto-configurations register last. That is a
        // property of Boot's bootstrap sequence, not a guarantee anyone wrote down, which is precisely
        // why the competing bean has to be deleted rather than re-ordered.
        assertThat(floorLast).as("input order survived: nothing ranked either above the other")
                .containsExactly(unordered, floor);
        assertThat(floorFirst)
                .as("the REVERSED input order survived too — the pair is TIED, so which factory wins is "
                        + "decided by bean-registration order alone and no @Order can fix it")
                .containsExactly(floor, unordered);
    }

    // ------------------------------------------------------------------------
    // Fleet guard: no NEW competing customizer may appear
    // ------------------------------------------------------------------------

    /**
     * A customizer bean that installs a request factory and declares no order, matched on source.
     * Restricted to {@code RestClientCustomizer} bodies so an ordinary client calling
     * {@code .requestFactory(..)} on its own builder — which is correct and encouraged — is not flagged.
     */
    private static final Pattern CUSTOMIZER_INSTALLING_A_FACTORY = Pattern.compile(
            "RestClientCustomizer[^;{]*\\{?[^;]*?\\.requestFactory\\(", Pattern.DOTALL);

    private static final Pattern DECLARES_AN_ORDER =
            Pattern.compile("@Order|implements\\s+Ordered|Ordered\\.");

    /**
     * The one remaining competitor in the fleet, with its fix. A shrinking baseline: this test fails both
     * when a new one appears and when this one is removed without being delisted.
     *
     * <p>Fix: delete the {@code patchCapableRequestFactoryCustomizer} bean from
     * {@code services/ops-partner-bff/src/main/java/com/gme/pay/bff/client/rest/ClientBeans.java}
     * (leaving the class empty is fine; so is deleting the class if nothing else lives in it).
     * {@link HttpClientTimeouts#requestFactory} already installs a {@code JdkClientHttpRequestFactory},
     * so PATCH keeps working and the BFF's twelve upstream reads gain a read timeout they never had.
     */
    private static final Set<String> KNOWN_COMPETING_CUSTOMIZERS = Set.of(
            "services/ops-partner-bff/src/main/java/com/gme/pay/bff/client/rest/ClientBeans.java");

    @Test
    @DisplayName("no service registers an unordered RestClientCustomizer that installs a request factory")
    void noServiceRegistersAnUnorderedRequestFactoryCustomizer() {
        Path root = OutboundClientScanner.repositoryRoot();
        List<String> competing = new ArrayList<>();

        for (Path file : javaSourcesUnder(root.resolve("services"))) {
            String source = stripComments(read(file));
            if (!CUSTOMIZER_INSTALLING_A_FACTORY.matcher(source).find()) {
                continue;
            }
            if (DECLARES_AN_ORDER.matcher(source).find()) {
                continue;
            }
            competing.add(root.relativize(file).toString().replace('\\', '/'));
        }

        Set<String> unexpected = new LinkedHashSet<>(competing);
        unexpected.removeAll(KNOWN_COMPETING_CUSTOMIZERS);
        if (!unexpected.isEmpty()) {
            fail("""
                    These register a RestClientCustomizer that calls builder.requestFactory(..) and \
                    declare no order, so they TIE with lib-errors' timeout floor at \
                    LOWEST_PRECEDENCE. The winner is then decided by bean-registration order alone, \
                    and if the bare factory wins the client is silently unbounded while every \
                    property still resolves. Delete the bean: HttpClientTimeouts.requestFactory(..) \
                    already installs a PATCH-capable JdkClientHttpRequestFactory, so there is nothing \
                    left for it to add. Offenders: """ + unexpected);
        }

        Set<String> stale = new LinkedHashSet<>(KNOWN_COMPETING_CUSTOMIZERS);
        stale.removeAll(competing);
        assertTrue(stale.isEmpty(),
                "KNOWN_COMPETING_CUSTOMIZERS in " + getClass().getSimpleName() + " is stale: " + stale
                        + " no longer competes. Delete the entry so the baseline keeps shrinking.");
    }

    // ------------------------------------------------------------------------

    private static String stripComments(String source) {
        return Pattern.compile("//[^\\n]*").matcher(
                        Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL).matcher(source).replaceAll(""))
                .replaceAll("");
    }

    private static List<Path> javaSourcesUnder(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .filter(p -> !p.toString().replace('\\', '/').contains("/build/"))
                    .filter(p -> !p.toString().replace('\\', '/').contains("/src/test/"))
                    .sorted()
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
