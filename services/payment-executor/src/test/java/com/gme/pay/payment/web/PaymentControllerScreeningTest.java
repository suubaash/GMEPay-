package com.gme.pay.payment.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gme.pay.contracts.OperationalStatusView;
import com.gme.pay.events.RecordingEventPublisher;
import com.gme.pay.kyb.NoProviderPaymentScreeningPort;
import com.gme.pay.kyb.PaymentParty;
import com.gme.pay.kyb.PaymentScreeningPort;
import com.gme.pay.kyb.PaymentScreeningSubject;
import com.gme.pay.kyb.ScreeningProvenance;
import com.gme.pay.kyb.ScreeningResult;
import com.gme.pay.kyb.UnscreenedReason;
import com.gme.pay.payment.alert.OpsAlertPipeline;
import com.gme.pay.payment.domain.OperationalGate;
import com.gme.pay.payment.domain.PaymentOrchestrator;
import com.gme.pay.payment.domain.PaymentScreeningGate;
import com.gme.pay.payment.domain.client.PartnerConfigClient;
import com.gme.pay.payment.persistence.PaymentAuthorizationRepository;
import com.gme.pay.payment.persistence.UnscreenedPaymentCounter;
import com.gme.pay.payment.service.PaymentAuthorizationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * T5-3 on the ORCHESTRATED entry point: {@code POST /v1/payments/authorize}.
 *
 * <p>The gate is wired for REAL (a real {@link OperationalGate} composing a real
 * {@link PaymentScreeningGate} over a real port and a fixed clock) — a mocked gate would prove only that
 * the controller calls something. What is proven here is the part that can only be proven at the entry
 * point:
 *
 * <ol>
 *   <li><b>the default posture does not change behaviour</b> — a payment still authorizes;</li>
 *   <li><b>fail-closed refuses before any side effect</b> — 422, non-retryable, and the orchestrator
 *       (which owns the float reserve and the scheme call) is <b>never touched</b>;</li>
 *   <li>the subject the real contract can offer is the opaque {@code customer_ref}, so the recorded
 *       cause is {@code NO_SUBJECT_IDENTITY}-class rather than a pretend screening.</li>
 * </ol>
 */
class PaymentControllerScreeningTest {

    private static final Instant TUE = Instant.parse("2026-07-28T03:00:00Z");

    private static final String AUTHORIZE_BODY = """
            {
              "quote_id": "Q-1",
              "merchant_qr": "ZPQR0001",
              "direction": "INBOUND",
              "scheme_id": "ZEROPAY",
              "customer_ref": "cust-t53",
              "partner_txn_ref": "PTR-T53-1",
              "collection_amount": "50000",
              "collection_currency": "KRW"
            }
            """;

    private final PaymentOrchestrator orchestrator = mock(PaymentOrchestrator.class);
    private final PartnerConfigClient partnerConfigClient = mock(PartnerConfigClient.class);
    private final PaymentAuthorizationRepository authorizationRepository =
            mock(PaymentAuthorizationRepository.class);
    private final PaymentAuthorizationService authorizationService =
            mock(PaymentAuthorizationService.class);
    private final RecordingEventPublisher eventPublisher = new RecordingEventPublisher();
    private final UnscreenedPaymentCounter counter = mock(UnscreenedPaymentCounter.class);
    private final OpsAlertPipeline alerts = mock(OpsAlertPipeline.class);

