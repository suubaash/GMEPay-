import * as yup from 'yup';

/**
 * Yup schema for Slice 6 — Step 6 (Commercial Terms) of the Partner Setup wizard.
 *
 * Wire shape sent on submit (PATCH /api/v1/admin/partners/draft/{code}/step-6-commercial):
 *   {
 *     feeSchedule: {
 *       scheme:        string,
 *       direction:     'INBOUND' | 'OUTBOUND',
 *       fixedFeeUsd:   string (decimal),
 *       bpsFee:        string (decimal, basis points),
 *       tiers:         [{ fromVolumeUsd: string, bpsOverride: string }]
 *     },
 *     fxConfig: {
 *       marginBps:          string (decimal, basis points),
 *       referenceRateSource:'SEOUL_FX_BROKER' | 'PARTNER_PROVIDED' | 'MID_MARKET',
 *       quoteHoldSeconds:   number (60..1800)
 *     },
 *     limits: {
 *       perTxnMinUsd:   string (decimal),
 *       perTxnMaxUsd:   string (decimal),
 *       dailyCapUsd:    string (decimal),
 *       monthlyCapUsd:  string (decimal),
 *       annualCapUsd:   string (decimal),
 *       licenseType:    string
 *     },
 *     contract: {
 *       effectiveFrom:         string (YYYY-MM-DD),
 *       effectiveTo:           string (YYYY-MM-DD) | null,
 *       autoRenewal:           boolean,
 *       noticePeriodDays:      number (integer >= 0),
 *       refundChargebackPolicy:'PARTNER_BEARS' | 'SHARED' | 'GME_BEARS',
 *       terminationReason:     string | null
 *     }
 *   }
 *
 * Money fields MUST be transmitted as decimal strings (never floats) per
 * docs/MONEY_CONVENTION.md. The schema validates the string shape but does
 * NOT coerce to Number.
 */

/** Valid payment direction values. */
export const DIRECTIONS = ['INBOUND', 'OUTBOUND'];

/** Valid FX reference rate source values. */
export const REFERENCE_RATE_SOURCES = [
  'SEOUL_FX_BROKER',
  'PARTNER_PROVIDED',
  'MID_MARKET',
];

/** Human-readable labels for reference rate source select. */
export const REFERENCE_RATE_SOURCE_LABELS = {
  SEOUL_FX_BROKER:   'Seoul FX Broker (real-time KRW mid-market)',
  PARTNER_PROVIDED:  'Partner Provided (partner quotes the rate)',
  MID_MARKET:        'Mid-Market (ECB / Reuters mid-market)',
};

/** Valid refund/chargeback policy values. */
export const REFUND_CHARGEBACK_POLICIES = [
  'PARTNER_BEARS',
  'SHARED',
  'GME_BEARS',
];

/** Human-readable labels for refund/chargeback policy radio. */
export const REFUND_CHARGEBACK_POLICY_LABELS = {
  PARTNER_BEARS: 'Partner bears all refund & chargeback costs',
  SHARED:        'Costs shared 50/50 between GME and partner',
  GME_BEARS:     'GME bears all refund & chargeback costs',
};

/**
 * License types with the special 소액해외송금업 option that enforces
 * per-txn ≤ $5,000 and monthly ≤ $50,000 limits.
 */
export const LICENSE_TYPES = [
  'FULL_BANKING',
  'MSB',
  '소액해외송금업',
  'EMI',
  'OTHER',
];

export const LICENSE_TYPE_LABELS = {
  FULL_BANKING:   'Full Banking License',
  MSB:            'Money Services Business (MSB)',
  소액해외송금업: '소액해외송금업 (Korean small-amount overseas remittance)',
  EMI:            'Electronic Money Institution (EMI)',
  OTHER:          'Other',
};

/**
 * Per-txn max and monthly cap hard limits enforced when license_type is
 * 소액해외송금업. The submit button is blocked and form fields are visually
 * capped when these are exceeded.
 */
export const SOAEK_PER_TXN_MAX_USD = '5000.00';
export const SOAEK_MONTHLY_CAP_USD = '50000.00';

