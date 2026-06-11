package com.gme.pay.registry.contact;

import com.gme.pay.contracts.ContactCommand;
import com.gme.pay.contracts.ContactView;
import com.gme.pay.contracts.PartnerStatus;
import com.gme.pay.registry.audit.AuditLogService;
import com.gme.pay.registry.persistence.PartnerEntity;
import com.gme.pay.registry.persistence.PartnerRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Slice 2 — owns the {@code partner_contact} child aggregate (V009) behind the
 * wizard's step-2 endpoints.
 *
 * <h2>Bulk-replace semantics</h2>
 *
 * <p>The wizard's contract is "send the full step-2 state on every save", so a
 * PATCH is a <b>bulk replace</b>: inside one transaction every current contact
 * row for the partner is superseded ({@code superseded_at = now}) and the new
 * set is inserted ({@code recorded_at = now}), both halves sharing the same
 * MICROS-truncated instant — the SCD-6 paired-write discipline
 * {@code PartnerStore.save} established for the parent aggregate (ADR-010).
 * Sending an empty list clears all contacts; {@code null} is a 400.
 *
 * <h2>Validation</h2>
 *
 * <p>Server-side, before any row is touched (the transaction never partially
 * applies a bad payload): role roster, name/email required + length caps,
 * RFC-shaped email, E.164 phone ({@code ^\+[1-9]\d{1,14}$}), notes cap.
 * Failures surface as 400 {@link ResponseStatusException} with the offending
 * index in the message so the multi-row contact editor can highlight the row.
 *
 * <h2>Audit (ADR-007)</h2>
 *
 * <p>One audit row per bulk replace, {@code aggregateType="partner_contact"},
 * keyed by the partner business code (same Expand-phase key choice as the
 * partner chain), BEFORE = canonical JSON of the superseded set ({@code null}
 * on first write), AFTER = canonical JSON of the inserted set. Published inside
 * the same transaction via the existing {@link AuditLogService}, resolved
 * through an {@link ObjectProvider} so {@code @DataJpaTest} slices that omit the
 * audit module skip publication silently — the same wiring contract as
 * {@code PartnerStore}.
 */
@Service
public class PartnerContactService {

    /** Aggregate-type discriminator on audit rows for contact mutations. */
    public static final String AGGREGATE_TYPE = "partner_contact";

    /** Audit verb for the step-2 bulk replace. */
    public static final String EVENT_TYPE = "PARTNER_CONTACTS_REPLACED";

    /** E.164: '+' then 2..15 digits total, first digit non-zero. */
    static final Pattern PHONE_E164 = Pattern.compile("^\\+[1-9]\\d{1,14}$");

    /**
     * Pragmatic server-side email shape: one '@', non-empty local part, domain
     * with at least one dot, no whitespace. Full RFC 5322 grammar is deliberately
     * NOT attempted — the activation gate plus a verification email (later slice)
     * are the real guarantee; this catches operator typos.
     */
    static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    /**
     * Default actor until the Keycloak {@code sub} claim is threaded through the
     * BFF (same Slice 1B.4 carve-out as {@code PartnerDraftService}).
     */
    private static final String DEFAULT_ACTOR = "system";

    private final ContactRepository contactRepository;
    private final PartnerRepository partnerRepository;
    private final ObjectProvider<AuditLogService> auditLogProvider;

    public PartnerContactService(ContactRepository contactRepository,
                                 PartnerRepository partnerRepository,
                                 ObjectProvider<AuditLogService> auditLogProvider) {
        this.contactRepository = contactRepository;
        this.partnerRepository = partnerRepository;
        this.auditLogProvider = auditLogProvider;
    }

