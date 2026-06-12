/**
 * Contract lock for commercialTermsSlice (Slice 6B.2).
 *
 * CommercialTermsView (GET /v1/admin/partners/draft/{code}/commercial):
 *   { feeSchedule, fxConfig, limits, contract }
 *
 * patchDraftStep6Commercial PATCH result mirrors PartnerView shape.
 */
import { describe, expect, it } from 'vitest';
import reducer, {
  fetchCommercial,
  patchDraftStep6Commercial,
  clearCommercialError,
} from '@/store/commercialTermsSlice';

const PARTNER_CODE = 'GME_KR_TEST';

const COMMERCIAL_CONFIG = {
  feeSchedule: {
    scheme: 'ZEROPAY',
    direction: 'OUTBOUND',
    fixedFeeUsd: '1.50',
    bpsFee: '100',
    tiers: [],
  },
  fxConfig: {
    marginBps: '150',
    referenceRateSource: 'SEOUL_FX_BROKER',
    quoteHoldSeconds: 300,
  },
  limits: {
    perTxnMinUsd: '1.00',
    perTxnMaxUsd: '4000.00',
    dailyCapUsd: '40000.00',
    monthlyCapUsd: '200000.00',
    annualCapUsd: '1000000.00',
    licenseType: 'MSB',
  },
  contract: {
    effectiveFrom: '2026-01-01',
    effectiveTo: null,
    autoRenewal: true,
    noticePeriodDays: 30,
    refundChargebackPolicy: 'PARTNER_BEARS',
    terminationReason: null,
  },
};

describe('commercialTermsSlice', () => {
  describe('initial state', () => {
    it('starts with empty configByCode, loadingByCode, false saving, null error', () => {
      const state = reducer(undefined, { type: '@@INIT' });
      expect(state.configByCode).toEqual({});
      expect(state.loadingByCode).toEqual({});
      expect(state.saving).toBe(false);
      expect(state.error).toBeNull();
    });
  });

  describe('fetchCommercial', () => {
    it('sets loadingByCode[code]=true on pending', () => {
      const next = reducer(undefined, {
        type: fetchCommercial.pending.type,
        meta: { arg: PARTNER_CODE },
      });
      expect(next.loadingByCode[PARTNER_CODE]).toBe(true);
      expect(next.error).toBeNull();
    });

    it('caches config keyed by partnerCode on fulfilled', () => {
      const next = reducer(undefined, {
        type: fetchCommercial.fulfilled.type,
        payload: { partnerCode: PARTNER_CODE, config: COMMERCIAL_CONFIG },
      });
      expect(next.configByCode[PARTNER_CODE]).toEqual(COMMERCIAL_CONFIG);
      expect(next.loadingByCode[PARTNER_CODE]).toBe(false);
    });

    it('stores feeSchedule.scheme correctly', () => {
      const next = reducer(undefined, {
        type: fetchCommercial.fulfilled.type,
        payload: { partnerCode: PARTNER_CODE, config: COMMERCIAL_CONFIG },
      });
      expect(next.configByCode[PARTNER_CODE].feeSchedule.scheme).toBe('ZEROPAY');
    });

    it('stores fxConfig.referenceRateSource correctly', () => {
      const next = reducer(undefined, {
        type: fetchCommercial.fulfilled.type,
        payload: { partnerCode: PARTNER_CODE, config: COMMERCIAL_CONFIG },
      });
      expect(next.configByCode[PARTNER_CODE].fxConfig.referenceRateSource).toBe('SEOUL_FX_BROKER');
    });

    it('stores limits.licenseType correctly', () => {
      const next = reducer(undefined, {
        type: fetchCommercial.fulfilled.type,
        payload: { partnerCode: PARTNER_CODE, config: COMMERCIAL_CONFIG },
      });
      expect(next.configByCode[PARTNER_CODE].limits.licenseType).toBe('MSB');
    });

    it('sets loadingByCode[code]=false on rejected', () => {
      const next = reducer(undefined, {
        type: fetchCommercial.rejected.type,
        meta: { arg: PARTNER_CODE },
        error: { message: 'Not found' },
      });
      expect(next.loadingByCode[PARTNER_CODE]).toBe(false);
      expect(next.error).toBe('Not found');
    });

    it('does not store config when payload.config is null on fulfilled', () => {
      const base = reducer(undefined, {
        type: fetchCommercial.fulfilled.type,
        payload: { partnerCode: PARTNER_CODE, config: COMMERCIAL_CONFIG },
      });
      const next = reducer(base, {
        type: fetchCommercial.fulfilled.type,
        payload: { partnerCode: PARTNER_CODE, config: null },
      });
      // Should retain the previous value since config is null
      expect(next.configByCode[PARTNER_CODE]).toEqual(COMMERCIAL_CONFIG);
    });
  });

  describe('patchDraftStep6Commercial', () => {
    it('sets saving=true on pending', () => {
      const next = reducer(undefined, {
        type: patchDraftStep6Commercial.pending.type,
      });
      expect(next.saving).toBe(true);
      expect(next.error).toBeNull();
    });

    it('sets saving=false on fulfilled', () => {
      const pending = reducer(undefined, {
        type: patchDraftStep6Commercial.pending.type,
      });
      const next = reducer(pending, {
        type: patchDraftStep6Commercial.fulfilled.type,
        payload: { partnerCode: PARTNER_CODE },
      });
      expect(next.saving).toBe(false);
    });

    it('sets saving=false and error on rejected', () => {
      const pending = reducer(undefined, {
        type: patchDraftStep6Commercial.pending.type,
      });
      const next = reducer(pending, {
        type: patchDraftStep6Commercial.rejected.type,
        error: { message: 'Server error' },
      });
      expect(next.saving).toBe(false);
      expect(next.error).toBe('Server error');
    });
  });

  describe('clearCommercialError', () => {
    it('clears error field', () => {
      const withError = reducer(undefined, {
        type: fetchCommercial.rejected.type,
        meta: { arg: PARTNER_CODE },
        error: { message: 'Some error' },
      });
      expect(withError.error).toBe('Some error');
      const cleared = reducer(withError, clearCommercialError());
      expect(cleared.error).toBeNull();
    });
  });
});
