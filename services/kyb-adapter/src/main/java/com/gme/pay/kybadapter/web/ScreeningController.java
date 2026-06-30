package com.gme.pay.kybadapter.web;

import com.gme.pay.events.EventPublisher;
import com.gme.pay.kyb.KybProvider;
import com.gme.pay.kyb.KybSubject;
import com.gme.pay.kyb.ScreeningResult;
import com.gme.pay.kybadapter.event.KybScreeningEvent;
import com.gme.pay.kybadapter.kyb.KybVerificationRequest;
import com.gme.pay.kybadapter.kyb.KybVerificationResult;
import com.gme.pay.kybadapter.service.KybVerificationService;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Internal screening API (ADR-009). config-registry's
 * {@code KybService.runScreening} is the primary caller; the daily rescreen
 * scheduler joins in a later slice.
 *
 * <h2>Event fan-out</h2>
 *
 * <p>Every successful screening is published to Kafka topic
 * {@code gmepay.kyb.screening} via the injected {@link EventPublisher}
 * (lib-events-kafka's {@code KafkaEventPublisher} when
 * {@code spring.kafka.bootstrap-servers} is configured, the log fallback
 * otherwise — see {@code KybProviderConfig}). Publication is best-effort at
 * this seam: the caller persists the result to {@code partner_kyb} (the
 * regulator-defensible record, ADR-007-audited there), so a broker outage must
 * not turn a completed vendor screening into a 500 — the failure is logged and
 * the result still returns.
 */
@RestController
@RequestMapping("/v1/kyb")
public class ScreeningController {

    private static final Logger log = LoggerFactory.getLogger(ScreeningController.class);

    private final KybProvider kybProvider;
    private final EventPublisher eventPublisher;
    private final KybVerificationService verificationService;

    public ScreeningController(KybProvider kybProvider, EventPublisher eventPublisher,
            KybVerificationService verificationService) {
        this.kybProvider = kybProvider;
        this.eventPublisher = eventPublisher;
        this.verificationService = verificationService;
    }

    /**
     * Screen one subject (entity + UBOs) through the active provider.
     * Returns the provider's {@link ScreeningResult} unchanged; 400 when the
     * payload is missing the partner code that keys the event stream.
     */
    @PostMapping("/screen")
    public ScreeningResult screen(@RequestBody KybSubject subject) {
        if (subject == null || subject.partnerCode() == null || subject.partnerCode().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "partnerCode is required (it keys the gmepay.kyb.screening event stream)");
        }
        ScreeningResult result = kybProvider.screen(subject);

        try {
            eventPublisher.publish(KybScreeningEvent.of(subject.partnerCode(), result));
        } catch (RuntimeException e) {
            // Best-effort fan-out (see class javadoc): the screening already
            // succeeded and the caller stores it durably; losing one Kafka emit
            // is recoverable via rescreen, failing the request is not.
            log.error("failed to publish kyb.screening event for partner {}: {}",
                    subject.partnerCode(), e.getMessage(), e);
        }
        return result;
    }

    /**
     * Run a FULL KYB verification — sanctions/PEP screening + business-registration
     * verification + document-completeness, collapsed into one
     * PASS/FAIL/MANUAL_REVIEW decision and persisted to {@code kyb_screening}.
     *
     * <p>Idempotent: re-verifying an unchanged subject replays the stored run
     * (no second vendor call, no duplicate event) unless {@code force=true}.
     * config-registry's onboarding wizard calls this; the verdict is evidence,
     * not an activation authorisation (the activation stays an ADR-008 4-eyes
     * operator decision). 400 when the subject or its partner code is missing.
     */
    @PostMapping("/verify")
    public KybVerificationResult verify(@RequestBody KybVerificationRequest request) {
        if (request == null || request.subject() == null
                || request.subject().partnerCode() == null
                || request.subject().partnerCode().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "subject with a partnerCode is required");
        }
        return verificationService.verify(request);
    }

    /**
     * Retrieve a persisted verification run by its provider reference (the
     * {@code GET /v1/kyb/result/{vendorRef}} contract named in the service
     * backlog). 404 when no run with that reference exists.
     */
    @GetMapping("/result/{providerRef}")
    public KybVerificationResult result(@PathVariable String providerRef) {
        return verificationService.findByProviderRef(providerRef)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "no KYB verification run found for providerRef " + providerRef));
    }

    /**
     * Liveness probe without the actuator dependency: confirms the service is
     * up and which provider class is active (stub vs octa) so an operator can
     * spot a mis-wired environment at a glance.
     */
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of(
                "status", "UP",
                "provider", kybProvider.getClass().getSimpleName());
    }
}
