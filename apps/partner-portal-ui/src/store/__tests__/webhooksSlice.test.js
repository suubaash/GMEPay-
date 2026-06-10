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
    const wire = [
      {
        url: 'https://partner.example.com/GMEREMIT/webhook/payments',
        eventTypes: ['payment.approved', 'payment.failed'],
        status: 'ACTIVE',
        lastDeliveredAt: '2026-06-09T11:00:00Z'
      },
      {
        url: 'https://partner.example.com/GMEREMIT/webhook/settlements',
        eventTypes: ['settlement.completed'],
        status: 'ACTIVE',
        lastDeliveredAt: '2026-06-08T22:30:00Z'
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
