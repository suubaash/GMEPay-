package com.gme.pay.registry.scheme;

import com.gme.pay.contracts.MerchantFeeScheduleCommand;
import com.gme.pay.contracts.MerchantFeeScheduleView;
import com.gme.pay.registry.audit.AuditLogService;
import com.gme.pay.registry.web.SchemeCatalogResponse;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Owns the {@code merchant_fee_schedule} aggregate (V032) behind QR-scheme
 * setup — the configurable GROSS merchant fee rate by (scheme × merchant type),
 * the INPUT to the V031 commission split. Mirrors
 * {@code SchemeCommissionShareService}'s SCD-6 bulk-replace + audit discipline.
 *
 * <p>Also exposes {@link #resolveRate} — the most-specific (exact merchant type
 * beats the {@code merchant_type = NULL} default) lookup the payment path uses
 * to snapshot the rate onto a transaction at creation.
 */
@Service
public class MerchantFeeScheduleService {

    public static final String AGGREGATE_TYPE = "merchant_fee_schedule";
    public static final String EVENT_TYPE_REPLACED = "MERCHANT_FEE_SCHEDULE_REPLACED";

    private static final String DEFAULT_ACTOR = "system";

    /** NUMERIC(7,4) ceiling — a fee RATE above 100% is nonsensical. */
    private static final BigDecimal MAX_RATE = BigDecimal.ONE;

    private final MerchantFeeScheduleRepository repository;
    private final SchemeCatalogService schemeCatalogService;
    private final ObjectProvider<AuditLogService> auditLogProvider;

    public MerchantFeeScheduleService(MerchantFeeScheduleRepository repository,
                                      SchemeCatalogService schemeCatalogService,
                                      ObjectProvider<AuditLogService> auditLogProvider) {
        this.repository = repository;
        this.schemeCatalogService = schemeCatalogService;
        this.auditLogProvider = auditLogProvider;
    }

    /**
     * Bulk-replace the merchant-fee set for a scheme.
     *
     * @throws ResponseStatusException 404 unknown scheme; 400 on validation
     *         failure (rate out of [0,1] / over-scale / duplicate merchant type).
     */
    @Transactional
    public List<MerchantFeeScheduleView> replaceMerchantFees(
            String schemeId, List<MerchantFeeScheduleCommand> fees, String actor) {
        if (fees == null) {
            throw badRequest("merchantFees is required (send an empty list to clear all rows)");
        }
        String scheme = requireKnownScheme(schemeId);

        for (int i = 0; i < fees.size(); i++) {
            validate(fees.get(i), i);
        }
        validateNoDuplicateKeys(fees);

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        List<MerchantFeeScheduleEntity> prior = repository.findCurrentBySchemeId(scheme);
        byte[] before = prior.isEmpty() ? null : canonical(prior);

        if (!prior.isEmpty()) {
            for (MerchantFeeScheduleEntity p : prior) {
                p.setSupersededAt(now);
            }
            repository.saveAllAndFlush(prior);
        }

        List<MerchantFeeScheduleEntity> fresh = new ArrayList<>(fees.size());
        for (MerchantFeeScheduleCommand cmd : fees) {
            MerchantFeeScheduleEntity e = new MerchantFeeScheduleEntity();
            e.setSchemeId(scheme);
            e.setMerchantType(blankToNull(cmd.merchantType()));
            e.setMerchantFeePct(normalizeScale4(cmd.merchantFeePct()));
            e.setRecordedAt(now);
            e.setValidFrom(now);
            fresh.add(e);
        }
        List<MerchantFeeScheduleEntity> saved = repository.saveAllAndFlush(fresh);

        publishAudit(scheme, actor, before, saved.isEmpty() ? null : canonical(saved));
        return saved.stream().map(MerchantFeeScheduleEntity::toView).toList();
    }