    /**
     * Bulk-replace the contact set on a draft partner (wizard step-2 "Next").
     *
     * @param partnerCode the human-facing business code routing the PATCH.
     * @param contacts    the FULL desired contact set; empty clears, {@code null}
     *                    is rejected with 400.
     * @param actor       the operator (X-Actor header); {@code "system"} when absent.
     * @return the freshly-inserted current set as canonical {@link ContactView}s.
     * @throws ResponseStatusException 404 when no current partner row matches;
     *         409 when the partner is no longer in {@code ONBOARDING} (drafts are
     *         immutable once they leave ONBOARDING — post-activation contact
     *         changes go through the change_request 4-eyes path instead);
     *         400 on any validation failure.
     */
    @Transactional
    public List<ContactView> replaceDraftContacts(String partnerCode,
                                                  List<ContactCommand> contacts,
                                                  String actor) {
        if (contacts == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "contacts is required (send an empty list to clear all contacts)");
        }
        PartnerEntity partner = partnerRepository.findCurrentByPartnerCode(partnerCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "no partner '" + partnerCode + "'"));
        if (partner.getStatus() != PartnerStatus.ONBOARDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "partner '" + partnerCode + "' is in status " + partner.getStatus()
                            + ", step-2 edits are only permitted while ONBOARDING");
        }
        // Validate the WHOLE payload before touching any row — a bad element must
        // not leave the set half-replaced (the tx would roll back anyway, but
        // failing fast keeps the 400 free of side effects and flush noise).
        for (int i = 0; i < contacts.size(); i++) {
            validate(contacts.get(i), i);
        }

        // One transaction-time instant shared by both halves of the paired write
        // (supersede + insert), truncated to MICROS so the stored TIMESTAMP equals
        // the in-memory value on both PostgreSQL and H2 — see PartnerStore.save.
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        List<ContactEntity> prior = contactRepository.findCurrentByPartnerId(partner.getId());
        byte[] before = prior.isEmpty() ? null : ContactJson.canonical(prior);

        // Supersede the prior current set. flush() forces the UPDATEs out before
        // the INSERTs below so the write order in the database matches the SCD-6
        // narrative (close out, then open) — same ordering discipline as
        // PartnerStore.save, even though contacts carry no partial unique index
        // that would make the order load-bearing.
        if (!prior.isEmpty()) {
            for (ContactEntity p : prior) {
                p.setSupersededAt(now);
            }
            contactRepository.saveAllAndFlush(prior);
        }

        // Insert the new current set. IDENTITY ids are assigned at flush; the
        // returned (managed) entities carry them, which the audit AFTER snapshot
        // and the response views both need.
        List<ContactEntity> fresh = new ArrayList<>(contacts.size());
        for (ContactCommand cmd : contacts) {
            fresh.add(toEntity(partner.getId(), cmd, now));
        }
        List<ContactEntity> saved = contactRepository.saveAllAndFlush(fresh);

        // ADR-007 audit row, same-transaction (commits iff the replace commits).
        AuditLogService auditLog = auditLogProvider.getIfAvailable();
        if (auditLog != null) {
            auditLog.publish(
                    AGGREGATE_TYPE,
                    partnerCode,
                    actor == null || actor.isBlank() ? DEFAULT_ACTOR : actor,
                    null,
                    EVENT_TYPE,
                    before,
                    ContactJson.canonical(saved));
        }

        return saved.stream().map(ContactEntity::toView).toList();
    }

    /**
     * The CURRENT contact set for the given partner code (no historical rows).
     *
     * @throws ResponseStatusException 404 when no current partner row matches —
     *         "partner exists with zero contacts" returns an empty list, only an
     *         unknown code 404s.
     */
    @Transactional(readOnly = true)
    public List<ContactView> currentContacts(String partnerCode) {
        PartnerEntity partner = partnerRepository.findCurrentByPartnerCode(partnerCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "no partner '" + partnerCode + "'"));
        return contactRepository.findCurrentByPartnerId(partner.getId()).stream()
                .map(ContactEntity::toView)
                .toList();
    }

    // -------------------------- Helpers --------------------------------------

    /** Build a fresh current row from one validated command. */
    private static ContactEntity toEntity(Long partnerId, ContactCommand cmd, Instant now) {
        ContactEntity e = new ContactEntity();
        e.setPartnerId(partnerId);
        e.setRole(ContactRole.valueOf(cmd.role()));
        e.setName(cmd.name());
        e.setEmail(cmd.email());
        e.setPhoneE164(blankToNull(cmd.phoneE164()));
        e.setAuthorizedSignatory(Boolean.TRUE.equals(cmd.authorizedSignatory()));
        e.setNotes(blankToNull(cmd.notes()));
        e.setRecordedAt(now);
        // Business time starts when the fact was captured — the wizard does not
        // back-date contacts (a future correction flow would set this explicitly).
        e.setValidFrom(now);
        return e;
    }

    /**
     * Field-format validation for one contact element. Index-qualified messages
     * ({@code contacts[2].email ...}) so the multi-row editor can map the 400 to
     * the offending row.
     */
    private static void validate(ContactCommand cmd, int index) {
        String at = "contacts[" + index + "]";
        if (cmd == null) {
            throw badRequest(at + " must be an object");
        }
        if (cmd.role() == null || cmd.role().isBlank()) {
            throw badRequest(at + ".role is required");
        }
        try {
            ContactRole.valueOf(cmd.role());
        } catch (IllegalArgumentException e) {
            throw badRequest(at + ".role must be one of "
                    + java.util.Arrays.toString(ContactRole.values()) + ", was: " + cmd.role());
        }
        if (cmd.name() == null || cmd.name().isBlank()) {
            throw badRequest(at + ".name is required");
        }
        if (cmd.name().length() > 120) {
            throw badRequest(at + ".name must be at most 120 characters");
        }
        if (cmd.email() == null || cmd.email().isBlank()) {
            throw badRequest(at + ".email is required");
        }
        if (cmd.email().length() > 254 || !EMAIL.matcher(cmd.email()).matches()) {
            throw badRequest(at + ".email is not a valid email address: " + cmd.email());
        }
        if (cmd.phoneE164() != null && !cmd.phoneE164().isBlank()
                && !PHONE_E164.matcher(cmd.phoneE164()).matches()) {
            throw badRequest(at + ".phoneE164 must be E.164 format"
                    + " (+ followed by up to 15 digits, e.g. +821012345678), was: "
                    + cmd.phoneE164());
        }
        if (cmd.notes() != null && cmd.notes().length() > 500) {
            throw badRequest(at + ".notes must be at most 500 characters");
        }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
