package com.gme.pay.registry.partner;

import com.gme.pay.changerequest.ChangeRequestState;
import com.gme.pay.contracts.AddressCommand;
import com.gme.pay.contracts.AddressView;
import com.gme.pay.contracts.PartnerCommand;
import com.gme.pay.contracts.PartnerStatus;
import com.gme.pay.contracts.PartnerView;
import com.gme.pay.domain.Partner;
import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import com.gme.pay.registry.changerequest.ChangeRequestEntity;
import com.gme.pay.registry.changerequest.ChangeRequestRepository;
import com.gme.pay.registry.persistence.PartnerEntity;
import com.gme.pay.registry.persistence.PartnerRepository;
import com.gme.pay.registry.changerequest.PartnerChangeRequestApplier;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Slice 1 (1C.2) — orchestrates the partner-wizard draft endpoints.
 *
 * <p>The draft endpoints are the operator-facing surface of the 8-step Partner
 * Setup wizard (docs/PARTNER_SETUP_PLAN.md §"Slice 1 — Identity + Foundation",
 * ADR-012). Each Next button POSTs the step's fields into a long-running row in
 * {@code partners} whose {@code status} is {@link PartnerStatus#ONBOARDING} and
 * whose paired {@code change_request} sits in {@link ChangeRequestState#DRAFT}.
 *
 * <h2>Why a dedicated service</h2>
 *
 * <p>{@link PartnerStore} is the canonical mutation path for partner rows — it
 * owns SCD-6 paired writes (UPDATE prior + INSERT new) and audit-log publication
 * (ADR-010 + ADR-007). It writes the legacy four-field aggregate ({@code Partner}
 * record); the Identity columns added by V007 are not yet on the record (the
 * Partner domain record stays slim until Slice 1's wider Identity-on-domain work
 * lands).
 *
 * <p>Rather than widen {@link PartnerStore} (and the {@link Partner} record) for
 * a single slice, this service writes the Identity fields directly onto the
 * {@link PartnerEntity} after the four-field write goes through
 * {@link PartnerStore#save}. The supersession discipline is preserved: the
 * Identity fields ride on the same new current row that {@link PartnerStore#save}
 * just produced; we mutate the entity in the same transaction, before it is
 * flushed back. When Slice 2+ folds Identity into the canonical
 * {@link Partner} record, this service collapses into a thin wrapper around
 * {@link PartnerStore}.
 *
 * <h2>Change-request shape</h2>
 *
 * <p>{@link ChangeRequestEntity} for a draft sits in {@link ChangeRequestState#DRAFT}
 * with {@code aggregateType="partner"}, {@code aggregateId=partner_code},
 * {@code proposedBy=actor} and a payload echoing the step-1 fields. The
 * {@link com.gme.pay.registry.changerequest.ChangeRequestService} owns the
 * DRAFT→PROPOSED→APPROVED→APPLIED transitions; this service only writes the
 * initial DRAFT row. The maker calls {@code submit}/{@code approve}/{@code apply}
 * via the 4-eyes endpoints (Slice 2 surfaces the approval queue).
 *
 * <h2>What this service does NOT do</h2>
 *
 * <ul>
 *   <li>It does not transition the change_request out of {@link ChangeRequestState#DRAFT}.
 *       The wizard saves drafts; activation submits and approves them.</li>
 *   <li>It does not enforce required-field validation. The wizard saves partial
 *       progress; the Slice 8 activation gate enforces "all required fields
 *       filled" before {@link PartnerStatus#ONBOARDING} → KYB_PENDING.</li>
 *   <li>It does not write to {@code audit_log} directly. {@link PartnerStore#save}
 *       publishes the audit row in the same transaction; the Identity-column
 *       updates apply to the same row {@link PartnerStore} just wrote, so the
 *       audit row already covers the change semantically. A dedicated
 *       "step-1 saved" audit verb lands in Slice 2 when {@code lib-audit} grows
 *       a per-field-set verb taxonomy.</li>
 * </ul>
 */
@Service
public class PartnerDraftService {

    /** Aggregate-type discriminator stored on the paired {@link ChangeRequestEntity}. */
    public static final String AGGREGATE_TYPE = PartnerChangeRequestApplier.AGGREGATE_TYPE;

    /**
     * Default actor when the BFF is in pre-Keycloak Slice 1B.4 mode (no
     * authenticated principal). The DB CHECK on (proposed_by IS DISTINCT FROM
     * approved_by) accepts {@code (system, system)} via the ADR-008 carve-out,
     * so wizard drafts work end-to-end until the Keycloak slice lands and we
     * resolve a real {@code sub} claim into the actor field.
     */
    private static final String DEFAULT_ACTOR = "system";

    private final PartnerStore partnerStore;
    private final PartnerRepository partnerRepository;
    private final ChangeRequestRepository changeRequestRepository;

    @PersistenceContext
    private EntityManager entityManager;

    public PartnerDraftService(PartnerStore partnerStore,
                               PartnerRepository partnerRepository,
                               ChangeRequestRepository changeRequestRepository) {
        this.partnerStore = partnerStore;
        this.partnerRepository = partnerRepository;
        this.changeRequestRepository = changeRequestRepository;
    }

    /**
     * Create a new draft partner from the wizard "Start a new partner" entry
     * point. The row lands in {@code partners} with {@link PartnerStatus#ONBOARDING}
     * and a paired {@code change_request} in {@link ChangeRequestState#DRAFT}.
     *
     * <p>Identity fields on the request are applied if present — the wizard may
     * submit only the {@code partnerCode} (operator starting from scratch) or
     * the full Step-1 form (operator pasting from a hand-off note). Either is
     * valid; unset fields stay NULL on the row.
     *
     * @throws ResponseStatusException 409 if a partner with the same business
     *         code already exists (current or historical — duplicate codes are
     *         disallowed regardless of supersession).
     */
    @Transactional
    public PartnerView createDraft(PartnerCommand.CreateDraft req, String actor) {
        if (req == null || req.partnerCode() == null || req.partnerCode().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "partnerCode is required");
        }
        if (req.type() == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "type is required");
        }
        if (partnerRepository.existsByPartnerCode(req.partnerCode())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "partner '" + req.partnerCode() + "' already exists");
        }

        // Four-field aggregate write — goes through PartnerStore so SCD-6 paired
        // writes + audit_log publication ride the canonical mutation path.
        java.math.RoundingMode mode = req.settlementRoundingMode() == null
                ? java.math.RoundingMode.HALF_UP
                : req.settlementRoundingMode();
        Partner saved = partnerStore.save(
                Partner.of(req.partnerCode(), req.type(), req.settlementCurrency(), mode));

        // Now apply the Identity-step fields to the row PartnerStore just wrote.
        // findCurrentByPartnerCode resolves the same row PartnerStore.save just
        // INSERTed (it has superseded_at IS NULL); we mutate it in-place inside
        // this transaction so the changes land before commit. Per ADR-010 the row
        // is NOT yet committed — this is the same logical write, not a separate
        // generation, so the SCD-6 "no in-place mutation after commit" rule still
        // holds.
        PartnerEntity entity = partnerRepository.findCurrentByPartnerCode(req.partnerCode())
                .orElseThrow(() -> new IllegalStateException(
                        "PartnerStore.save did not produce a current row for "
                                + req.partnerCode()));
        applyIdentity(entity, req.legalNameLocal(), req.legalNameRomanized(),
                req.taxId(), req.taxIdType(), req.countryOfIncorporation(),
                req.legalForm(), req.registeredAddress(), req.operatingAddress(),
                req.lei());
        entity.setStatus(PartnerStatus.ONBOARDING);
        partnerRepository.saveAndFlush(entity);

        // Paired change_request row in DRAFT. Aggregate id is the business code
        // (matches PartnerChangeRequestApplier's lookup contract in Slice 1).
        ChangeRequestEntity cr = new ChangeRequestEntity();
        cr.setId(nextChangeRequestId());
        cr.setAggregateType(AGGREGATE_TYPE);
        cr.setAggregateId(req.partnerCode());
        cr.setState(ChangeRequestState.DRAFT);
        cr.setProposedBy(actor == null || actor.isBlank() ? DEFAULT_ACTOR : actor);
        cr.setProposedAt(Instant.now());
        cr.setPayloadJsonb(null); // populated on submit (Slice 2 wires the maker UI)
        cr.setAppliesToFieldSet(new String[]{"step1"});
        changeRequestRepository.saveAndFlush(cr);

        return toView(entity);
    }

    /**
     * Apply Step-1 (Identity) fields to an existing draft. Per ADR-010 / SCD-6
     * this is a paired (UPDATE prior SET superseded_at) + (INSERT new) write —
     * we route through {@link PartnerStore#save} to get a fresh current row, then
     * update the Identity columns on it in the same transaction.
     *
     * @throws ResponseStatusException 404 if no current row matches {@code partnerCode};
     *         409 if the row is no longer in {@code status=ONBOARDING} (drafts
     *         are immutable once they leave ONBOARDING — Slice 8's FSM locks
     *         attributes after activation).
     */
    @Transactional
    public PartnerView patchStep1(String partnerCode, PartnerCommand.UpdateStep1 req,
                                  String actor) {
        if (partnerCode == null || partnerCode.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "partnerCode is required");
        }
        PartnerEntity current = partnerRepository.findCurrentByPartnerCode(partnerCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "no partner '" + partnerCode + "'"));
        if (current.getStatus() != PartnerStatus.ONBOARDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "partner '" + partnerCode + "' is in status "
                            + current.getStatus()
                            + ", Step-1 edits are only permitted while ONBOARDING");
        }

        // Carry over the four-field aggregate from the current row, applying any
        // settlement-related overrides from the request. This is the SCD-6
        // supersession write: PartnerStore.save will INSERT a new current row and
        // close the prior one.
        java.math.RoundingMode mode = req.settlementRoundingMode() == null
                ? current.getSettlementRoundingMode()
                : req.settlementRoundingMode();
        com.gme.pay.domain.PartnerType type = req.type() == null
                ? current.getType()
                : req.type();
        String settlementCurrency = req.settlementCurrency() == null
                ? current.getSettlementCurrency()
                : req.settlementCurrency();
        partnerStore.save(Partner.of(partnerCode, type, settlementCurrency, mode));

        // Now find the freshly-written current row and apply Identity-step fields.
        // null fields on the request mean "leave the prior value" — we carry
        // forward the prior row's Identity attributes for any field not in the
        // PATCH body. This matches the wizard's "save partial progress" semantics.
        PartnerEntity fresh = partnerRepository.findCurrentByPartnerCode(partnerCode)
                .orElseThrow(() -> new IllegalStateException(
                        "PartnerStore.save did not produce a current row for " + partnerCode));
        // Carry-over: for each Identity field, if the request omits it, copy from
        // the prior current row (which `current` still references in-memory). This
        // makes PATCH semantically a partial update.
        applyIdentity(fresh,
                req.legalNameLocal() != null ? req.legalNameLocal() : current.getLegalNameLocal(),
                req.legalNameRomanized() != null ? req.legalNameRomanized() : current.getLegalNameRomanized(),
                req.taxId() != null ? req.taxId() : current.getTaxId(),
                req.taxIdType() != null ? req.taxIdType() : current.getTaxIdType(),
                req.countryOfIncorporation() != null ? req.countryOfIncorporation() : current.getCountryOfIncorporation(),
                req.legalForm() != null ? req.legalForm() : current.getLegalForm(),
                pickAddress(req.registeredAddress(), addressFromCurrent(
                        current.getRegisteredStreet1(), current.getRegisteredStreet2(),
                        current.getRegisteredCity(), current.getRegisteredState(),
                        current.getRegisteredPostcode(), current.getRegisteredCountry())),
                pickAddress(req.operatingAddress(), addressFromCurrent(
                        current.getOperatingStreet1(), current.getOperatingStreet2(),
                        current.getOperatingCity(), current.getOperatingState(),
                        current.getOperatingPostcode(), current.getOperatingCountry())),
                req.lei() != null ? req.lei() : current.getLei());
        // Status stays ONBOARDING while drafts are being edited.
        fresh.setStatus(PartnerStatus.ONBOARDING);
        partnerRepository.saveAndFlush(fresh);

        return toView(fresh);
    }

    /**
     * Read the current draft for the given business code, together with the latest
     * change_request state. Returns the four-field aggregate + Identity attributes
     * as a {@link PartnerView}.
     *
     * @throws ResponseStatusException 404 if no current row matches.
     */
    @Transactional(readOnly = true)
    public PartnerView getDraft(String partnerCode) {
        PartnerEntity entity = partnerRepository.findCurrentByPartnerCode(partnerCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "no partner '" + partnerCode + "'"));
        return toView(entity);
    }

    /**
     * List every current draft (rows with {@code status=ONBOARDING}). The Admin
     * UI Partners view shows these in a "Drafts in progress" section so operators
     * can resume mid-flow per ADR-012.
     */
    @Transactional(readOnly = true)
    public List<PartnerView> listDrafts() {
        return partnerRepository.findAllCurrent().stream()
                .filter(e -> e.getStatus() == PartnerStatus.ONBOARDING)
                .map(PartnerDraftService::toView)
                .toList();
    }

    // -------------------------- Helpers --------------------------------------

    /**
     * Allocate the next change_request id from {@code change_request_id_seq}
     * (V005). Same dual-engine pattern used by {@code PartnerStore.save} and
     * {@code ChangeRequestService.propose}: explicit NEXTVAL at the application
     * layer so PostgreSQL and H2 in PostgreSQL mode behave identically.
     */
    private Long nextChangeRequestId() {
        Object value = entityManager
                .createNativeQuery("select nextval('change_request_id_seq')")
                .getSingleResult();
        if (value instanceof Number n) {
            return n.longValue();
        }
        throw new IllegalStateException(
                "change_request_id_seq returned non-numeric value: " + value);
    }

    /** Copy Identity fields from a write payload into the entity's columns. */
    private static void applyIdentity(PartnerEntity entity,
                                      String legalNameLocal, String legalNameRomanized,
                                      String taxId, String taxIdType,
                                      String countryOfIncorporation, String legalForm,
                                      AddressCommand registered, AddressCommand operating,
                                      String lei) {
        entity.setLegalNameLocal(legalNameLocal);
        entity.setLegalNameRomanized(legalNameRomanized);
        entity.setTaxId(taxId);
        entity.setTaxIdType(taxIdType);
        entity.setCountryOfIncorporation(countryOfIncorporation);
        entity.setLegalForm(legalForm);
        if (registered != null) {
            entity.setRegisteredStreet1(registered.street1());
            entity.setRegisteredStreet2(registered.street2());
            entity.setRegisteredCity(registered.city());
            entity.setRegisteredState(registered.state());
            entity.setRegisteredPostcode(registered.postcode());
            entity.setRegisteredCountry(registered.country());
        } else {
            entity.setRegisteredStreet1(null);
            entity.setRegisteredStreet2(null);
            entity.setRegisteredCity(null);
            entity.setRegisteredState(null);
            entity.setRegisteredPostcode(null);
            entity.setRegisteredCountry(null);
        }
        if (operating != null) {
            entity.setOperatingStreet1(operating.street1());
            entity.setOperatingStreet2(operating.street2());
            entity.setOperatingCity(operating.city());
            entity.setOperatingState(operating.state());
            entity.setOperatingPostcode(operating.postcode());
            entity.setOperatingCountry(operating.country());
        } else {
            entity.setOperatingStreet1(null);
            entity.setOperatingStreet2(null);
            entity.setOperatingCity(null);
            entity.setOperatingState(null);
            entity.setOperatingPostcode(null);
            entity.setOperatingCountry(null);
        }
        entity.setLei(lei);
    }

    private static AddressCommand pickAddress(AddressCommand fromRequest,
                                              AddressCommand carriedOver) {
        return fromRequest != null ? fromRequest : carriedOver;
    }

    private static AddressCommand addressFromCurrent(String s1, String s2, String city,
                                                     String state, String postcode,
                                                     String country) {
        if (s1 == null && s2 == null && city == null && state == null
                && postcode == null && country == null) {
            return null;
        }
        return new AddressCommand(s1, s2, city, state, postcode, country);
    }

    /** Adapt a {@link PartnerEntity} (including its Identity columns) to {@link PartnerView}. */
    public static PartnerView toView(PartnerEntity e) {
        AddressView registered = addressView(
                e.getRegisteredStreet1(), e.getRegisteredStreet2(), e.getRegisteredCity(),
                e.getRegisteredState(), e.getRegisteredPostcode(), e.getRegisteredCountry());
        AddressView operating = addressView(
                e.getOperatingStreet1(), e.getOperatingStreet2(), e.getOperatingCity(),
                e.getOperatingState(), e.getOperatingPostcode(), e.getOperatingCountry());
        return new PartnerView(
                e.getId(),
                e.getPartnerCode(),
                e.getType(),
                e.getSettlementCurrency(),
                e.getSettlementRoundingMode(),
                e.getCollectionCcy(),
                e.getSettleACcy(),
                e.getLegalNameLocal(),
                e.getLegalNameRomanized(),
                e.getTaxId(),
                e.getTaxIdType(),
                e.getCountryOfIncorporation(),
                e.getLegalForm(),
                registered,
                operating,
                e.getLei(),
                e.getStatus(),
                e.getValidFrom(),
                e.getValidTo(),
                e.getRecordedAt());
    }

    private static AddressView addressView(String s1, String s2, String city, String state,
                                           String postcode, String country) {
        if (s1 == null && s2 == null && city == null && state == null
                && postcode == null && country == null) {
            return null;
        }
        return new AddressView(s1, s2, city, state, postcode, country);
    }

    /**
     * Look up the latest change_request paired with the given partner draft.
     * Used by the GET draft endpoint to surface "is this draft awaiting review?".
     */
    @SuppressWarnings("unused")
    Optional<ChangeRequestEntity> latestChangeRequest(String partnerCode) {
        List<ChangeRequestEntity> rows = changeRequestRepository
                .findByAggregateTypeAndAggregateIdOrderByProposedAtDesc(
                        AGGREGATE_TYPE, partnerCode);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}
