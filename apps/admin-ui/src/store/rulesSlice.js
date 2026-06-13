'use client';

import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { adminApi } from '@/api/client';

/**
 * Rules slice — backs the Step 6 Rules editor section in the Partner Setup
 * wizard (Slice 6A, ADR-010 bitemporal SCD-6, ADR-013 Expand/Backfill/Contract).
 *
 * Manages:
 *   - `rulesByCode`    : map partnerCode → RuleView[]  (current rule set)
 *   - `loadingByCode`  : map partnerCode → boolean
 *   - `saving`         : boolean — PATCH in-flight
 *   - `error`          : last user-visible failure
 *
 * RuleView (GET /v1/admin/partners/{code}/rules):
 *   {
 *     id:               number,
 *     schemeId:         string,
 *     direction:        'INBOUND' | 'OUTBOUND' | 'BOTH',
 *     mA:               string (decimal fraction, e.g. "0.0150" = 1.50%),
 *     mB:               string (decimal fraction),
 *     serviceChargeUsd: string (decimal),
 *     validFrom:        ISO instant,
 *     validTo:          ISO instant | null,
 *     recordedAt:       ISO instant
 *   }
 *
 * The PATCH body sent by patchRules carries the FULL desired rule set
 * (bulk-replace semantics). An empty array clears all rules.
 * Margins and money are always decimal STRINGS on the wire per
 * docs/MONEY_CONVENTION.md.
 */
const initialState = {
  /** partnerCode → RuleView[] */
  rulesByCode: {},
  /** partnerCode → boolean */
  loadingByCode: {},
  saving: false,
  error: null,
};

/**
 * GET /v1/admin/partners/{partnerCode}/rules
 * -> RuleView[]
 */
export const fetchRules = createAsyncThunk(
  'rules/fetch',
  async (partnerCode) => {
    const rules = await adminApi.getRules(partnerCode);
    return { partnerCode, rules };
  },
);

/**
 * PATCH /v1/admin/partners/draft/{partnerCode}/step-6-rules
 * body: { rules: RuleCommand[] }
 * -> RuleView[] (fresh current set after bulk replace)
 *
 * @param {object} params
 * @param {string} params.partnerCode
 * @param {Array}  params.rules  Full desired rule set (bulk replace).
 */
export const patchRules = createAsyncThunk(
  'rules/patch',
  async ({ partnerCode, rules }) => {
    const updated = await adminApi.patchDraftStep6Rules(partnerCode, rules);
    return { partnerCode, rules: updated };
  },
);

const rulesSlice = createSlice({
  name: 'rules',
  initialState,
  reducers: {
    clearRulesError(state) {
      state.error = null;
    },
    /** Optimistic local set — used for wizard rehydrate when the BFF is slow. */
    setLocalRules(state, action) {
      const { partnerCode, rules } = action.payload;
      state.rulesByCode[partnerCode] = rules;
    },
  },
  extraReducers: (builder) => {
    builder
      // ---- fetchRules ----
      .addCase(fetchRules.pending, (state, action) => {
        const code = action.meta.arg;
        state.loadingByCode[code] = true;
        state.error = null;
      })
      .addCase(fetchRules.fulfilled, (state, action) => {
        const { partnerCode, rules } = action.payload;
        state.loadingByCode[partnerCode] = false;
        state.rulesByCode[partnerCode] = Array.isArray(rules) ? rules : [];
      })
      .addCase(fetchRules.rejected, (state, action) => {
        const code = action.meta.arg;
        state.loadingByCode[code] = false;
        // Non-fatal: a new draft may have no rules yet.
        state.error = action.error?.message ?? 'Failed to load pricing rules';
      })

      // ---- patchRules ----
      .addCase(patchRules.pending, (state) => {
        state.saving = true;
        state.error = null;
      })
      .addCase(patchRules.fulfilled, (state, action) => {
        state.saving = false;
        const { partnerCode, rules } = action.payload;
        state.rulesByCode[partnerCode] = Array.isArray(rules) ? rules : [];
      })
      .addCase(patchRules.rejected, (state, action) => {
        state.saving = false;
        state.error = action.error?.message ?? 'Failed to save pricing rules';
      });
  },
});

export const { clearRulesError, setLocalRules } = rulesSlice.actions;
export default rulesSlice.reducer;
