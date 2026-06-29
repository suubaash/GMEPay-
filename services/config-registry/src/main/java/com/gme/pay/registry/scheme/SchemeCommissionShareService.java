package com.gme.pay.registry.scheme;

import com.gme.pay.contracts.SchemeCommissionShareCommand;
import com.gme.pay.contracts.SchemeCommissionShareView;
import com.gme.pay.registry.audit.AuditLogService;
import com.gme.pay.registry.web.SchemeCatalogResponse;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Owns the {@code scheme_commission_share} aggregate (V031) behind the QR-scheme
 * setup commission endpoints — the configurable GME ↔ scheme split of the net
 * merchant fee. There is <b>no fixed 70/30</b>; every scheme carries its own
 * {@code gmeSharePct} (and optional {@code vanFeePct}).
 *
 * <h2>Bulk-replace semantics</h2>
 *
 * <p>A save is a <b>bulk replace</b> of the scheme's whole share set: inside one
 * transaction every current row for the scheme is superseded
 * ({@code superseded_at = now}) and the new set is inserted
 * ({@code recorded_at = now}), both halves sharing one MICROS-truncated instant
 * — the SCD-6 paired-write discipline of {@code FeeScheduleService} (ADR-010).
 * An empty list clears all rows; {@code null} is rejected with 400.
 *
 * <h2>Key shape</h2>
 *
 * <p>At most one row per {@code direction} per save — {@code null} (= all
 * directions) is part of the key (one wildcard row allowed). Duplicates are a
 * 400 carrying the offending {@code commissionShares[i]} index.
 *
 * <h2>Audit (ADR-007)</h2>
 *
 * <p>One audit row per replace, {@code aggregateType="scheme_commission_share"},
 * keyed by the scheme code, published inside the same transaction.
 */
@Service
public class SchemeCommissionShareService {

    /** Aggregate-type discriminator on audit rows for scheme-commission mutations. */
    public static final String AGGREGATE_TYPE = "scheme_commission_share";

    /** Audit verb for the commission-share bulk replace. */
    public static final String EVENT_TYPE_REPLACED = "SCHEME_COMMISSION_SHARES_REPLACED";

    static final Set<String> DIRECTIONS = Set.of("INBOUND", "OUTBOUND", "BOTH");

    /** Default actor until the auth {@code sub} claim is threaded through. */
    private static final String DEFAULT_ACTOR = "system";

    private final SchemeCommissionShareRepository repository;
    private final SchemeCatalogService schemeCatalogService;
    private final ObjectProvider<AuditLogService> auditLogProvider;

    public SchemeCommissionShareService(SchemeCommissionShareRepository repository,
                                        SchemeCatalogService schemeCatalogService,
                                        ObjectProvider<AuditLogService> auditLogProvider) {
        this.repository = repository;
        this.schemeCatalogService = schemeCatalogService;
        this.auditLogProvider = auditLogProvider;
    }

    /**
     * Bulk-replace the commission-share set for a scheme.
     *
     * @throws ResponseStatusException 404 when the scheme code is unknown; 400
     *         on validation failure (bad direction roster / out-of-range share /
     *         over-scale value / duplicate direction).
     */
    @Transactional
    public List<SchemeCommissionShareView> replaceCommissionShares(
            String schemeId, List<SchemeCommissionShareCommand> shares, String actor) {
        if (shares == null) {
            throw badRequest(
                    "commissionShares is required (send an empty list to clear all rows)");
        }
        String scheme = requireKnownScheme(schemeId);

        for (int i = 0; i < shares.size(); i++) {
            validate(shares.get(i), i);
        }
        validateNoDuplicateKeys(shares);

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        List<SchemeCommissionShareEntity> prior = repository.findCurrentBySchemeId(scheme);
        byte[] before = prior.isEmpty() ? null : canonical(prior);

        if (!prior.isEmpty()) {
            for (SchemeCommissionShareEntity p : prior) {
                p.setSupersededAt(now);
            }
            repository.saveAllAndFlush(prior);
        }

        List<SchemeCommissionShareEntity> fresh = new ArrayList<>(shares.size());
        for (SchemeCommissionShareCommand cmd : shares) {
            SchemeCommissionShareEntity e = new SchemeCommissionShareEntity();
            e.setSchemeId(scheme);
            e.setDirection(blankToNull(cmd.direction()));
            e.setGmeSharePct(normalizeScale4(cmd.gmeSharePct()));
            e.setVanFeePct(normalizeScale4(
                    cmd.vanFeePct() == null ? BigDecimal.ZERO : cmd.vanFeePct()));
            e.setRecordedAt(now);
            e.setValidFrom(now);
            fresh.add(e);
        }
        List<SchemeCommissionShareEntity> saved = repository.saveAllAndFlush(fresh);

        publishAudit(scheme, actor, before, saved.isEmpty() ? null : canonical(saved));
        return saved.stream().map(SchemeCommissionShareEntity::toView).toList();
    }

    /**
     * The CURRENT commission-share set for the scheme (empty list when none).
     *
     * @throws ResponseStatusException 404 when the scheme code is unknown.
     */
    @Transactional(readOnly = true)
    public List<SchemeCommissionShareView> currentCommissionShares(String schemeId) {
        String scheme = requireKnownScheme(schemeId);
        return repository.findCurrentBySchemeId(scheme).stream()
                .map(SchemeCommissionShareEntity::toView)
                .toList();
    }

