package com.gme.pay.registry.ops;

import com.gme.pay.contracts.OperationalStatusView;
import com.gme.pay.registry.audit.AuditLogService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/**
 * The Operations kill-switch. Owns the global pause / maintenance flags and the
 * per-entity emergency suspensions, and projects them onto the shared
 * {@link OperationalStatusView} read model.
 *
 * <h2>Emergency, not 4-eyes</h2>
 *
 * <p>Unlike the config change-request flow, kill-switch actions are
 * <b>single-operator and immediate</b> — the whole point of a kill switch is that
 * one operator can pull it now. The 4-eyes gate would defeat that. In exchange,
 * <b>every</b> action is hash-chain audited (ADR-007) with the operator and the
 * reason, so the who/what/when/why is regulator-defensible after the fact.
 *
 * <h2>Idempotency</h2>
 *
 * <p>All commands are idempotent: pausing an already-paused system, suspending an
 * already-suspended entity, or unsuspending something not suspended is a no-op
 * that still returns the current state. An audit row is written only when the
 * state actually changed, so replays do not spam the chain.
 *
 * <h2>Audit key</h2>
 *
 * <p>Global actions chain under aggregate {@code ops-control} / id {@code global};
 * per-entity actions chain under {@code ops-suspension} / id {@code TYPE:id} so a
 * given entity's suspend/unsuspend history is a self-contained chain.
 */
@Service
public class OpsControlService {

    static final String AGG_CONTROL = "ops-control";
    static final String AGG_SUSPENSION = "ops-suspension";
    static final String GLOBAL_ID = "global";
    static final String DEFAULT_ACTOR = "ops";

    private final OpsControlRepository controlRepository;
    private final OpsSuspensionRepository suspensionRepository;
    private final AuditLogService auditLog;

    public OpsControlService(OpsControlRepository controlRepository,
                             OpsSuspensionRepository suspensionRepository,
                             AuditLogService auditLog) {
        this.controlRepository = controlRepository;
        this.suspensionRepository = suspensionRepository;
        this.auditLog = auditLog;
    }

    // ---- Read --------------------------------------------------------------

    /**
     * Project the current control row + active suspensions onto the shared
     * {@link OperationalStatusView}. Returns the {@link OperationalStatusView#allClear()}
     * shape when nothing is paused, in maintenance, or suspended.
     */
    @Transactional(readOnly = true)
    public OperationalStatusView status() {
        OpsControlEntity control = requireControl();
        List<OpsSuspensionEntity> active = suspensionRepository.findAllActive();

        boolean anySuspension = !active.isEmpty();
        if (!control.isSystemPaused() && !control.isMaintenanceMode() && !anySuspension) {
            return OperationalStatusView.allClear();
        }

        List<String> partners = new ArrayList<>();
        List<String> schemes = new ArrayList<>();
        List<String> routes = new ArrayList<>();
        for (OpsSuspensionEntity s : active) {
            switch (s.getEntityType()) {
                case "PARTNER" -> partners.add(s.getEntityId());
                case "SCHEME" -> schemes.add(s.getEntityId());
                case "ROUTE" -> routes.add(s.getEntityId());
                default -> { /* CHECK-guarded; ignore any stray value defensively */ }
            }
        }

        // `since` reflects the global flag stamp when set, else the earliest active
        // suspension; `reason` mirrors the global reason when a global flag is on.
        Instant since = control.getSince();
        String reason = control.getReason();
        if (since == null && anySuspension) {
            since = active.stream()
                    .map(OpsSuspensionEntity::getCreatedAt)
                    .min(Instant::compareTo)
                    .orElse(null);
        }

        return new OperationalStatusView(
                control.isSystemPaused(),
                control.isMaintenanceMode(),
                List.copyOf(partners),
                List.copyOf(schemes),
                List.copyOf(routes),
                reason,
                since == null ? null : since.toString());
    }

    // ---- Global actions ----------------------------------------------------

    /** Engage the global master kill switch. Idempotent. */
    @Transactional
    public OperationalStatusView pause(String reason, String actor, String actorIp) {
        OpsControlEntity control = requireControl();
        if (!control.isSystemPaused()) {
            control.setSystemPaused(true);
            control.setReason(reason);
            stamp(control, actor);
            controlRepository.saveAndFlush(control);
            audit(actor, actorIp, "OPS_PAUSED", reason);
        }
        return status();
    }

    /** Release the global master kill switch. Idempotent. */
    @Transactional
    public OperationalStatusView resume(String actor, String actorIp) {
        OpsControlEntity control = requireControl();
        if (control.isSystemPaused()) {
            control.setSystemPaused(false);
            // Clear the reason/since only when maintenance is also off, so a
            // resume that leaves maintenance engaged keeps its narrative.
            if (!control.isMaintenanceMode()) {
                control.setReason(null);
                control.setSince(null);
            }
            control.setUpdatedBy(actor(actor));
            control.setUpdatedAt(now());
            controlRepository.saveAndFlush(control);
            audit(actor, actorIp, "OPS_RESUMED", null);
        }
        return status();
    }

