/**
 * Contract lock for POST /v1/admin/rates/preview.
 *
 * Snapshot mirrors the BFF RateQuotePreview record:
 *   { collectionAmount, collectionCurrency,
 *     payoutAmount, payoutCurrency,
 *     collectionUsd, payoutUsdCost,
 *     collectionMarginUsd, payoutMarginUsd,
 *     offerRateColl, crossRate,
 *     shortCircuit:boolean, quotedAt:ISO }
 */
import { describe, expect, it } from 'vitest';
import reducer, { previewRate, clearPreview } from '@/store/ratesSlice';

const SAMPLE_REQ = {
  fromCcy: 'KRW',
  toCcy: 'USD',
  amount: '100000',
  direction: 'INBOUND',
  partnerId: 'GME_KR_001',
};

const SAMPLE_RESP = {
  collectionAmount: '100000',
  collectionCurrency: 'KRW',
  payoutAmount: '72.50',
  payoutCurrency: 'USD',
  collectionUsd: '73.00',
  payoutUsdCost: '72.50',
  collectionMarginUsd: '0.50',
  payoutMarginUsd: '0.00',
  offerRateColl: '0.000730',
  crossRate: '1369.86',
  shortCircuit: false,
  quotedAt: '2026-06-10T08:00:00Z',
};

describe('ratesSlice', () => {
  it('stores the preview response verbatim on fulfilled', () => {
    const next = reducer(undefined, {
      type: previewRate.fulfilled.type,
      payload: SAMPLE_RESP,
    });
    expect(next.loading).toBe(false);
    expect(next.error).toBeNull();
    expect(next.preview).toEqual(SAMPLE_RESP);
    expect(next.preview.shortCircuit).toBe(false);
    expect(next.preview.collectionMarginUsd).toBe('0.50');
  });

  it('mirrors the submitted request on pending and clears error', () => {
    const next = reducer(
      { preview: null, request: null, loading: false, error: 'old' },
      { type: previewRate.pending.type, meta: { arg: SAMPLE_REQ } },
    );
    expect(next.loading).toBe(true);
    expect(next.error).toBeNull();
    expect(next.request).toEqual(SAMPLE_REQ);
  });

  it('records the error on rejected', () => {
    const next = reducer(undefined, {
      type: previewRate.rejected.type,
      payload: 'bad request',
      error: { message: 'fallback' },
    });
    expect(next.loading).toBe(false);
    expect(next.error).toBe('bad request');
  });

  it('clearPreview resets state', () => {
    const start = reducer(undefined, {
      type: previewRate.fulfilled.type,
      payload: SAMPLE_RESP,
    });
    const next = reducer(start, clearPreview());
    expect(next.preview).toBeNull();
    expect(next.request).toBeNull();
    expect(next.error).toBeNull();
  });
});
