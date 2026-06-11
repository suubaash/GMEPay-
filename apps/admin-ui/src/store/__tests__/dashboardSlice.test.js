/**
 * Contract lock for the BFF GET /v1/admin/dashboard response shape.
 *
 * If the BFF AdminDashboard record changes, this test breaks first.
 * Snapshot fields below mirror
 *   services/ops-partner-bff/src/main/java/com/gme/pay/bff/web/dto/AdminDashboard.java
 *   record AdminDashboard(int recentTxnCount, int partnerCount,
 *                         int lowBalanceCount, BigDecimal todayRevenueUsd) {}
 */
import { describe, expect, it } from 'vitest';
import reducer, { fetchDashboard } from '@/store/dashboardSlice';

describe('dashboardSlice', () => {
  it('stores the BFF AdminDashboard payload verbatim on fulfilled', () => {
    const snapshot = {
      recentTxnCount: 42,
      partnerCount: 7,
      lowBalanceCount: 2,
      todayRevenueUsd: 1234.5,
    };
    const next = reducer(undefined, {
      type: fetchDashboard.fulfilled.type,
      payload: snapshot,
    });
    expect(next.loading).toBe(false);
    expect(next.error).toBeNull();
    expect(next.data).toEqual(snapshot);
    expect(next.data.recentTxnCount).toBe(42);
    expect(next.data.partnerCount).toBe(7);
    expect(next.data.lowBalanceCount).toBe(2);
    expect(next.data.todayRevenueUsd).toBe(1234.5);
  });

  it('flags loading on pending and clears error', () => {
    const next = reducer(
      { data: null, loading: false, error: 'old' },
      { type: fetchDashboard.pending.type },
    );
    expect(next.loading).toBe(true);
    expect(next.error).toBeNull();
  });

  it('records the error on rejected', () => {
    const next = reducer(undefined, {
      type: fetchDashboard.rejected.type,
      error: { message: 'boom' },
    });
    expect(next.loading).toBe(false);
    expect(next.error).toBe('boom');
  });
});
