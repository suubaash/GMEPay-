import * as yup from 'yup';

/**
 * Yup schema for the manual rate-preview form (/rates).
 *
 * Mirrors the BFF body shape for POST /v1/admin/rates/preview:
 *   { fromCcy, toCcy, amount, direction, partnerId }
 *
 *  - fromCcy / toCcy : 3 uppercase letters (ISO-4217 subset KRW/USD/MNT/JPY/VND
 *                      driven by SUPPORTED_CURRENCIES on the page).
 *  - amount          : positive decimal, accepted as a string so we never lose
 *                      precision (per docs/MONEY_CONVENTION.md).
 *  - direction       : "INBOUND" | "OUTBOUND" — radio.
 *  - partnerId       : required; resolved from the partners list.
 */
export const ratePreviewSchema = yup.object({
  fromCcy: yup
    .string()
    .required('From currency is required')
    .matches(/^[A-Z]{3}$/, 'Currency must be a 3-letter ISO-4217 code'),
  toCcy: yup
    .string()
    .required('To currency is required')
    .matches(/^[A-Z]{3}$/, 'Currency must be a 3-letter ISO-4217 code'),
  amount: yup
    .string()
    .required('Amount is required')
    .matches(/^\d+(\.\d+)?$/, 'Amount must be a positive number')
    .test('non-zero', 'Amount must be greater than zero', (v) => {
      if (!v) return false;
      return Number.parseFloat(v) > 0;
    }),
  direction: yup
    .string()
    .oneOf(['INBOUND', 'OUTBOUND'], 'Invalid direction')
    .required('Direction is required'),
  partnerId: yup.string().required('Partner is required'),
});
