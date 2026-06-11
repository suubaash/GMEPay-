import { describe, expect, it } from 'vitest';
import partnerStep4BankSchema, {
  ibanMod97Valid,
  bankAccountRowSchema,
} from '@/schemas/partnerStep4BankSchema';

/**
 * Yup-level tests for the Slice 4 Banking & Settlement schema.
 *
 * Covers:
 *   - ibanMod97Valid standalone function
 *   - bankAccountRowSchema: required fields, BIC format, IBAN mod-97
 *   - partnerStep4BankSchema: one-primary-per-currency array constraint
 */

// ── Minimal valid account factory ───────────────────────────────────────────

function validAccount(overrides = {}) {
  return {
    currency: 'USD',
    bankName: 'First National Bank',
    bicSwift: 'FNBKUSD1',
    ibanOrAccountNumber: '123456789',   // domestic number — no mod-97 check
    accountHolderName: 'GME Corp',
    bankCountry: 'US',
    intermediaryBic: null,
    swiftChargeBearer: 'SHA',
    purpose: 'PAYOUT',
    isPrimary: true,
    ...overrides,
  };
}

function validSchema(overrides = {}) {
  return { bankAccounts: [validAccount(overrides)] };
}

// ── ibanMod97Valid ────────────────────────────────────────────────────────────

describe('ibanMod97Valid', () => {
  it('accepts a known-good GB IBAN', () => {
    // GB29 NWBK 6016 1331 9268 19 — standard test vector
    expect(ibanMod97Valid('GB29NWBK60161331926819')).toBe(true);
  });

  it('accepts a known-good DE IBAN', () => {
    // DE89 3704 0044 0532 0130 00
    expect(ibanMod97Valid('DE89370400440532013000')).toBe(true);
  });

  it('rejects an IBAN with a bad check digit', () => {
    // Flip the check digits of the GB IBAN above.
    expect(ibanMod97Valid('GB30NWBK60161331926819')).toBe(false);
  });

  it('returns true for a domestic account number (no leading alpha-2)', () => {
    // Domestic numbers do not start with two letters — skip mod-97.
    expect(ibanMod97Valid('123456789012')).toBe(true);
    expect(ibanMod97Valid('0123-4567-89')).toBe(true);
  });

  it('returns false for non-string input', () => {
    expect(ibanMod97Valid(null)).toBe(false);
    expect(ibanMod97Valid(undefined)).toBe(false);
    expect(ibanMod97Valid(12345)).toBe(false);
  });

  it('tolerates spaces (strips them before check)', () => {
    expect(ibanMod97Valid('GB29 NWBK 6016 1331 9268 19')).toBe(true);
  });
});

// ── bankAccountRowSchema ──────────────────────────────────────────────────────

describe('bankAccountRowSchema — happy path', () => {
  it('accepts a fully-populated row with a domestic account number', async () => {
    await expect(bankAccountRowSchema.validate(validAccount())).resolves.toBeDefined();
  });

  it('accepts a row with a valid IBAN', async () => {
    await expect(
      bankAccountRowSchema.validate(
        validAccount({ ibanOrAccountNumber: 'GB29NWBK60161331926819' }),
      ),
    ).resolves.toBeDefined();
  });

  it('accepts an 11-char BIC', async () => {
    await expect(
      bankAccountRowSchema.validate(validAccount({ bicSwift: 'FNBKUSD1XXX' })),
    ).resolves.toBeDefined();
  });

  it('accepts a row with an intermediary BIC', async () => {
    await expect(
      bankAccountRowSchema.validate(
        validAccount({ intermediaryBic: 'CHASUS33' }),
      ),
    ).resolves.toBeDefined();
  });

  it('accepts a null intermediary BIC', async () => {
    await expect(
      bankAccountRowSchema.validate(validAccount({ intermediaryBic: null })),
    ).resolves.toBeDefined();
  });

  it('accepts an empty-string intermediary BIC (coerced to null)', async () => {
    await expect(
      bankAccountRowSchema.validate(validAccount({ intermediaryBic: '' })),
    ).resolves.toBeDefined();
  });
});