    private MockMvc mvcWith(PaymentScreeningPort port, boolean failClosed) {
        PaymentScreeningGate screeningGate = new PaymentScreeningGate(
                port, alerts, counter, null, failClosed, Clock.fixed(TUE, ZoneOffset.UTC));
        PaymentController controller = new PaymentController(
                orchestrator, partnerConfigClient, authorizationRepository, authorizationService,
                eventPublisher,
                new OperationalGate(OperationalStatusView::allClear, null, screeningGate));
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return standaloneSetup(controller)
                .setControllerAdvice(new PaymentExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    @DisplayName("fail-closed ON: 422 SANCTIONS_SCREENING_UNAVAILABLE, non-retryable, orchestrator NEVER runs")
    void failClosed_refusesBeforeAnySideEffect() throws Exception {
        when(authorizationRepository.findByPartnerIdAndPartnerTxnRef(anyLong(), any()))
                .thenReturn(Optional.empty());

        mvcWith(new NoProviderPaymentScreeningPort(), /* failClosed = */ true)
                .perform(post("/v1/payments/authorize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(AUTHORIZE_BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SANCTIONS_SCREENING_UNAVAILABLE"))
                .andExpect(jsonPath("$.retryable").value(false));

        // THE assertion of this test: the orchestrator owns the quote load, the merchant resolve, the
        // PENDING transaction row, the prefunding RESERVE and the scheme submit. Never touching it is
        // what "no float moved and no scheme call" means at this layer.
        verifyNoInteractions(orchestrator);
        verifyNoInteractions(authorizationService);
        // ...and nothing was published either.
        assertEquals(0, eventPublisher.published().size(), "no event may escape a refused payment");
    }

    @Test
    @DisplayName("default (fail-closed OFF): the payment still authorizes — existing behaviour unchanged")
    void defaultPosture_doesNotChangeBehaviour() throws Exception {
        when(authorizationRepository.findByPartnerIdAndPartnerTxnRef(anyLong(), any()))
                .thenReturn(Optional.empty());
        when(orchestrator.authorizeMpm(any(), any()))
                .thenThrow(new IllegalStateException("REACHED_THE_ORCHESTRATOR"));

        // The point is that the gate does NOT short-circuit: the request gets all the way to the
        // orchestrator, which is the pre-T5-3 behaviour. (A stub AuthorizeResult would need the whole
        // persistence path; a sentinel throw pins "we got there" without it.)
        try {
            mvcWith(new NoProviderPaymentScreeningPort(), /* failClosed = */ false)
                    .perform(post("/v1/payments/authorize")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(AUTHORIZE_BODY));
        } catch (Exception expected) {
            // MockMvc rethrows the unmapped sentinel; that IS the pass condition.
        }
        verify(orchestrator).authorizeMpm(any(), any());
    }

    @Test
    @DisplayName("the subject the authorize contract offers is opaque, so the recorded cause names the contract gap")
    void authorizeContract_carriesNoScreenableIdentity() throws Exception {
        when(authorizationRepository.findByPartnerIdAndPartnerTxnRef(anyLong(), any()))
                .thenReturn(Optional.empty());
        // A provider that IS real, to isolate the second half of the gap from the first.
        PaymentScreeningPort real = new PaymentScreeningPort() {
            @Override
            public ScreeningResult screen(PaymentScreeningSubject subject) {
                return new ScreeningResult(ScreeningResult.Status.CLEAR, List.of(), TUE, "r",
                        ScreeningProvenance.vendor("acme"));
            }

            @Override
            public String providerId() {
                return "acme";
            }

            @Override
            public boolean authoritative() {
                return true;
            }
        };

        try {
            mvcWith(real, false).perform(post("/v1/payments/authorize")
                    .contentType(MediaType.APPLICATION_JSON).content(AUTHORIZE_BODY));
        } catch (Exception ignored) {
            // the orchestrator mock returns null → NPE downstream; irrelevant to this assertion
        }

        // Even with a real vendor wired, API-05's authorize body carries no originator NAME — only the
        // opaque customer_ref — so nobody is screened and the cause says exactly that. This is the
        // finding that a vendor purchase alone would not fix.
        ArgumentCaptor<String> ref = ArgumentCaptor.forClass(String.class);
        verify(counter).countUnscreened(eq(UnscreenedReason.NO_SUBJECT_IDENTITY),
                eq(PaymentParty.PAYER), eq("acme"), any(), ref.capture());
        // The evidence anchor is the partner's own txn ref, so a coverage row is traceable to a payment.
        assertEquals("PTR-T53-1", ref.getValue());
    }

    @Test
    @DisplayName("an idempotent authorize REPLAY is not re-screened (and not double-counted)")
    void idempotentReplay_isNotReScreened() throws Exception {
        // An already-authorized payment is in flight; re-screening it could refuse a payment whose float
        // is already reserved. Same carve-out the operational gate and T3-6 already make for a replay.
        var existing = mock(com.gme.pay.payment.persistence.PaymentAuthorizationEntity.class);
        when(existing.getPartnerTxnRef()).thenReturn("PTR-T53-1");
        when(authorizationRepository.findByPartnerIdAndPartnerTxnRef(anyLong(), any()))
                .thenReturn(Optional.of(existing));

        try {
            mvcWith(new NoProviderPaymentScreeningPort(), /* failClosed = */ true)
                    .perform(post("/v1/payments/authorize")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(AUTHORIZE_BODY));
        } catch (Exception ignored) {
            // response mapping of a mocked entity is not what this test is about
        }

        // Even with fail-closed ARMED the replay is not refused by the screening gate, because the gate
        // is never reached — the replay returns before it.
        verifyNoInteractions(counter);
    }
}
