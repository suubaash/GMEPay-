/**
 * Contract lock for GET /v1/admin/audit?page=&size= -> Page<AuditEntry>.
 *
 * BFF Page<T> uses `total` (NOT totalElements). AuditEntry mirrors:
 *   { id, actor, action, target, at:ISO, detail }
 */
import { describe, expect, it } from 'vitest';
import reducer, { fetchAuditPage } from '@/store/auditSlice';

const SNAPSHOT = {
  content: [
    {
      id: 'a1',
      actor: 'admin',
      action: 'CREATE',
      target: 'partner:GME_KR_001',
      at: '2026-06-10T08:00:00Z',
      detail: 'Created LOCAL partner',
    },
    {
      id: 'a2',
      actor: 'admin',
      action: 'UPDATE',
      target: 'partner:GME_VN_002',
      at: '2026-06-10T08:05:00Z',
      detail: 'Changed rounding mode to DOWN',
    },
  ],
  page: 0,
  size: 20,
  total: 47,
};

describe('auditSlice', () => {
  it('stores the page contents and pagination fields', () => {
    const next = reducer(undefined, {
      type: fetchAuditPage.fulfilled.type,
      payload: SNAPSHOT,
    });
    expect(next.loading).toBe(false);
    expect(next.error).toBeNull();
    expect(next.items).toEqual(SNAPSHOT.content);
    expect(next.items).toHaveLength(2);
    expect(next.items[0].action).toBe('CREATE');
    expect(next.page).toBe(0);
    expect(next.size).toBe(20);
    expect(next.total).toBe(47);
  });

  it('defaults gracefully on a partial payload', () => {
    const next = reducer(undefined, {
      type: fetchAuditPage.fulfilled.type,
      payload: {},
    });
    expect(next.items).toEqual([]);
    expect(next.page).toBe(0);
    expect(next.size).toBe(20);
    expect(next.total).toBe(0);
  });

  it('records the error on rejected', () => {
    const next = reducer(undefined, {
      type: fetchAuditPage.rejected.type,
      payload: 'boom',
    });
    expect(next.loading).toBe(false);
    expect(next.error).toBe('boom');
  });
});
