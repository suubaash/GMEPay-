package com.gme.pay.registry.partner;

import com.gme.pay.contracts.AddressCommand;
import com.gme.pay.contracts.PartnerCommand;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Server-side validator for the Slice 1 Identity-step fields on a partner write.
 *
 * <h2>What this validates</h2>
 *
 * <p>One method per write-shape ({@link #validateCreateDraft} for
 * {@link PartnerCommand.CreateDraft}, {@link #validateUpdateStep1} for
 * {@link PartnerCommand.UpdateStep1}). Both share the same per-field rules:
 *
 * <ul>
 *   <li>{@code taxId} format is selected by {@code taxIdType}:
 *       <ul>
 *         <li>{@code KR_BRN} — Korean Business Registration Number, exactly 10
 *             digits (no dashes; the operator UI strips them before send).</li>
 *         <li>{@code KH_VAT} — Cambodian VAT TIN, exactly 10 digits per General
 *             Department of Taxation guidance.</li>
 *         <li>{@code VN_MST} — Vietnamese Mã số thuế, 10 or 13 digits (parent
 *             enterprise vs branch).</li>
 *         <li>{@code SG_UEN} — Singapore Unique Entity Number, 9-10 characters
 *             matching the ACRA pattern (8 or 9 chars + 1 alphabetic check).</li>
 *         <li>{@code GENERIC} — any non-blank string; used for jurisdictions
 *             without a structured ID in this revision.</li>
 *       </ul>
 *       Unknown {@code taxIdType} values are rejected — keeps the discriminator
 *       enum explicit at the contract boundary.</li>
 *   <li>{@code countryOfIncorporation} (and per-address {@code country}) must be
 *       a valid ISO-3166 alpha-2 code recognised by {@link Locale#getISOCountries()}.</li>
 *   <li>{@code legalForm} must be one of {@code CORP|LLC|MTO|EMI|BANK|OTHER}.</li>
 *   <li>{@code lei} must be exactly 20 alphanumeric characters with a valid
 *       ISO 17442 mod-97-10 checksum in the trailing two positions
 *       (see {@link #leiChecksumValid}).</li>
 * </ul>
 *
 * <p>Every field is <b>optional</b> at the contract level — the wizard saves
 * partial progress. The validator only enforces format <i>when a value is
 * supplied</i>. Mandatory-at-activation is the job of a later activation gate
 * (Slice 8), not this validator.
 *
 * <h2>Failure mode</h2>
 *
 * <p>On the first failure the validator throws {@link ValidationException} with
 * a human-readable message that names the offending field and the rule it
 * violated. The controller layer catches this and re-throws as a
 * {@code ResponseStatusException(BAD_REQUEST, msg)} so the Admin UI surfaces the
 * exact message inline next to the form field.
 */
public final class PartnerValidator {

    /**
     * Allowed {@code taxIdType} discriminators. {@code GENERIC} is the catch-all
     * for jurisdictions without a structured rule (validation falls through to
     * "must be non-blank").
     */
    private static final Set<String> TAX_ID_TYPES =
            Set.of("KR_BRN", "KH_VAT", "VN_MST", "SG_UEN", "GENERIC");

    /** Allowed {@code legalForm} values per the Slice 1 brief. */
    private static final Set<String> LEGAL_FORMS =
            Set.of("CORP", "LLC", "MTO", "EMI", "BANK", "OTHER");

    private static final Pattern KR_BRN = Pattern.compile("\\d{10}");
    private static final Pattern KH_VAT = Pattern.compile("\\d{10}");
    private static final Pattern VN_MST = Pattern.compile("\\d{10}(\\d{3})?");
    // Singapore UEN — combines the legacy 9-char Business / Local-Company forms
    // (e.g. 53999999X) with the newer 10-char form for entities registered after
    // 2009 (e.g. 201712345A). The trailing character is alphabetic in both shapes.
    private static final Pattern SG_UEN = Pattern.compile("[A-Za-z0-9]{8,9}[A-Za-z]");

    /** ISO 17442 LEI: 20 chars total, A-Z0-9 only, last two are the mod-97-10 checksum. */
    private static final Pattern LEI_SHAPE = Pattern.compile("[A-Z0-9]{20}");

    /** ISO-3166 alpha-2 country codes recognised by the JVM Locale tables. */
    private static final Set<String> ISO_COUNTRIES = Set.of(Locale.getISOCountries());

    private PartnerValidator() {
        // utility — every method is static
    }

    /**
     * Validate a Step-1 create-draft payload. Every Identity field is optional at
     * the contract; only present values are format-checked.
     *
     * @throws ValidationException with a field-specific message on the first failure.
     */
    public static void validateCreateDraft(PartnerCommand.CreateDraft cmd) {
        if (cmd == null) {
            throw new ValidationException("request body is required");
        }
        validateIdentityFields(
                cmd.taxId(), cmd.taxIdType(),
                cmd.countryOfIncorporation(), cmd.legalForm(),
                cmd.registeredAddress(), cmd.operatingAddress(),
                cmd.lei());
    }

    /**
     * Validate a Step-1 update payload. Same rules as the create form — the only
     * shape difference is the missing {@code partnerCode} (the URL carries it).
     */
    public static void validateUpdateStep1(PartnerCommand.UpdateStep1 cmd) {
        if (cmd == null) {
            throw new ValidationException("request body is required");
        }
        validateIdentityFields(
                cmd.taxId(), cmd.taxIdType(),
                cmd.countryOfIncorporation(), cmd.legalForm(),
                cmd.registeredAddress(), cmd.operatingAddress(),
                cmd.lei());
    }

    /**
     * Shared field-level rules. Called by both write-shape entry points; the
     * branching is per-field on null/blank so the wizard can save partial
     * progress without tripping unrelated rules.
     */
    private static void validateIdentityFields(
            String taxId,
            String taxIdType,
            String countryOfIncorporation,
            String legalForm,
            AddressCommand registeredAddress,
            AddressCommand operatingAddress,
            String lei) {

        // Tax id: type+value travel together. If a type is supplied without a value
        // (or vice versa) that is a contract error; surface it cleanly rather than
        // letting the regex fall through.
        if (taxIdType != null && !taxIdType.isBlank()) {
            if (!TAX_ID_TYPES.contains(taxIdType)) {
                throw new ValidationException("taxIdType must be one of "
                        + TAX_ID_TYPES + ", was: " + taxIdType);
            }
            if (taxId == null || taxId.isBlank()) {
                throw new ValidationException(
                        "taxId is required when taxIdType is set (" + taxIdType + ")");
            }
            checkTaxIdFormat(taxIdType, taxId);
        } else if (taxId != null && !taxId.isBlank()) {
            // value without type → ambiguous; force the caller to pick a discriminator
            throw new ValidationException(
                    "taxIdType is required when taxId is set; pick one of " + TAX_ID_TYPES);
        }

        if (countryOfIncorporation != null && !countryOfIncorporation.isBlank()) {
            checkIso3166Alpha2("countryOfIncorporation", countryOfIncorporation);
        }

        if (legalForm != null && !legalForm.isBlank()) {
            if (!LEGAL_FORMS.contains(legalForm)) {
                throw new ValidationException("legalForm must be one of "
                        + LEGAL_FORMS + ", was: " + legalForm);
            }
        }

        if (registeredAddress != null && registeredAddress.country() != null
                && !registeredAddress.country().isBlank()) {
            checkIso3166Alpha2("registeredAddress.country", registeredAddress.country());
        }
        if (operatingAddress != null && operatingAddress.country() != null
                && !operatingAddress.country().isBlank()) {
            checkIso3166Alpha2("operatingAddress.country", operatingAddress.country());
        }

        if (lei != null && !lei.isBlank()) {
            checkLei(lei);
        }
    }

    /**
     * Apply the per-{@code taxIdType} format rule to {@code taxId}. The
     * discriminator has already been validated against {@link #TAX_ID_TYPES} by
     * the caller, so this method's switch is exhaustive over the allowed set.
     */
    private static void checkTaxIdFormat(String taxIdType, String taxId) {
        switch (taxIdType) {
            case "KR_BRN" -> {
                if (!KR_BRN.matcher(taxId).matches()) {
                    throw new ValidationException(
                            "taxId for KR_BRN must be exactly 10 digits, was: " + taxId);
                }
            }
            case "KH_VAT" -> {
                if (!KH_VAT.matcher(taxId).matches()) {
                    throw new ValidationException(
                            "taxId for KH_VAT must be exactly 10 digits, was: " + taxId);
                }
            }
            case "VN_MST" -> {
                if (!VN_MST.matcher(taxId).matches()) {
                    throw new ValidationException(
                            "taxId for VN_MST must be 10 or 13 digits, was: " + taxId);
                }
            }
            case "SG_UEN" -> {
                if (!SG_UEN.matcher(taxId).matches()) {
                    throw new ValidationException(
                            "taxId for SG_UEN must match ACRA UEN format "
                                    + "(8-9 alphanumeric + trailing letter), was: " + taxId);
                }
            }
            case "GENERIC" -> {
                if (taxId.isBlank()) {
                    throw new ValidationException("taxId for GENERIC must be non-blank");
                }
            }
            default -> throw new ValidationException("taxIdType not recognised: " + taxIdType);
        }
    }

    /**
     * Verify {@code code} is a known ISO-3166 alpha-2 country code. The JVM ships
     * the full alpha-2 list via {@link Locale#getISOCountries()} so we do not need
     * to hand-maintain a static set. Codes are uppercased for the check so a
     * lowercase "kr" still matches; the controller layer leaves the case as the
     * caller supplied it to keep the round-trip lossless.
     */
    private static void checkIso3166Alpha2(String fieldName, String code) {
        if (code.length() != 2 || !ISO_COUNTRIES.contains(code.toUpperCase(Locale.ROOT))) {
            throw new ValidationException(
                    fieldName + " must be an ISO-3166 alpha-2 code (e.g. KR, KH, VN, SG), was: " + code);
        }
    }

    /**
     * ISO 17442 LEI validation: 20 alphanumeric characters with a valid mod-97-10
     * checksum. Shape and checksum are checked together so the controller does
     * not have to combine error messages.
     *
     * <p>Reference: GLEIF "ISO 17442 — The LEI code structure"
     * (https://www.gleif.org/en/about-lei/iso-17442-the-lei-code-structure).
     *
     * @throws ValidationException with a precise reason on failure.
     */
    private static void checkLei(String lei) {
        String upper = lei.toUpperCase(Locale.ROOT);
        if (!LEI_SHAPE.matcher(upper).matches()) {
            throw new ValidationException(
                    "lei must be exactly 20 alphanumeric characters (A-Z, 0-9), was: " + lei);
        }
        if (!leiChecksumValid(upper)) {
            throw new ValidationException(
                    "lei mod-97-10 checksum invalid per ISO 17442, was: " + lei);
        }
    }

    /**
     * ISO 17442 mod-97-10 checksum: replace each letter with its base-10 value
     * (A=10, B=11, ..., Z=35), interpret the resulting digit string as a base-10
     * integer, and verify {@code mod 97 == 1}. Computed in long arithmetic by
     * processing the digit string in chunks of 9 (the largest run that fits in
     * a long after appending one more digit-pair) — no BigInteger needed.
     *
     * <p>Package-private so unit tests can exercise the checksum path directly
     * without going through a full payload.
     */
    static boolean leiChecksumValid(String lei) {
        // Build the digit string: each char contributes either one digit (0-9)
        // or two digits (A=10..Z=35). The full LEI is 20 chars; max digit-string
        // length is 40 (every char a letter). The mod-97 result is computed in
        // a single pass keeping a running remainder.
        long remainder = 0;
        for (int i = 0; i < lei.length(); i++) {
            char c = lei.charAt(i);
            int value;
            if (c >= '0' && c <= '9') {
                value = c - '0';
                remainder = (remainder * 10 + value) % 97;
            } else if (c >= 'A' && c <= 'Z') {
                value = c - 'A' + 10; // 10..35
                // append the two-digit value: tens place first, ones place second
                remainder = (remainder * 10 + (value / 10)) % 97;
                remainder = (remainder * 10 + (value % 10)) % 97;
            } else {
                // shape check upstream should have caught this; defensive return
                return false;
            }
        }
        return remainder == 1L;
    }

    /**
     * Thrown when a Step-1 payload fails server-side validation. The controller
     * catches this and re-throws as a {@code ResponseStatusException(BAD_REQUEST)}
     * so the wire response shape is consistent with the rest of the partner
     * endpoints.
     */
    public static class ValidationException extends RuntimeException {
        public ValidationException(String message) {
            super(message);
        }
    }
}
