package com.gme.pay.prefunding.aml;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal read surface for AML monitoring EVIDENCE — gap T5-3.
 *
 * <p>{@code GET /internal/v1/prefunding/{partnerId}/aml-monitoring?from=yyyy-MM-dd&to=yyyy-MM-dd}
 * returns one partner's cumulative-usage ledger aggregated per KST day across the window, the window
 * totals, and the AML caps configured for that partner. It exists because prefunding held the only
 * complete record of per-partner volume and velocity in the platform and there was no way to read it
 * as a period — only as the single-period cap sums the authorize path compares under a row lock.
 *
 * <h2>What this endpoint is and is not</h2>
 *
 * <p>It serves EVIDENCE. Every figure is a reproducible aggregate of append-only rows. The endpoint
 * itself applies no threshold and forms no opinion, and <b>it does not constitute an AML control</b>.
 * The caps in the response are CONTEXT — what config-registry configured for this partner — not rules
 * this surface evaluates.
 *
 * <p>With {@code evaluate=true} the operator-configured monitoring rules
 * ({@link AmlMonitoringRules}) are additionally run against the window and any firings are raised
 * through prefunding's existing outbox + audit pipeline. Those rules ship <b>EMPTY</b>, because a
 * threshold is a compliance policy input no engineer may invent; while the list is empty
 * {@code evaluate=true} is an expensive way of writing {@code evaluate=false}, and the response says
 * so explicitly via {@code evaluation.rulesConfigured = 0}. Default is {@code false} so a plain read
 * is a plain read.
 *
 * <h2>Window</h2>
 *
 * <p>{@code from} and {@code to} are KST day keys ({@code yyyy-MM-dd}) and BOTH are inclusive —
 * unlike the instant-based {@code /v1/prefunding/{code}/movements} feed, because these are the day
 * strings the ledger actually stores, so there is no boundary instant to double-count. An inverted
 * range, a malformed date, an unknown partner, or a span over
 * {@code gmepay.aml.monitoring.max-window-days} is a 400 — never a silently empty window, which would
 * read to a reviewer exactly like a partner that transacted nothing.
 *
 * <p><b>Authentication:</b> this path is inside {@code /internal/**}, which is already covered by
 * {@code gmepay.internal-auth.path-patterns}, so every request must carry the shared
 * {@code X-Gme-Internal} token or be refused 401 by {@code InternalAuthFilter} before this controller
 * runs — the same gate fronting {@link com.gme.pay.prefunding.api.internal.PrefundingInternalController}.
 * No configuration change was needed and the gate is not weakened here.
 */
@RestController
@RequestMapping("/internal/v1/prefunding")
public class AmlMonitoringController {

    private final AmlMonitoringService monitoring;
    private final AmlMonitoringEvaluator evaluator;

    public AmlMonitoringController(AmlMonitoringService monitoring, AmlMonitoringEvaluator evaluator) {
        this.monitoring = monitoring;
        this.evaluator = evaluator;
    }

    /**
     * Per-KST-day usage evidence for {@code partnerId} across the inclusive window
     * {@code [from, to]}, plus the partner's configured caps as context.
     *
     * @param evaluate when {@code true}, also run the configured monitoring rules and raise any
     *                 firings through the outbox + audit pipeline. Default {@code false}
     */
    @GetMapping("/{partnerId}/aml-monitoring")
    public AmlMonitoringResponse amlMonitoring(
            @PathVariable String partnerId,
            @RequestParam(name = "from") String from,
            @RequestParam(name = "to") String to,
            @RequestParam(name = "evaluate", defaultValue = "false") boolean evaluate) {
        AmlWindowEvidence evidence = monitoring.evidence(partnerId, from, to);
        AmlEvaluation evaluation = evaluate ? evaluator.evaluate(evidence) : null;
        return AmlMonitoringResponse.of(evidence, evaluation);
    }

    /**
     * Wire shape of the evidence.
     *
     * <p>{@code days} holds only the KST days that HAVE ledger rows — a day with no activity is
     * absent rather than present-and-zero, because the ledger records what happened and a synthesised
     * zero row is not a fact the ledger contains.
     *
     * <p>{@code evaluation} is {@code null} unless {@code evaluate=true} was requested; that is
     * distinct from an evaluation that ran and found nothing, which reports
     * {@code rulesConfigured} and empty lists.
     */
    public record AmlMonitoringResponse(String partnerId, String from, String to,
                                        List<DayView> days,
                                        @JsonFormat(shape = JsonFormat.Shape.STRING)
                                        BigDecimal windowNetUsd,
                                        long windowNetTxnCount,
                                        int daysWithActivity,
                                        CapsView configuredCaps,
                                        EvaluationView evaluation) {

        static AmlMonitoringResponse of(AmlWindowEvidence e, AmlEvaluation evaluation) {
            List<DayView> days = e.days().stream()
                    .map(d -> new DayView(d.dailyKey(), d.netUsd(), d.netTxnCount(), d.chargeCount()))
                    .toList();
            AmlWindowEvidence.ConfiguredCaps caps = e.caps();
            return new AmlMonitoringResponse(e.partnerId(), e.fromDailyKey(), e.toDailyKey(), days,
                    e.windowNetUsd(), e.windowNetTxnCount(), days.size(),
                    new CapsView(caps.dailyCapUsd(), caps.monthlyCapUsd(), caps.annualCapUsd(),
                            caps.dailyTxnCountCap()),
                    EvaluationView.of(evaluation));
        }
    }

    /**
     * One KST day. {@code netUsd} and {@code netTxnCount} are charges MINUS reverses;
     * {@code chargeCount} is charges only, so a day whose charges were all reversed reads as zero
     * volume with a non-zero attempt count rather than as an idle day.
     */
    public record DayView(String dailyKey,
                          @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal netUsd,
                          long netTxnCount, long chargeCount) { }

    /**
     * The partner's CONFIGURED AML caps, reported as context. A {@code null} field means
     * <b>unconstrained</b> for that period — materially different from a cap of zero, and the
     * distinction is preserved rather than defaulted away.
     */
    public record CapsView(@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal dailyCapUsd,
                           @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal monthlyCapUsd,
                           @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal annualCapUsd,
                           Integer dailyTxnCountCap) { }

    /**
     * Outcome of running the configured rules. {@code rulesConfigured = 0} is the shipped default and
     * means NOTHING was checked — it must not be read as "nothing was wrong".
     */
    public record EvaluationView(int rulesConfigured, List<FiredView> fired,
                                 List<UnevaluatedView> notEvaluated) {

        static EvaluationView of(AmlEvaluation evaluation) {
            if (evaluation == null) {
                return null;
            }
            return new EvaluationView(evaluation.rulesConfigured(),
                    evaluation.fired().stream()
                            .map(f -> new FiredView(f.rule(), f.metric().name(), f.observed(),
                                    f.threshold(), f.windowFrom(), f.windowTo(), f.windowDays(),
                                    f.alertRaised()))
                            .toList(),
                    evaluation.notEvaluated().stream()
                            .map(u -> new UnevaluatedView(u.rule(), u.reason()))
                            .toList());
        }
    }

    /**
     * One rule that tripped. {@code alertRaised=false} means the rule tripped but an identical
     * (partner, rule, window) alert had already been raised by this instance and was suppressed —
     * reported rather than hidden.
     */
    public record FiredView(String rule, String metric,
                            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal observed,
                            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal threshold,
                            String windowFrom, String windowTo, int windowDays,
                            boolean alertRaised) { }

    /** One rule that could not be run against this window, and why. */
    public record UnevaluatedView(String rule, String reason) { }
}
