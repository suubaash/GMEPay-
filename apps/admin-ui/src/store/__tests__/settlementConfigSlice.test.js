/**
 * Contract lock for settlementConfigSlice.
 *
 * SettlementConfigView (GET /v1/admin/partners/draft/{code}/settlement-config):
 *   { cycleTPlusN: 0..5, cutoffTime: "HH:mm", cutoffTimezone: string,
 *     settlementMethod: 'SWIFT'|'ACH'|'FPS'|'RTGS'|'SEPA'|'CHAPS'|'OTHER' }
 *
 * SettlementPreviewView (GET .../settlement-preview?txnInstant=ISO):
 *   { payoutDate: "YYYY-MM-DD", explanation: string[] }
 *
 * patchDraftStep4Settlement PATCH result mirrors PartnerView shape.
 */
import { describe, expect, it } from 'vitest';
import reducer, {
  fetchSettlementConfig,
  fetchSettlementPreview,
  patchDraftStep4Settlement,
} from '@/store/settlementConfigSlice';

const CONFIG = {
  cycleTPlusN: 2,
  cutoffTime: '17:00',
  cutoffTimezone: 'Asia/Seoul',
  settlementMethod: 'SWIFT',
};

const PREVIEW = {
  payoutDate: '2026-06-15',
  explanation: [
    'Fri 2026-06-12: cutoff passed — rolls to next business day',
    'Sat 2026-06-13: skip — weekend',
    'Sun 2026-06-14: skip — weekend',
    'Mon 2026-06-15: payout date (T+2)',
  ],
};

const PARTNER_CODE = 'GME_KR_001';

describe('settlementConfigSlice', () => {
  describe('fetchSettlementConfig', () => {
    it('caches config keyed by partnerCode on fulfilled', () => {
      const next = reducer(undefined, {
        type: fetchSettlementConfig.fulfilled.type,
        payload: { partnerCode: PARTNER_CODE, config: CONFIG },
      });
      expect(next.configByCode[PARTNER_CODE]).toEqual(CONFIG);
      expect(next.configByCode[PARTNER_CODE].cycleTPlusN).toBe(2);
      expect(next.configByCode[PARTNER_CODE].cutoffTimezone).toBe('Asia/Seoul');
      expect(next.configByCode[PARTNER_CODE].settlementMethod).toBe('SWIFT');
    });

    it('sets configLoading[code]=true on pending', () => {
      const next = reducer(undefined, {
        type: fetchSettlementConfig.pending.type,
        meta: { arg: PARTNER_CODE },
      });
      expect(next.configLoading[PARTNER_CODE]).toBe(true);
    });

    it('clears configLoading on fulfilled', () => {
      const state = {
        configByCode: {},
        previewByCode: {},
        configLoading: { [PARTNER_CODE]: true },
        previewLoading: {},
        patchSaving: false,
        error: null,
      };
      const next = reducer(state, {
        type: fetchSettlementConfig.fulfilled.type,
        payload: { partnerCode: PARTNER_CODE, config: CONFIG },
      });
      expect(next.configLoading[PARTNER_CODE]).toBe(false);
    });

    it('records error on rejected', () => {
      const next = reducer(undefined, {
        type: fetchSettlementConfig.rejected.type,
        meta: { arg: PARTNER_CODE },
        error: { message: 'Not found' },
      });
      expect(next.error).toBe('Not found');
      expect(next.configLoading[PARTNER_CODE]).toBe(false);
    });
  });

  describe('fetchSettlementPreview', () => {
    it('caches preview keyed by partnerCode on fulfilled', () => {
      const next = reducer(undefined, {
        type: fetchSettlementPreview.fulfilled.type,
        payload: { partnerCode: PARTNER_CODE, preview: PREVIEW },
      });
      expect(next.previewByCode[PARTNER_CODE]).toEqual(PREVIEW);
      expect(next.previewByCode[PARTNER_CODE].payoutDate).toBe('2026-06-15');
      expect(next.previewByCode[PARTNER_CODE].explanation).toHaveLength(4);
    });

    it('sets previewLoading[code]=true on pending', () => {
      const next = reducer(undefined, {
        type: fetchSettlementPreview.pending.type,
        meta: { arg: { partnerCode: PARTNER_CODE, txnInstant: '2026-06-12T10:00:00Z' } },
      });
      expect(next.previewLoading[PARTNER_CODE]).toBe(true);
    });

    it('clears previewLoading on fulfilled', () => {
      const state = {
        configByCode: {},
        previewByCode: {},
        configLoading: {},
        previewLoading: { [PARTNER_CODE]: true },
        patchSaving: false,
        error: null,
      };
      const next = reducer(state, {
        type: fetchSettlementPreview.fulfilled.type,
        payload: { partnerCode: PARTNER_CODE, preview: PREVIEW },
        meta: { arg: { partnerCode: PARTNER_CODE } },
      });
      expect(next.previewLoading[PARTNER_CODE]).toBe(false);
    });

    it('clears previewLoading on rejected without setting error', () => {
      const state = {
        configByCode: {},
        previewByCode: {},
        configLoading: {},
        previewLoading: { [PARTNER_CODE]: true },
        patchSaving: false,
        error: null,
      };
      const next = reducer(state, {
        type: fetchSettlementPreview.rejected.type,
        meta: { arg: { partnerCode: PARTNER_CODE } },
        error: { message: 'preview error' },
      });
      // Preview failure is non-fatal — error field should NOT be set.
      expect(next.previewLoading[PARTNER_CODE]).toBe(false);
      expect(next.error).toBeNull();
    });
  });

  describe('patchDraftStep4Settlement', () => {
    it('sets patchSaving=true on pending', () => {
      const next = reducer(undefined, {
        type: patchDraftStep4Settlement.pending.type,
      });
      expect(next.patchSaving).toBe(true);
      expect(next.error).toBeNull();
    });

    it('clears patchSaving on fulfilled', () => {
      const state = {
        configByCode: {},
        previewByCode: {},
        configLoading: {},
        previewLoading: {},
        patchSaving: true,
        error: null,
      };
      const next = reducer(state, {
        type: patchDraftStep4Settlement.fulfilled.type,
        payload: { partnerCode: PARTNER_CODE },
      });
      expect(next.patchSaving).toBe(false);
    });

    it('records error and clears patchSaving on rejected', () => {
      const state = {
        configByCode: {},
        previewByCode: {},
        configLoading: {},
        previewLoading: {},
        patchSaving: true,
        error: null,
      };
      const next = reducer(state, {
        type: patchDraftStep4Settlement.rejected.type,
        error: { message: 'Patch failed' },
      });
      expect(next.patchSaving).toBe(false);
      expect(next.error).toBe('Patch failed');
    });
  });
});
