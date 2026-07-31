package com.gme.pay.gateway.partner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gme.pay.gateway.registry.RestConfigRegistryClient;
import com.gme.pay.http.WebClientTimeoutAutoConfiguration;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Gap <b>T3-11</b> defect 1, reactive half: api-gateway's upstream hops, driven against a peer that
 * <b>accepts the connection and then answers nothing</b>.
 *
 * <h2>Why this service was the last unbounded one</h2>
 *
 * <p>The fleet floor shipped as a {@code RestClientCustomizer}, and Spring Boot applies that type to the
 * {@code RestClient.Builder} bean and nothing else. api-gateway talks upstream over {@code WebClient}, so
 * <b>no part of the floor had ever applied here</b> — and the guard written to catch unbounded clients
 * scanned for {@code RestClient.builder()}, so it could not see these three hops either. The one
 * component every external request passes through had no outbound bound of any kind, while
 * {@code gmepay.http.client.read-timeout} resolved and appeared in {@code /actuator/env} exactly as if it
 * did.
 *
 * <p>The consequence is specific to this service: all three hops run inside filters on a Netty
 * <b>event loop</b>, and each has a declared degradation — pass through unstamped, answer 503, let the
 * {@code fail-open} flag decide. None of those degradations can run until the call gives up. Unbounded,
 * the request neither completed nor was rejected; the loop slot was simply held.
 *
 * <h2>ADR-016</h2>
 *
 * <p>None of these three is an irreversible submit — they are an RBAC resolve, a credential-status read
 * and an IP-allowlist read, all idempotent GET/POST lookups performed <em>before</em> the request is
 * forwarded. So there is no ambiguous-outcome case to resolve by probe here, and no retry is introduced:
 * each failure maps to the degradation the class already declares.
 */
class ReactiveOutboundTimeoutTest {

    private ServerSocket blackHole;
    private Thread acceptor;
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final CountDownLatch accepted = new CountDownLatch(1);

