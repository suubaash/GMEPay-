package com.gme.pay.registry.corridor;

import com.gme.pay.contracts.PartnerCorridorCommand;
import com.gme.pay.contracts.PartnerCorridorView;
import com.gme.pay.contracts.PartnerStatus;
import com.gme.pay.registry.audit.AuditLogService;
import com.gme.pay.registry.persistence.PartnerEntity;
import com.gme.pay.registry.persistence.PartnerRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Slice 7 — owns the {@code partner_corridor} child aggregate (V023) behind
 * the wizard's step-7 corridor-matrix endpoints (see
 * {@code docs/PARTNER_SETUP_PLAN.md} §"Slice 7 — Schemes &amp; Corridors").
 *
 * <h2>Bulk-replace semantics</h2>
 *
 * <p>The wizard's contract is "send the full corridor set on every save", so a
 * PATCH is a <b>bulk replace</b>: inside one transaction every current
 * {@code partner_corridor} row for the partner is superseded
 * ({@code superseded_at = now}) and the new set is inserted
 * ({@code recorded_at = now}), both halves sharing the same MICROS-truncated
 * instant — the SCD-6 paired-write discipline of {@code RuleService}
 * (ADR-010). Sending an empty list clears all corridors; {@code null} is a
 * 400.
 *
 * <h2>Corridor key</h2>
 *
 * <p>A corridor is keyed by (partner × srcCountry × srcCcy × dstCountry ×
 * dstCcy). At most one element per key in the payload — the V023
 * partial-unique index (stored GENERATED {@code is_current} + composite
 * UNIQUE) is the storage-level backstop; the payload-level duplicate check
 * keeps the error a friendly indexed 400 instead of a constraint violation.
 *
 * <h2>Audit (ADR-007)</h2>
 *
 * <p>One audit row per write, {@code aggregateType="partner_corridor"}, keyed
 * by the partner business code, BEFORE/AFTER = {@link CorridorJson} canonical
 * snapshots, published inside the same transaction through the
 * {@link ObjectProvider}-resolved {@link AuditLogService} — the same wiring
 * contract as {@code RuleService} / {@code PartnerStore}.
 */
@Service
public class PartnerCorridorService {

    /** Aggregate-type discriminator on audit rows for corridor mutations. */
    public static final String AGGREGATE_TYPE = "partner_corridor";

    /** Audit verb for the step-7 bulk replace. */
    public static final String EVENT_TYPE_REPLACED = "PARTNER_CORRIDORS_REPLACED";

    /** ISO-3166 alpha-2, UPPERCASE (the V023 CHAR(2) envelope). */
    static final Pattern COUNTRY = Pattern.compile("[A-Z]{2}");

    /** ISO-4217, UPPERCASE (the V023 CHAR(3) envelope). */
    static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    /** Default actor until the Keycloak {@code sub} claim is threaded through (Slice 1B.4 carve-out). */
    private static final String DEFAULT_ACTOR = "system";

    private final PartnerCorridorRepository corridorRepository;
    private final PartnerRepository partnerRepository;
    private final ObjectProvider<AuditLogService> auditLogProvider;

    public PartnerCorridorService(PartnerCorridorRepository corridorRepository,
                                  PartnerRepository partnerRepository,
                                  ObjectProvider<AuditLogService> auditLogProvider) {
        this.corridorRepository = corridorRepository;
        this.partnerRepository = partnerRepository;
        this.auditLogProvider = auditLogProvider;
    }

    /**
     * Bulk-replace the corridor set on a draft partner (wizard step-7 "Next").
     *
     * @param partnerCode the human-facing business code routing the PATCH.
     * @param corridors   the FULL desired set; empty clears, {@code null} is a 400.
     * @param actor       the operator (X-Actor header); {@code "system"} when absent.
     * @return the freshly-inserted current set as canonical {@link PartnerCorridorView}s.
     * @throws ResponseStatusException 404 when no current partner row matches;
     *         409 when the partner is no longer in {@code ONBOARDING}
     *         (post-activation corridor changes ride the change_request
     *         approval flow with the Slice 8 FSM); 400 on any validation
     *         failure, reported with the offending {@code corridors[i]} index
     *         so the matrix builder can highlight the row.
     */
    @Transactional
    public List<PartnerCorridorView> replaceDraftCorridors(String partnerCode,
                                                           List<PartnerCorridorCommand> corridors,
                                                           String actor) {
        if (corridors == null) {
            throw badRequest("corridors is required (send an empty list to clear all corridors)");
        }
        PartnerEntity partner = requirePartner(partnerCode);
        if (partner.getStatus() != PartnerStatus.ONBOARDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "partner '" + partnerCode + "' is in status " + partner.getStatus()
                            + ", step-7 corridor edits are only permitted while ONBOARDING"
                            + " (post-activation corridor changes require the change_request"
                            + " approval flow)");
        }
        // Validate the WHOLE payload before touching any row — a bad element
        // must not leave the set half-replaced (fail fast, side-effect free).
        for (int i = 0; i < corridors.size(); i++) {
            validate(corridors.get(i), i);
        }
        validateNoDuplicateLanes(corridors);

        // One transaction-time instant shared by both halves of the paired
        // write (supersede + insert), truncated to MICROS — see PartnerStore.save.
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        List<PartnerCorridorEntity> prior =
                corridorRepository.findAllCurrentByPartnerId(partner.getId());
        byte[] before = prior.isEmpty() ? null : CorridorJson.canonical(prior);

        // Supersede the prior current set first (flush forces the UPDATEs out
        // before the INSERTs so the V023 partial-unique index never sees two
        // current rows per lane mid-transaction — the generated is_current
        // column recomputes to NULL on the UPDATE, vacating the index slot;
        // same SCD-6 ordering as RuleService).
        if (!prior.isEmpty()) {
            for (PartnerCorridorEntity p : prior) {
                p.setSupersededAt(now);
            }
            corridorRepository.saveAllAndFlush(prior);
        }

