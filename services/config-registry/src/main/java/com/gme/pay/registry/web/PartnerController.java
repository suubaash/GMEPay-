package com.gme.pay.registry.web;

import com.gme.pay.contracts.ContactView;
import com.gme.pay.contracts.PartnerCommand;
import com.gme.pay.contracts.PartnerView;
import com.gme.pay.domain.Partner;
import com.gme.pay.domain.PartnerType;
import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import com.gme.pay.registry.contact.PartnerContactService;
import com.gme.pay.registry.partner.PartnerDraftService;
import com.gme.pay.registry.partner.PartnerStore;
import com.gme.pay.registry.partner.PartnerValidator;
import com.gme.pay.registry.persistence.PartnerEntity;
import com.gme.pay.registry.persistence.PartnerRepository;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read/update partners from the registry, including the per-partner settlement rounding mode.
 *
 * <h2>URL contract — Slice 1 schism resolution</h2>
 *
 * <p>The {@code {id}} path variable on every operator-facing endpoint is the
 * human-facing <b>partner code</b> (e.g. {@code "GMEREMIT"}). The new
 * {@code BIGINT} surrogate added by V003 is the universal join key used internally
 * by every consuming service (auth-identity / notification-webhook /
 * settlement-reconciliation) — it is intentionally NOT routed on the URL line so
 * operators continue to type the same human-readable identifier they always have.
 *
 * <h2>Wire shape — Slice 1 DTO collapse</h2>
 *
 * <p>Responses use the canonical {@link PartnerView} read DTO (from
 * {@code lib-api-contracts}). Bodies use {@link PartnerCommand.CreateDraft} —
 * the richer Slice 1 wizard payload carrying Identity-step fields alongside the
 * four legacy settlement attributes. Existing callers that POST only the
 * four-field subset continue to work; the new Identity columns stay {@code null}
 * on the persisted row until the wizard saves step 1.
 */
@RestController
@RequestMapping("/v1/partners")
public class PartnerController {

    private final PartnerStore store;
    private final PartnerRepository repository;
    private final PartnerDraftService draftService;
    private final PartnerContactService contactService;

    public PartnerController(PartnerStore store, PartnerRepository repository,
                             PartnerDraftService draftService,
                             PartnerContactService contactService) {
        this.store = store;
        this.repository = repository;
        this.draftService = draftService;
        this.contactService = contactService;
    }

    /** Every partner currently in the registry. Powers the Admin UI partner list. */
    @GetMapping
    public List<PartnerView> list() {
        return store.listAll().stream().map(PartnerController::toView).toList();
    }

    /**
     * Current view by default (served cache-aside). With {@code ?at=<ISO-8601 instant>}
     * the lookup is point-in-time against the half-open effective window
     * {@code [effective_from, effective_to)}; point-in-time reads bypass the cache.
     *
     * <p>{@code id} is the partner code (e.g. {@code "GMEREMIT"}), not the surrogate.
     */
    @GetMapping("/{id}")
    public PartnerView get(@PathVariable String id,
                           @RequestParam(name = "at", required = false) String at) {
        Partner p = (at == null || at.isBlank())
                ? store.get(id)
                : store.getEffectiveAt(id, parseInstant(at));
        return toView(p);
    }

