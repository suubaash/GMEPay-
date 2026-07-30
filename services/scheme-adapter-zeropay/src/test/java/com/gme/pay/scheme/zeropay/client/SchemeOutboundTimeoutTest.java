package com.gme.pay.scheme.zeropay.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import com.gme.pay.http.HttpClientTimeoutAutoConfiguration;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.Properties;
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
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.web.client.RestClient;

/**
 * T3-11 defect 1, on the adapter's outbound leg: <b>a hung scheme times out and lands in the
 * ambiguous / SCHEME_UNAVAILABLE path, and the irreversible call is not repeated.</b>
 *
 * <p>The peer is a real socket that accepts and then answers nothing — the specific failure
 * {@code RUNBOOK_LOAD_AND_CAPACITY.md} §4.1 #7 describes and the one a closed port cannot reproduce.
 * Before this fix the adapter would block on that socket indefinitely, outliving payment-executor's
 * own 5 s budget and so converting a slow scheme into an UNCERTAIN payment a human has to resolve.
 */
class SchemeOutboundTimeoutTest {

    private ServerSocket blackHole;
    private Thread acceptor;
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final CountDownLatch accepted = new CountDownLatch(1);
    private final AtomicInteger requestsReceived = new AtomicInteger();

    @BeforeEach
    void startUnresponsiveScheme() throws IOException {
        blackHole = new ServerSocket(0);
        acceptor = new Thread(() -> {
            while (!stopped.get()) {
                try (Socket socket = blackHole.accept()) {
                    accepted.countDown();
                    requestsReceived.incrementAndGet();
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
    void stopUnresponsiveScheme() throws IOException {
        stopped.set(true);
        blackHole.close();
        acceptor.interrupt();
    }

    @Test
    @DisplayName("a hung scheme becomes SCHEME_UNAVAILABLE — ambiguous — and the commit is sent once")
    void hungSchemeCommitIsBoundedAndNotRepeated() throws Exception {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(HttpClientTimeoutAutoConfiguration.class))
                // The values this adapter's application.properties really ships.
                .withPropertyValues("gmepay.http.client.connect-timeout=2s",
                        "gmepay.http.client.read-timeout=4s")
                .run(context -> {
                    RestClient.Builder builder = RestClient.builder();
                    context.getBean(RestClientCustomizer.class).customize(builder);
                    ZeroPaySchemeApiClient client = new ZeroPaySchemeApiClient(
                            builder, "http://127.0.0.1:" + blackHole.getLocalPort() + "/v1/scheme");

                    long start = System.nanoTime();
                    assertThatThrownBy(() -> client.commit("AUTH-1"))
                            .isInstanceOfSatisfying(ApiException.class, ex ->
                                    // SCHEME_UNAVAILABLE is the technical/unknown outcome.
                                    // payment-executor maps it to the failover + ADR-016 §4 status
                                    // probe; a VALIDATION_ERROR or MERCHANT_NOT_FOUND here would be a
                                    // money bug, because those are terminal business declines that
                                    // release the hold on a commit that may well have succeeded.
                                    assertThat(ex.errorCode()).isEqualTo(ErrorCode.SCHEME_UNAVAILABLE));
                    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

                    assertThat(accepted.await(2, TimeUnit.SECONDS))
                            .as("the peer must have ACCEPTED — otherwise this proves only the "
                                    + "connect timeout, which was never the defect")
                            .isTrue();
                    assertThat(elapsedMs)
                            .as("must abort on the ~4s read timeout, not hang")
                            .isBetween(1_000L, 15_000L);
                    assertThat(requestsReceived.get())
                            .as("an irreversible commit must be sent EXACTLY ONCE; a timeout must "
                                    + "never become an automatic retry (ADR-016 §4)")
                            .isEqualTo(1);
                });
    }

    @Test
    @DisplayName("the shipped outbound budget nests inside payment-executor's 5s hub->adapter budget")
    void shippedOutboundBudgetNestsInsideTheCallersBudget() throws IOException {
        Properties shipped = PropertiesLoaderUtils.loadProperties(
                new ClassPathResource("application.properties"));
        Duration outboundRead = Duration.parse(
                "PT" + shipped.getProperty("gmepay.http.client.read-timeout").replace("s", "S"));

        // payment-executor's gmepay.scheme.read-timeout-millis is 5000. Asserted as a number here
        // rather than read across modules, because this module must not depend on that one — but the
        // relationship is the whole point of the value, so it is pinned rather than left in a comment.
        assertThat(outboundRead)
                .as("the adapter must give up BEFORE its caller does, or payment-executor infers "
                        + "'unknown' from its own socket timeout and books UNCERTAIN")
                .isLessThan(Duration.ofMillis(5000));
        assertThat(outboundRead)
                .as("...and not so tight that a healthy scheme call is cut off")
                .isGreaterThanOrEqualTo(Duration.ofSeconds(3));
    }
}