describe('bankAccountRowSchema — BIC validation', () => {
  it('rejects a BIC shorter than 8 characters', async () => {
    await expect(
      bankAccountRowSchema.validate(validAccount({ bicSwift: 'FNBKUSD' })),
    ).rejects.toThrow(/BIC must be 8 or 11/i);
  });

  it('rejects a BIC of 10 characters (not 8 or 11)', async () => {
    await expect(
      bankAccountRowSchema.validate(validAccount({ bicSwift: 'FNBKUSD1XX' })),
    ).rejects.toThrow(/BIC must be 8 or 11/i);
  });

  it('rejects a BIC containing a lowercase letter (after transform toUpperCase, valid)', async () => {
    // After the transform, 'fnbkusd1' becomes 'FNBKUSD1' — should resolve.
    await expect(
      bankAccountRowSchema.validate(validAccount({ bicSwift: 'fnbkusd1' })),
    ).resolves.toBeDefined();
  });

  it('rejects a BIC containing a special character', async () => {
    await expect(
      bankAccountRowSchema.validate(validAccount({ bicSwift: 'FNBK-USD' })),
    ).rejects.toThrow(/BIC must be 8 or 11/i);
  });

  it('rejects an empty BIC', async () => {
    await expect(
      bankAccountRowSchema.validate(validAccount({ bicSwift: '' })),
    ).rejects.toThrow(/BIC\/SWIFT is required/i);
  });
});

describe('bankAccountRowSchema — IBAN mod-97', () => {
  it('rejects a mal-formed IBAN (starts with letters but fails mod-97)', async () => {
    await expect(
      bankAccountRowSchema.validate(
        validAccount({ ibanOrAccountNumber: 'GB00NWBK60161331926819' }),
      ),
    ).rejects.toThrow(/checksum/i);
  });

  it('accepts a domestic account number unchanged', async () => {
    await expect(
      bankAccountRowSchema.validate(
        validAccount({ ibanOrAccountNumber: '9876543210' }),
      ),
    ).resolves.toBeDefined();
  });
});

describe('bankAccountRowSchema — required fields', () => {
  it('rejects a missing currency', async () => {
    await expect(
      bankAccountRowSchema.validate(validAccount({ currency: '' })),
    ).rejects.toThrow(/Currency is required/i);
  });

  it('rejects a currency of wrong length', async () => {
    await expect(
      bankAccountRowSchema.validate(validAccount({ currency: 'US' })),
    ).rejects.toThrow(/ISO-4217/i);
  });

  it('rejects a missing bank name', async () => {
    await expect(
      bankAccountRowSchema.validate(validAccount({ bankName: '' })),
    ).rejects.toThrow(/Bank name is required/i);
  });

  it('rejects a missing account holder name', async () => {
    await expect(
      bankAccountRowSchema.validate(validAccount({ accountHolderName: '' })),
    ).rejects.toThrow(/Account holder name is required/i);
  });

  it('rejects a missing bank country', async () => {
    await expect(
      bankAccountRowSchema.validate(validAccount({ bankCountry: '' })),
    ).rejects.toThrow(/Bank country is required/i);
  });

  it('rejects an invalid charge bearer', async () => {
    await expect(
      bankAccountRowSchema.validate(validAccount({ swiftChargeBearer: 'INVALID' })),
    ).rejects.toThrow(/Select a charge bearer/i);
  });

  it('rejects an invalid purpose', async () => {
    await expect(
      bankAccountRowSchema.validate(validAccount({ purpose: 'UNKNOWN' })),
    ).rejects.toThrow(/Select a purpose/i);
  });
});

// ── partnerStep4BankSchema — one-primary-per-currency ────────────────────────

describe('partnerStep4BankSchema — one-primary-per-currency', () => {
  it('accepts when each currency has exactly one primary', async () => {
    await expect(
      partnerStep4BankSchema.validate({
        bankAccounts: [
          validAccount({ currency: 'USD', isPrimary: true }),
          validAccount({ currency: 'KRW', isPrimary: true }),
        ],
      }),
    ).resolves.toBeDefined();
  });

  it('accepts when some accounts are non-primary', async () => {
    await expect(
      partnerStep4BankSchema.validate({
        bankAccounts: [
          validAccount({ currency: 'USD', isPrimary: true }),
          validAccount({ currency: 'USD', isPrimary: false }),
        ],
      }),
    ).resolves.toBeDefined();
  });

  it('rejects when two accounts in the same currency are both primary', async () => {
    await expect(
      partnerStep4BankSchema.validate({
        bankAccounts: [
          validAccount({ currency: 'USD', isPrimary: true }),
          validAccount({ currency: 'USD', isPrimary: true }),
        ],
      }),
    ).rejects.toThrow(/at most one primary/i);
  });

  it('rejects an empty bankAccounts array', async () => {
    await expect(
      partnerStep4BankSchema.validate({ bankAccounts: [] }),
    ).rejects.toThrow(/at least one bank account/i);
  });
});
