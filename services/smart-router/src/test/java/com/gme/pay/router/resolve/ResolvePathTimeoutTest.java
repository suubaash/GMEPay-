package com.gme.pay.router.resolve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import com.gme.pay.router.client.RestPartnerSchemeResolver;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Gap <b>T3-11</b> defect 1, the three highest-priority clients in the fleet: smart-router's resolve
 * path. Driven against a peer that <b>accepts the connection and then answers nothing</b>.
 *
 * <h2>Why these three first</h2>
 *
 * <p>{@link RestPartnerSchemeResolver}, {@link RestPartnerSchemeRegistry} and
 * {@link RestSchemeOperatingHoursSource} all run <em>while a payment is being routed</em>. All three
 * used to build their {@code RestClient} from the <b>static</b> {@code RestClient.builder()} factory,
 * which returns a fresh builder that no {@code RestClientCustomizer} has ever touched — so
 * {@code HttpClientTimeoutAutoConfiguration}'s fleet floor never reached them and they had
 * <b>no read timeout at all</b>, while {@code gmepay.http.client.read-timeout} resolved and appeared in
 * {@code /actuator/env} exactly as if it had. A hung config-registry did not fail the resolve; it held
 * the routing thread, and the payment behind it, until the OS closed the socket.
 *
 * <h2>What this test proves that a MockRestServiceServer test cannot</h2>
 *
 * <p>Every existing wire test for these classes uses {@link org.springframework.test.web.client.MockRestServiceServer},
 * which never times out and which is bound to a <em>hand-built</em> client — so it exercises the
 * package-private test constructor and can say nothing at all about the constructor Spring actually
 * calls. The bug lived entirely in the production constructor. Every case below therefore goes through
 * the <b>production constructor</b> against a real socket.
 *
 * <h2>ADR-016 §4: why fast failure is correct here, and where the line is</h2>
 *
 * <p>Resolution is <b>pre-submit</b>. Nothing has been sent to a scheme when these calls run, so there
 * is no irreversible operation whose outcome could be unknown and nothing that a retry could
 * double-send. That is why these hops get the platform's tightest budget (500 ms) while a scheme
 * <em>submit</em> gets a deliberately looser one: the asymmetry is not about speed, it is about what a
 * timeout means. {@link #aTimedOutResolveIsSentOnceAndNeverRetried()} pins the "never retried" half,
 * because the moment a resolve is retried automatically the fan-out over partners multiplies the load
 * on the very upstream that is already failing.
 */
class ResolvePathTimeoutTest {

    /** Budget used throughout: short enough to keep the suite fast, real enough to be a read timeout. */
    private static final long CONNECT_MILLIS = 5_000;
    private static final long READ_MILLIS = 300;

    private ServerSocket blackHole;
    private Thread acceptor;
    private final AtomicBoolean stopped = new AtomicBoolean();
    /** How many requests the unresponsive peer actually received — the anti-retry assertion. */
    private final AtomicInteger requestsReceived = new AtomicInteger();

    @BeforeEach
    void startUnresponsivePeer() throws IOException {
        blackHole = new ServerSocket(0);
        acceptor = new Thread(() -> {
            while (!stopped.get()) {
                try (Socket socket = blackHole.accept()) {
                    InputStream in = socket.getInputStream();
                    // One byte is enough to know a request arrived; then answer nothing at all. The
                    // caller is now blocked on read() over a fully established connection, which is
                    // the failure the fleet floor exists to bound (a closed port would only prove the
                    // connect timeout, which the JDK already bounds).
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

    private String blackHoleBaseUrl() {
        return "http://127.0.0.1:" + blackHole.getLocalPort();
    }

    // ------------------------------------------------------------------------
    // The registry: resolution itself, so a timeout must be a definite answer.
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("a hung config-registry bounds the partner-scheme registry read and answers "
            + "SCHEME_UNAVAILABLE")
    void registryReadIsBoundedAndMapsToSchemeUnavailable() {
        RestPartnerSchemeRegistry registry = new RestPartnerSchemeRegistry(
                RestClient.builder(), blackHoleBaseUrl(), CONNECT_MILLIS, READ_MILLIS);

        long start = System.nanoTime();
        assertThatThrownBy(() -> registry.schemesForCountry("KR"))
                // A definite, retryable 503 carrying a reason — not a thread parked on read().
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.SCHEME_UNAVAILABLE);

        assertThat(elapsedMillis(start))
                .as("must abort on the READ timeout. Before this fix there was no read timeout on this "
                        + "client at all, so this call did not return")
                .isLessThan(5_000L);
        assertThat(requestsReceived.get())
                .as("the peer must have ACCEPTED and received a request — otherwise this test proves "
                        + "only the connect timeout, which was never the defect")
                .isGreaterThanOrEqualTo(1);
    }

    // ------------------------------------------------------------------------
    // The resolver: same shape, the other entry point onto the same upstream.
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("a hung config-registry bounds the per-partner scheme resolve and answers "
            + "SCHEME_UNAVAILABLE")
    void resolverIsBoundedAndMapsToSchemeUnavailable() {
        RestPartnerSchemeResolver resolver = new RestPartnerSchemeResolver(
                RestClient.builder(), blackHoleBaseUrl(), CONNECT_MILLIS, READ_MILLIS);

        long start = System.nanoTime();
        assertThatThrownBy(() -> resolver.resolveForPartner("GMEREMIT"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.SCHEME_UNAVAILABLE);

        assertThat(elapsedMillis(start)).isLessThan(5_000L);
        assertThat(requestsReceived.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("a timed-out resolve is sent exactly once and never retried")
    void aTimedOutResolveIsSentOnceAndNeverRetried() {
        RestPartnerSchemeResolver resolver = new RestPartnerSchemeResolver(
                RestClient.builder(), blackHoleBaseUrl(), CONNECT_MILLIS, READ_MILLIS);

        assertThatThrownBy(() -> resolver.resolveForPartner("GMEREMIT"))
                .isInstanceOf(ApiException.class);

        // ADR-016 §4's discipline, applied one layer earlier than the submit it was written for: a
        // timeout is never a licence to send again. Here the reason is load rather than money — the
        // country scan already fans out one request per partner, so an automatic retry would multiply
        // pressure on an upstream that has just demonstrated it cannot answer. The count is asserted
        // rather than the absence of a retry() call, because only the count survives a refactor.
        assertThat(requestsReceived.get())
                .as("exactly one request per resolve, even when it times out")
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------------
    // Operating hours: the opposite fail direction, and it needs the bound too.
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("a hung config-registry bounds the operating-hours read and degrades to UNVERIFIED "
            + "rather than hanging")
    void operatingHoursReadIsBoundedAndDegradesToAnEmptySchedule() {
        RestSchemeOperatingHoursSource source = new RestSchemeOperatingHoursSource(
                RestClient.builder(), blackHoleBaseUrl(), 600_000, CONNECT_MILLIS, READ_MILLIS);

        long start = System.nanoTime();
        List<?> schedule = source.weeklySchedule("ZEROPAY");

        // Empty ⇒ SchemeAvailability answers UNVERIFIED ⇒ the candidate is KEPT and the fact is
        // logged. That documented degradation was previously unreachable: a hop that never returns
        // never degrades either, it just holds the routing thread. The degraded path only exists once
        // the read is bounded, which is the point of the whole gap.
        assertThat(schedule).isEmpty();
        assertThat(elapsedMillis(start)).isLessThan(5_000L);
        assertThat(requestsReceived.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("the operating-hours source does not re-hit a hung upstream inside one call")
    void operatingHoursSendsOneRequestPerCall() {
        RestSchemeOperatingHoursSource source = new RestSchemeOperatingHoursSource(
                RestClient.builder(), blackHoleBaseUrl(), 600_000, CONNECT_MILLIS, READ_MILLIS);

        source.weeklySchedule("ZEROPAY");

        assertThat(requestsReceived.get()).isEqualTo(1);
    }

    // ------------------------------------------------------------------------
    // The control case: without the fix, the same call does NOT come back.
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("control: the bare static builder these clients used to call has no read timeout")
    void theStaticBuilderHasNoReadTimeoutAtAll() throws Exception {
        // Not "wait forever and see" — a test cannot assert that. It pins the mechanism instead: the
        // pre-fix expression, verbatim, on a thread that is abandoned. If a read timeout existed on a
        // bare static builder the call would return and the latch would trip; it does not, which is
        // exactly why the injected builder (or an explicit factory) is the fix and why a property
        // alone was never going to be one.
        RestClient unbounded = RestClient.builder().baseUrl(blackHoleBaseUrl()).build();

        Thread caller = new Thread(() -> {
            try {
                unbounded.get().uri("/v1/partners").retrieve().body(String.class);
            } catch (Exception ignored) {
                // Any outcome is fine; what matters is that it does not arrive within the budget.
            }
        });
        caller.setDaemon(true);
        caller.start();
        // Ten times the 300ms read budget the fixed clients use. Still blocked.
        caller.join(3_000);
        assertThat(caller.isAlive())
                .as("a client built from the STATIC RestClient.builder() is still blocked on read() "
                        + "long after the fixed clients' budget has expired — this is the defect")
                .isTrue();
        caller.interrupt();
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
