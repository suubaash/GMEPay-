package com.gme.pay.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.core.Ordered;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * The reactive twin of {@link HttpClientTimeoutTest}: proves
 * {@link WebClientTimeoutAutoConfiguration} bounds a {@code WebClient} against a peer that
 * <b>accepts the connection and never answers</b> (gap <b>T3-11</b> defect 1).
 *
 * <p>A test pointed at a closed port would prove nothing — that fails on connect, which Netty already
 * bounds. The whole defect is the socket that opens successfully and then goes silent, so the fixture is
 * a real {@link ServerSocket} that accepts and does not write. Before this auto-configuration existed,
 * a request to it from any {@code WebClient} in api-gateway never terminated: no
 * {@code RestClientCustomizer} has ever applied to a {@code WebClient}, so the "fleet-wide" floor did
 * not reach the reactive stack at all.
 */
class WebClientTimeoutTest {

    private ServerSocket blackHole;
    private Thread acceptor;
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final CountDownLatch accepted = new CountDownLatch(1);
    private final AtomicInteger requestsReceived = new AtomicInteger();

    @BeforeEach
    void startUnresponsivePeer() throws IOException {
        blackHole = new ServerSocket(0);
        acceptor = new Thread(() -> {
            while (!stopped.get()) {
                try (Socket socket = blackHole.accept()) {
                    accepted.countDown();
                    InputStream in = socket.getInputStream();
                    if (in.read() >= 0) {
                        requestsReceived.incrementAndGet();
                    }
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

    private String blackHoleUrl() {
        return "http://127.0.0.1:" + blackHole.getLocalPort() + "/hang";
    }

    @Test
    @DisplayName("the response timeout aborts a call to a peer that accepts and never answers")
    void responseTimeoutAbortsACallToAnUnresponsivePeer() throws Exception {
        WebClient client = WebClient.builder()
                .clientConnector(new org.springframework.http.client.reactive
                        .ReactorClientHttpConnector(WebClientTimeoutAutoConfiguration.httpClient(
                        Duration.ofMillis(500), Duration.ofMillis(300))))
                .build();

        long start = System.nanoTime();
        assertThatThrownBy(() -> client.get().uri(blackHoleUrl())
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(10)))
                .as("must terminate with an error rather than hang; the reactive stack's equivalent of "
                        + "ResourceAccessException is a WebClientRequestException wrapping Netty's "
                        + "timeout")
                .isNotNull();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(accepted.await(2, TimeUnit.SECONDS))
                .as("the peer must have ACCEPTED the connection — otherwise this test proves only the "
                        + "connect timeout, which was never the defect")
                .isTrue();
        assertThat(elapsedMs)
                .as("must abort on the RESPONSE timeout (300ms), not on block()'s 10s ceiling")
                .isLessThan(5_000L);
    }

    @Test
    @DisplayName("a timed-out reactive call is sent exactly once")
    void aTimedOutReactiveCallIsNotRetried() {
        WebClient client = WebClient.builder()
                .clientConnector(new org.springframework.http.client.reactive
                        .ReactorClientHttpConnector(WebClientTimeoutAutoConfiguration.httpClient(
                        Duration.ofMillis(500), Duration.ofMillis(300))))
                .build();

        try {
            client.get().uri(blackHoleUrl()).retrieve().bodyToMono(String.class)
                    .block(Duration.ofSeconds(10));
        } catch (RuntimeException expected) {
            // The outcome under test is the request COUNT, not the exception.
        }

        // ADR-016 §4's discipline holds on the reactive stack too, and it needs stating because Reactor
        // makes a retry a one-word change (`.retry()`). None of api-gateway's three hops is an
        // irreversible submit — they are an RBAC resolve, a credential-status read and an IP-allowlist
        // read — but a timeout must still not multiply load onto an upstream that has just shown it
        // cannot answer, and this pins that no retry has been introduced anywhere in the transport.
        assertThat(requestsReceived.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("the auto-configuration installs the connector and runs last")
    void autoConfigurationInstallsTheConnectorAndRunsLast() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(WebClientTimeoutAutoConfiguration.class))
                .withPropertyValues("gmepay.http.client.read-timeout=250ms",
                        "gmepay.http.client.connect-timeout=400ms")
                .run(context -> {
                    assertThat(context).hasSingleBean(WebClientCustomizer.class);
                    WebClientCustomizer customizer = context.getBean(WebClientCustomizer.class);

                    assertThat(customizer).isInstanceOf(Ordered.class);
                    assertThat(((Ordered) customizer).getOrder())
                            .isEqualTo(Ordered.LOWEST_PRECEDENCE);

                    // The bound has to survive the same path production takes: the customizer is
                    // applied to a builder, and the client built from it must be bounded. Asserting the
                    // bean exists would be the mistake the whole second pass was about.
                    WebClient.Builder builder = WebClient.builder();
                    customizer.customize(builder);
                    WebClient client = builder.build();

                    long start = System.nanoTime();
                    assertThatThrownBy(() -> client.get().uri(blackHoleUrl())
                            .retrieve()
                            .bodyToMono(String.class)
                            .block(Duration.ofSeconds(10)))
                            .isNotNull();
                    assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(5_000L);
                });
    }

    @Test
    @DisplayName("both auto-configurations read the same two properties")
    void bothStacksShareOneSetOfNumbers() {
        // Two floors with two independent property namespaces is two floors that drift. The reactive
        // twin deliberately reuses HttpClientTimeoutProperties, so "the fleet's outbound budget" stays
        // one reviewable pair of numbers rather than a per-stack accident.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        HttpClientTimeoutAutoConfiguration.class,
                        WebClientTimeoutAutoConfiguration.class))
                .withPropertyValues("gmepay.http.client.read-timeout=1234ms")
                .run(context -> {
                    assertThat(context).hasSingleBean(HttpClientTimeoutProperties.class);
                    assertThat(context.getBean(HttpClientTimeoutProperties.class).getReadTimeout())
                            .isEqualTo(Duration.ofMillis(1234));
                    assertThat(context).hasSingleBean(WebClientCustomizer.class);
                    assertThat(context).hasSingleBean(
                            org.springframework.boot.web.client.RestClientCustomizer.class);
                });
    }

    @Test
    @DisplayName("the reactive floor can be disabled by the same switch as the blocking one")
    void theSharedEnabledSwitchGovernsBothStacks() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(WebClientTimeoutAutoConfiguration.class))
                .withPropertyValues("gmepay.http.client.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(WebClientCustomizer.class));
    }
}
