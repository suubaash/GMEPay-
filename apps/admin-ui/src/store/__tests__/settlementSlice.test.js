/**
 * Contract lock for the BFF settlement endpoints.
 *
 * SettlementBatchSummary (SettlementClient.java):
 *   { batchId, partnerId, settlementDate (LocalDate string), currency, amount, status }
 *
 * SettlementBatchDetail (web/dto/SettlementBatchDetail.java):
 *   { batch: SettlementBatchSummary,
 *     lines: [{ txnRef, amount, currency, matched }] }
 */
import { describe, expect, it } from 'vitest';
import reducer, { listSettlements, getSettlement } from '@/store/settlementSlice';

const BATCH = {
  batchId: 'BATCH-2026-06-09-001',
  partnerId: 'GME_VN_002',
  settlementDate: '2026-06-09',
  currency: 'USD',
  amount: '50000.00',
  status: 'CLOSED',
};

const DETAIL = {
  batch: BATCH,
  lines: [
    { txnRef: 'TXN-2001', amount: '25000.00', currency: 'USD', matched: true },
    { txnRef: 'TXN-2002', amount: '25000.00', currency: 'USD', matched: false },
  ],
};

describe('settlementSlice', () => {
  it('stores SettlementBatchSummary[] with BFF field names', () => {
    const next = reducer(undefined, {
      type: listSettlements.fulfilled.type,
      payload: [BATCH],
    });
    expect(next.items).toEqual([BATCH]);
    expect(next.items[0].batchId).toBe('BATCH-2026-06-09-001');
    expect(next.items[0].settlementDate).toBe('2026-06-09');
    expect(next.items[0].amount).toBe('50000.00');
    expect(next.items[0].status).toBe('CLOSED');
  });

  it('caches SettlementBatchDetail keyed by batch.batchId', () => {
    const next = reducer(undefined, {
      type: getSettlement.fulfilled.type,
      payload: DETAIL,
    });
    const cached = next.details['BATCH-2026-06-09-001'];
    expect(cached).toEqual(DETAIL);
    expect(cached.batch.partnerId).toBe('GME_VN_002');
    expect(cached.lines).toHaveLength(2);
    expect(cached.lines[0].txnRef).toBe('TXN-2001');
    expect(cached.lines[0].matched).toBe(true);
    expect(cached.lines[1].matched).toBe(false);
  });
});
