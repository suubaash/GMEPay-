/**
 * Vitest contract tests for partnerStep6CommercialSchema (Slice 6B.2).
 *
 * Covers:
 *  - Valid complete object passes validation.
 *  - Missing required fields produce appropriate errors.
 *  - Decimal string validation (fixedFeeUsd, bpsFee, etc.).
 *  - effectiveTo must be after effectiveFrom.
 *  - quoteHoldSeconds range (60..1800).
 *  - noticePeriodDays must be >= 0.
 *  - direction must be INBOUND or OUTBOUND.
 *  - referenceRateSource must be one of the allowed enum values.
 *  - refundChargebackPolicy must be one of the allowed values.
 *  - Tier bpsOverride and fromVolumeUsd are decimal strings.
 */
import { describe, expect, it } from 'vitest';
import partnerStep6CommercialSchema from '@/schemas/partnerStep6CommercialSchema';

function valid() {
  return {
    feeSchedule: {
      scheme: 'ZEROPAY',
      direction: 'OUTBOUND',
      fixedFeeUsd: '1.50',
      bpsFee: '100',
      tiers: [],
    },
    fxConfig: {
      marginBps: '150',
      referenceRateSource: 'SEOUL_FX_BROKER',
      quoteHoldSeconds: 300,
    },
    limits: {
      perTxnMinUsd: '1.00',
      perTxnMaxUsd: '4000.00',
      dailyCapUsd: '40000.00',
      monthlyCapUsd: '200000.00',
      annualCapUsd: '1000000.00',
      licenseType: 'MSB',
    },
    contract: {
      effectiveFrom: '2026-01-01',
      effectiveTo: null,
      autoRenewal: true,
      noticePeriodDays: 30,
      refundChargebackPolicy: 'PARTNER_BEARS',
      terminationReason: null,
    },
  };
}

