package com.gme.pay.registry.partner;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gme.pay.contracts.AddressCommand;
import com.gme.pay.contracts.PartnerCommand;
import com.gme.pay.domain.PartnerType;
import java.math.RoundingMode;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link PartnerValidator}. The validator is pure — no Spring,
 * no DB — so this test is a plain JUnit 5 class that exercises each rule branch.
 *
 * <p>Why every branch:
 * <ul>
 *   <li><b>Each tax_id_type</b> has its own regex; if any branch silently accepts
 *       garbage the wire contract leaks malformed identifiers into the DB. One
 *       valid case + one obvious-bad case per type pins the regex.</li>
 *   <li><b>ISO-3166 alpha-2</b> uses {@link java.util.Locale#getISOCountries()} —
 *       we want a quick confirmation that a non-existent code (e.g. {@code "ZZ"})
 *       is rejected and a real one (e.g. {@code "KR"}) is accepted.</li>
 *   <li><b>LEI mod-97-10 checksum</b> is the only non-trivial algorithm in the
 *       validator. A known-good LEI (the published GLEIF example) confirms the
 *       implementation matches ISO 17442; a corrupted-checksum variant confirms
 *       the rejection path.</li>
 * </ul>
 */
class PartnerValidatorTest {

    /** Build a CreateDraft with the legacy four fields set + arbitrary Identity fields. */
    private static PartnerCommand.CreateDraft draft(String taxId, String taxIdType,
                                                    String country, String legalForm,
                                                    String lei) {
        return new PartnerCommand.CreateDraft(
                "ACME", PartnerType.OVERSEAS, "USD", RoundingMode.HALF_UP,
                /* legalNameLocal */ null, /* legalNameRomanized */ null,
                taxId, taxIdType, country, legalForm,
                /* registeredAddress */ null, /* operatingAddress */ null,
                lei);
    }

    @Nested
    class TaxIdRules {

        @Test
        void krBrn_tenDigits_passes() {
            assertDoesNotThrow(() ->
                    PartnerValidator.validateCreateDraft(
                            draft("1234567890", "KR_BRN", "KR", "MTO", null)));
        }

        @Test
        void krBrn_nineDigits_rejected() {
            // KR Business Registration Numbers are exactly 10 digits; anything else
            // must fail with a message that names the field and the rule.
            PartnerValidator.ValidationException e = assertThrows(
                    PartnerValidator.ValidationException.class, () ->
                            PartnerValidator.validateCreateDraft(
                                    draft("123456789", "KR_BRN", "KR", "MTO", null)));
            assertTrue(e.getMessage().contains("KR_BRN"),
                    "error should mention the tax_id_type that failed: " + e.getMessage());
            assertTrue(e.getMessage().contains("10 digits"),
                    "error should mention the digit-count rule: " + e.getMessage());
        }

        @Test
        void khVat_tenDigits_passes() {
            assertDoesNotThrow(() ->
                    PartnerValidator.validateCreateDraft(
                            draft("0001234567", "KH_VAT", "KH", "CORP", null)));
        }

        @Test
        void khVat_letters_rejected() {
            assertThrows(PartnerValidator.ValidationException.class, () ->
                    PartnerValidator.validateCreateDraft(
                            draft("ABCDE12345", "KH_VAT", "KH", "CORP", null)));
        }

        @Test
        void vnMst_tenDigits_passes() {
            assertDoesNotThrow(() ->
                    PartnerValidator.validateCreateDraft(
                            draft("0312345678", "VN_MST", "VN", "LLC", null)));
        }

        @Test
        void vnMst_thirteenDigits_passes() {
            // Vietnamese branch entities carry a 13-digit MST.
            assertDoesNotThrow(() ->
                    PartnerValidator.validateCreateDraft(
                            draft("0312345678123", "VN_MST", "VN", "LLC", null)));
        }

        @Test
        void vnMst_elevenDigits_rejected() {
            assertThrows(PartnerValidator.ValidationException.class, () ->
                    PartnerValidator.validateCreateDraft(
                            draft("03123456789", "VN_MST", "VN", "LLC", null)));
        }

        @Test
        void sgUen_nineCharsTrailingLetter_passes() {
            // Legacy 9-char Business form: 8 alphanumeric + check letter.
            assertDoesNotThrow(() ->
                    PartnerValidator.validateCreateDraft(
                            draft("53999999X", "SG_UEN", "SG", "CORP", null)));
        }

        @Test
        void sgUen_tenCharsTrailingLetter_passes() {
            // Modern 10-char form for entities registered after 2009: YYYY + 5 digits + letter.
            assertDoesNotThrow(() ->
                    PartnerValidator.validateCreateDraft(
                            draft("201712345A", "SG_UEN", "SG", "CORP", null)));
        }

        @Test
        void sgUen_trailingDigit_rejected() {
            // UEN must end with an alphabetic check character.
            assertThrows(PartnerValidator.ValidationException.class, () ->
                    PartnerValidator.validateCreateDraft(
                            draft("201712345", "SG_UEN", "SG", "CORP", null)));
        }

        @Test
        void generic_anyNonBlank_passes() {
            assertDoesNotThrow(() ->
                    PartnerValidator.validateCreateDraft(
                            draft("free-form-tax-string", "GENERIC", "TH", "OTHER", null)));
        }

        @Test
        void unknownTaxIdType_rejected() {
            assertThrows(PartnerValidator.ValidationException.class, () ->
                    PartnerValidator.validateCreateDraft(
                            draft("1234567890", "ZZ_UNKNOWN", "KR", "CORP", null)));
        }

        @Test
        void taxId_withoutType_rejected() {
            assertThrows(PartnerValidator.ValidationException.class, () ->
                    PartnerValidator.validateCreateDraft(
                            draft("1234567890", null, "KR", "CORP", null)));
        }

        @Test
        void taxIdType_withoutValue_rejected() {
            assertThrows(PartnerValidator.ValidationException.class, () ->
                    PartnerValidator.validateCreateDraft(
                            draft(null, "KR_BRN", "KR", "CORP", null)));
        }
    }

    @Nested
    class CountryRules {

        @Test
        void validIsoAlpha2_passes() {
            assertDoesNotThrow(() ->
                    PartnerValidator.validateCreateDraft(
                            draft(null, null, "KR", "CORP", null)));
        }

        @Test
        void unknownCountry_rejected() {
            // "ZZ" is reserved-for-private-use under ISO-3166 and is not in
            // Locale#getISOCountries(); the validator should reject it.
            PartnerValidator.ValidationException e = assertThrows(
                    PartnerValidator.ValidationException.class, () ->
                            PartnerValidator.validateCreateDraft(
                                    draft(null, null, "ZZ", "CORP", null)));
            assertTrue(e.getMessage().contains("countryOfIncorporation"),
                    "error should name the offending field: " + e.getMessage());
        }

        @Test
        void threeCharCountry_rejected() {
            // alpha-3 codes (e.g. KOR) are rejected — we want alpha-2.
            assertThrows(PartnerValidator.ValidationException.class, () ->
                    PartnerValidator.validateCreateDraft(
                            draft(null, null, "KOR", "CORP", null)));
        }

        @Test
        void registeredAddressCountry_validated() {
            AddressCommand bad = new AddressCommand("street", null, "city", null, "00000", "XX");
            PartnerCommand.CreateDraft cmd = new PartnerCommand.CreateDraft(
                    "ACME", PartnerType.OVERSEAS, "USD", RoundingMode.HALF_UP,
                    null, null, null, null, "KR", "CORP",
                    bad, null, null);
            PartnerValidator.ValidationException e = assertThrows(
                    PartnerValidator.ValidationException.class, () ->
                            PartnerValidator.validateCreateDraft(cmd));
            assertTrue(e.getMessage().contains("registeredAddress.country"),
                    "address country errors should mention the sub-field path: "
                            + e.getMessage());
        }
    }

    @Nested
    class LegalFormRules {

        @Test
        void allKnownForms_pass() {
            for (String form : new String[]{"CORP", "LLC", "MTO", "EMI", "BANK", "OTHER"}) {
                assertDoesNotThrow(() ->
                                PartnerValidator.validateCreateDraft(
                                        draft(null, null, "KR", form, null)),
                        "legal form " + form + " should be accepted");
            }
        }

        @Test
        void unknownLegalForm_rejected() {
            assertThrows(PartnerValidator.ValidationException.class, () ->
                    PartnerValidator.validateCreateDraft(
                            draft(null, null, "KR", "PARTNERSHIP", null)));
        }
    }

    @Nested
    class LeiRules {

        // Known-good LEI from the GLEIF "ISO 17442 — The LEI code structure" page
        // (https://www.gleif.org/en/about-lei/iso-17442-the-lei-code-structure):
        // a Bank of America Merrill Lynch LEI whose checksum is published and
        // verifiable. Used to confirm our checksum implementation matches the
        // official reference; if this test starts failing the checksum routine has
        // regressed.
        private static final String VALID_LEI = "B4TYDEB6GKMZO031MB27";

        @Test
        void validLei_passes() {
            assertTrue(PartnerValidator.leiChecksumValid(VALID_LEI),
                    "GLEIF reference LEI must pass the mod-97-10 checksum");
            assertDoesNotThrow(() ->
                    PartnerValidator.validateCreateDraft(
                            draft(null, null, null, null, VALID_LEI)));
        }

        @Test
        void leiTooShort_rejected() {
            assertThrows(PartnerValidator.ValidationException.class, () ->
                    PartnerValidator.validateCreateDraft(
                            draft(null, null, null, null, "B4TYDEB6GKMZO031MB2")));
        }

        @Test
        void leiWithSpecialChars_rejected() {
            // 20 chars but one is a hyphen — the LEI alphabet is strictly A-Z0-9.
            assertThrows(PartnerValidator.ValidationException.class, () ->
                    PartnerValidator.validateCreateDraft(
                            draft(null, null, null, null, "B4TYDEB6GKMZO031MB-7")));
        }

        @Test
        void leiWithBadChecksum_rejected() {
            // Flip the last two checksum digits from "27" to "00" — the shape still
            // passes the alphanumeric/length check, only the mod-97-10 verification
            // should fire.
            String corrupted = VALID_LEI.substring(0, 18) + "00";
            PartnerValidator.ValidationException e = assertThrows(
                    PartnerValidator.ValidationException.class, () ->
                            PartnerValidator.validateCreateDraft(
                                    draft(null, null, null, null, corrupted)));
            assertTrue(e.getMessage().toLowerCase().contains("checksum"),
                    "checksum failure should be named in the error: " + e.getMessage());
        }
    }

    @Nested
    class UpdateStep1Rules {

        @Test
        void sameRulesAsCreate() {
            // The UpdateStep1 shape is identical sans partnerCode; the validator must
            // apply the same per-field rules. One sanity check is enough — the
            // per-field branches are already exhaustively covered above.
            PartnerCommand.UpdateStep1 bad = new PartnerCommand.UpdateStep1(
                    PartnerType.OVERSEAS, "USD", RoundingMode.HALF_UP,
                    null, null, "INVALID_KR_BRN", "KR_BRN", "KR", "CORP",
                    null, null, null);
            assertThrows(PartnerValidator.ValidationException.class, () ->
                    PartnerValidator.validateUpdateStep1(bad));
        }

        @Test
        void allNull_passes() {
            // Every Identity field is optional — a completely empty UpdateStep1 is
            // legal (the wizard might save Step 1 with no Identity progress yet).
            PartnerCommand.UpdateStep1 empty = new PartnerCommand.UpdateStep1(
                    PartnerType.OVERSEAS, "USD", RoundingMode.HALF_UP,
                    null, null, null, null, null, null, null, null, null);
            assertDoesNotThrow(() -> PartnerValidator.validateUpdateStep1(empty));
        }
    }
}