        // Insert the new current set. IDENTITY ids are assigned at flush; the
        // RETURNED managed entities carry them, which the audit AFTER snapshot
        // and the response views both need.
        List<PartnerCorridorEntity> fresh = new ArrayList<>(corridors.size());
        for (PartnerCorridorCommand cmd : corridors) {
            fresh.add(toEntity(partner.getId(), cmd, now));
        }
        List<PartnerCorridorEntity> saved = corridorRepository.saveAllAndFlush(fresh);

        publishAudit(partnerCode, actor, before, CorridorJson.canonical(saved));

        return saved.stream().map(PartnerCorridorEntity::toView).toList();
    }

    /**
     * The CURRENT corridor set for the given partner code (no historical rows).
     *
     * @throws ResponseStatusException 404 when no current partner row matches —
     *         "partner exists with zero corridors" returns an empty list, only
     *         an unknown code 404s.
     */
    @Transactional(readOnly = true)
    public List<PartnerCorridorView> currentCorridors(String partnerCode) {
        PartnerEntity partner = requirePartner(partnerCode);
        return corridorRepository.findAllCurrentByPartnerId(partner.getId()).stream()
                .map(PartnerCorridorEntity::toView)
                .toList();
    }

    // -------------------------- Helpers --------------------------------------

    private PartnerEntity requirePartner(String partnerCode) {
        return partnerRepository.findCurrentByPartnerCode(partnerCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "no partner '" + partnerCode + "'"));
    }

    /** ADR-007 audit row, same-transaction (commits iff the business write commits). */
    private void publishAudit(String partnerCode, String actor, byte[] before, byte[] after) {
        AuditLogService auditLog = auditLogProvider.getIfAvailable();
        if (auditLog != null) {
            auditLog.publish(
                    AGGREGATE_TYPE,
                    partnerCode,
                    actor == null || actor.isBlank() ? DEFAULT_ACTOR : actor,
                    null,
                    EVENT_TYPE_REPLACED,
                    before,
                    after);
        }
    }

    /** Build a fresh current row from one validated command. */
    private static PartnerCorridorEntity toEntity(Long partnerId,
                                                  PartnerCorridorCommand cmd,
                                                  Instant now) {
        PartnerCorridorEntity e = new PartnerCorridorEntity();
        e.setPartnerId(partnerId);
        e.setSrcCountry(cmd.srcCountry());
        e.setSrcCcy(cmd.srcCcy());
        e.setDstCountry(cmd.dstCountry());
        e.setDstCcy(cmd.dstCcy());
        e.setGoLiveDate(cmd.goLiveDate());
        // Mirrors the V023 column DEFAULT TRUE when the wire omits the toggle.
        e.setIsActive(cmd.isActive() == null ? Boolean.TRUE : cmd.isActive());
        e.setRecordedAt(now);
        // Business time starts at capture — the wizard does not back-date corridors.
        e.setValidFrom(now);
        return e;
    }

    /**
     * Field-format validation for one corridor element. Index-qualified
     * messages ({@code corridors[2].srcCcy ...}) so the matrix builder can map
     * the 400 to the offending row. The cross-element duplicate check runs
     * separately once every element passes.
     */
    private static void validate(PartnerCorridorCommand cmd, int index) {
        String at = "corridors[" + index + "]";
        if (cmd == null) {
            throw badRequest(at + " must be an object");
        }
        validateCountry(at + ".srcCountry", cmd.srcCountry());
        validateCurrency(at + ".srcCcy", cmd.srcCcy());
        validateCountry(at + ".dstCountry", cmd.dstCountry());
        validateCurrency(at + ".dstCcy", cmd.dstCcy());
    }

    /** One country code against the V023 CHAR(2) envelope: required, ISO-3166 alpha-2 UPPERCASE. */
    private static void validateCountry(String field, String value) {
        if (value == null || value.isBlank()) {
            throw badRequest(field + " is required (ISO-3166 alpha-2, e.g. KR)");
        }
        if (!COUNTRY.matcher(value).matches()) {
            throw badRequest(field + " must be an UPPERCASE ISO-3166 alpha-2 country code"
                    + " (e.g. KR), was: " + value);
        }
    }

    /** One currency code against the V023 CHAR(3) envelope: required, ISO-4217 UPPERCASE. */
    private static void validateCurrency(String field, String value) {
        if (value == null || value.isBlank()) {
            throw badRequest(field + " is required (ISO-4217, e.g. KRW)");
        }
        if (!CURRENCY.matcher(value).matches()) {
            throw badRequest(field + " must be an UPPERCASE ISO-4217 currency code"
                    + " (e.g. KRW), was: " + value);
        }
    }

    /**
     * At most one corridor per (srcCountry, srcCcy, dstCountry, dstCcy) lane
     * across the payload (the payload IS the full current state under replace
     * semantics, so the payload-level check is the V023 partial-unique
     * invariant).
     */
    private static void validateNoDuplicateLanes(List<PartnerCorridorCommand> corridors) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < corridors.size(); i++) {
            PartnerCorridorCommand cmd = corridors.get(i);
            String lane = cmd.srcCountry() + ":" + cmd.srcCcy()
                    + ":" + cmd.dstCountry() + ":" + cmd.dstCcy();
            if (!seen.add(lane)) {
                throw badRequest("corridors[" + i + "]: duplicate corridor "
                        + cmd.srcCountry() + "/" + cmd.srcCcy()
                        + " -> " + cmd.dstCountry() + "/" + cmd.dstCcy()
                        + " (at most one row per corridor lane)");
            }
        }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