    // -------------------------- Helpers --------------------------------------

    /** Resolve the scheme against the master catalog (case-insensitive), 404 if unknown. */
    private String requireKnownScheme(String schemeId) {
        if (schemeId == null || schemeId.isBlank()) {
            throw badRequest("schemeId is required");
        }
        return schemeCatalogService.listSchemes().stream()
                .map(SchemeCatalogResponse::schemeId)
                .filter(id -> id.equalsIgnoreCase(schemeId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Unknown scheme: " + schemeId));
    }

    private void publishAudit(String schemeId, String actor, byte[] before, byte[] after) {
        AuditLogService auditLog = auditLogProvider.getIfAvailable();
        if (auditLog != null) {
            auditLog.publish(
                    AGGREGATE_TYPE,
                    schemeId,
                    actor == null || actor.isBlank() ? DEFAULT_ACTOR : actor,
                    null,
                    EVENT_TYPE_REPLACED,
                    before,
                    after);
        }
    }

    private static void validate(SchemeCommissionShareCommand cmd, int index) {
        String at = "commissionShares[" + index + "]";
        if (cmd == null) {
            throw badRequest(at + " must be an object");
        }
        if (cmd.direction() != null && !cmd.direction().isBlank()
                && !DIRECTIONS.contains(cmd.direction())) {
            throw badRequest(at + ".direction must be one of " + DIRECTIONS
                    + " (or null for all directions), was: " + cmd.direction());
        }
        validateSharePct(at + ".gmeSharePct", cmd.gmeSharePct(), true);
        validateRate(at + ".vanFeePct", cmd.vanFeePct());
    }

    /** At most one row per direction per save — null (=all) is part of the key. */
    private static void validateNoDuplicateKeys(List<SchemeCommissionShareCommand> shares) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < shares.size(); i++) {
            String key = blankToNull(shares.get(i).direction()) == null
                    ? "*" : shares.get(i).direction();
            if (!seen.add(key)) {
                throw badRequest(at(i) + ": duplicate direction " + key
                        + " — at most one row per direction");
            }
        }
    }

    private static String at(int i) {
        return "commissionShares[" + i + "]";
    }

    /**
     * A share fraction: required (when {@code strictlyPositive}) in (0,1],
     * else [0,1]; at most 4 decimal places.
     */
    /** NUMERIC(7,4) ceiling for the VAN rate column (3 integer digits, scale 4). */
    private static final BigDecimal VAN_MAX = new BigDecimal("999.9999");

    private static void validateSharePct(String field, BigDecimal value, boolean strictlyPositive) {
        if (value == null) {
            throw badRequest(field + " is required");
        }
        // stripTrailingZeros so a padded "0.70000" is treated as 1 dp (matches the
        // partner side and the whole FeeSchedule money convention).
        if (value.stripTrailingZeros().scale() > 4) {
            throw badRequest(field + " must have at most 4 decimal places, was: "
                    + value.toPlainString());
        }
        int lower = value.compareTo(BigDecimal.ZERO);
        if (strictlyPositive ? lower <= 0 : lower < 0) {
            throw badRequest(field + " must be "
                    + (strictlyPositive ? "> 0" : ">= 0") + ", was: " + value.toPlainString());
        }
        if (value.compareTo(BigDecimal.ONE) > 0) {
            throw badRequest(field + " must be <= 1 (a fraction, e.g. 0.7000 = 70%), was: "
                    + value.toPlainString());
        }
    }

    /** A non-negative rate that must fit NUMERIC(7,4); null allowed (defaults to 0). */
    private static void validateRate(String field, BigDecimal value) {
        if (value == null) {
            return;
        }
        if (value.stripTrailingZeros().scale() > 4) {
            throw badRequest(field + " must have at most 4 decimal places, was: "
                    + value.toPlainString());
        }
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            throw badRequest(field + " must be >= 0, was: " + value.toPlainString());
        }
        // Guard the NUMERIC(7,4) envelope so an over-range value is a clean 400, not a
        // DataIntegrityViolation 500 on write.
        if (value.compareTo(VAN_MAX) > 0) {
            throw badRequest(field + " must be <= " + VAN_MAX.toPlainString()
                    + " (NUMERIC(7,4)), was: " + value.toPlainString());
        }
    }

    private static BigDecimal normalizeScale4(BigDecimal value) {
        return value == null ? null : value.setScale(4, java.math.RoundingMode.UNNECESSARY);
    }

    /** Deterministic snapshot for the ADR-007 audit chain (one line per row, id order). */
    private static byte[] canonical(List<SchemeCommissionShareEntity> rows) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            SchemeCommissionShareEntity r = rows.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"direction\":")
                    .append(r.getDirection() == null ? "null" : "\"" + r.getDirection() + "\"")
                    .append(",\"gmeSharePct\":\"").append(plain(r.getGmeSharePct())).append('"')
                    .append(",\"vanFeePct\":\"").append(plain(r.getVanFeePct())).append("\"}");
        }
        return sb.append(']').toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String plain(BigDecimal v) {
        return v == null ? "0" : v.toPlainString();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
