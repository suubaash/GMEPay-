import * as yup from 'yup';

/**
 * Yup schemas for the V031 configurable commission-sharing editors — there is
 * NO fixed 70/30. Two surfaces share these helpers:
 *
 *   - Scheme setup (QR Schemes page): GME↔scheme split of the net merchant fee.
 *       row: { direction|'', gmeSharePct, vanFeePct }
 *       PUT /v1/admin/schemes/{schemeId}/commission-shares
 *
 *   - Partner setup (Step 6 commercial terms): GME↔partner split of GME's
 *       commission. row: { schemeId, direction|'', partnerSharePct }
 *       PUT /v1/admin/partners/{partnerCode}/commission-shares
 *
 * All shares are decimal FRACTION strings (e.g. "0.7000" = 70%) matching the
 * SchemeCommissionShareView / PartnerCommissionShareView wire format per
 * docs/MONEY_CONVENTION.md. Direction "" means "all directions" (the wildcard
 * row config-registry stores as NULL).
 */

/** Direction roster; "" (empty) is the "all directions" wildcard. */
export const COMMISSION_DIRECTIONS = ['INBOUND', 'OUTBOUND', 'BOTH'];

/** Select options including the wildcard "all" entry. */
export const DIRECTION_OPTIONS = [
  { value: '', label: 'All directions' },
  { value: 'INBOUND', label: 'Inbound' },
  { value: 'OUTBOUND', label: 'Outbound' },
  { value: 'BOTH', label: 'Both' },
];

/** NUMERIC(7,4) ceiling for the VAN rate (mirrors the config-registry guard). */
const VAN_MAX = 999.9999;

const FRACTION_RE = /^\d+(\.\d{1,4})?$/;

/**
 * Format a decimal-fraction string as a percentage (up to 2 dp). "" on parse
 * failure. e.g. "0.7000" -> "70.00%".
 */
export function fmtPct(fractionStr) {
  const n = parseFloat(fractionStr ?? '');
  if (!Number.isFinite(n)) return '';
  return `${(n * 100).toFixed(2)}%`;
}

/**
 * A share-fraction validator: decimal string in [0,1] (or (0,1] when
 * strictlyPositive), at most 4 decimal places.
 *
 * @param {string}  label
 * @param {boolean} strictlyPositive  reject 0 (GME's scheme share must be > 0).
 */
export function shareFractionField(label, strictlyPositive = false) {
  return yup
    .string()
    .trim()
    .required(`${label} is required`)
    .test(
      'is-fraction-string',
      `${label} must be a decimal fraction (e.g. "0.7000" for 70%)`,
      (v) => typeof v === 'string' && FRACTION_RE.test(v.trim()),
    )
    .test(
      'in-range',
      strictlyPositive
        ? `${label} must be greater than 0 and at most 1 (100%)`
        : `${label} must be between 0 and 1 (100%)`,
      (v) => {
        const n = parseFloat(v ?? '');
        if (!Number.isFinite(n)) return false;
        return strictlyPositive ? n > 0 && n <= 1 : n >= 0 && n <= 1;
      },
    );
}

/** VAN rate validator: optional non-negative decimal up to NUMERIC(7,4). */
export function vanRateField(label) {
  return yup
    .string()
    .trim()
    .nullable()
    .default('0.0000')
    .test(
      'is-rate-or-empty',
      `${label} must be a non-negative rate up to ${VAN_MAX} (max 4 dp)`,
      (v) => {
        if (v == null || v === '') return true;
        if (!FRACTION_RE.test(v.trim())) return false;
        const n = parseFloat(v.trim());
        return Number.isFinite(n) && n >= 0 && n <= VAN_MAX;
      },
    );
}

const directionField = yup
  .string()
  .oneOf(['', ...COMMISSION_DIRECTIONS], 'Select a direction')
  .default('');

/** One scheme-side commission-share row. */
export const schemeShareRowSchema = yup.object({
  direction: directionField,
  gmeSharePct: shareFractionField('GME share', true),
  vanFeePct: vanRateField('VAN rate'),
});

/** One partner-side commission-share row. */
export const partnerShareRowSchema = yup.object({
  schemeId: yup.string().trim().max(40, 'Scheme ID max 40 characters').default(''),
  direction: directionField,
  partnerSharePct: shareFractionField('Partner share', false),
});

/** Root schema for the scheme commission editor ({ shares: [...] }). */
export const schemeCommissionFormSchema = yup.object({
  shares: yup.array().of(schemeShareRowSchema).default([]),
});

/** Root schema for the partner commission editor ({ shares: [...] }). */
export const partnerCommissionFormSchema = yup.object({
  shares: yup.array().of(partnerShareRowSchema).default([]),
});

/**
 * Duplicate-key detector mirroring the config-registry validation (at most one
 * row per (schemeId, direction) pair; "" / null = wildcard). Returns the index
 * of the first duplicate, or -1 when the set is unique.
 *
 * @param {Array} rows      the row objects
 * @param {boolean} withScheme  true for partner rows (key on schemeId+direction),
 *                              false for scheme rows (key on direction only).
 */
export function firstDuplicateIndex(rows, withScheme) {
  const seen = new Set();
  for (let i = 0; i < (rows ?? []).length; i += 1) {
    const r = rows[i] ?? {};
    const dir = (r.direction ?? '').trim() || '*';
    const key = withScheme ? `${(r.schemeId ?? '').trim() || '*'}:${dir}` : dir;
    if (seen.has(key)) return i;
    seen.add(key);
  }
  return -1;
}