    /** The CURRENT merchant-fee set for the scheme (empty when none). 404 unknown scheme. */
    @Transactional(readOnly = true)
    public List<MerchantFeeScheduleView> currentMerchantFees(String schemeId) {
        String scheme = requireKnownScheme(schemeId);
        return repository.findCurrentBySchemeId(scheme).stream()
                .map(MerchantFeeScheduleEntity::toView)
                .toList();
    }

    /**
     * Resolve the effective gross fee rate for a (scheme, merchantType): the
     * exact merchant-type row wins over the {@code merchant_type = NULL} default;
     * empty when neither is configured. Used by the payment path to snapshot the
     * rate onto a transaction at creation. Does NOT validate the scheme (lenient
     * — settlement/payment callers pass whatever the txn carried).
     */
    @Transactional(readOnly = true)
    public Optional<BigDecimal> resolveRate(String schemeId, String merchantType) {
        if (schemeId == null || schemeId.isBlank()) {
            return Optional.empty();
        }
        String type = blankToNull(merchantType);
        MerchantFeeScheduleEntity wildcard = null;
        for (MerchantFeeScheduleEntity r : repository.findCurrentBySchemeId(schemeId)) {
            if (r.getMerchantType() == null) {
                wildcard = r;
            } else if (r.getMerchantType().equals(type)) {
                return Optional.of(r.getMerchantFeePct()); // exact type — most specific
            }
        }
        return Optional.ofNullable(wildcard).map(MerchantFeeScheduleEntity::getMerchantFeePct);
    }

    // -------------------------- Helpers --------------------------------------

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
            auditLog.publish(AGGREGATE_TYPE, schemeId,
                    actor == null || actor.isBlank() ? DEFAULT_ACTOR : actor,
                    null, EVENT_TYPE_REPLACED, before, after);
        }
    }

    private static void validate(MerchantFeeScheduleCommand cmd, int index) {
        String at = "merchantFees[" + index + "]";
        if (cmd == null) {
            throw badRequest(at + " must be an object");
        }
        if (cmd.merchantType() != null && !cmd.merchantType().isBlank()
                && cmd.merchantType().length() > 40) {
            throw badRequest(at + ".merchantType must be at most 40 characters");
        }
        BigDecimal pct = cmd.merchantFeePct();
        if (pct == null) {
            throw badRequest(at + ".merchantFeePct is required");
        }
        if (pct.stripTrailingZeros().scale() > 4) {
            throw badRequest(at + ".merchantFeePct must have at most 4 decimal places, was: "
                    + pct.toPlainString());
        }
        if (pct.compareTo(BigDecimal.ZERO) < 0 || pct.compareTo(MAX_RATE) > 0) {
            throw badRequest(at + ".merchantFeePct must be between 0 and 1 (a fraction, e.g."
                    + " 0.0080 = 0.80%), was: " + pct.toPlainString());
        }
    }

    /** At most one row per merchant type per save — null (= default) is part of the key. */
    private static void validateNoDuplicateKeys(List<MerchantFeeScheduleCommand> fees) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < fees.size(); i++) {
            String key = blankToNull(fees.get(i).merchantType()) == null
                    ? "*" : fees.get(i).merchantType();
            if (!seen.add(key)) {
                throw badRequest("merchantFees[" + i + "]: duplicate merchant type " + key
                        + " — at most one row per type");
            }
        }
    }

    private static BigDecimal normalizeScale4(BigDecimal value) {
        return value == null ? null : value.setScale(4, java.math.RoundingMode.UNNECESSARY);
    }

    /** Deterministic snapshot for the ADR-007 audit chain (one line per row, id order). */
    private static byte[] canonical(List<MerchantFeeScheduleEntity> rows) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            MerchantFeeScheduleEntity r = rows.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"merchantType\":")
                    .append(r.getMerchantType() == null ? "null" : "\"" + r.getMerchantType() + "\"")
                    .append(",\"merchantFeePct\":\"")
                    .append(r.getMerchantFeePct() == null ? "0" : r.getMerchantFeePct().toPlainString())
                    .append("\"}");
        }
        return sb.append(']').toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