    /** Toggle maintenance mode on/off. Idempotent. */
    @Transactional
    public OperationalStatusView maintenance(boolean on, String reason, String actor, String actorIp) {
        OpsControlEntity control = requireControl();
        if (control.isMaintenanceMode() != on) {
            control.setMaintenanceMode(on);
            if (on) {
                control.setReason(reason);
                stamp(control, actor);
            } else if (!control.isSystemPaused()) {
                control.setReason(null);
                control.setSince(null);
                control.setUpdatedBy(actor(actor));
                control.setUpdatedAt(now());
            } else {
                control.setUpdatedBy(actor(actor));
                control.setUpdatedAt(now());
            }
            controlRepository.saveAndFlush(control);
            audit(actor, actorIp, on ? "OPS_MAINTENANCE_ON" : "OPS_MAINTENANCE_OFF", reason);
        }
        return status();
    }

    // ---- Per-entity actions ------------------------------------------------

    /** Suspend a single PARTNER / SCHEME / ROUTE. Idempotent. */
    @Transactional
    public OperationalStatusView suspend(String entityType, String entityId, String reason,
                                         String actor, String actorIp) {
        String type = normaliseType(entityType);
        String id = requireId(entityId);
        Optional<OpsSuspensionEntity> existing = suspensionRepository.findByEntity(type, id);

        boolean changed;
        if (existing.isPresent()) {
            OpsSuspensionEntity row = existing.get();
            changed = !row.isActive();
            row.setActive(true);
            row.setReason(reason);
            if (changed) {
                row.setCreatedBy(actor(actor));
                row.setCreatedAt(now());
            }
            suspensionRepository.saveAndFlush(row);
        } else {
            OpsSuspensionEntity row = new OpsSuspensionEntity();
            row.setEntityType(type);
            row.setEntityId(id);
            row.setReason(reason);
            row.setActive(true);
            row.setCreatedBy(actor(actor));
            row.setCreatedAt(now());
            suspensionRepository.saveAndFlush(row);
            changed = true;
        }
        if (changed) {
            auditSuspension(type, id, actor, actorIp, "OPS_SUSPENDED", reason);
        }
        return status();
    }

    /** Clear a single suspension. Idempotent. */
    @Transactional
    public OperationalStatusView unsuspend(String entityType, String entityId,
                                           String actor, String actorIp) {
        String type = normaliseType(entityType);
        String id = requireId(entityId);
        Optional<OpsSuspensionEntity> existing = suspensionRepository.findByEntity(type, id);
        if (existing.isPresent() && existing.get().isActive()) {
            OpsSuspensionEntity row = existing.get();
            row.setActive(false);
            suspensionRepository.saveAndFlush(row);
            auditSuspension(type, id, actor, actorIp, "OPS_UNSUSPENDED", null);
        }
        return status();
    }

    // ---- Internals ---------------------------------------------------------

    private OpsControlEntity requireControl() {
        return controlRepository.findById(OpsControlEntity.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException(
                        "ops_control singleton row missing — V038 seed not applied"));
    }

    private void stamp(OpsControlEntity control, String actor) {
        Instant now = now();
        if (control.getSince() == null) {
            control.setSince(now);
        }
        control.setUpdatedBy(actor(actor));
        control.setUpdatedAt(now);
    }

    private static String normaliseType(String entityType) {
        if (entityType == null || entityType.isBlank()) {
            throw badRequest("entityType is required (PARTNER|SCHEME|ROUTE)");
        }
        String t = entityType.trim().toUpperCase(Locale.ROOT);
        if (!t.equals("PARTNER") && !t.equals("SCHEME") && !t.equals("ROUTE")) {
            throw badRequest("entityType must be one of PARTNER|SCHEME|ROUTE");
        }
        return t;
    }

    private static String requireId(String entityId) {
        if (entityId == null || entityId.isBlank()) {
            throw badRequest("entityId is required");
        }
        return entityId.trim();
    }

    private static ResponseStatusException badRequest(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private static String actor(String actor) {
        return actor == null || actor.isBlank() ? DEFAULT_ACTOR : actor;
    }

    private void audit(String actor, String actorIp, String eventType, String reason) {
        auditLog.publish(AGG_CONTROL, GLOBAL_ID, actor(actor), actorIp, eventType,
                null, snapshot(eventType, reason));
    }

    private void auditSuspension(String type, String id, String actor, String actorIp,
                                 String eventType, String reason) {
        auditLog.publish(AGG_SUSPENSION, type + ":" + id, actor(actor), actorIp, eventType,
                null, snapshot(eventType, reason));
    }

    /** Canonical after-snapshot bytes for the audit row (stable key order). */
    private static byte[] snapshot(String eventType, String reason) {
        String r = reason == null ? "null" : "\"" + reason.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        return ("{\"event\":\"" + eventType + "\",\"reason\":" + r + "}")
                .getBytes(StandardCharsets.UTF_8);
    }
}
