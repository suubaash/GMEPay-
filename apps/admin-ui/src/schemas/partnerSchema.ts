import * as yup from 'yup';
import { PARTNER_TYPES, ROUNDING_MODES } from '@/api/types';

/**
 * Yup schema for the partner CREATE form.
 *
 * - partnerId: 3-32 chars, [A-Z0-9_-] (matches config-registry's PartnerEntity@Id length=32).
 * - type: LOCAL | OVERSEAS (com.gme.pay.domain.PartnerType).
 * - settlementCurrency: 3 uppercase letters (ISO-4217).
 * - settlementRoundingMode: one of the 7 java.math.RoundingMode values that
 *   settlement booking uses (see docs/MONEY_CONVENTION.md).
 */
export const partnerSchema = yup.object({
  partnerId: yup
    .string()
    .required('Partner ID is required')
    .matches(
      /^[A-Z0-9_-]{3,32}$/,
      'Partner ID must be 3-32 uppercase letters, digits, hyphen or underscore',
    ),
  type: yup
    .string()
    .oneOf(PARTNER_TYPES as readonly string[], 'Invalid partner type')
    .required('Partner type is required'),
  settlementCurrency: yup
    .string()
    .required('Settlement currency is required')
    .matches(/^[A-Z]{3}$/, 'Currency must be a 3-letter ISO-4217 code'),
  settlementRoundingMode: yup
    .string()
    .oneOf(ROUNDING_MODES as readonly string[], 'Invalid rounding mode')
    .required('Rounding mode is required'),
});

export type PartnerFormValues = yup.InferType<typeof partnerSchema>;
