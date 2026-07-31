import { describe, expect, it } from 'vitest';
import {
  SCREENING_STATUS,
  isManuallyAttested,
  screeningCaveatSummary,
  screeningStatusMeta,
  wasScreened,
} from '../screeningStatus';

/**
 * The screening-status vocabulary (GAP T1-4). These tests exist to make three presentations
 * unrepresentable rather than merely discouraged:
 *
 *  1. a partner cleared by a MANUAL SOP attestation must not be drawn the same way as one cleared
 *     by a vendor;
 *  2. "nothing was screened" must not be drawn as a neutral / pending unknown;
 *  3. a status this app does not define must never resolve to success.
 */
describe('screeningStatusMeta', () => {
  it('gives a vendor CLEAR the only success colour in the vocabulary', () => {
    const meta = screeningStatusMeta('CLEAR');
    expect(meta.color).toBe('success');
    expect(meta.screened).toBe(true);
    expect(meta.manual).toBe(false);
    expect(meta.activates).toBe(true);
    expect(meta.label).toMatch(/vendor/i);
  });

  it('a manual attestation is a screening, but is never labelled or coloured as a vendor clear', () => {
    const manual = screeningStatusMeta('CLEAR_MANUAL_ATTESTATION');
    const vendor = screeningStatusMeta('CLEAR');

    expect(manual.screened).toBe(true);
    expect(manual.activates).toBe(true);
    expect(manual.manual).toBe(true);
    // The two must be distinguishable on all three visual axes a chip has.
    expect(manual.label).not.toBe(vendor.label);
    expect(manual.color).not.toBe(vendor.color);
    expect(manual.variant).not.toBe(vendor.variant);
    expect(manual.label).toMatch(/manual/i);
    expect(manual.description).toMatch(/NOT a vendor screening/i);
    expect(manual.description).toMatch(/no ongoing rescreening|ongoing rescreening/i);
  });

  it('NOT_SCREENED_NO_PROVIDER warns and says nothing was checked — it is not "pending"', () => {
    const meta = screeningStatusMeta('NOT_SCREENED_NO_PROVIDER');
    expect(meta.color).toBe('warning');
    expect(meta.screened).toBe(false);
    expect(meta.activates).toBe(false);
    expect(meta.label).toMatch(/NOT SCREENED/);
    expect(meta.label).not.toMatch(/pending/i);
    expect(meta.description).toMatch(/not a clean result/i);
  });

  it('an absent status reads as "not screened yet" and never activates', () => {
    for (const absent of [null, undefined, '', '   ']) {
      const meta = screeningStatusMeta(absent);
      expect(meta.status).toBeNull();
      expect(meta.screened).toBe(false);
      expect(meta.activates).toBe(false);
    }
  });

  it('an unrecognised status is never upgraded to success', () => {
    const meta = screeningStatusMeta('APPROVED_BY_SOMEONE');
    expect(meta.color).toBe('warning');
    expect(meta.screened).toBe(false);
    expect(meta.clear).toBe(false);
    expect(meta.activates).toBe(false);
    expect(meta.label).toMatch(/unrecognised/);
  });

  it('normalises case and whitespace', () => {
    expect(screeningStatusMeta('  clear_manual_attestation ').status)
      .toBe(SCREENING_STATUS.CLEAR_MANUAL_ATTESTATION);
  });

  it('HIT and NEEDS_REVIEW are screenings that do not activate on their own', () => {
    for (const status of ['HIT', 'NEEDS_REVIEW']) {
      const meta = screeningStatusMeta(status);
      expect(meta.screened).toBe(true);
      expect(meta.clear).toBe(false);
      expect(meta.activates).toBe(false);
    }
  });
});

describe('helpers', () => {
  it('wasScreened is true for both authorities and false for everything else', () => {
    expect(wasScreened('CLEAR')).toBe(true);
    expect(wasScreened('CLEAR_MANUAL_ATTESTATION')).toBe(true);
    expect(wasScreened('HIT')).toBe(true);
    expect(wasScreened('NOT_SCREENED_NO_PROVIDER')).toBe(false);
    expect(wasScreened(null)).toBe(false);
  });

  it('isManuallyAttested singles out the human authority', () => {
    expect(isManuallyAttested('CLEAR_MANUAL_ATTESTATION')).toBe(true);
    expect(isManuallyAttested('CLEAR')).toBe(false);
    expect(isManuallyAttested('NOT_SCREENED_NO_PROVIDER')).toBe(false);
  });

  it('screeningCaveatSummary is silent only for a real vendor screening', () => {
    expect(screeningCaveatSummary('CLEAR')).toBeNull();
    expect(screeningCaveatSummary('CLEAR_MANUAL_ATTESTATION'))
      .toMatch(/MANUAL screening attestation, not by a screening vendor/);
    expect(screeningCaveatSummary('NOT_SCREENED_NO_PROVIDER'))
      .toMatch(/activation will be refused/);
    expect(screeningCaveatSummary(null)).toMatch(/No screening has been performed/);
  });
});