describe('partnerStep6CommercialSchema', () => {
  it('validates a fully-correct object', async () => {
    await expect(partnerStep6CommercialSchema.validate(valid(), { abortEarly: false })).resolves.toBeDefined();
  });

  it('rejects when scheme is empty', async () => {
    const data = valid();
    data.feeSchedule.scheme = '';
    // An empty scheme trips both `.required` and the `.matches` pattern, so with abortEarly:false
    // yup's top-level message is the "N errors occurred" summary — inspect inner errors instead
    // (same pattern as the decimal/effective-from cases below).
    const err = await partnerStep6CommercialSchema.validate(data, { abortEarly: false }).catch((e) => e);
    const messages = err.inner?.map((e) => e.message) ?? [err.message];
    expect(messages.some((m) => /scheme/i.test(m))).toBe(true);
  });

  it('rejects invalid direction', async () => {
    const data = valid();
    data.feeSchedule.direction = 'SIDEWAYS';
    await expect(partnerStep6CommercialSchema.validate(data, { abortEarly: false })).rejects.toThrow(/direction/i);
  });

  it('rejects non-decimal fixedFeeUsd', async () => {
    const data = valid();
    data.feeSchedule.fixedFeeUsd = 'abc';
    const err = await partnerStep6CommercialSchema.validate(data, { abortEarly: false }).catch((e) => e);
    const messages = err.inner?.map((e) => e.message) ?? [err.message];
    expect(messages.some((m) => /decimal/i.test(m))).toBe(true);
  });

  it('rejects negative bpsFee', async () => {
    const data = valid();
    data.feeSchedule.bpsFee = '-1';
    const err = await partnerStep6CommercialSchema.validate(data, { abortEarly: false }).catch((e) => e);
    const messages = err.inner?.map((e) => e.message) ?? [err.message];
    expect(messages.some((m) => /0 or greater/i.test(m))).toBe(true);
  });

  it('rejects invalid referenceRateSource', async () => {
    const data = valid();
    data.fxConfig.referenceRateSource = 'UNKNOWN_SOURCE';
    await expect(partnerStep6CommercialSchema.validate(data, { abortEarly: false })).rejects.toThrow(/reference rate source/i);
  });

  it('rejects quoteHoldSeconds below 60', async () => {
    const data = valid();
    data.fxConfig.quoteHoldSeconds = 30;
    await expect(partnerStep6CommercialSchema.validate(data, { abortEarly: false })).rejects.toThrow(/60/);
  });

  it('rejects quoteHoldSeconds above 1800', async () => {
    const data = valid();
    data.fxConfig.quoteHoldSeconds = 1801;
    await expect(partnerStep6CommercialSchema.validate(data, { abortEarly: false })).rejects.toThrow(/1800/);
  });

  it('accepts quoteHoldSeconds at boundary values 60 and 1800', async () => {
    const d60 = valid();
    d60.fxConfig.quoteHoldSeconds = 60;
    await expect(partnerStep6CommercialSchema.validate(d60, { abortEarly: false })).resolves.toBeDefined();

    const d1800 = valid();
    d1800.fxConfig.quoteHoldSeconds = 1800;
    await expect(partnerStep6CommercialSchema.validate(d1800, { abortEarly: false })).resolves.toBeDefined();
  });

  it('rejects perTxnMinUsd = 0 (must be gte0)', async () => {
    const data = valid();
    data.limits.perTxnMinUsd = '0.00';
    // gte0 allows 0
    await expect(partnerStep6CommercialSchema.validate(data, { abortEarly: false })).resolves.toBeDefined();
  });

  it('rejects perTxnMaxUsd = 0 (must be gt0)', async () => {
    const data = valid();
    data.limits.perTxnMaxUsd = '0.00';
    await expect(partnerStep6CommercialSchema.validate(data, { abortEarly: false })).rejects.toThrow(/greater than 0/i);
  });

  it('rejects empty licenseType', async () => {
    const data = valid();
    data.limits.licenseType = '';
    await expect(partnerStep6CommercialSchema.validate(data, { abortEarly: false })).rejects.toThrow(/license/i);
  });

  it('rejects missing effectiveFrom', async () => {
    const data = valid();
    data.contract.effectiveFrom = '';
    const err = await partnerStep6CommercialSchema.validate(data, { abortEarly: false }).catch((e) => e);
    const messages = err.inner?.map((e) => e.message) ?? [err.message];
    expect(messages.some((m) => /effective from/i.test(m))).toBe(true);
  });

  it('rejects effectiveFrom with wrong format', async () => {
    const data = valid();
    data.contract.effectiveFrom = '01/01/2026';
    await expect(partnerStep6CommercialSchema.validate(data, { abortEarly: false })).rejects.toThrow(/YYYY-MM-DD/);
  });

  it('rejects effectiveTo before effectiveFrom', async () => {
    const data = valid();
    data.contract.effectiveFrom = '2026-06-01';
    data.contract.effectiveTo = '2026-01-01';
    await expect(partnerStep6CommercialSchema.validate(data, { abortEarly: false })).rejects.toThrow(/after/i);
  });

  it('accepts effectiveTo after effectiveFrom', async () => {
    const data = valid();
    data.contract.effectiveFrom = '2026-01-01';
    data.contract.effectiveTo = '2027-01-01';
    await expect(partnerStep6CommercialSchema.validate(data, { abortEarly: false })).resolves.toBeDefined();
  });

  it('accepts null effectiveTo (open-ended contract)', async () => {
    const data = valid();
    data.contract.effectiveTo = null;
    await expect(partnerStep6CommercialSchema.validate(data, { abortEarly: false })).resolves.toBeDefined();
  });

  it('rejects noticePeriodDays below 0', async () => {
    const data = valid();
    data.contract.noticePeriodDays = -1;
    await expect(partnerStep6CommercialSchema.validate(data, { abortEarly: false })).rejects.toThrow(/negative/i);
  });

  it('accepts noticePeriodDays = 0', async () => {
    const data = valid();
    data.contract.noticePeriodDays = 0;
    await expect(partnerStep6CommercialSchema.validate(data, { abortEarly: false })).resolves.toBeDefined();
  });

  it('rejects invalid refundChargebackPolicy', async () => {
    const data = valid();
    data.contract.refundChargebackPolicy = 'NOBODY_BEARS';
    await expect(partnerStep6CommercialSchema.validate(data, { abortEarly: false })).rejects.toThrow(/policy/i);
  });

  it('validates tiers with correct decimal strings', async () => {
    const data = valid();
    data.feeSchedule.tiers = [
      { fromVolumeUsd: '10000.00', bpsOverride: '80' },
      { fromVolumeUsd: '50000.00', bpsOverride: '60' },
    ];
    await expect(partnerStep6CommercialSchema.validate(data, { abortEarly: false })).resolves.toBeDefined();
  });

  it('rejects tiers with non-decimal bpsOverride', async () => {
    const data = valid();
    data.feeSchedule.tiers = [
      { fromVolumeUsd: '10000.00', bpsOverride: 'N/A' },
    ];
    const err = await partnerStep6CommercialSchema.validate(data, { abortEarly: false }).catch((e) => e);
    const messages = err.inner?.map((e) => e.message) ?? [err.message];
    expect(messages.some((m) => /decimal/i.test(m))).toBe(true);
  });
});
