/**
 * Contract lock for the BFF partners endpoints. Snapshot mirrors
 *   ConfigRegistryClient.PartnerSummary
 *   { partnerId, type, settlementCurrency, settlementRoundingMode }
 */
import { describe, expect, it } from 'vitest';
import reducer, {
  fetchPartners,
  getPartner,
  createPartner,
  updatePartnerRoundingMode,
} from '@/store/partnersSlice';

const SNAPSHOT = [
  {
    partnerId: 'GME_KR_001',
    type: 'LOCAL',
    settlementCurrency: 'KRW',
    settlementRoundingMode: 'HALF_UP',
  },
  {
    partnerId: 'GME_VN_002',
    type: 'OVERSEAS',
    settlementCurrency: 'USD',
    settlementRoundingMode: 'DOWN',
  },
];

describe('partnersSlice', () => {
  it('stores fetchPartners payload as `items`', () => {
    const next = reducer(undefined, {
      type: fetchPartners.fulfilled.type,
      payload: SNAPSHOT,
    });
    expect(next.items).toEqual(SNAPSHOT);
    expect(next.items[0].partnerId).toBe('GME_KR_001');
    expect(next.items[0].settlementRoundingMode).toBe('HALF_UP');
  });

  it('caches getPartner detail keyed by partnerId', () => {
    const detail = SNAPSHOT[0];
    const next = reducer(undefined, {
      type: getPartner.fulfilled.type,
      payload: detail,
    });
    expect(next.details['GME_KR_001']).toEqual(detail);
    expect(next.detailLoading).toBe(false);
  });

  it('appends a newly created partner to items + caches detail', () => {
    const created = SNAPSHOT[1];
    const start = reducer(undefined, {
      type: fetchPartners.fulfilled.type,
      payload: [SNAPSHOT[0]],
    });
    const next = reducer(start, {
      type: createPartner.fulfilled.type,
      payload: created,
    });
    expect(next.items).toHaveLength(2);
    expect(next.items[1]).toEqual(created);
    expect(next.details['GME_VN_002']).toEqual(created);
    expect(next.saving).toBe(false);
  });

  it('updates only the rounding mode in items + cache on updatePartnerRoundingMode', () => {
    const start = reducer(undefined, {
      type: fetchPartners.fulfilled.type,
      payload: SNAPSHOT,
    });
    const updated = { ...SNAPSHOT[0], settlementRoundingMode: 'DOWN' };
    const next = reducer(start, {
      type: updatePartnerRoundingMode.fulfilled.type,
      payload: updated,
    });
    expect(next.items[0].settlementRoundingMode).toBe('DOWN');
    expect(next.details['GME_KR_001'].settlementRoundingMode).toBe('DOWN');
    // Other partner untouched.
    expect(next.items[1].settlementRoundingMode).toBe('DOWN');
    expect(next.saving).toBe(false);
  });
});
