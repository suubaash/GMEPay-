/**
 * Contract lock for GET /v1/admin/audit-trail?aggregateType=&aggregateId=&page=&size=
 *
 * BFF response shape:
 *   { entries:[{recordedAt,actorId,eventType,beforeJson,afterJson}],
 *     chainValid:boolean, page:number, size:number, total:number }
 */
import { describe, expect, it } from 'vitest';
import reducer, { fetchAuditTrail, trailKey } from '@/store/auditTrailSlice';

const AGG_TYPE = 'partner';
const AGG_ID = 'GME_KR_001';
const KEY = trailKey(AGG_TYPE, AGG_ID);

const MOCK_ENTRIES = [
  {
    recordedAt: '2026-06-10T08:00:00Z',
    actorId: 'admin@gme.com',
    eventType: 'PARTNER_CREATED',
    beforeJson: null,
    afterJson: '{"partnerCode":"GME_KR_001"}',
  },
  {
    recordedAt: '2026-06-10T09:00:00Z',
    actorId: 'admin@gme.com',
    eventType: 'PARTNER_UPDATED',
    beforeJson: '{"status":"ONBOARDING"}',
    afterJson: '{"status":"ACTIVE"}',
  },
];

const FULFILLED_PAYLOAD = {
  aggregateType: AGG_TYPE,
  aggregateId: AGG_ID,
  entries: MOCK_ENTRIES,
  chainValid: true,
  page: 0,
  size: 20,
  total: 2,
};

describe('auditTrailSlice', () => {
  it('starts empty', () => {
    const state = reducer(undefined, { type: '@@init' });
    expect(state.byKey).toEqual({});
  });

  it('sets loading on pending', () => {
    const state = reducer(undefined, {
      type: fetchAuditTrail.pending.type,
      meta: { arg: { aggregateType: AGG_TYPE, aggregateId: AGG_ID } },
    });
    expect(state.byKey[KEY].loading).toBe(true);
    expect(state.byKey[KEY].error).toBeNull();
  });

  it('stores entries + chainValid on fulfilled (chain valid)', () => {
    const state = reducer(undefined, {
      type: fetchAuditTrail.fulfilled.type,
      payload: FULFILLED_PAYLOAD,
    });
    const trail = state.byKey[KEY];
    expect(trail.loading).toBe(false);
    expect(trail.error).toBeNull();
    expect(trail.entries).toHaveLength(2);
    expect(trail.entries[0].eventType).toBe('PARTNER_CREATED');
    expect(trail.entries[1].actorId).toBe('admin@gme.com');
    expect(trail.chainValid).toBe(true);
    expect(trail.page).toBe(0);
    expect(trail.size).toBe(20);
    expect(trail.total).toBe(2);
  });

  it('stores chainValid=false when chain is broken', () => {
    const state = reducer(undefined, {
      type: fetchAuditTrail.fulfilled.type,
      payload: { ...FULFILLED_PAYLOAD, chainValid: false },
    });
    expect(state.byKey[KEY].chainValid).toBe(false);
  });

  it('stores error on rejected', () => {
    const state = reducer(undefined, {
      type: fetchAuditTrail.rejected.type,
      payload: { aggregateType: AGG_TYPE, aggregateId: AGG_ID, msg: 'network error' },
    });
    expect(state.byKey[KEY].loading).toBe(false);
    expect(state.byKey[KEY].error).toBe('network error');
  });

  it('defaults gracefully when payload entries is missing', () => {
    const state = reducer(undefined, {
      type: fetchAuditTrail.fulfilled.type,
      payload: { aggregateType: AGG_TYPE, aggregateId: AGG_ID },
    });
    const trail = state.byKey[KEY];
    expect(trail.entries).toEqual([]);
    expect(trail.chainValid).toBeNull();
    expect(trail.total).toBe(0);
  });

  it('trailKey produces a deterministic string', () => {
    expect(trailKey('partner', 'GME_KR_001')).toBe('partner:GME_KR_001');
    expect(trailKey('contact', 'c-1')).toBe('contact:c-1');
  });
});
