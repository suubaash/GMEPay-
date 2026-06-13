import * as yup from 'yup';

/**
 * Yup schema for the Slice 4 Banking & Settlement step of the Partner Setup
 * wizard. Mirrors the BFF's BankAccountRequest shape:
 *
 *   {
 *     currency:              string (3-letter ISO-4217),
 *     bankName:              string,
 *     bicSwift:              string (8 or 11 chars, uppercase letters/digits),
 *     ibanOrAccountNumber:   string,
 *     accountHolderName:     string,
 *     bankCountry:           string (ISO-3166 alpha-2),
 *     intermediaryBic:       string? (same format as bicSwift),
 *     swiftChargeBearer:     'OUR'|'BEN'|'SHA',
 *     purpose:               'PAYOUT'|'FLOAT_TOPUP'|'REFUND',
 *     isPrimary:             boolean,
 *   }
 *
 * IBAN mod-97 check mirrors the server-side rule. Accounts that use a
 * domestic account number instead of an IBAN pass as long as the value is
 * non-empty — the mod-97 test is skipped when the value doesn't start with
 * two letters (i.e. is clearly not an IBAN).
 *
 * Primary constraint: at most one row per currency may have isPrimary=true.
 * This is enforced at the array level via a custom test on the top-level
 * schema, not per-row.
 */

/** ISO-4217 3-letter currency code pattern. */
const CURRENCY_PATTERN = /^[A-Z]{3}$/;

/** BIC/SWIFT 8-char or 11-char format: 4 bank + 2 country + 2 loc + 3? branch */
const BIC_PATTERN = /^[A-Z0-9]{4}[A-Z]{2}[A-Z0-9]{2}([A-Z0-9]{3})?$/;

/** SWIFT charge bearer values. */
export const CHARGE_BEARERS = ['OUR', 'BEN', 'SHA'];

/** Human-readable labels for charge bearer. */
export const CHARGE_BEARER_LABELS = {
  OUR: 'OUR — Sender pays all fees',
  BEN: 'BEN — Beneficiary pays all fees',
  SHA: 'SHA — Shared (each pays own fees)',
};

/** Bank-account purpose values. */
export const ACCOUNT_PURPOSES = ['PAYOUT', 'FLOAT_TOPUP', 'REFUND'];

/** Human-readable labels for bank-account purpose. */
export const ACCOUNT_PURPOSE_LABELS = {
  PAYOUT: 'Payout',
  FLOAT_TOPUP: 'Float top-up',
  REFUND: 'Refund',
};

/** Verification-status display configuration. */
export const VERIFICATION_STATUS_CONFIG = {
  UNVERIFIED: { label: 'Unverified', color: 'default' },
  KFTC_VERIFIED: { label: 'KFTC Verified', color: 'success' },
  BANK_LETTER: { label: 'Bank Letter', color: 'info' },
  MICRO_DEPOSIT: { label: 'Micro-deposit', color: 'warning' },
};

/**
 * IBAN mod-97 check (ISO 13616-1 / ECBS 204).
 *
 * Steps:
 *   1. Move first 4 chars to the end.
 *   2. Convert each letter A-Z to its decimal value (A=10 … Z=35).
 *   3. Compute the resulting digit string mod 97.
 *   4. Result must equal 1.
 *
 * Returns true when the value does NOT look like an IBAN (no leading two
 * letters) — domestic account numbers are allowed and skip the check.
 *
 * @param {string} value
 * @returns {boolean}
 */
export function ibanMod97Valid(value) {
  if (typeof value !== 'string') return false;
  const v = value.replace(/\s/g, '').toUpperCase();
  // If it doesn't start with two letters it's not an IBAN — allow it.
  if (!/^[A-Z]{2}/.test(v)) return true;
  // Must be at least 5 chars (country + 2-digit check + 1 char BBAN)
  if (v.length < 5) return false;
  // Rearrange: move first 4 chars to end.
  const rearranged = v.slice(4) + v.slice(0, 4);
  // Replace letters with digits: A=10 … Z=35.
  const numeric = rearranged.replace(/[A-Z]/g, (c) => String(c.charCodeAt(0) - 55));
  // Compute mod 97 in chunks to avoid JS float precision loss.
  let remainder = 0;
  for (let i = 0; i < numeric.length; i += 9) {
    const chunk = String(remainder) + numeric.slice(i, i + 9);
    remainder = parseInt(chunk, 10) % 97;
  }
  return remainder === 1;
}

/** Single bank account row schema. */
export const bankAccountRowSchema = yup.object({
  currency: yup
    .string()
    .trim()
    .required('Currency is required')
    .matches(CURRENCY_PATTERN, 'Currency must be a 3-letter ISO-4217 code (e.g. USD)'),

  bankName: yup
    .string()
    .trim()
    .required('Bank name is required')
    .max(200, 'Bank name must be 200 characters or fewer'),

  bicSwift: yup
    .string()
    .trim()
    .required('BIC/SWIFT is required')
    .transform((v) => (typeof v === 'string' ? v.toUpperCase().replace(/\s/g, '') : v))
    .matches(BIC_PATTERN, 'BIC must be 8 or 11 uppercase alphanumeric characters'),

  ibanOrAccountNumber: yup
    .string()
    .trim()
    .required('IBAN or account number is required')
    .max(34, 'IBAN/account number must be 34 characters or fewer')
    .test('iban-mod97', 'IBAN checksum invalid (mod-97 check failed)', (v) =>
      v == null || ibanMod97Valid(v),
    ),

  accountHolderName: yup
    .string()
    .trim()
    .required('Account holder name is required')
    .max(200, 'Account holder name must be 200 characters or fewer'),

  bankCountry: yup
    .string()
    .trim()
    .required('Bank country is required')
    .matches(/^[A-Z]{2}$/, 'Bank country must be a 2-letter ISO-3166 alpha-2 code'),

  intermediaryBic: yup
    .string()
    .trim()
    .nullable()
    .transform((v) => {
      if (!v || v.trim() === '') return null;
      return v.toUpperCase().replace(/\s/g, '');
    })
    .test(
      'intermediary-bic-format',
      'Intermediary BIC must be 8 or 11 uppercase alphanumeric characters',
      (v) => v == null || BIC_PATTERN.test(v),
    ),

  swiftChargeBearer: yup
    .string()
    .oneOf(CHARGE_BEARERS, 'Select a charge bearer')
    .required('Charge bearer is required'),

  purpose: yup
    .string()
    .oneOf(ACCOUNT_PURPOSES, 'Select a purpose')
    .required('Purpose is required'),

  isPrimary: yup.boolean().default(false),
});

/**
 * Top-level Step-4 schema — wraps an array of bank account rows and enforces
 * the "one primary per currency" constraint.
 */
const partnerStep4BankSchema = yup.object({
  bankAccounts: yup
    .array()
    .of(bankAccountRowSchema)
    .min(1, 'At least one bank account is required')
    .required('Bank accounts are required')
    .test(
      'one-primary-per-currency',
      'Each currency may have at most one primary account',
      (accounts) => {
        if (!Array.isArray(accounts)) return true;
        const primaryCountByCurrency = {};
        for (const acct of accounts) {
          if (acct?.isPrimary) {
            const ccy = acct.currency ?? '';
            primaryCountByCurrency[ccy] = (primaryCountByCurrency[ccy] ?? 0) + 1;
            if (primaryCountByCurrency[ccy] > 1) return false;
          }
        }
        return true;
      },
    ),
});

export default partnerStep4BankSchema;
