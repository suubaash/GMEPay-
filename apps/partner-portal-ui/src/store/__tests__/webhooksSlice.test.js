import { describe, it, expect } from 'vitest';
import reducer, { fetchWebhooks } from '../webhooksSlice';

/**
 * Contract lock: webhooks reducer stores the BFF list verbatim.
 *
 * Wire shape: Array<WebhookConfigView>
 *   { url, eventTypes, status, lastDeliveredAt }
 */
describe('webhooksSlice', () => {
  it('starts idle with no data', () => {
    expect(reducer(undefined, { type: '@@INIT' })).toEqual({
      data: null,
      status: 'idle',
      error: null
    });
  });

  it('stores the BFF webhook list verbatim', () => {
    // The REAL wire shape from notification-webhook's endpoint registry (gap T1-3):
    // a partner-owned URL, and lastDeliveredAt always null because there is no
    // per-endpoint last-delivery read. The old fixture used partner.example.com rows
    // with literal delivery timestamps, hardcoded in the BFF controller.
    const wire = [
      {
        url: 'https://ops.gmeremit.com/gmepay/payments',
        eventTypes: ['payment.approved', 'payment.failed'],
        status: 'ACTIVE',
        lastDeliveredAt: null
      },
      {
        url: 'https://ops.gmeremit.com/gmepay/settlements',
        eventTypes: [],
        status: 'INACTIVE',
        lastDeliveredAt: null
      }
    ];
    const state = reducer(undefined, {
      type: fetchWebhooks.fulfilled.type,
      payload: wire
    });
    expect(state.status).toBe('succeeded');
    expect(state.data).toEqual(wire);
    expect(state.data[0].eventTypes).toEqual(['payment.approved', 'payment.failed']);
    expect(state.data[0].status).toBe('ACTIVE');
    // An empty eventTypes array means "all events" — it must not be defaulted away.
    expect(state.data[1].eventTypes).toEqual([]);
    expect(state.data[1].status).toBe('INACTIVE');
    expect(state.data.every((w) => w.lastDeliveredAt === null)).toBe(true);
  });

  it('coerces non-array payloads to []', () => {
    const state = reducer(undefined, {
      type: fetchWebhooks.fulfilled.type,
      payload: null
    });
    expect(state.data).toEqual([]);
  });

  it('captures errors', () => {
    const state = reducer(undefined, {
      type: fetchWebhooks.rejected.type,
      error: { message: 'oops' }
    });
    expect(state.status).toBe('failed');
    expect(state.error).toBe('oops');
  });
});
