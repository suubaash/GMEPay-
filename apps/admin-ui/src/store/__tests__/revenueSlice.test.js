/**
 * Contract lock for the BFF revenue endpoints.
 *
 * RevenueSummary (RevenueLedgerClient.java):
 *   { date, totalRevenueUsd, feeRevenueUsd, marginRevenueUsd }
 *
 * RevenueBreakdown (web/dto/RevenueBreakdown.java):
 *   { byPartner: {string -> string},
 *     byScheme:  {string -> string},
 *     byCurrency:{string -> string} }
 *
 * Map values are BigDecimal serialised as JSON strings.
 */
import { describe, expect, it } from 'vitest';
import reducer, { getSummary, getBreakdown } from '@/store/revenueSlice';

const SUMMARY = {
  date: '2026-06-09',
  totalRevenueUsd: '12345.67',
  feeRevenueUsd: '8000.00',
  marginRevenueUsd: '4345.67',
};

const BREAKDOWN = {
  byPartner: { GME_KR_001: '7000.00', GME_VN_002: '5345.67' },
  byScheme: { ZEROPAY_KR: '7000.00', VIETQR_VN: '5345.67' },
  byCurrency: { KRW: '7000.00', VND: '5345.67' },
};

describe('revenueSlice', () => {
  it('stores RevenueSummary as `summary`', () => {
    const next = reducer(undefined, {
      type: getSummary.fulfilled.type,
      payload: SUMMARY,
    });
    expect(next.summary).toEqual(SUMMARY);
    expect(next.summary.totalRevenueUsd).toBe('12345.67');
    expect(next.summary.feeRevenueUsd).toBe('8000.00');
    expect(next.summary.marginRevenueUsd).toBe('4345.67');
  });

  it('records the requested range on pending', () => {
    const next = reducer(undefined, {
      type: getSummary.pending.type,
      meta: { arg: { from: '2026-05-10', to: '2026-06-09' } },
    });
    expect(next.range).toEqual({ from: '2026-05-10', to: '2026-06-09' });
    expect(next.loading).toBe(true);
  });

  it('stores RevenueBreakdown with byPartner/byScheme/byCurrency keys', () => {
    const next = reducer(undefined, {
      type: getBreakdown.fulfilled.type,
      payload: BREAKDOWN,
    });
    expect(next.breakdown).toEqual(BREAKDOWN);
    expect(next.breakdown.byPartner.GME_KR_001).toBe('7000.00');
    expect(next.breakdown.byScheme.VIETQR_VN).toBe('5345.67');
    expect(next.breakdown.byCurrency.KRW).toBe('7000.00');
    expect(next.breakdownLoading).toBe(false);
  });
});
