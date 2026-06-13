/**
 * Slice 1 Identity-step enum constants for the Admin UI Partner Setup wizard.
 *
 * These values are pinned to the server-side allow-lists in
 * {@code services/config-registry/.../partner/PartnerValidator.java}. Any
 * drift will surface as a 400 from the BFF — the form's helper-text rule
 * names the offending field so the operator can react without inspecting
 * the wire.
 *
 * Kept out of {@code api/constants.js} (which carries the long-standing
 * PARTNER_TYPES / ROUNDING_MODES / TXN_STATES set) so the Identity step
 * stays self-contained and easy to delete or rename per slice.
 */

/**
 * Tax-id discriminator. See {@code PartnerValidator.TAX_ID_TYPES}.
 *  - KR_BRN  Korean Business Registration Number (10 digits)
 *  - KH_VAT  Cambodian VAT TIN (10 digits)
 *  - VN_MST  Vietnamese Mã số thuế (10 or 13 digits)
 *  - SG_UEN  Singapore Unique Entity Number (ACRA format)
 *  - GENERIC Catch-all for jurisdictions without a structured ID rule
 */
export const TAX_ID_TYPES = ['KR_BRN', 'KH_VAT', 'VN_MST', 'SG_UEN', 'GENERIC'];

/** Display labels for the tax-id type picker. */
export const TAX_ID_TYPE_LABELS = {
  KR_BRN: 'KR — Business Registration Number (사업자등록번호)',
  KH_VAT: 'KH — VAT TIN',
  VN_MST: 'VN — Mã số thuế (MST)',
  SG_UEN: 'SG — Unique Entity Number (UEN)',
  GENERIC: 'Other (generic)',
};

/**
 * Legal-form enum. See {@code PartnerValidator.LEGAL_FORMS}.
 *  - CORP  Corporation / 株式会社 / 주식회사
 *  - LLC   Limited Liability Company / 유한회사
 *  - MTO   Money Transfer Operator / 소액해외송금업
 *  - EMI   Electronic Money Institution
 *  - BANK  Bank
 *  - OTHER Anything else (rare; activation gate will challenge)
 */
export const LEGAL_FORMS = ['CORP', 'LLC', 'MTO', 'EMI', 'BANK', 'OTHER'];

/** Display labels for the legal-form picker. */
export const LEGAL_FORM_LABELS = {
  CORP: 'Corporation',
  LLC: 'Limited Liability Company',
  MTO: 'Money Transfer Operator (소액해외송금업)',
  EMI: 'Electronic Money Institution',
  BANK: 'Bank',
  OTHER: 'Other',
};

/**
 * ISO-3166 alpha-2 country codes. Static seed list — covers the entries
 * recognised by the JVM's {@code Locale.getISOCountries()} (the same table
 * the server-side validator consults). Sorted alphabetically so the form's
 * picker has stable order.
 *
 * Maintained by hand to keep the bundle minimal and avoid pulling in a
 * full localisation package for what is, today, a single picker. If the
 * server adds a code the list does not contain, the Yup test surfaces it
 * as "Unknown ISO-3166 alpha-2 country code" — add the missing code here.
 */
export const ISO_3166_ALPHA2 = [
  'AD', 'AE', 'AF', 'AG', 'AI', 'AL', 'AM', 'AO', 'AQ', 'AR',
  'AS', 'AT', 'AU', 'AW', 'AX', 'AZ', 'BA', 'BB', 'BD', 'BE',
  'BF', 'BG', 'BH', 'BI', 'BJ', 'BL', 'BM', 'BN', 'BO', 'BQ',
  'BR', 'BS', 'BT', 'BV', 'BW', 'BY', 'BZ', 'CA', 'CC', 'CD',
  'CF', 'CG', 'CH', 'CI', 'CK', 'CL', 'CM', 'CN', 'CO', 'CR',
  'CU', 'CV', 'CW', 'CX', 'CY', 'CZ', 'DE', 'DJ', 'DK', 'DM',
  'DO', 'DZ', 'EC', 'EE', 'EG', 'EH', 'ER', 'ES', 'ET', 'FI',
  'FJ', 'FK', 'FM', 'FO', 'FR', 'GA', 'GB', 'GD', 'GE', 'GF',
  'GG', 'GH', 'GI', 'GL', 'GM', 'GN', 'GP', 'GQ', 'GR', 'GS',
  'GT', 'GU', 'GW', 'GY', 'HK', 'HM', 'HN', 'HR', 'HT', 'HU',
  'ID', 'IE', 'IL', 'IM', 'IN', 'IO', 'IQ', 'IR', 'IS', 'IT',
  'JE', 'JM', 'JO', 'JP', 'KE', 'KG', 'KH', 'KI', 'KM', 'KN',
  'KP', 'KR', 'KW', 'KY', 'KZ', 'LA', 'LB', 'LC', 'LI', 'LK',
  'LR', 'LS', 'LT', 'LU', 'LV', 'LY', 'MA', 'MC', 'MD', 'ME',
  'MF', 'MG', 'MH', 'MK', 'ML', 'MM', 'MN', 'MO', 'MP', 'MQ',
  'MR', 'MS', 'MT', 'MU', 'MV', 'MW', 'MX', 'MY', 'MZ', 'NA',
  'NC', 'NE', 'NF', 'NG', 'NI', 'NL', 'NO', 'NP', 'NR', 'NU',
  'NZ', 'OM', 'PA', 'PE', 'PF', 'PG', 'PH', 'PK', 'PL', 'PM',
  'PN', 'PR', 'PS', 'PT', 'PW', 'PY', 'QA', 'RE', 'RO', 'RS',
  'RU', 'RW', 'SA', 'SB', 'SC', 'SD', 'SE', 'SG', 'SH', 'SI',
  'SJ', 'SK', 'SL', 'SM', 'SN', 'SO', 'SR', 'SS', 'ST', 'SV',
  'SX', 'SY', 'SZ', 'TC', 'TD', 'TF', 'TG', 'TH', 'TJ', 'TK',
  'TL', 'TM', 'TN', 'TO', 'TR', 'TT', 'TV', 'TW', 'TZ', 'UA',
  'UG', 'UM', 'US', 'UY', 'UZ', 'VA', 'VC', 'VE', 'VG', 'VI',
  'VN', 'VU', 'WF', 'WS', 'YE', 'YT', 'ZA', 'ZM', 'ZW',
];

/**
 * A small set of human-readable country labels for the picker. The full
 * map would be ~250 entries; we keep the cross-border-payment hotlist
 * inline so the dropdown shows something more legible than the raw code
 * for the common cases. Codes not in this map render as the bare alpha-2.
 */
export const COUNTRY_LABELS = {
  KR: 'Korea, Republic of',
  KH: 'Cambodia',
  VN: 'Viet Nam',
  SG: 'Singapore',
  US: 'United States',
  JP: 'Japan',
  CN: 'China',
  HK: 'Hong Kong',
  TH: 'Thailand',
  MY: 'Malaysia',
  ID: 'Indonesia',
  PH: 'Philippines',
  LA: 'Lao People’s Democratic Republic',
  MM: 'Myanmar',
  TW: 'Taiwan',
  IN: 'India',
  AE: 'United Arab Emirates',
  GB: 'United Kingdom',
  AU: 'Australia',
  NZ: 'New Zealand',
};
