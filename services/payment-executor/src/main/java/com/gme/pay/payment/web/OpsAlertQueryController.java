package com.gme.pay.payment.web;

import com.gme.pay.payment.persistence.OpsAlertArchive;
import com.gme.pay.payment.persistence.OpsAlertEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bounded ops read surface over the durable alert archive (gap T3-3).
 *
 * <p><b>{@code GET /internal/ops/alerts}</b> — the answer to "what fired overnight?", which before
 * Flyway V006 could only be answered by whatever happened to still be in ops-partner-bff's in-memory
 * deque (nothing, after any restart) or by log-mining a container with no log aggregation.
 *
 * <h2>Authorisation</h2>
 * <p>Mounted under {@code /internal/**}, which {@code SandboxSurfaceInternalAuthConfig} gates
 * <b>unconditionally</b> with the platform internal token ({@code X-Gme-Internal}) — including when no
 * secret is configured, in which case every caller is refused 401 (a blank configured secret can never
 * equal a presented one). Fail-closed: alert detail names partners and schemes and their decline rates,
 * so it must never be anonymous.
 *
 * <h2>Bounded by construction</h2>
 * <p>{@code limit} is clamped by {@link OpsAlertArchive#MAX_LIMIT}; there is no offset/scan parameter,
 * so no caller can walk the whole table. Filters are exact, case-insensitive matches on severity and
 * alertType.
 */
@RestController
@RequestMapping("/internal/ops/alerts")
@Tag(name = "Ops alerts (internal)",
        description = "Durable ops-alert history emitted by payment-executor (gap T3-3)")
public class OpsAlertQueryController {

    private final OpsAlertArchive archive;

    public OpsAlertQueryController(OpsAlertArchive archive) {
        this.archive = archive;
    }

    /**
     * Recent alerts, newest first.
     *
     * @param severity  optional exact filter: {@code INFO} | {@code WARN} | {@code CRITICAL}
     * @param alertType optional exact filter, e.g. {@code DECLINE_SPIKE}
     * @param limit     page size; defaults to 50, hard-capped at {@link OpsAlertArchive#MAX_LIMIT}
     */
    @GetMapping
    @Operation(summary = "Recent ops alerts (newest first, bounded)")
    public List<OpsAlertResponse> recent(
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String alertType,
            @RequestParam(defaultValue = "50") int limit) {
        return archive.recent(severity, alertType, limit).stream()
                .map(OpsAlertResponse::from)
                .toList();
    }

    /**
     * One archived alert plus the outcome of its outbound notification, so an operator can see both
     * "what fired" and "was anyone actually told" in a single row.
     */
    public record OpsAlertResponse(
            Long id,
            String alertType,
            String severity,
            String subjectRef,
            String detail,
            Instant occurredAt,
            Instant recordedAt,
            String notifyStatus,
            String notifyChannel,
            String notifyError) {

        static OpsAlertResponse from(OpsAlertEntity e) {
            return new OpsAlertResponse(
                    e.getId(), e.getAlertType(), e.getSeverity(), e.getSubjectRef(), e.getDetail(),
                    e.getOccurredAt(), e.getCreatedAt(),
                    e.getNotifyStatus(), e.getNotifyChannel(), e.getNotifyError());
        }
    }
}