/**
 * Decimal-string validator: non-empty, parseable as a finite non-negative number.
 *
 * @param {string}        label   Field label for the error message.
 * @param {'gte0'|'gt0'}  mode    'gte0' → value >= 0; 'gt0' → value > 0.
 * @returns {yup.StringSchema}
 */
export function decimalStringField(label, mode = 'gte0') {
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

/** Sub-schema for a single fee tier row. */
const feeTierSchema = yup.object({
  fromVolumeUsd: decimalStringField('From volume (USD)', 'gte0'),
  bpsOverride:   decimalStringField('BPS override', 'gte0'),
});

/** Sub-schema for the fee schedule section. */
export const feeScheduleSchema = yup.object({
  scheme:      yup
    .string()
    .trim()
    .required('Scheme is required')
    .max(40, 'Scheme ID max 40 characters')
    .matches(/^[a-zA-Z0-9_-]+$/, 'Scheme ID may contain only letters, digits, hyphen and underscore'),
  direction:   yup
    .string()
    .oneOf(DIRECTIONS, 'Select a direction')
    .required('Direction is required'),
  fixedFeeUsd: decimalStringField('Fixed fee (USD)', 'gte0'),
  bpsFee:      decimalStringField('BPS fee', 'gte0'),
  tiers: yup
    .array()
    .of(feeTierSchema)
    .default([]),
});

/** Sub-schema for the FX config section. */
export const fxConfigSchema = yup.object({
  marginBps: decimalStringField('Margin (BPS)', 'gte0'),
  referenceRateSource: yup
    .string()
    .oneOf(REFERENCE_RATE_SOURCES, 'Select a reference rate source')
    .required('Reference rate source is required'),
  quoteHoldSeconds: yup
    .number()
    .integer('Must be a whole number of seconds')
    .min(60, 'Minimum 60 seconds')
    .max(1800, 'Maximum 1800 seconds')
    .required('Quote hold seconds is required')
    .typeError('Quote hold seconds must be a number'),
});

/** Sub-schema for the limits section. */
export const limitsSchema = yup.object({
  perTxnMinUsd:  decimalStringField('Per-txn minimum (USD)', 'gte0'),
  perTxnMaxUsd:  decimalStringField('Per-txn maximum (USD)', 'gt0'),
  dailyCapUsd:   decimalStringField('Daily cap (USD)', 'gt0'),
  monthlyCapUsd: decimalStringField('Monthly cap (USD)', 'gt0'),
  annualCapUsd:  decimalStringField('Annual cap (USD)', 'gt0'),
  licenseType:   yup.string().trim().required('License type is required'),
});

/** Sub-schema for the contract section. */
export const contractSchema = yup.object({
  effectiveFrom: yup
    .string()
    .trim()
    .required('Effective from date is required')
    .matches(/^\d{4}-\d{2}-\d{2}$/, 'Must be YYYY-MM-DD'),
  effectiveTo: yup
    .string()
    .trim()
    .nullable()
    .default(null)
    .transform((v) => (v === '' ? null : v))
    .test(
      'effective-to-after-from',
      'Effective to must be after effective from',
      function test(val) {
        if (!val) return true;
        const { effectiveFrom } = this.parent;
        if (!effectiveFrom) return true;
        return val > effectiveFrom;
      },
    ),
  autoRenewal:           yup.boolean().default(false),
  noticePeriodDays:      yup
    .number()
    .integer('Must be a whole number')
    .min(0, 'Cannot be negative')
    .max(3650, 'Notice period cannot exceed 3650 days (~10 years)')
    .required('Notice period is required')
    .typeError('Notice period must be a number'),
  refundChargebackPolicy: yup
    .string()
    .oneOf(REFUND_CHARGEBACK_POLICIES, 'Select a refund/chargeback policy')
    .required('Refund/chargeback policy is required'),
  terminationReason: yup
    .string()
    .max(200, 'Termination reason must be 200 characters or fewer')
    .nullable()
    .default(null)
    .transform((v) => (v === '' ? null : v)),
});

/** Root schema composing all four sub-schemas. */
const partnerStep6CommercialSchema = yup.object({
  feeSchedule: feeScheduleSchema,
  fxConfig:    fxConfigSchema,
  limits:      limitsSchema,
  contract:    contractSchema,
});

export default partnerStep6CommercialSchema;
