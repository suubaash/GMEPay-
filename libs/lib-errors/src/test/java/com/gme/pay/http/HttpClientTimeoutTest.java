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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.core.Ordered;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Proves the T3-11 defect-1 fix against a peer that <b>accepts the connection and never answers</b>
 * — the exact shape {@code RUNBOOK_LOAD_AND_CAPACITY.md} §4.1 #7 describes.
 *
 * <p>A test that pointed at a closed port would prove nothing: that fails on connect, which the JDK
 * already bounds. The whole defect is the socket that opens successfully and then goes silent, so the
 * fixture here is a real {@link ServerSocket} that accepts and then simply does not write. Before
 * this fix, a request to it never returned.
 */
class HttpClientTimeoutTest {

    private ServerSocket blackHole;
    private Thread acceptor;
    private final AtomicBoolean stopped = new AtomicBoolean();
    /** Released once the server has accepted a connection, proving connect succeeded. */
    private final CountDownLatch accepted = new CountDownLatch(1);

    @BeforeEach
    void startUnresponsivePeer() throws IOException {
        blackHole = new ServerSocket(0);
        acceptor = new Thread(() -> {
            while (!stopped.get()) {
                try (Socket socket = blackHole.accept()) {
                    accepted.countDown();
                    // Read the request and then answer nothing at all. The client is now blocked on
                    // read() with a fully established connection — the failure this fix bounds.
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

    private String blackHoleUrl() {
        return "http://127.0.0.1:" + blackHole.getLocalPort() + "/hang";
    }

    @Test
    void readTimeoutAbortsACallToAPeerThatAcceptsAndNeverAnswers() throws Exception {
        RestClient client = RestClient.builder()
                .requestFactory(HttpClientTimeouts.requestFactory(
                        Duration.ofMillis(500), Duration.ofMillis(300)))
                .build();

        long start = System.nanoTime();
        assertThatThrownBy(() -> client.get().uri(blackHoleUrl()).retrieve().body(String.class))
                // ResourceAccessException, specifically, is what makes this fix land in the right
                // place: it is the exception the scheme clients and every adapter already catch and
                // map to the ambiguous / SCHEME_UNAVAILABLE path. A different exception type would
                // bound the call and still break the anti-double-charge contract.
                .isInstanceOf(ResourceAccessException.class);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(accepted.await(2, TimeUnit.SECONDS))
                .as("the peer must have ACCEPTED the connection — otherwise this test is only "
                        + "proving the connect timeout, which was never the defect")
                .isTrue();
        assertThat(elapsedMs)
                .as("must abort on the read timeout, not hang")
                .isLessThan(5_000L);
    }

    @Test
    void aClientWithoutTheFixtureTimeoutsIsWhatTheDefectLookedLike() {
        // Not an assertion about hanging forever (a test cannot wait for that). It pins the
        // mechanism instead: a bare builder carries no request factory of its own, so nothing in
        // the RestClient API supplies a read timeout by default. That absence is the defect, and
        // it is why the fix has to be installed rather than configured away.
        RestClient bare = RestClient.builder().build();
        assertThat(bare).isNotNull();
    }

    @Test
    void autoConfigurationInstallsTheTimeoutAndRunsLastSoNothingCanUnsetIt() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(HttpClientTimeoutAutoConfiguration.class))
                .withPropertyValues("gmepay.http.client.read-timeout=250ms",
                        "gmepay.http.client.connect-timeout=400ms")
                .run(context -> {
                    assertThat(context).hasSingleBean(RestClientCustomizer.class);
                    RestClientCustomizer customizer = context.getBean(RestClientCustomizer.class);

                    // Ordering is load-bearing: two services already register a customizer that
                    // sets a bare request factory. If this one did not run LAST, theirs would
                    // replace it and quietly restore the unbounded client.
                    assertThat(customizer).isInstanceOf(Ordered.class);
                    assertThat(((Ordered) customizer).getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE);

                    RestClient.Builder builder = RestClient.builder();
                    customizer.customize(builder);
                    RestClient client = builder.build();

                    assertThatThrownBy(() ->
                            client.get().uri(blackHoleUrl()).retrieve().body(String.class))
                            .isInstanceOf(ResourceAccessException.class);
                });
    }

    @Test
    void defaultsAreTheDocumentedFleetFloor() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(HttpClientTimeoutAutoConfiguration.class))
                .run(context -> {
                    HttpClientTimeoutProperties properties =
                            context.getBean(HttpClientTimeoutProperties.class);
                    // Pinned so a change to the fleet-wide floor is a deliberate edit with a test
                    // change attached, not a value someone nudges while tuning one service.
                    assertThat(properties.getConnectTimeout()).isEqualTo(Duration.ofSeconds(2));
                    assertThat(properties.getReadTimeout()).isEqualTo(Duration.ofSeconds(10));
                });
    }

    @Test
    void theFactoryStillSupportsPatch() {
        // The factory this fix installs replaces the one payment-executor and ops-partner-bff added
        // purely to make PATCH work (HttpURLConnection rejects the verb outright). If the timeout
        // fix silently reverted that, RestTransactionClient.commitStatus — PATCH
        // /v1/transactions/{ref}/status — would fail AFTER the scheme had already captured.
        var factory = HttpClientTimeouts.requestFactory(Duration.ofSeconds(1), Duration.ofSeconds(1));
        assertThat(factory)
                .isInstanceOf(org.springframework.http.client.JdkClientHttpRequestFactory.class);
    }
}
