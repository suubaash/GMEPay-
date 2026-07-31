package com.gme.pay.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gme.pay.payment.client.rest.RestSchemeClient;
import com.gme.pay.payment.domain.GmeremitPaymentService.WalletResult;
import com.gme.pay.payment.domain.client.SmartRouterClient;
import com.gme.pay.payment.domain.client.SmartRouterClient.PartnerSchemeView;
import com.gme.pay.payment.persistence.ExecutionAttemptRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * <b>The load-bearing T3-11 / ADR-016 §4 test: a read timeout on an irreversible submit is never
 * treated as a failure, is never re-sent, and is resolved by the status probe.</b>
 *
 * <p>T3-11 defect 1 is that money-path clients had no read timeout. The fix is a bound — and a bound
 * introduces a new failure that did not exist before: the client now gives up while the scheme may
 * still be processing the charge. That is a strictly better position than hanging forever <em>only
 * if</em> the ambiguity is handled as ambiguity. If the platform read "my socket timed out" as "the
 * payment failed", adding timeouts would convert hung threads into <b>lost money</b> — a customer
 * charged by the scheme and marked FAILED here — and if it read it as "retry", into
 * <b>double charges</b>. Both are worse than the defect being fixed.
 *
 * <p>Every other timeout test in this repo stops one layer short of that. {@code InternalHttpTimeoutTest}
 * proves the client maps a hung socket to {@code SchemeTimeoutException} and sends the request once;
 * {@code ResilientFailoverIntegrationTest} proves the router honours the anti-double-charge guard when
 * a <em>mocked</em> client throws. Neither shows the two halves working together, which is where the
 * defect actually lived: the bug T3-11 uncovered was precisely a client whose timeout surfaced as the
 * wrong exception type, so the router's guard was never reached at all and no ambiguous row was
 * written. A mock cannot reproduce that, because a mock is told which exception to throw.
 *
 * <p>So this test uses the <b>real</b> {@link RestSchemeClient} against a <b>real</b> HTTP server that
 * accepts the submit and never answers it, wired into the <b>real</b> {@link FailoverPaymentRouter}:
 *
 * <ol>
 *   <li>the submit hits the read timeout (no mocked exception anywhere in the path);</li>
 *   <li>the router treats it as technical/unknown, not as a decline;</li>
 *   <li>the router probes {@code lookupStatus} with the same stable reference;</li>
 *   <li>the scheme answers APPROVED — the charge did land — and the payment is resolved as approved
 *       <em>without a second submit</em>.</li>
 * </ol>
 *
 * <p>The submit endpoint's hit counter is the assertion that matters most: <b>exactly one</b>. A second
 * candidate is deliberately present in the routing list, so a router that failed over instead of
 * probing would submit again and be caught here.
 */
class TimedOutSubmitResolvedByProbeTest {

    /** A fonepay QR — classification only needs to succeed; the candidate list is supplied below. */
    private static final String QR = "00020101021126150011fonepay.com5802NP5910KINAUN PVT6304ABCD";
    private static final BigDecimal AMOUNT = new BigDecimal("1000");

    /** Read timeout for the fake scheme leg. Short so the test is fast; the semantics are unchanged. */
    private static final long READ_TIMEOUT_MS = 1_500L;

    /**
     * Deliberately generous, and NOT a "make the flake go away" number.
     *
     * <p>This test is only meaningful if the failure it induces is a READ timeout — the peer accepted
     * the request and went silent, so the charge may have landed. A CONNECT timeout is a different and
     * unambiguous failure (nothing was sent, nothing was charged), and it produces the same
     * {@code SchemeTimeoutException}. With a tight connect budget the two are indistinguishable from
     * the outside, and a loopback connect on a machine running the full suite really does miss a few
     * hundred milliseconds: the first run of this test failed with {@code submits == 0}, i.e. it was
     * silently proving the wrong thing. The {@code submits} assertions are what keep this honest — a
     * connect timeout leaves them at zero and fails the test rather than passing it vacuously.
     */
    private static final long CONNECT_TIMEOUT_MS = 10_000L;

    /**
     * How long a hung submit holds its exchange open. Must comfortably outlast
     * {@link #READ_TIMEOUT_MS} — the client has to give up first, or the failure under test is not a
     * read timeout at all — but no longer than that, because <b>every</b> extra second is a handler
     * thread this test keeps pinned.
     *
     * <p>It was {@code READ_TIMEOUT_MS * 20} (30 s) against a fixed pool of four, and that combination
     * made the control case flaky under a full-suite run: two pinned submit handlers plus a burst of
     * probes could leave the second probe queued behind a black hole, so it never reached the server and
     * {@code statusProbes} read 1. The failure mode is the dangerous kind — a probe that never arrived
     * is not "the scheme said NOT_FOUND", so the test would have been asserting a fact it had not
     * established. Four times the read budget is ample proof that the client, not the server, decided.
     */
    private static final long HOLD_OPEN_MS = READ_TIMEOUT_MS * 8;

