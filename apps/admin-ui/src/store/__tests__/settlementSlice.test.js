/**
 * Contract lock for the BFF settlement endpoints.
 *
 * SettlementBatchSummary (SettlementClient.java):
 *   { batchId, partnerId, settlementDate (LocalDate string), currency, amount, status,
 *     transmissionState, transmissionReason, transmittedAt }
 *
 * SettlementBatchDetail (web/dto/SettlementBatchDetail.java):
 *   { batch: SettlementBatchSummary,
 *     lines: [{ txnRef, amount, currency, matched }],
 *     matchedCount, openCount }
 *
 * TransmissionChannel (SettlementClient.TransmissionChannel):
 *   { live, reachableState, reason }
 *
 * GAP T4-5: `status` and `transmissionState` are two independent axes, and the slice stores
 * both verbatim — normalisation belongs to `@/api/settlementStatus` alone. The fixture uses
 * the REAL lifecycle vocabulary (PENDING|GENERATED|TRANSMITTED|RECEIVED|RECONCILED|ERROR);
 * it previously said `CLOSED`, which no upstream produces.
 */
import { describe, expect, it } from 'vitest';
import reducer, {
  fetchTransmissionChannel,
  getSettlement,
  listSettlementBatches,
  listSettlements,
} from '@/store/settlementSlice';

const BATCH = {
  batchId: 'BATCH-2026-06-09-001',
  partnerId: 'GME_VN_002',
  settlementDate: '2026-06-09',
  currency: 'USD',
  amount: '50000.00',
  status: 'RECONCILED',
  transmissionState: 'NOT_TRANSMITTED_CHANNEL_UNAVAILABLE',
  transmissionReason: 'no transmission channel is configured',
  transmittedAt: null,
};

const DETAIL = {
  batch: BATCH,
  lines: [
    { txnRef: 'TXN-2001', amount: '25000.00', currency: 'USD', matched: true },
    { txnRef: 'TXN-2002', amount: '25000.00', currency: 'USD', matched: false },
  ],
  matchedCount: 1,
  openCount: 1,
};

describe('settlementSlice', () => {
  it('stores SettlementBatchSummary[] with BFF field names, both axes verbatim', () => {
    const next = reducer(undefined, {
      type: listSettlements.fulfilled.type,
      payload: [BATCH],
    });
    expect(next.items).toEqual([BATCH]);
    expect(next.items[0].batchId).toBe('BATCH-2026-06-09-001');
    expect(next.items[0].settlementDate).toBe('2026-06-09');
    expect(next.items[0].amount).toBe('50000.00');
    expect(next.items[0].status).toBe('RECONCILED');
    expect(next.items[0].transmissionState).toBe('NOT_TRANSMITTED_CHANNEL_UNAVAILABLE');
    expect(next.items[0].transmittedAt).toBeNull();
  });

  it('lands the date-ranged endpoint payload in the same place', () => {
    const next = reducer(undefined, {
      type: listSettlementBatches.fulfilled.type,
      payload: [BATCH],
    });
    expect(next.items).toEqual([BATCH]);
    expect(next.loading).toBe(false);
  });

  it('caches SettlementBatchDetail keyed by batch.batchId, with the line counts', () => {
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
    expect(cached.matchedCount).toBe(1);
    expect(cached.openCount).toBe(1);
  });

  it('stores the transmission-channel board verbatim', () => {
    const board = { live: false, reachableState: 'NOT_TRANSMITTED_CHANNEL_UNAVAILABLE', reason: 'x' };
    const next = reducer(undefined, {
      type: fetchTransmissionChannel.fulfilled.type,
      payload: board,
    });
    expect(next.channel).toEqual(board);
  });

  it('leaves the channel NULL (= unknown) on a failed or non-object board', () => {
    const rejected = reducer(undefined, { type: fetchTransmissionChannel.rejected.type });
    expect(rejected.channel).toBeNull();
    // A non-object payload is never coerced into something truthy that could read as
    // availability.
    for (const junk of ['live', 0, [], null]) {
      const next = reducer(undefined, {
        type: fetchTransmissionChannel.fulfilled.type,
        payload: junk,
      });
      expect(next.channel).toBeNull();
    }
  });

  it('does not set a page-level error when only the channel board fails', () => {
    const withList = reducer(undefined, {
      type: listSettlements.fulfilled.type,
      payload: [BATCH],
    });
    const next = reducer(withList, { type: fetchTransmissionChannel.rejected.type });
    expect(next.error).toBeNull();
    expect(next.items).toHaveLength(1);
  });
});
