package com.gme.pay.payment.client.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gme.pay.http.HttpClientTimeoutAutoConfiguration;
import com.gme.pay.http.HttpClientTimeoutProperties;
import com.gme.pay.payment.domain.SchemeTimeoutException;
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
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.web.client.RestClient;

/**
 * T3-11 defect 1, on the service where it costs money: <b>a money-path client with an unresponsive
 * peer times out and lands in the UNKNOWN/probe path rather than retrying an irreversible submit.</b>
 *
 * <p>The peer here is a real socket that accepts and then answers nothing — the specific failure
 * {@code RUNBOOK_LOAD_AND_CAPACITY.md} §4.1 #7 describes, and the one a closed port would not
 * reproduce. Before this fix, every client built from Boot's shared {@code RestClient.Builder} would
 * block on that socket until the OS gave up.
 */
class InternalHttpTimeoutTest {

    private ServerSocket blackHole;
    private Thread acceptor;
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final CountDownLatch accepted = new CountDownLatch(1);
    /** Counts requests the fake scheme received — the assertion that nothing was resubmitted. */
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
                    Thread.sleep(30_000);   // accept, take the request, answer nothing
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

    private String baseUrl() {
        return "http://127.0.0.1:" + blackHole.getLocalPort();
    }

    @Test
    @DisplayName("a hung scheme becomes SchemeTimeoutException — the UNKNOWN path — and is NOT resubmitted")
    void hungSchemeSubmitBecomesTimeoutAndIsNotRetried() throws Exception {
        RestSchemeClient client = new RestSchemeClient(
                RestClient.builder(), baseUrl(), 500L, 400L, "");

        assertThatThrownBy(() -> client.submitMpm(new com.gme.pay.payment.domain.client
                .SchemeClient.MpmSubmitRequest(
                "TXN-T311", "MERCH-1", new java.math.BigDecimal("100.00"), "KRW", "ZEROPAY", "qr")))
                // SchemeTimeoutException is the technical/ambiguous outcome. PaymentOrchestrator
                // catches exactly this and commits UNCERTAIN while LEAVING THE HOLD IN PLACE; it is
                // also what FailoverPaymentRouter routes through the ADR-016 §4 anti-double-charge
                // lookup. A SchemeDeclinedException here would be a money bug: it would release the
                // hold and mark the payment FAILED for a scheme call that may well have succeeded.
                .isInstanceOf(SchemeTimeoutException.class);

        assertThat(accepted.await(2, TimeUnit.SECONDS))
                .as("the peer must have ACCEPTED — otherwise this proves only the connect timeout")
                .isTrue();
        assertThat(requestsReceived.get())
                .as("an irreversible submit must be sent EXACTLY ONCE; a timeout must never be "
                        + "turned into an automatic retry (ADR-016 §4)")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("internal (non-scheme) clients are bounded too, via the platform customizer")
    void internalClientsAreBoundedByThePlatformCustomizer() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(HttpClientTimeoutAutoConfiguration.class))
                // The values payment-executor's application.properties really ships.
                .withPropertyValues("gmepay.http.client.connect-timeout=2s",
                        "gmepay.http.client.read-timeout=5s")
                .run(context -> {
                    RestClient.Builder builder = RestClient.builder();
                    context.getBean(RestClientCustomizer.class).customize(builder);
                    // RestTransactionClient takes the shared builder and adds nothing of its own,
                    // which is exactly why it used to be unbounded.
                    RestTransactionClient txnClient = new RestTransactionClient(builder, baseUrl());

                    long start = System.nanoTime();
                    assertThatThrownBy(() -> txnClient.commitStatus("TXN-T311",
                            new com.gme.pay.payment.domain.client.TransactionClient.StatusPatch(
                                    com.gme.pay.payment.domain.PaymentStatus.APPROVED,
                                    null, null, null, null)))
                            .isNotNull();
                    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

                    assertThat(elapsedMs)
                            .as("must abort on the 5s read timeout rather than hold the worker thread")
                            .isLessThan(15_000L);
                });
    }

    @Test
    @DisplayName("the shipped properties keep every internal hop inside the scheme leg's own budget")
    void shippedTimeoutsNestInsideTheSchemeBudget() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new PropertiesPropertySource("shipped",
                PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"))));

        Duration internalRead = Duration.parse(
                "PT" + environment.getRequiredProperty("gmepay.http.client.read-timeout")
                        .replace("s", "S"));
        long schemeReadMs = Long.parseLong(
                environment.getRequiredProperty("gmepay.scheme.read-timeout-millis"));

        // The nesting rule, asserted rather than merely written down in a comment: no internal
        // dependency may hold a payment longer than the scheme leg is allowed to. If someone raises
        // the internal budget "just for one slow report", this fails.
        assertThat(internalRead.toMillis())
                .as("internal read budget must not exceed the scheme read budget")
                .isLessThanOrEqualTo(schemeReadMs);

        // And it must be TIGHTER than the fleet-wide floor, or setting it achieved nothing.
        assertThat(internalRead)
                .isLessThan(new HttpClientTimeoutProperties().getReadTimeout());
    }
}