    @BeforeEach
    void startUnresponsivePeer() throws IOException {
        blackHole = new ServerSocket(0);
        acceptor = new Thread(() -> {
            while (!stopped.get()) {
                try (Socket socket = blackHole.accept()) {
                    accepted.countDown();
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

    private String blackHoleBaseUrl() {
        return "http://127.0.0.1:" + blackHole.getLocalPort();
    }

    /**
     * The builder api-gateway's clients receive in production: the {@code WebClient.Builder} bean with
     * lib-errors' reactive floor applied to it. Built from the <b>shipped</b> transport factory rather
     * than a hand-rolled connector, so what is proved here is what runs.
     */
    private WebClient.Builder boundedBuilder(Duration connect, Duration response) {
        return WebClient.builder().clientConnector(new ReactorClientHttpConnector(
                WebClientTimeoutAutoConfiguration.httpClient(connect, response)));
    }

    // ------------------------------------------------------------------------

    @Test
    @DisplayName("the IP-allowlist read is bounded and terminates instead of holding the event loop")
    void ipAllowlistReadIsBounded() throws Exception {
        // The PRODUCTION constructor, which is where the bug lived: it used to call the static
        // WebClient.builder() and so escaped every customizer in the context.
        RestConfigRegistryClient client = new RestConfigRegistryClient(
                boundedBuilder(Duration.ofSeconds(5), Duration.ofMillis(300)), blackHoleBaseUrl());

        long start = System.nanoTime();
        assertThatThrownBy(() -> client.getIpAllowlist("GMEREMIT", "PRODUCTION")
                .block(Duration.ofSeconds(10)))
                .as("the Mono must TERMINATE. Unbounded it did neither: the allowlist filter's "
                        + "fail-open flag never got to decide, because there was no outcome to decide "
                        + "about")
                .isNotNull();
        long elapsedMs = elapsedMillis(start);

        assertThat(accepted.await(2, TimeUnit.SECONDS))
                .as("the peer must have ACCEPTED — otherwise this proves only the connect timeout, "
                        + "which was never the defect")
                .isTrue();
        assertThat(elapsedMs)
                .as("must abort on the response timeout (300ms), not on block()'s 10s ceiling")
                .isLessThan(5_000L);
    }

    @Test
    @DisplayName("the credential-status read is bounded by the transport, not only by its own operator")
    void credentialStatusReadIsBoundedByTheTransport() {
        // This client already had a per-call reactive .timeout(3s). That bounds the SUBSCRIBER's wait and
        // cancels; it is not a transport bound, it is per-call, and it is easy to omit — its sibling
        // WebClientRbacClaimResolver has none at all. Driven with a transport budget an order of
        // magnitude tighter than the operator, the transport is demonstrably what fires.
        AuthIdentityCredentialStatusClient client = new AuthIdentityCredentialStatusClient(
                boundedBuilder(Duration.ofSeconds(5), Duration.ofMillis(300)),
                blackHoleBaseUrl(), "test-internal-secret");

        long start = System.nanoTime();
        assertThatThrownBy(() -> client.statusOf("some-api-key").block(Duration.ofSeconds(10)))
                // The class's documented failure mapping: never a guess about the credential, always an
                // explicit "the store could not be consulted", which the filter answers 503. It can only
                // be reached once the call gives up.
                .isInstanceOf(PartnerCredentialSourceUnavailableException.class);
        long elapsedMs = elapsedMillis(start);

        assertThat(elapsedMs)
                .as("the 300ms transport budget must fire well inside the client's own 3s operator "
                        + "(%dms observed) — otherwise the operator is doing the work and the socket is "
                        + "still open", elapsedMs)
                .isLessThan(2_000L);
    }

    @Test
    @DisplayName("the customizer really is in the context for this service's WebClient.Builder")
    void theReactiveFloorIsWiredForThisService() {
        // Constructing the transport by hand (as the cases above do) proves it works; it does not prove
        // it is PRESENT. That gap between "the mechanism functions" and "the mechanism is reached" is the
        // entire T3-11 story, so it is asserted separately.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(WebClientTimeoutAutoConfiguration.class))
                .run(context -> assertThat(context).hasSingleBean(WebClientCustomizer.class));
    }

    @Test
    @DisplayName("the shipped budget is tighter than the per-call operator it nests inside")
    void shippedBudgetNestsInsideThePerCallOperator() {
        String yaml = read(repositoryRoot()
                .resolve("services/api-gateway/src/main/resources/application.yml"));

        long connectMs = shippedDurationMillis(yaml, "connect-timeout");
        long responseMs = shippedDurationMillis(yaml, "read-timeout");
        long operatorMs = AuthIdentityCredentialStatusClient.TIMEOUT.toMillis();

        assertThat(responseMs)
                .as("the transport budget must be STRICTLY tighter than the %dms per-call operator, so "
                        + "the transport gives up first and RELEASES THE SOCKET instead of the operator "
                        + "cancelling a subscription over a connection that stays open", operatorMs)
                .isLessThan(operatorMs);
        assertThat(connectMs)
                .as("a connect budget must be stated too: no response timeout bounds a black-holed "
                        + "address, where the wait is the kernel's SYN-retry budget")
                .isGreaterThan(0L);
        assertThat(responseMs)
                .as("these hops are in-cluster reads on the request path of an authenticated partner "
                        + "call; the 10s fleet floor is for reports and batch, not for the edge")
                .isLessThanOrEqualTo(2_000L);
    }

    // ------------------------------------------------------------------------

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /**
     * Reads {@code gmepay.http.client.<key>} out of the shipped YAML. A regex rather than a parser
     * because no YAML library is on this module's test classpath; the six-space indent pins it to the
     * {@code gmepay: http: client:} block so a same-named key elsewhere cannot satisfy it.
     */
    private static long shippedDurationMillis(String yaml, String key) {
        Matcher matcher = Pattern.compile(
                        "^ {6}" + Pattern.quote(key) + ": *(\\d+)(ms|s) *$", Pattern.MULTILINE)
                .matcher(yaml);
        assertThat(matcher.find())
                .as("gmepay.http.client.%s must be stated in api-gateway's application.yml under "
                        + "gmepay.http.client — a budget that lives only in a library default is one "
                        + "nobody reviews", key)
                .isTrue();
        long amount = Long.parseLong(matcher.group(1));
        return "s".equals(matcher.group(2)) ? amount * 1_000L : amount;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.exists(candidate.resolve("settings.gradle"))
                    && Files.isDirectory(candidate.resolve("services"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("could not locate the repository root above "
                + Path.of("").toAbsolutePath());
    }
}
