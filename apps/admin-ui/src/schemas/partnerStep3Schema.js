import * as yup from 'yup';

/**
 * Yup schema for the Slice 3 KYB step of the Partner Setup wizard.
 *
 * Mirrors the BFF's KybCommand.UpdateStep3 shape:
 *   {
 *     riskRating: 'LOW'|'MEDIUM'|'HIGH',
 *     riskRationale: string,
 *     nextReviewDate: string (ISO-8601 date),
 *     licenseType: string,
 *     licenseNumber: string,
 *     licenseAuthority: string,
 *     licenseExpiry: string (ISO-8601 date),
 *     uboList: [{ name, ownershipPct, isPep, country }],
 *     cbddqDocId: string|null,
 *   }
 *
 * Ownership % per UBO is validated 0–100. A soft warning (shown in KybForm)
 * alerts the operator when the sum exceeds 100 — it is advisory and does not
 * block submission (ownership stakes can legitimately overlap for holding
 * structures). The BFF is the hard authority.
 */

/** Risk rating options. */
export const RISK_RATINGS = ['LOW', 'MEDIUM', 'HIGH'];

/** Human-readable labels for risk rating. */
export const RISK_RATING_LABELS = {
  LOW: 'Low',
  MEDIUM: 'Medium',
  HIGH: 'High',
};

/** ISO-3166-1 alpha-2 country list (same constants as step-1). */
export { ISO_3166_ALPHA2, COUNTRY_LABELS } from '@/api/identityConstants';

/** ISO date pattern (YYYY-MM-DD). */
const ISO_DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;

/** Single UBO row schema. */
export const uboRowSchema = yup.object({
  name: yup
    .string()
    .trim()
    .required('UBO name is required')
    .max(200, 'Name must be 200 characters or fewer'),

  ownershipPct: yup
    .number()
    .typeError('Ownership % must be a number')
    .required('Ownership % is required')
    .min(0, 'Ownership % must be 0 or more')
    .max(100, 'Ownership % must be 100 or less'),

  isPep: yup.boolean().default(false),

  country: yup
    .string()
    .trim()
    .required('Country is required')
    .length(2, 'Country must be a 2-letter ISO code'),
});

/** Top-level Step-3 schema. */
const partnerStep3Schema = yup.object({
  licenseType: yup
    .string()
    .trim()
    .required('License type is required')
    .max(50, 'License type must be 50 characters or fewer'),

  licenseNumber: yup
    .string()
    .trim()
    .required('License number is required')
    .max(50, 'License number must be 50 characters or fewer'),

  licenseAuthority: yup
    .string()
    .trim()
    .required('Issuing authority is required')
    .max(200, 'Authority must be 200 characters or fewer'),

  licenseExpiry: yup
    .string()
    .trim()
    .required('License expiry is required')
    .matches(ISO_DATE_PATTERN, 'Expiry must be a date in YYYY-MM-DD format'),

  uboList: yup
    .array()
    .of(uboRowSchema)
    .min(1, 'At least one UBO is required')
    .required('UBO list is required'),

  riskRating: yup
    .string()
    .oneOf(RISK_RATINGS, 'Select a risk rating')
    .required('Risk rating is required'),

  riskRationale: yup
    .string()
    .trim()
    .when('riskRating', {
      is: (v) => Boolean(v),
      then: (s) => s.required('Risk rationale is required when a rating is set'),
      otherwise: (s) => s.nullable().optional(),
    })
    .max(2000, 'Rationale must be 2000 characters or fewer'),

  nextReviewDate: yup
    .string()
    .trim()
    .required('Next review date is required')
    .matches(ISO_DATE_PATTERN, 'Review date must be a date in YYYY-MM-DD format'),

  cbddqDocId: yup
    .string()
    .trim()
    .nullable()
    .transform((v) => (v === '' ? null : v))
    .optional(),
});

export default partnerStep3Schema;
