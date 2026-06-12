/**
 * Yup-level checks for the Slice 6A.2 rules schema.
 *
 * Covers:
 *  - marginFractionField: valid fractions, rejects negatives/non-numeric
 *  - currencySplitSchema: required, ISO-3-letter pattern
 *  - ruleRowSchema: direction roster, mA+mB presence, serviceCharge optional
 *  - isCrossBorder: same/different currency detection
 *  - isMarginSumBelowFloor: 2% floor for cross-border pairs
 */
import { describe, expect, it } from 'vitest';
import partnerStep6RulesSchema, {
  currencySplitSchema,
  ruleRowSchema,
  isCrossBorder,
  isMarginSumBelowFloor,
  CROSS_BORDER_FLOOR,
  marginFractionField,
} from '@/schemas/partnerStep6RulesSchema';

// ─────────────────────────────────────────────────────────────────────────────
// marginFractionField
// ─────────────────────────────────────────────────────────────────────────────

describe('marginFractionField', () => {
  const field = marginFractionField('Test');

  it('accepts "0"', async () => {
    await expect(field.validate('0')).resolves.toBeDefined();
  });

  it('accepts "0.0150"', async () => {
    await expect(field.validate('0.0150')).resolves.toBeDefined();
  });

  it('accepts "1.0000"', async () => {
    await expect(field.validate('1.0000')).resolves.toBeDefined();
  });

  it('rejects empty string', async () => {
    await expect(field.validate('')).rejects.toThrow(/required/i);
  });

  it('rejects non-numeric string', async () => {
    await expect(field.validate('abc')).rejects.toThrow(/decimal fraction/i);
  });

  it('rejects more than 4 decimal places', async () => {
    // 5 dp not allowed by /^\d+(\.\d{1,4})?$/
    await expect(field.validate('0.01500')).rejects.toThrow(/decimal fraction/i);
  });

  it('rejects negative value', async () => {
    // "-0.01" fails the decimal-fraction regex (no leading minus in pattern)
    await expect(field.validate('-0.01')).rejects.toThrow(/decimal fraction/i);
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// currencySplitSchema
// ─────────────────────────────────────────────────────────────────────────────

describe('currencySplitSchema', () => {
  const valid = { collectionCcy: 'KRW', settleACcy: 'USD' };

  it('accepts a valid cross-border pair', async () => {
    await expect(currencySplitSchema.validate(valid)).resolves.toBeDefined();
  });

  it('accepts a same-currency pair (domestic)', async () => {
    await expect(
      currencySplitSchema.validate({ collectionCcy: 'KRW', settleACcy: 'KRW' }),
    ).resolves.toBeDefined();
  });

  it('rejects a missing collectionCcy', async () => {
    await expect(
      currencySplitSchema.validate({ collectionCcy: '', settleACcy: 'USD' }),
    ).rejects.toThrow(/collection currency is required/i);
  });

  it('rejects a missing settleACcy', async () => {
    await expect(
      currencySplitSchema.validate({ collectionCcy: 'KRW', settleACcy: '' }),
    ).rejects.toThrow(/settlement currency is required/i);
  });

  it('rejects a lowercase currency code', async () => {
    await expect(
      currencySplitSchema.validate({ collectionCcy: 'krw', settleACcy: 'USD' }),
    ).rejects.toThrow(/ISO-4217/i);
  });

  it('rejects a 4-letter code', async () => {
    await expect(
      currencySplitSchema.validate({ collectionCcy: 'EURO', settleACcy: 'USD' }),
    ).rejects.toThrow(/ISO-4217/i);
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// ruleRowSchema
// ─────────────────────────────────────────────────────────────────────────────

describe('ruleRowSchema', () => {
  const validRow = {
    schemeId:         'ZEROPAY',
    direction:        'OUTBOUND',
    mA:               '0.0150',
    mB:               '0.0050',
    serviceChargeUsd: '0.5000',
  };

  it('accepts a fully-populated valid row', async () => {
    await expect(ruleRowSchema.validate(validRow)).resolves.toBeDefined();
  });

  it('accepts INBOUND direction', async () => {
    await expect(ruleRowSchema.validate({ ...validRow, direction: 'INBOUND' })).resolves.toBeDefined();
  });

  it('accepts BOTH direction', async () => {
    await expect(ruleRowSchema.validate({ ...validRow, direction: 'BOTH' })).resolves.toBeDefined();
  });

  it('accepts null serviceChargeUsd (optional)', async () => {
    await expect(ruleRowSchema.validate({ ...validRow, serviceChargeUsd: null })).resolves.toBeDefined();
  });

  it('accepts zero mA and mB (domestic corridor)', async () => {
    await expect(
      ruleRowSchema.validate({ ...validRow, mA: '0', mB: '0' }),
    ).resolves.toBeDefined();
  });

  it('rejects a missing schemeId', async () => {
    await expect(
      ruleRowSchema.validate({ ...validRow, schemeId: '' }),
    ).rejects.toThrow(/scheme is required/i);
  });

  it('rejects schemeId longer than 40 characters', async () => {
    await expect(
      ruleRowSchema.validate({ ...validRow, schemeId: 'A'.repeat(41) }),
    ).rejects.toThrow(/max 40/i);
  });

  it('rejects an invalid direction', async () => {
    await expect(
      ruleRowSchema.validate({ ...validRow, direction: 'FORWARD' }),
    ).rejects.toThrow(/direction/i);
  });

  it('rejects a missing mA', async () => {
    await expect(
      ruleRowSchema.validate({ ...validRow, mA: '' }),
    ).rejects.toThrow(/required/i);
  });

  it('rejects a missing mB', async () => {
    await expect(
      ruleRowSchema.validate({ ...validRow, mB: '' }),
    ).rejects.toThrow(/required/i);
  });

  it('rejects a negative mA', async () => {
    await expect(
      ruleRowSchema.validate({ ...validRow, mA: '-0.01' }),
    ).rejects.toThrow(/decimal fraction/i);
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// isCrossBorder
// ─────────────────────────────────────────────────────────────────────────────

describe('isCrossBorder', () => {
  it('returns true when currencies differ', () => {
    expect(isCrossBorder('KRW', 'USD')).toBe(true);
  });

  it('returns false when currencies are the same', () => {
    expect(isCrossBorder('KRW', 'KRW')).toBe(false);
  });

  it('returns false when collectionCcy is null', () => {
    expect(isCrossBorder(null, 'USD')).toBe(false);
  });

  it('returns false when settleACcy is null', () => {
    expect(isCrossBorder('KRW', null)).toBe(false);
  });

  it('is case-insensitive', () => {
    expect(isCrossBorder('krw', 'KRW')).toBe(false);
    expect(isCrossBorder('krw', 'USD')).toBe(true);
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// isMarginSumBelowFloor
// ─────────────────────────────────────────────────────────────────────────────

describe('isMarginSumBelowFloor', () => {
  it('returns true when cross-border and sum < 2%', () => {
    // 0.0100 + 0.0050 = 0.0150 < 0.02
    expect(isMarginSumBelowFloor('KRW', 'USD', '0.0100', '0.0050')).toBe(true);
  });

  it('returns false when cross-border and sum exactly 2%', () => {
    expect(isMarginSumBelowFloor('KRW', 'USD', '0.0150', '0.0050')).toBe(false);
  });

  it('returns false when cross-border and sum > 2%', () => {
    expect(isMarginSumBelowFloor('KRW', 'USD', '0.0200', '0.0100')).toBe(false);
  });

  it('returns false for domestic corridor even when sum < 2%', () => {
    // Same currency — floor does not apply
    expect(isMarginSumBelowFloor('KRW', 'KRW', '0.0000', '0.0000')).toBe(false);
  });

  it('returns false when currencies are null (unknown)', () => {
    expect(isMarginSumBelowFloor(null, null, '0.0000', '0.0000')).toBe(false);
  });

  it('handles zero margins gracefully', () => {
    expect(isMarginSumBelowFloor('KRW', 'USD', '0', '0')).toBe(true);
  });

  it('handles string "0.0200" + "0.0000" = exactly 2%', () => {
    expect(isMarginSumBelowFloor('KRW', 'USD', '0.0200', '0.0000')).toBe(false);
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// CROSS_BORDER_FLOOR constant
// ─────────────────────────────────────────────────────────────────────────────

describe('CROSS_BORDER_FLOOR', () => {
  it('is 0.02 (2%)', () => {
    expect(CROSS_BORDER_FLOOR).toBe(0.02);
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// Root schema (partnerStep6RulesSchema)
// ─────────────────────────────────────────────────────────────────────────────

describe('partnerStep6RulesSchema', () => {
  const baseValues = {
    currencySplit: { collectionCcy: 'KRW', settleACcy: 'USD' },
    rules: [
      {
        schemeId:         'ZEROPAY',
        direction:        'OUTBOUND',
        mA:               '0.0150',
        mB:               '0.0050',
        serviceChargeUsd: '0.0000',
      },
    ],
  };

  it('accepts a valid cross-border rule set', async () => {
    await expect(partnerStep6RulesSchema.validate(baseValues)).resolves.toBeDefined();
  });

  it('accepts an empty rules array', async () => {
    await expect(
      partnerStep6RulesSchema.validate({ ...baseValues, rules: [] }),
    ).resolves.toBeDefined();
  });

  it('rejects when currencySplit is missing collectionCcy', async () => {
    await expect(
      partnerStep6RulesSchema.validate({
        ...baseValues,
        currencySplit: { collectionCcy: '', settleACcy: 'USD' },
      }),
    ).rejects.toThrow(/collection currency is required/i);
  });

  it('rejects when a rule has an invalid direction', async () => {
    await expect(
      partnerStep6RulesSchema.validate({
        ...baseValues,
        rules: [{ ...baseValues.rules[0], direction: 'LEFT' }],
      }),
    ).rejects.toThrow(/direction/i);
  });
});
