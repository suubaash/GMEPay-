import { describe, expect, it } from 'vitest';
import partnerStep1Schema, {
  TAX_ID_PATTERNS,
  leiChecksumValid,
} from '@/schemas/partnerStep1Schema';

/**
 * Yup-level checks for the Slice 1 Identity schema. These mirror the
 * server-side rules in {@code PartnerValidator.java} so a green local form
 * matches a green server validate.
 */

const fullAddress = (overrides = {}) => ({
  street1: '1 Yangjae-daero',
  street2: 'Floor 7',
  city: 'Seoul',
  state: 'Seoul',
  postcode: '06743',
  country: 'KR',
  ...overrides,
});

const baseValues = (overrides = {}) => ({
  partnerCode: 'GME_KR_001',
  legalNameLocal: '주식회사 지엠이',
  legalNameRomanized: 'GME Corporation Co., Ltd.',
  taxIdType: 'KR_BRN',
  taxId: '1234567890',
  countryOfIncorporation: 'KR',
  legalForm: 'CORP',
  registeredAddress: fullAddress(),
  operatingSameAsRegistered: true,
  operatingAddress: fullAddress(),
  lei: '',
  ...overrides,
});

describe('partnerStep1Schema — happy path', () => {
  it('accepts a fully-populated KR partner with no LEI', async () => {
    await expect(partnerStep1Schema.validate(baseValues())).resolves.toBeDefined();
  });

  it('accepts a fully-populated KR partner with a valid LEI', async () => {
    // 213800WSGIIZCXF1P572 — published GLEIF sample (Goldman Sachs).
    await expect(
      partnerStep1Schema.validate(baseValues({ lei: '213800WSGIIZCXF1P572' })),
    ).resolves.toBeDefined();
  });

  it('accepts the schema when operatingSameAsRegistered=true even with blank operatingAddress', async () => {
    // The IdentityForm copies registered → operating on submit; the schema
    // tolerates a missing operating block while the toggle is on.
    await expect(
      partnerStep1Schema.validate(
        baseValues({
          operatingSameAsRegistered: true,
          operatingAddress: null,
        }),
      ),
    ).resolves.toBeDefined();
  });
});

describe('partnerStep1Schema — required-field guards', () => {
  it('rejects a missing partnerCode', async () => {
    await expect(
      partnerStep1Schema.validate(baseValues({ partnerCode: '' })),
    ).rejects.toThrow(/Partner code is required/i);
  });

  it('rejects a missing legalNameLocal', async () => {
    await expect(
      partnerStep1Schema.validate(baseValues({ legalNameLocal: '' })),
    ).rejects.toThrow(/Legal name \(local script\)/i);
  });

  it('rejects a missing legalNameRomanized', async () => {
    await expect(
      partnerStep1Schema.validate(baseValues({ legalNameRomanized: '' })),
    ).rejects.toThrow(/Legal name \(romanized\)/i);
  });

  it('rejects a missing countryOfIncorporation', async () => {
    await expect(
      partnerStep1Schema.validate(baseValues({ countryOfIncorporation: '' })),
    ).rejects.toThrow(/Country of incorporation is required/i);
  });

  it('rejects a missing legalForm', async () => {
    await expect(
      partnerStep1Schema.validate(baseValues({ legalForm: '' })),
    ).rejects.toThrow(/Legal form/i);
  });

  it('rejects an incomplete registered address (no city)', async () => {
    await expect(
      partnerStep1Schema.validate(
        baseValues({ registeredAddress: fullAddress({ city: '' }) }),
      ),
    ).rejects.toThrow(/City is required/i);
  });

  it('rejects an incomplete operating address when same-as is off', async () => {
    await expect(
      partnerStep1Schema.validate(
        baseValues({
          operatingSameAsRegistered: false,
          operatingAddress: fullAddress({ postcode: '' }),
        }),
      ),
    ).rejects.toThrow(/Postcode is required/i);
  });
});

