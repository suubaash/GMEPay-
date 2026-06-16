import * as yup from 'yup';

/**
 * Yup schema for Slice 6A — Step 6 currency-split + pricing rules sections
 * of the Partner Setup wizard (agent 6A.2).
 *
 * Wire shapes:
 *   currencySplit: { collectionCcy: string, settleACcy: string }
 *     -> PATCH /v1/admin/partners/draft/{code}/step-6-currency-split
 *
 *   rules: [{ schemeId, direction, mA, mB, serviceChargeUsd }]
 *     -> PATCH /v1/admin/partners/draft/{code}/step-6-rules
 *
 * Cross-border invariant (lib-domain Rule.validate):
 *   mA + mB >= 2% (i.e. >= 0.02) when collectionCcy !== settleACcy.
 *   This is enforced as a SOFT WARNING (chip) on the form — submit is blocked
 *   when cross-border and sum < 2%, but pure-domestic (same currency) is
 *   allowed to go below 2%.
 *
 * All margin fields are decimal FRACTION strings:
 *   "0.0150" = 1.50%   "0.0200" = 2.00%
 * Matching the RuleView / RuleCommand BigDecimal wire format per
 * docs/MONEY_CONVENTION.md.
 */

/** Allowed transaction directions for a pricing rule. */
export const RULE_DIRECTIONS = ['INBOUND', 'OUTBOUND', 'BOTH'];

/** Human-readable labels for the direction radio group. */
export const RULE_DIRECTION_LABELS = {
  INBOUND:  'Inbound (partner sends funds to GME)',
  OUTBOUND: 'Outbound (GME disburses to partner)',
  BOTH:     'Both directions',
};

/**
 * Cross-border minimum margin floor (2%) as a decimal fraction.
 * Matches lib-domain Rule.CROSS_BORDER_FLOOR = 0.02.
 */
export const CROSS_BORDER_FLOOR = 0.02;

/**
 * Decimal-fraction string validator for mA / mB margin fields.
 *
 * Accepts: "0", "0.0150", "0.02", "1.0000"  (any decimal fraction >= 0, <= 4dp)
 * Rejects: negative, non-numeric, more than 4 decimal places.
 *
 * @param {string} label  Field label for error messages.
 * @returns {yup.StringSchema}
 */
export function marginFractionField(label) {
  const FRACTION_RE = /^\d+(\.\d{1,4})?$/;
  return yup
    .string()
    .trim()
    .required(`${label} is required`)
    .test(
      'is-fraction-string',
      `${label} must be a decimal fraction (e.g. "0.0150" for 1.50%)`,
      (v) => typeof v === 'string' && FRACTION_RE.test(v.trim()),
    )
    .test(
      'is-non-negative',
      `${label} must be 0 or greater`,
      (v) => {
        if (typeof v !== 'string') return false;
        const n = parseFloat(v.trim());
        return Number.isFinite(n) && n >= 0;
      },
    )
    .test(
      'is-at-most-one',
      `${label} must not exceed 1.0 (100%)`,
      (v) => {
        if (typeof v !== 'string') return false;
        const n = parseFloat(v.trim());
        return Number.isFinite(n) && n <= 1;
      },
    );
}

/**
 * Decimal-string validator for serviceChargeUsd.
 * Accepts any decimal >= 0 with up to 4 dp; the field is optional (nullable).
 */
export function serviceChargeField() {
  const DECIMAL_RE = /^\d{1,7}(\.\d{1,4})?$/; // <=7 integer digits (paired with the <=1,000,000 cap below)
  return yup
    .string()
    .trim()
    .nullable()
    .default('0.0000')
    .test(
      'is-decimal-string-or-empty',
      'Service charge must be a non-negative decimal up to 1,000,000 (max 4 dp)',
      (v) => {
        if (v == null || v === '') return true;
        const n = parseFloat(v.trim());
        return DECIMAL_RE.test(v.trim()) && n >= 0 && n <= 1000000;
      },
    );
}

/** Sub-schema for a single pricing rule row. */
export const ruleRowSchema = yup.object({
  schemeId:        yup.string().trim().required('Scheme is required').max(40, 'Scheme ID max 40 characters'),
  direction:       yup.string().oneOf(RULE_DIRECTIONS, 'Select a direction').required('Direction is required'),
  mA:              marginFractionField('Partner margin (mA)'),
  mB:              marginFractionField('GME margin (mB)'),
  serviceChargeUsd: serviceChargeField(),
});

/** Sub-schema for the currency split section. */
export const currencySplitSchema = yup.object({
  collectionCcy: yup
    .string()
    .trim()
    .required('Collection currency is required')
    .matches(/^[A-Z]{3}$/, 'Must be a 3-letter ISO-4217 code'),
  settleACcy: yup
    .string()
    .trim()
    .required('Settlement currency is required')
    .matches(/^[A-Z]{3}$/, 'Must be a 3-letter ISO-4217 code'),
});

/**
 * Root schema for the 6A.2 form sections (currency split + rules).
 * The parent Step6CommercialForm uses this to validate before calling
 * patchDraftStep6Rules (rules) and patchDraftStep6CurrencySplit (currencies).
 */
const partnerStep6RulesSchema = yup.object({
  currencySplit: currencySplitSchema,
  rules: yup.array().of(ruleRowSchema).default([]),
});

export default partnerStep6RulesSchema;

// ─────────────────────────────────────────────────────────────────────────────
// Cross-border soft-warning helpers
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Returns true when the two currencies are different (cross-border conversion
 * will occur), meaning the mA + mB >= 2% invariant applies.
 *
 * @param {string|null|undefined} collectionCcy
 * @param {string|null|undefined} settleACcy
 * @returns {boolean}
 */
export function isCrossBorder(collectionCcy, settleACcy) {
  if (!collectionCcy || !settleACcy) return false;
  return collectionCcy.trim().toUpperCase() !== settleACcy.trim().toUpperCase();
}

/**
 * Returns true when a rule row's mA + mB sum is below the 2% cross-border
 * floor AND the currency pair is cross-border. Returns false (no warning) for
 * pure-domestic (same-currency) pairs, even when sum < 2%.
 *
 * @param {string|null|undefined} collectionCcy
 * @param {string|null|undefined} settleACcy
 * @param {string|null|undefined} mA  Decimal-fraction string
 * @param {string|null|undefined} mB  Decimal-fraction string
 * @returns {boolean}
 */
export function isMarginSumBelowFloor(collectionCcy, settleACcy, mA, mB) {
  if (!isCrossBorder(collectionCcy, settleACcy)) return false;
  const a = parseFloat(mA ?? '0');
  const b = parseFloat(mB ?? '0');
  if (!Number.isFinite(a) || !Number.isFinite(b)) return false;
  return a + b < CROSS_BORDER_FLOOR;
}