    /**
     * How long to wait for a request the client has already given up on to reach the scheme. Generous,
     * because it bounds a machine's scheduling jitter under a full-suite run, not any behaviour of the
     * platform: an arrival that is merely late still satisfies every assertion here, so waiting longer
     * can only make the test more accurate, never more permissive.
     */
    private static final long ARRIVAL_WINDOW_MS = 5_000L;

    /**
     * Quiescence window after the expected arrivals are seen. This is what keeps "exactly once" honest:
     * without it, waiting for "at least one" would accept a duplicate that landed a moment later, and a
     * duplicated irreversible submit is the single worst outcome in this file.
     */
    private static final long QUIESCE_MS = 500L;

    private HttpServer scheme;
    private ExecutorService schemeExecutor;
    private final AtomicInteger submits = new AtomicInteger();
    private final AtomicInteger statusProbes = new AtomicInteger();

    /** What the scheme reports when finally asked about the reference. */
    private volatile String probeAnswer = "APPROVED";

    @BeforeEach
    void startScheme() throws IOException {
        scheme = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // A pool, not a single thread: the probe must be servable while the hung submit still holds
        // its own exchange open. On one thread the probe would queue behind the black hole and the
        // test would pass for the wrong reason (guard failure → fail over), which is the opposite of
        // what it is meant to prove.
        //
        // UNBOUNDED, not a fixed size. A fixed pool re-introduces the same coupling one step further
        // out: every hung submit permanently occupies a slot, so the number of probes this test can
        // serve depends on how many submits have already hung — which is exactly the quantity under
        // test. At four it flaked under full-suite load. A cached pool cannot starve a probe, and the
        // thread count is bounded in practice by the handful of requests one payment makes.
        schemeExecutor = Executors.newCachedThreadPool();
        scheme.setExecutor(schemeExecutor);

        // The irreversible submit: read the request, then never answer. This is the failure
        // RUNBOOK_LOAD_AND_CAPACITY.md §4.1 #7 describes — an accepted connection that goes silent —
        // and the one a closed port cannot reproduce, because a refused connection is unambiguous
        // (nothing was charged) while this one is genuinely unknown.
        scheme.createContext("/internal/scheme/zeropay/submit", exchange -> {
            submits.incrementAndGet();
            drain(exchange);
            try {
                Thread.sleep(HOLD_OPEN_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });

        scheme.createContext("/internal/scheme/zeropay/status", exchange -> {
            statusProbes.incrementAndGet();
            drain(exchange);
            byte[] body = ("{\"schemeTxnRef\":\"ZP-LANDED-1\",\"status\":\"" + probeAnswer
                    + "\",\"reference\":\"ref\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        scheme.start();
    }

    @AfterEach
    void stopScheme() {
        scheme.stop(0);
        // stop(0) closes the listener without waiting, and it does NOT interrupt handler threads. The
        // hung submit handlers are still sleeping, so without this each test method leaves live threads
        // behind for the rest of the suite — thread pressure that this test then blames on whatever runs
        // next. shutdownNow interrupts them; the handler's catch block restores the interrupt flag and
        // closes its exchange.
        schemeExecutor.shutdownNow();
    }

    private static void drain(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            in.readAllBytes();
        }
    }

    /**
     * The real client, bounded exactly as production is — {@code HttpClientTimeouts} via
     * {@code RestSchemeClient}'s own constructor, not a hand-built factory.
     */
    private FailoverPaymentRouter routerWithTwoCandidates() {
        RestSchemeClient realClient = new RestSchemeClient(
                RestClient.builder(),
                "http://127.0.0.1:" + scheme.getAddress().getPort(),
                CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS, "");

        SmartRouterClient smartRouter = mock(SmartRouterClient.class);
        // TWO candidates: the second exists so that "did it fail over?" is observable. If the guard
        // did not short-circuit, the loop would submit to candidate 2 — through the same client and
        // therefore the same submit endpoint — and submits would read 2.
        when(smartRouter.resolve(anyString(), any(), anyString(), anyString()))
                .thenReturn(List.of(
                        new PartnerSchemeView(1L, "PrimaryPartner", "zeropay", 0),
                        new PartnerSchemeView(2L, "SecondaryPartner", "zeropay", 1)));

        return new FailoverPaymentRouter(
                smartRouter, realClient, mock(ExecutionAttemptRepository.class));
    }


    /**
     * Waits until the scheme has actually <b>received</b> the requests this test is about, then proves no
     * further ones arrive.
     *
     * <h2>Why the raw counters cannot be asserted directly</h2>
     *
     * <p>The client abandons the submit at {@link #READ_TIMEOUT_MS} and the router carries on
     * immediately, so a request can be delivered to the server <em>after</em> {@code pay()} has already
     * returned. Reading the counters at that instant is a race between the assertion and the network, and
     * on a loaded machine the network loses: the suite saw {@code submits == 0} and
     * {@code statusProbes == 1} for calls that had provably been sent. Both readings are the dangerous
     * kind of green-adjacent failure — "the scheme never received the submit" and "the test looked too
     * early" are the same number.
     *
     * <p>Note that a late arrival is not a flaw in the behaviour under test; it is the behaviour under
     * test. A submit the client stopped waiting for, which the scheme then receives and may act on, is
     * exactly the ambiguous outcome ADR-016 §4 exists for. So the fix is to observe it properly rather
     * than to loosen what is asserted.
     *
     * <h2>Why a quiescence window follows</h2>
     *
     * <p>"At least N" alone would let a double-send pass. After the expected arrivals are seen, this
     * waits a further {@link #QUIESCE_MS} and requires the counts to be <b>exactly</b> N — so the
     * "sent exactly once" contract is still enforced, and enforced against late duplicates too, which a
     * bare read of the counter could never have caught.
     */
    private void awaitRequestsThenQuiesce(int expectedSubmits, int expectedProbes) {
        long deadline = System.nanoTime() + ARRIVAL_WINDOW_MS * 1_000_000L;
        while (System.nanoTime() < deadline
                && (submits.get() < expectedSubmits || statusProbes.get() < expectedProbes)) {
            sleep(20);
        }
        sleep(QUIESCE_MS);

        assertThat(submits.get())
                .as("the scheme must have received EXACTLY %d submit(s). Fewer means the request never "
                        + "arrived within %dms (so nothing about the charge has been established and "
                        + "this test proves nothing); more means an irreversible submit was re-sent, "
                        + "which is the double charge ADR-016 4 forbids",
                        expectedSubmits, ARRIVAL_WINDOW_MS)
                .isEqualTo(expectedSubmits);
        assertThat(statusProbes.get())
                .as("the scheme must have received EXACTLY %d status probe(s): the ambiguity has to be "
                        + "RESOLVED by asking, and every assertion about what the scheme said is void if "
                        + "the question never arrived", expectedProbes)
                .isEqualTo(expectedProbes);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the scheme", e);
        }
    }

    @Test
    @DisplayName("a timed-out submit whose charge LANDED is approved by the probe, and is never re-sent")
    void timedOutSubmitConfirmedByProbeIsApprovedAndNotResent() {
        probeAnswer = "APPROVED";

        WalletResult result = routerWithTwoCandidates().pay(QR, AMOUNT, "user-t311", "OVERSEAS");

        // Exactly one submit and one probe: a timeout is not evidence of failure, so it must never
        // become a retry and must never let the loop move to the next candidate before the probe has
        // answered (ADR-016 4).
        awaitRequestsThenQuiesce(1, 1);
        assertThat(result.approved())
                .as("the scheme confirmed the charge landed, so the payment is APPROVED — reporting "
                        + "FAILED here would lose money the customer really paid")
                .isTrue();
    }

    @Test
    @DisplayName("a timed-out submit the scheme calls PENDING is NOT approved and NOT failed — it stays unknown")
    void timedOutSubmitStillPendingIsNeitherApprovedNorResent() {
        probeAnswer = "PENDING";

        WalletResult result = routerWithTwoCandidates().pay(QR, AMOUNT, "user-t311", "OVERSEAS");

        // PENDING means the charge MAY be in flight — the one state where a re-send is a guaranteed
        // double charge, so the submit count is the assertion that matters most here.
        awaitRequestsThenQuiesce(1, 1);
        assertThat(result.approved())
                .as("PENDING is not an approval: nothing may be captured on the strength of it")
                .isFalse();
        assertThat(result.declineReason())
                .as("and it is not a decline either — the outcome is surfaced as PENDING so the "
                        + "existing reconciliation path resolves it, rather than being flattened into "
                        + "a terminal failure")
                .isEqualTo("PENDING");
    }

    @Test
    @DisplayName("only a probe that says NOT_FOUND permits a second submit — the charge provably did not land")
    void probeSayingNotFoundIsWhatPermitsFailover() {
        // The scheme has no record of the reference, so nothing was charged and moving on is safe.
        // This is the ONLY reading of a timeout that may result in a second submit, and it is the
        // control case for the two tests above: it shows they pass because the guard short-circuited,
        // not because failover was impossible.
        probeAnswer = "UNKNOWN_TO_THE_SCHEME";

        WalletResult result = routerWithTwoCandidates().pay(QR, AMOUNT, "user-t311", "OVERSEAS");

        // Both candidates attempted once each, each one probed and each one shown not to have charged.
        // A probe that never arrived is NOT the scheme saying NOT_FOUND, so this control case is void
        // unless both probes are known to have been received — which is why arrival is waited for rather
        // than sampled.
        awaitRequestsThenQuiesce(2, 2);
        assertThat(result.approved())
                .as("no candidate answered, so nothing may be reported as approved")
                .isFalse();
    }
}
