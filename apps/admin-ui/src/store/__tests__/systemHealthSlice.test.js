/**
 * Contract lock for GET /v1/admin/system/health.
 *
 * Snapshot mirrors the BFF SystemHealth record:
 *   { checkedAt:ISO, services:[ServiceHealth] }
 * ServiceHealth { name, status:"UP"|"DOWN"|"DEGRADED", lastSeenAt, uptimeSec }
 */
import { describe, expect, it } from 'vitest';
import reducer, { fetchSystemHealth } from '@/store/systemHealthSlice';

const SNAPSHOT = {
  checkedAt: '2026-06-10T08:30:00Z',
  services: [
    { name: 'rate-fx',       status: 'UP',       lastSeenAt: '2026-06-10T08:29:55Z', uptimeSec: 86400 },
    { name: 'config-registry', status: 'DEGRADED', lastSeenAt: '2026-06-10T08:29:55Z', uptimeSec: 3600 },
    { name: 'prefunding',    status: 'DOWN',     lastSeenAt: '2026-06-10T08:00:00Z', uptimeSec: 0 },
  ],
};

describe('systemHealthSlice', () => {
  it('stores services + checkedAt on fulfilled', () => {
    const next = reducer(undefined, {
      type: fetchSystemHealth.fulfilled.type,
      payload: SNAPSHOT,
    });
    expect(next.loading).toBe(false);
    expect(next.error).toBeNull();
    expect(next.checkedAt).toBe(SNAPSHOT.checkedAt);
    expect(next.services).toEqual(SNAPSHOT.services);
    expect(next.services[0].status).toBe('UP');
    expect(next.services[1].status).toBe('DEGRADED');
    expect(next.services[2].status).toBe('DOWN');
  });

  it('defaults to empty list on a partial payload', () => {
    const next = reducer(undefined, {
      type: fetchSystemHealth.fulfilled.type,
      payload: {},
    });
    expect(next.services).toEqual([]);
    expect(next.checkedAt).toBeNull();
  });

  it('records the error on rejected', () => {
    const next = reducer(undefined, {
      type: fetchSystemHealth.rejected.type,
      payload: 'unreachable',
    });
    expect(next.loading).toBe(false);
    expect(next.error).toBe('unreachable');
  });
});
