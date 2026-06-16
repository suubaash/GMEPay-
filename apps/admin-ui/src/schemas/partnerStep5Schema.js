import * as yup from 'yup';

/**
 * Yup schema for Slice 5 — Step 5 (Prefunding) of the Partner Setup wizard.
 *
 * Only applies to OVERSEAS partners; the wizard shell enforces the guard
 * before rendering the form.
 *
 * Wire shape (PATCH /v1/admin/partners/draft/{code}/step-5):
 *   {
 *     fundingModel:            'PREFUNDED' | 'POSTPAID' | 'HYBRID',
 *     openingBalanceUsd:       string  (decimal, e.g. "10000.00"),
 *     lowBalanceThresholdUsd:  string  (decimal, > 0),
 *     alertTier70:             boolean,
 *     alertTier85:             boolean,
 *     alertTier95:             boolean,
 *     creditLimitUsd:          string  (decimal, >= 0),
 *     autoSuspendOnBreach:     boolean,
 *     floatTopUpBankAccountId: string | null  (UUID or null),
 *     topUpReferencePattern:   string  (must contain {partner_code}),
 *     collateralAmountUsd:     string  (decimal, >= 0),
 *   }
 *
 * Money fields MUST be transmitted as decimal strings (never floats) per
 * docs/MONEY_CONVENTION.md.  The schema validates the string shape but does
 * NOT coerce to Number — the consumer is responsible for keeping the value as
 * a string throughout the React layer.
 */

/** Valid funding-model discriminator values. */
export const FUNDING_MODELS = ['PREFUNDED', 'POSTPAID', 'HYBRID'];

/** Human-readable labels for the funding model radio. */
export const FUNDING_MODEL_LABELS = {
  PREFUNDED: 'Prefunded — partner maintains a positive float balance',
  POSTPAID:  'Postpaid — GME extends credit; partner settles periodically',
  HYBRID:    'Hybrid — prefunded with a credit facility overlay',
};

/**
 * Decimal-string validator: non-empty, parseable as a finite non-negative number.
 *
 * Yup's built-in `.number()` transforms strings to JS Number, which breaks the
 * money-convention requirement (BigDecimal on the wire).  We stay with
 * `.string()` and write a custom `.test()` instead.
 *
 * @param {string} label    Field label for the error message.
 * @param {'gte0'|'gt0'}  mode  'gte0' → value >= 0; 'gt0' → value > 0.
 * @returns {yup.StringSchema}
 */
function decimalStringField(label, mode = 'gte0') {
  const DECIMAL_RE = /^\d{1,15}(\.\d{1,4})?$/; // NUMERIC(19,4): <=15 integer digits, <=4 dp
  return yup
    .string()
    .trim()
    .required(`${label} is required`)
    .test(
      'is-decimal-string',
      `${label} must be a valid decimal number (e.g. "1000.00")`,
      (v) => typeof v === 'string' && DECIMAL_RE.test(v.trim()),
    )
    .test(
      mode === 'gt0' ? 'is-positive' : 'is-non-negative',
      mode === 'gt0'
        ? `${label} must be greater than 0`
        : `${label} must be 0 or greater`,
      (v) => {
        if (typeof v !== 'string') return false;
        const n = parseFloat(v.trim());
        return Number.isFinite(n) && (mode === 'gt0' ? n > 0 : n >= 0);
      },
    );
}

const partnerStep5Schema = yup.object({
  fundingModel: yup
    .string()
    .oneOf(FUNDING_MODELS, 'Select a funding model')
    .required('Funding model is required'),

  openingBalanceUsd: decimalStringField('Opening balance (USD)', 'gte0'),

  lowBalanceThresholdUsd: decimalStringField('Low-balance threshold (USD)', 'gt0'),

  alertTier70: yup.boolean().default(true),
  alertTier85: yup.boolean().default(true),
  alertTier95: yup.boolean().default(true),

  creditLimitUsd: decimalStringField('Credit limit (USD)', 'gte0'),

  autoSuspendOnBreach: yup.boolean().default(false),

  floatTopUpBankAccountId: yup
    .string()
    .nullable()
    .default(null)
    .transform((v) => (v === '' ? null : v)),

  topUpReferencePattern: yup
    .string()
    .trim()
    .max(60, 'Pattern must be 60 characters or fewer')
    .required('Top-up reference pattern is required')
    .test(
      'contains-partner-code-placeholder',
      'Pattern must contain {partner_code}',
      (v) => typeof v === 'string' && v.includes('{partner_code}'),
    ),

  collateralAmountUsd: decimalStringField('Collateral amount (USD)', 'gte0'),
});

export default partnerStep5Schema;
