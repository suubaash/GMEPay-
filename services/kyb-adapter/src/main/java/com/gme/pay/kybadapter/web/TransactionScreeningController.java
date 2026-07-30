package com.gme.pay.kybadapter.web;

import com.gme.pay.kyb.PaymentParty;
import com.gme.pay.kyb.PaymentScreeningSubject;
import com.gme.pay.kyb.TransactionScreeningEvidence;
import com.gme.pay.kyb.TransactionScreeningPolicy;
import com.gme.pay.kybadapter.persistence.TransactionScreeningRepository;
import com.gme.pay.kybadapter.screening.TransactionScreeningOutcome;
import com.gme.pay.kybadapter.screening.TransactionScreeningService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/**
 * Payment-path transaction screening API — gap <b>T5-3</b>.
 *
 * <p>Distinct from {@link ScreeningController} ({@code /v1/kyb}), which screens a PARTNER ENTITY and its
 * UBOs once at onboarding. This surface screens the COUNTERPARTIES TO A PAYMENT, per transaction, and
 * conflating the two is the confusion the gap exists to remove: an activation-time entity check does
 * not discharge a per-payment counterparty duty, and neither substitutes for the other.
 *
 * <h2>Who calls this</h2>
 *
 * <p>The intended caller is payment-executor, before any float moves. That wiring is <b>not yet in
 * place</b> — payment-executor is owned by another change in flight — so today this endpoint is
 * reachable and correct but unexercised by the live money path. The follow-up is recorded in
 * {@code outputs/agent/fix_t5-aml-screening_2026-07-28.md}; nothing here should be read as evidence
 * that payments are being screened.
 *
 * <h2>Internal only</h2>
 *
 * <p>There is no api-gateway route to this service and the whole surface requires the shared
 * {@code X-Gme-Internal} token ({@code gmepay.internal-auth.path-patterns} covers {@code /v1/screening/**}).
 * The token is also what lets a screening be attributed to an attested caller rather than to an
 * unverifiable claim — see {@code AuditActorResolver}.
 */
@RestController
@RequestMapping("/v1/screening")
public class TransactionScreeningController {

    private final TransactionScreeningService service;
    private final TransactionScreeningRepository repository;

    public TransactionScreeningController(TransactionScreeningService service,
                                          TransactionScreeningRepository repository) {
        this.service = service;
        this.repository = repository;
    }