describe('partnerStep1Schema — tax-id format', () => {
  it('rejects a KR_BRN with letters', async () => {
    await expect(
      partnerStep1Schema.validate(baseValues({ taxId: '123abc7890' })),
    ).rejects.toThrow(/format/i);
  });

  it('rejects a KR_BRN with 9 digits', async () => {
    await expect(
      partnerStep1Schema.validate(baseValues({ taxId: '123456789' })),
    ).rejects.toThrow(/format/i);
  });

  it('accepts a 13-digit VN_MST branch suffix', async () => {
    await expect(
      partnerStep1Schema.validate(
        baseValues({ taxIdType: 'VN_MST', taxId: '0312345678123' }),
      ),
    ).resolves.toBeDefined();
  });

  it('rejects a VN_MST with 11 digits (neither 10 nor 13)', async () => {
    await expect(
      partnerStep1Schema.validate(
        baseValues({ taxIdType: 'VN_MST', taxId: '03123456781' }),
      ),
    ).rejects.toThrow(/format/i);
  });

  it('accepts an SG_UEN 9-char with trailing letter', async () => {
    await expect(
      partnerStep1Schema.validate(
        baseValues({ taxIdType: 'SG_UEN', taxId: '53999999X' }),
      ),
    ).resolves.toBeDefined();
  });

  it('accepts an SG_UEN 10-char with trailing letter', async () => {
    await expect(
      partnerStep1Schema.validate(
        baseValues({ taxIdType: 'SG_UEN', taxId: '201712345A' }),
      ),
    ).resolves.toBeDefined();
  });

  it('rejects an SG_UEN ending in a digit', async () => {
    await expect(
      partnerStep1Schema.validate(
        baseValues({ taxIdType: 'SG_UEN', taxId: '201712345' }),
      ),
    ).rejects.toThrow(/format/i);
  });

  it('GENERIC accepts any non-blank string', async () => {
    await expect(
      partnerStep1Schema.validate(
        baseValues({ taxIdType: 'GENERIC', taxId: 'foo-bar-baz' }),
      ),
    ).resolves.toBeDefined();
  });
});

describe('TAX_ID_PATTERNS table', () => {
  it('contains an entry per server-side discriminator', () => {
    expect(Object.keys(TAX_ID_PATTERNS).sort()).toEqual(
      ['GENERIC', 'KH_VAT', 'KR_BRN', 'SG_UEN', 'VN_MST'],
    );
  });
});

describe('leiChecksumValid — ISO 17442 mod-97-10', () => {
  it('accepts a published valid LEI', () => {
    // 213800WSGIIZCXF1P572 — Goldman Sachs sample published by GLEIF.
    expect(leiChecksumValid('213800WSGIIZCXF1P572')).toBe(true);
  });

  it('accepts the GLEIF reference sample (Apple)', () => {
    // HWUPKR0MPOU8FGXBT394 — Apple Inc. sample published by GLEIF.
    expect(leiChecksumValid('HWUPKR0MPOU8FGXBT394')).toBe(true);
  });

  it('rejects a string with the wrong length', () => {
    expect(leiChecksumValid('213800WSGIIZCXF1P57')).toBe(false); // 19 chars
    expect(leiChecksumValid('213800WSGIIZCXF1P5722')).toBe(false); // 21 chars
  });

  it('rejects a 20-char string with a broken checksum', () => {
    // Last two digits flipped from a known-good value.
    expect(leiChecksumValid('213800WSGIIZCXF1P527')).toBe(false);
  });

  it('rejects shapes outside [A-Z0-9]', () => {
    expect(leiChecksumValid('213800wsgiizcxf1p572'.toUpperCase())).toBe(true);
    expect(leiChecksumValid('213800WSGIIZCXF1P57-')).toBe(false);
    expect(leiChecksumValid('213800 SGIIZCXF1P572')).toBe(false);
  });

  it('rejects non-string input', () => {
    expect(leiChecksumValid(null)).toBe(false);
    expect(leiChecksumValid(undefined)).toBe(false);
    expect(leiChecksumValid(12345)).toBe(false);
  });
});

describe('partnerStep1Schema — LEI integration', () => {
  it('rejects an LEI with a broken checksum at the schema level', async () => {
    await expect(
      partnerStep1Schema.validate(baseValues({ lei: '213800WSGIIZCXF1P527' })),
    ).rejects.toThrow(/checksum/i);
  });

  it('rejects an LEI of the wrong length', async () => {
    await expect(
      partnerStep1Schema.validate(baseValues({ lei: 'ABCDEFGH' })),
    ).rejects.toThrow(/20 alphanumeric/i);
  });

  it('treats empty-string LEI as absent', async () => {
    const out = await partnerStep1Schema.validate(baseValues({ lei: '' }));
    expect(out.lei == null || out.lei === '').toBe(true);
  });

  it('accepts a lowercase LEI by uppercasing for the checksum check', async () => {
    await expect(
      partnerStep1Schema.validate(baseValues({ lei: '213800wsgiizcxf1p572' })),
    ).resolves.toBeDefined();
  });
});

describe('partnerStep1Schema — country picker', () => {
  it('rejects a lowercase country code via the regex', async () => {
    await expect(
      partnerStep1Schema.validate(baseValues({ countryOfIncorporation: 'kr' })),
    ).rejects.toThrow(/ISO-3166/i);
  });

  it('rejects an unknown alpha-2 code (XX is unassigned)', async () => {
    await expect(
      partnerStep1Schema.validate(baseValues({ countryOfIncorporation: 'XX' })),
    ).rejects.toThrow(/Unknown ISO-3166/i);
  });
});