    /**
     * Create a partner from the Admin UI wizard. Accepts the canonical
     * {@link PartnerCommand.CreateDraft} payload (Slice 1 DTO collapse). The
     * four legacy fields ({@code partnerCode}, {@code type},
     * {@code settlementCurrency}, {@code settlementRoundingMode}) are required;
     * the Identity-step fields are optional at create time and the row carries
     * {@code null} for any the wizard has not yet collected. Returns 201 with
     * the persisted view.
     */
    @PostMapping
    public ResponseEntity<PartnerView> create(@RequestBody PartnerCommand.CreateDraft req) {
        if (req == null || req.partnerCode() == null || req.partnerCode().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "partnerCode is required");
        }
        if (req.type() == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "type is required");
        }
        // Slice 1 step-1 server-side format validation (PartnerValidator). The
        // contract-level checks above stay where they are (legacy callers depend on
        // the ApiException shape for those two fields); the new Identity-step rules
        // — tax-id discriminator, ISO-3166 alpha-2, legal form enum, LEI checksum —
        // re-throw as 400 ResponseStatusException with the validator's message so the
        // Admin UI can surface it inline.
        try {
            PartnerValidator.validateCreateDraft(req);
        } catch (PartnerValidator.ValidationException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        // POST is create-only; duplicate codes return 409 so the caller (Admin UI) can
        // surface the conflict rather than implicitly opening a new SCD-6 generation
        // via PartnerStore.save. After V004 the PK is the BIGINT surrogate (multiple
        // historical rows per code), so the duplicate check goes through the
        // partner_code finder rather than the legacy existsById(String).
        if (repository.existsByPartnerCode(req.partnerCode())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "partner '" + req.partnerCode() + "' already exists");
        }
        RoundingMode mode = req.settlementRoundingMode() == null
                ? RoundingMode.HALF_UP
                : req.settlementRoundingMode();
        // Build the domain record from the request. The surrogate id is null at this
        // point — PartnerStore.save pulls one from the V003 sequence on first persist.
        // The legacy four-field path goes through PartnerStore.save; the Identity-step
        // columns are then written to the freshly-created current row via the same
        // applyIdentity helper used by the PATCH endpoint, so a CreateDraft + step-1
        // payload lands in a single round-trip.
        Partner saved = store.save(
                Partner.of(req.partnerCode(), req.type(), req.settlementCurrency(), mode));
        if (hasAnyIdentityField(req)) {
            applyIdentityFieldsFromCreate(saved.partnerCode(), req);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(toView(saved));
    }

    /**
     * Save Step-1 Identity changes on an existing partner draft. The {@code code}
     * path variable is the human-facing partner code (e.g. {@code "GMEREMIT"});
     * the body carries only the Identity-step fields. Bitemporal semantics: the
     * Identity columns are updated on the current row's matching entity via the
     * JPA store rather than going through {@link PartnerStore#save}'s SCD-6
     * paired write — Slice 1B.4 (the change_request FSM agent) is responsible
     * for wrapping this in a proposed/approved transition; until that lands the
     * endpoint writes directly so the wizard can be demoed end-to-end.
     *
     * <p>Returns 404 if no current row exists for {@code code}, 400 if the body
     * fails {@link PartnerValidator}, 200 with the canonical {@link PartnerView}
     * on success.
     */
    @PatchMapping("/partner-code/{code}/step-1")
    public PartnerView updateStep1(@PathVariable String code,
                                   @RequestBody PartnerCommand.UpdateStep1 req) {
        try {
            PartnerValidator.validateUpdateStep1(req);
        } catch (PartnerValidator.ValidationException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        // Resolve the current row, 404 cleanly if the partner does not exist (or has
        // already been superseded with no replacement — under SCD-6 there should
        // always be exactly one current row per code).
        PartnerEntity current = repository.findCurrentByPartnerCode(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "unknown partner: " + code));
        // Slice 8 post-activation immutability (ADR-011): once go_live_at is
        // stamped, country_of_incorporation is frozen. Throws
        // ApiException(IMMUTABLE_AFTER_ACTIVATION) → 400 via the
        // RegistryApiExceptionHandler envelope.
        com.gme.pay.registry.lifecycle.PartnerImmutabilityGuard
                .checkIdentityWrite(current, req.countryOfIncorporation());
        applyIdentityFromUpdate(current, req);
        // saveAndFlush — same JPA path the store uses; this is a direct UPDATE on the
        // current row, which is acceptable Expand-phase behaviour for the Identity
        // columns (they were not present on the row when 4-eyes was added; Slice 1B.4
        // will replace this with a change_request-gated path).
        repository.saveAndFlush(current);
        return toView(current.toDomain());
    }

    /**
     * Stamp Identity-step columns from a {@link PartnerCommand.CreateDraft} onto
     * the freshly-saved partner row. Called by {@link #create} when the body
     * carries any non-null Identity field. Goes through the same JPA repository
     * as the PATCH path so both write surfaces share one persistence flow.
     */
    private void applyIdentityFieldsFromCreate(String code, PartnerCommand.CreateDraft req) {
        PartnerEntity entity = repository.findCurrentByPartnerCode(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "unknown partner: " + code));
        copyIdentityFields(entity,
                req.legalNameLocal(), req.legalNameRomanized(),
                req.taxId(), req.taxIdType(),
                req.countryOfIncorporation(), req.legalForm(),
                req.registeredAddress(), req.operatingAddress(),
                req.lei());
        repository.saveAndFlush(entity);
    }

    /**
     * Stamp Identity-step columns from a {@link PartnerCommand.UpdateStep1} onto
     * the resolved current row. The shared {@link #copyIdentityFields} does the
     * actual field-by-field assignment so the create and update paths cannot
     * drift in which columns they touch.
     */
    private static void applyIdentityFromUpdate(PartnerEntity entity, PartnerCommand.UpdateStep1 req) {
        copyIdentityFields(entity,
                req.legalNameLocal(), req.legalNameRomanized(),
                req.taxId(), req.taxIdType(),
                req.countryOfIncorporation(), req.legalForm(),
                req.registeredAddress(), req.operatingAddress(),
                req.lei());
    }

    /**
     * Copy every Identity-step field from the payload onto the entity. Null values
     * in the payload still overwrite the entity column — the wizard's contract is
     * "send the full Step-1 state on every save", so an explicit null means the
     * operator cleared the field. Address sub-components are unpacked here so the
     * entity stays flat (the table is flat too — see V007).
     */
    private static void copyIdentityFields(PartnerEntity entity,
                                           String legalNameLocal,
                                           String legalNameRomanized,
                                           String taxId,
                                           String taxIdType,
                                           String countryOfIncorporation,
                                           String legalForm,
                                           com.gme.pay.contracts.AddressCommand registered,
                                           com.gme.pay.contracts.AddressCommand operating,
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

    /**
     * True if any Identity-step field is present on the create payload — used by
     * {@link #create} to decide whether to issue the follow-up UPDATE that stamps
     * the Identity columns. If the caller only sent the four legacy fields we
     * skip the round-trip entirely.
     */
    private static boolean hasAnyIdentityField(PartnerCommand.CreateDraft req) {
        return req.legalNameLocal() != null
                || req.legalNameRomanized() != null
                || req.taxId() != null
                || req.taxIdType() != null
                || req.countryOfIncorporation() != null
                || req.legalForm() != null
                || req.registeredAddress() != null
                || req.operatingAddress() != null
                || req.lei() != null;
    }

    // -------- Slice 1 (1C.2) draft endpoints (ADR-012) -----------------------
    //
    // Server-side persistence for the 8-step wizard. Every Next button POSTs
    // into the partner row so drafts survive browser crashes, operator hand-offs
    // and session timeouts. POST /v1/partners/draft creates the row in
    // status=ONBOARDING + a paired change_request in state=DRAFT; PATCH writes
    // step-1 Identity fields; GET reads back the current draft; the list
    // endpoint surfaces every in-flight draft for the Admin UI dashboard.
    //
    // These endpoints are deliberately separate from POST /v1/partners (which
    // remains the legacy four-field create) so the wizard's URL shape matches
    // the draft-id-in-path discipline documented in ADR-012. Once the wizard is
    // the only create path (Slice 2+), the legacy POST collapses into a redirect.

    /**
     * Create a new partner draft from the wizard's "Start a new partner"
     * action. The row lands in {@code partners} with
     * {@link com.gme.pay.contracts.PartnerStatus#ONBOARDING} and a paired
     * {@link com.gme.pay.changerequest.ChangeRequestEntity} in
     * {@link com.gme.pay.changerequest.ChangeRequestState#DRAFT}. Returns 201
     * with the canonical {@link PartnerView} including the freshly-allocated
     * BIGINT surrogate id and the {@code partnerCode} the caller picked.
     *
     * <p>Validation matches the legacy POST: {@code partnerCode} + {@code type}
     * are required, Identity-step fields are optional (the wizard saves partial
     * progress and the Slice 8 activation gate enforces required-at-activation).
     */
    @PostMapping("/draft")
    public ResponseEntity<PartnerView> createDraft(
            @RequestBody PartnerCommand.CreateDraft req,
            @RequestHeader(value = "X-Actor", required = false) String actor) {
        try {
            PartnerValidator.validateCreateDraft(req);
        } catch (PartnerValidator.ValidationException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        PartnerView created = draftService.createDraft(req, actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * Save Step-1 Identity changes onto an existing draft (the wizard "Next"
     * button on the Identity tab). Bitemporal semantics — every PATCH writes a
     * fresh current row through {@link PartnerStore#save} and supersedes the
     * prior; the Identity columns ride along on the new row. Returns 200 with
     * the canonical {@link PartnerView} reflecting the new state.
     *
     * <p>Returns 404 if no current row matches {@code partnerCode}; 409 if the
     * row has already moved out of {@code ONBOARDING} (drafts are immutable
     * once they enter KYB_PENDING — the wizard's resume flow guards against
     * this in the UI, but the server enforces it as the final word).
     */
    @PatchMapping("/draft/{partnerCode}/step-1")
    public PartnerView patchDraftStep1(@PathVariable String partnerCode,
                                       @RequestBody PartnerCommand.UpdateStep1 req,
                                       @RequestHeader(value = "X-Actor", required = false) String actor) {
        try {
            PartnerValidator.validateUpdateStep1(req);
        } catch (PartnerValidator.ValidationException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return draftService.patchStep1(partnerCode, req, actor);
    }

    /**
     * Save Step-2 (Contacts) onto an existing draft — Slice 2. Bulk-replace
     * semantics: the body carries the FULL desired contact set and
     * {@link PartnerContactService#replaceDraftContacts} supersedes every
     * current {@code partner_contact} row + inserts the new set in one
     * transaction (SCD-6 paired writes, ADR-010), publishing one
     * {@code partner_contact} audit row (ADR-007).
     *
     * <p>Returns 200 with the freshly-inserted current set; 404 if no current
     * row matches {@code partnerCode}; 409 if the partner has left
     * {@code ONBOARDING}; 400 on validation failure (E.164 phone, email shape,
     * role roster — message carries the offending {@code contacts[i]} index).
     */
    @PatchMapping("/draft/{partnerCode}/step-2")
    public List<ContactView> patchDraftStep2(@PathVariable String partnerCode,
                                             @RequestBody PartnerCommand.UpdateStep2 req,
                                             @RequestHeader(value = "X-Actor", required = false) String actor) {
        if (req == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body required");
        }
        return contactService.replaceDraftContacts(partnerCode, req.contacts(), actor);
    }

    /**
     * Read the current draft for {@code partnerCode}. The wizard's "Resume
     * draft" action calls this to rehydrate the form. Returns 404 if no
     * current row exists for the code.
     */
    @GetMapping("/draft/{partnerCode}")
    public PartnerView getDraft(@PathVariable String partnerCode) {
        return draftService.getDraft(partnerCode);
    }

    /**
     * The CURRENT contact set for {@code id} (the partner business code) —
     * Slice 2. Powers the wizard's step-2 rehydrate and the partner detail
     * page. Returns an empty list for a partner with no contacts yet; 404 only
     * when the partner code itself is unknown.
     */
    @GetMapping("/{id}/contacts")
    public List<ContactView> contacts(@PathVariable String id) {
        return contactService.currentContacts(id);
    }

    /**
     * List every in-flight draft (rows in {@code status=ONBOARDING}). Powers
     * the Admin UI "Drafts in progress" section so operators can pick up where
     * a colleague left off.
     */
    @GetMapping("/drafts")
    public List<PartnerView> listDrafts() {
        return draftService.listDrafts();
    }

    @PutMapping("/{id}/rounding-mode")
    public PartnerView setRoundingMode(@PathVariable String id, @RequestBody RoundingModeRequest request) {
        return toView(store.updateRoundingMode(id, parseRoundingMode(request.mode())));
    }

    /** Adapt a domain {@link Partner} into the canonical {@link PartnerView} wire DTO. */
    private static PartnerView toView(Partner p) {
        return PartnerView.ofCore(p.partnerId(), p.partnerCode(), p.type(),
                p.settlementCurrency(), p.settlementRoundingMode());
    }

    private static Instant parseInstant(String at) {
        try {
            return Instant.parse(at);
        } catch (DateTimeParseException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "'at' must be an ISO-8601 instant (e.g. 2026-01-01T00:00:00Z), was: " + at);
        }
    }

    @SuppressWarnings("unused") // retained for the validation message path
    private static PartnerType parsePartnerType(String raw) {
        try {
            return PartnerType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "type must be one of LOCAL|OVERSEAS, was: " + raw);
        }
    }

    private static RoundingMode parseRoundingMode(String raw) {
        try {
            return RoundingMode.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "rounding mode must be a java.math.RoundingMode name (e.g. HALF_UP, DOWN), was: " + raw);
        }
    }

    /** Body for {@link #setRoundingMode}; carries the {@link RoundingMode} as its enum name. */
    public record RoundingModeRequest(String mode) {}
}
