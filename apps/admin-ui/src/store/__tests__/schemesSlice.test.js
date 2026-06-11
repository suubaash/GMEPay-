/**
 * Contract lock for GET /v1/admin/schemes -> SchemeSummary[].
 * Snapshot mirrors ConfigRegistryClient.SchemeSummary
 *   { schemeId, name, country, currency, mode, status }
 */
import { describe, expect, it } from 'vitest';
import reducer, { listSchemes } from '@/store/schemesSlice';

const SNAPSHOT = [
  {
    schemeId: 'ZEROPAY_KR',
    name: 'ZeroPay (Korea)',
    country: 'KR',
    currency: 'KRW',
    mode: 'CPM',
    status: 'ACTIVE',
  },
  {
    schemeId: 'VIETQR_VN',
    name: 'VietQR',
    country: 'VN',
    currency: 'VND',
    mode: 'MPM',
    status: 'INACTIVE',
  },
];

describe('schemesSlice', () => {
  it('stores the list verbatim with BFF field names', () => {
    const next = reducer(undefined, {
      type: listSchemes.fulfilled.type,
      payload: SNAPSHOT,
    });
    expect(next.items).toEqual(SNAPSHOT);
    expect(next.items[0].schemeId).toBe('ZEROPAY_KR');
    expect(next.items[0].name).toBe('ZeroPay (Korea)');
    expect(next.items[0].status).toBe('ACTIVE');
  });

  it('falls back to an empty array when the BFF returns null', () => {
    const next = reducer(undefined, {
      type: listSchemes.fulfilled.type,
      payload: null,
    });
    expect(next.items).toEqual([]);
  });
});