    /**
     * Screen the parties to one payment, record the outcome and report whether the payment may proceed.
     *
     * <p>Idempotent per {@code (txnRef, party)}: re-screening the same party overwrites the current row
     * and appends a fresh audit event, so a retry cannot produce two contradictory current answers.
     *
     * @return 200 with the outcome. A refusal is <b>200 with {@code allowed=false}</b>, not a 4xx: the
     *         screening call itself succeeded and produced a verdict, and encoding the verdict as a
     *         transport error would make an outage and a refusal look alike to the caller. The payment
     *         path turns {@code allowed=false} into its own refusal response.
     */
    @PostMapping("/transaction")
    public TransactionScreeningOutcome screen(@RequestBody ScreenTransactionRequest request) {
        if (request == null || request.txnRef() == null || request.txnRef().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "txnRef is required");
        }
        List<PaymentScreeningSubject> subjects = request.parties() == null ? List.of()
                : request.parties().stream().map(PartyRequest::toSubject).toList();
        return service.screen(request.txnRef(), request.partnerId(), subjects);
    }

    /**
     * The evidence recorded for one transaction — what was checked, by whom, when, and what was decided.
     * Read back through the evidence type, so a row that a migration or a hand-edit left as a
     * non-authoritative CLEAR is reported as {@code NOT_SCREENED_NO_PROVIDER}.
     *
     * @return 404 when nothing was ever recorded for this transaction. Deliberately not an empty 200:
     *         "no screening was recorded" and "screening was recorded and found nothing" are different
     *         answers and must not share a response shape.
     */
    @GetMapping("/transaction/{txnRef}")
    public TransactionEvidenceResponse evidence(@PathVariable String txnRef) {
        List<TransactionScreeningEvidence> evidence = service.evidenceFor(txnRef);
        if (evidence.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "no screening evidence recorded for transaction " + txnRef);
        }
        return new TransactionEvidenceResponse(txnRef, posture(), evidence);
    }

    /**
     * Screening coverage over a window: how many parties fell into each unscreened reason, per partner.
     *
     * <p>The posture is returned alongside the counts, always. A coverage report showing zero unscreened
     * parties because no payment was screened at all is indistinguishable from full coverage unless the
     * reader is told which provider answered and what was required — so they are told, in the same
     * payload, and cannot be separated.
     */
    @GetMapping("/coverage")
    public CoverageResponse coverage(
            @RequestParam(required = false) String partnerId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        if (from == null || to == null || !from.isBefore(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "from must be strictly before to");
        }
        List<CoverageBucket> buckets = repository.coverage(partnerId, from, to).stream()
                .map(r -> new CoverageBucket(
                        r.getPartnerId(),
                        r.getUnscreenedReason() == null ? null : r.getUnscreenedReason().name(),
                        r.getUnscreenedReason() == null,
                        r.getPartyCount()))
                .toList();
        return new CoverageResponse(from, to, partnerId, posture(), buckets);
    }

    /** The active posture — which provider answers, whether it is an authority, and what is required. */
    @GetMapping("/posture")
    public PostureResponse posture() {
        TransactionScreeningPolicy policy = service.policy();
        return new PostureResponse(
                service.port().providerId(),
                service.port().authoritative(),
                policy.requirement().describe(),
                policy.inert(),
                policy.overrideArmed(),
                policy.environment(),
                policy.inert()
                        ? "No party is required to be screened: outcomes are recorded and audited but no"
                                + " payment can be refused for want of a sanctions/PEP check. Populating"
                                + " gmepay.screening.transaction.required-parties is a compliance decision."
                        : "Required parties are screened FAIL-CLOSED unless the non-production override is"
                                + " armed.");
    }

    // ------------------------------------------------------------------
    // Wire shapes
    // ------------------------------------------------------------------

    /**
     * @param txnRef    the transaction reference; required
     * @param partnerId the partner, or {@code null} for wallet traffic
     * @param parties   the counterparties to screen
     */
    public record ScreenTransactionRequest(String txnRef, String partnerId, List<PartyRequest> parties) {
    }

    /**
     * One party as presented by the caller.
     *
     * <p>{@code name} is the only attribute any list-matching provider can use, and it is optional
     * because neither of this platform's payment contracts carries it today. Callers must send what
     * they genuinely hold and <b>must not synthesise a placeholder</b> — a made-up name produces a
     * made-up screening, which is worse than a recorded gap.
     */
    public record PartyRequest(PaymentParty party, String reference, String name,
                               String countryCode, String dateOfBirth) {

        PaymentScreeningSubject toSubject() {
            if (party == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "each screening party must name its role (PAYER | BENEFICIARY | MERCHANT)");
            }
            return new PaymentScreeningSubject(party, reference, name, countryCode, dateOfBirth);
        }
    }

    /** The posture, returned with every read so a count is never presented without its context. */
    public record PostureResponse(String providerId, boolean providerAuthoritative,
                                  String requiredParties, boolean screeningInert,
                                  boolean overrideArmed, String environment, String note) {
    }

    public record TransactionEvidenceResponse(String txnRef, PostureResponse posture,
                                              List<TransactionScreeningEvidence> evidence) {
    }

    /**
     * @param unscreenedReason the cause, or {@code null} when these parties WERE screened
     * @param completed        {@code true} for the bucket of genuinely completed screenings
     */
    public record CoverageBucket(String partnerId, String unscreenedReason, boolean completed,
                                 long partyCount) {
    }

    public record CoverageResponse(Instant from, Instant to, String partnerId, PostureResponse posture,
                                   List<CoverageBucket> buckets) {

        /** Convenience for a reader: total parties recorded in the window, and how many were screened. */
        public Map<String, Long> totals() {
            long all = buckets.stream().mapToLong(CoverageBucket::partyCount).sum();
            long completed = buckets.stream().filter(CoverageBucket::completed)
                    .mapToLong(CoverageBucket::partyCount).sum();
            return Map.of("parties", all, "completedScreenings", completed,
                    "unscreened", all - completed);
        }
    }
}
