import * as yup from 'yup';

/**
 * Yup schema for the Slice 1 Identity step of the Partner Setup wizard.
 *
 * Mirrors {@code PartnerValidator} (services/config-registry) so the form
 * surfaces field-level errors inline before round-tripping to the BFF; the
 * server still re-validates and is the source of truth (Partner Setup plan
 * §"Slice 1 — Identity + Foundation").
 *
 * Shape (matches DraftPartnerStep1Request on the BFF):
 *   {
 *     partnerCode: string,                 // shown on the form but read-only
 *                                            // once the draft exists; included
 *                                            // here for the create flow.
 *     legalNameLocal:        string,
 *     legalNameRomanized:    string,
 *     taxIdType: 'KR_BRN'|'KH_VAT'|'VN_MST'|'SG_UEN'|'GENERIC',
 *     taxId:                 string,
 *     countryOfIncorporation: string,      // ISO-3166 alpha-2
 *     legalForm: 'CORP'|'LLC'|'MTO'|'EMI'|'BANK'|'OTHER',
 *     registeredAddress: { street1, street2?, city, state?, postcode, country },
 *     operatingSameAsRegistered: boolean,  // UI-only — drives copy on submit
 *     operatingAddress:  { street1, street2?, city, state?, postcode, country },
 *     lei:                   string?,      // optional, ISO 17442
 *   }
 *
 * Every field is required for an Identity step submission *except* the LEI
 * and the secondary address sub-fields (street2, state). Slice 1 wizards
 * save partial progress on PATCH, but the operator clicking "Next" implies
 * the step is complete — so we lean on Yup to catch shape errors before the
 * BFF rejects them.
 */

import { TAX_ID_TYPES, LEGAL_FORMS, ISO_3166_ALPHA2 } from '@/api/identityConstants';

/**
 * Per-{@code taxIdType} tax-id format regex. Kept in sync with
 * {@code PartnerValidator.checkTaxIdFormat} on the server. Exported so the
 * IdentityForm can reuse the same patterns for live helper-text hints.
 */
export const TAX_ID_PATTERNS = {
  KR_BRN: /^\d{10}$/,
  KH_VAT: /^\d{10}$/,
  VN_MST: /^\d{10}(\d{3})?$/,
  SG_UEN: /^[A-Za-z0-9]{8,9}[A-Za-z]$/,
  GENERIC: /^\S(.*\S)?$/, // any non-blank string
};

/**
 * ISO 17442 mod-97-10 checksum check for a 20-char LEI. Replaces each
 * letter with its base-10 value (A=10 ... Z=35), interprets the digit
 * string as a base-10 integer, and verifies {@code mod 97 == 1}. Computed
 * with a running long-style remainder (kept ≤ 96 throughout) so JS doubles
 * do not lose precision.
 *
 * Exported so the form can hint "checksum invalid" without re-implementing
 * the rule, and so the unit test can exercise the path directly.
 */
export function leiChecksumValid(lei) {
  if (typeof lei !== 'string') return false;
  const upper = lei.toUpperCase();
  if (!/^[A-Z0-9]{20}$/.test(upper)) return false;
  let remainder = 0;
  for (let i = 0; i < upper.length; i++) {
    const c = upper.charCodeAt(i);
    if (c >= 48 && c <= 57) {
      // '0'..'9'
      remainder = (remainder * 10 + (c - 48)) % 97;
    } else if (c >= 65 && c <= 90) {
      // 'A'..'Z' -> 10..35, append both digits
      const value = c - 65 + 10;
      remainder = (remainder * 10 + Math.floor(value / 10)) % 97;
      remainder = (remainder * 10 + (value % 10)) % 97;
    } else {
      return false;
    }
  }
  return remainder === 1;
}

/**
 * Yup schema for a structured address. Both registered and operating use
 * the same shape per ADR notes; {@code street2} and {@code state} are
 * optional because not every jurisdiction has them (e.g. Singapore).
 */
const addressSchema = yup.object({
  street1: yup.string().trim().required('Street line 1 is required'),
  street2: yup.string().trim().nullable().transform((v) => (v === '' ? null : v)),
  city: yup.string().trim().required('City is required'),
  state: yup.string().trim().nullable().transform((v) => (v === '' ? null : v)),
  postcode: yup.string().trim().required('Postcode is required'),
  country: yup
    .string()
    .trim()
    .required('Country is required')
    .matches(/^[A-Z]{2}$/, 'Country must be a 2-letter ISO-3166 alpha-2 code')
    .test('iso-3166', 'Unknown ISO-3166 alpha-2 country code', (v) =>
      v == null || ISO_3166_ALPHA2.includes(v.toUpperCase()),
    ),
});

export const partnerStep1Schema = yup.object({
  partnerCode: yup
    .string()
    .trim()
    .required('Partner code is required')
    .matches(
      /^[A-Z0-9_-]{3,20}$/,
      'Partner code must be 3-20 uppercase letters, digits, hyphen or underscore',
    ),

  legalNameLocal: yup
    .string()
    .trim()
    .required('Legal name (local script) is required')
    .max(200, 'Legal name must be 200 characters or fewer'),

  legalNameRomanized: yup
    .string()
    .trim()
    .required('Legal name (romanized) is required')
    .max(200, 'Romanized name must be 200 characters or fewer'),

  taxIdType: yup
    .string()
    .oneOf(TAX_ID_TYPES, 'Pick a tax-id type')
    .required('Tax-id type is required'),

  taxId: yup
    .string()
    .trim()
    .required('Tax id is required')
    .when('taxIdType', {
      is: (v) => v && v !== '',
      then: (s) =>
        s.test(
          'tax-id-format',
          ({ value }) => `Tax id does not match the selected type's format`,
          function testFormat(value) {
            const type = this.parent.taxIdType;
            if (!type || !value) return true;
            const pat = TAX_ID_PATTERNS[type];
            if (!pat) return true;
            return pat.test(value);
          },
        ),
    }),

  countryOfIncorporation: yup
    .string()
    .trim()
    .required('Country of incorporation is required')
    .matches(/^[A-Z]{2}$/, 'Country must be a 2-letter ISO-3166 alpha-2 code')
    .test('iso-3166', 'Unknown ISO-3166 alpha-2 country code', (v) =>
      v == null || ISO_3166_ALPHA2.includes(v.toUpperCase()),
    ),

  legalForm: yup
    .string()
    .oneOf(LEGAL_FORMS, 'Pick a legal form')
    .required('Legal form is required'),

  registeredAddress: addressSchema.required('Registered address is required'),

  operatingSameAsRegistered: yup.boolean().default(false),

  /**
   * Operating address: required by the schema, but the IdentityForm copies
   * the registered address over on submit when {@code operatingSameAsRegistered}
   * is true, so the operator only fills it once. The "same-as" toggle is the
   * UI affordance; the BFF gets the populated object either way.
   */
  operatingAddress: yup.object().when('operatingSameAsRegistered', {
    is: true,
    then: () => yup.object().nullable().notRequired(),
    otherwise: () => addressSchema.required('Operating address is required'),
  }),

  lei: yup
    .string()
    .trim()
    .nullable()
    .transform((v) => (v === '' ? null : v))
    .test('lei-shape', 'LEI must be 20 alphanumeric characters (A-Z, 0-9)', (v) =>
      v == null || /^[A-Z0-9]{20}$/.test(v.toUpperCase()),
    )
    .test(
      'lei-checksum',
      'LEI checksum invalid per ISO 17442 mod-97-10',
      (v) => v == null || leiChecksumValid(v),
    ),
});

export default partnerStep1Schema;
