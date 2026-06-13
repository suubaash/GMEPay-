'use client';

import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { adminApi } from '@/api/client';

/**
 * Balance slice — prefunding balance + alerts for a single partner.
 *
 * Manages:
 *   - `byCode`       : map of partnerCode → BalanceView
 *   - `alertsByCode` : map of partnerCode → BalanceAlertView[]
 *   - `loading`      : map of partnerCode → boolean — balance fetch in-flight.
 *   - `alertsLoading`: map of partnerCode → boolean — alerts fetch in-flight.
 *   - `error`        : last user-visible failure.
 *
 * BalanceView shape (from GET /api/v1/admin/partners/{code}/balance):
 *   { currency: string, balance: string (BigDecimal), threshold: string (BigDecimal), pctOfThreshold: number }
 *
 * BalanceAlertView shape (from GET /api/v1/admin/partners/{code}/balance-alerts):
 *   { tier: 'WARNING'|'CRITICAL', balanceUsd: string, thresholdUsd: string, raisedAt: ISO, acknowledged: boolean }
 */
const initialState = {
  /** partnerCode → BalanceView */
  byCode: {},
  /** partnerCode → BalanceAlertView[] */
  alertsByCode: {},
  /** partnerCode → boolean */
  loading: {},
  /** partnerCode → boolean */
  alertsLoading: {},
  error: null,
};

/**
 * GET /v1/admin/partners/{partnerCode}/balance -> BalanceView
 */
export const fetchBalance = createAsyncThunk(
  'balance/fetchBalance',
  async (partnerCode) => {
    const balance = await adminApi.getPartnerBalance(partnerCode);
    return { partnerCode, balance };
  },
);

/**
 * GET /v1/admin/partners/{partnerCode}/balance-alerts -> BalanceAlertView[]
 */
export const fetchBalanceAlerts = createAsyncThunk(
  'balance/fetchAlerts',
  async (partnerCode) => {
    const alerts = await adminApi.getBalanceAlerts(partnerCode);
    return { partnerCode, alerts };
  },
);

const balanceSlice = createSlice({
  name: 'balance',
  initialState,
  reducers: {
    clearBalanceError(state) {
      state.error = null;
    },
  },
  extraReducers: (builder) => {
    builder
      // ---- fetchBalance ----
      .addCase(fetchBalance.pending, (state, action) => {
        const code = action.meta.arg;
        state.loading[code] = true;
        state.error = null;
      })
      .addCase(fetchBalance.fulfilled, (state, action) => {
        const { partnerCode, balance } = action.payload;
        state.loading[partnerCode] = false;
        state.byCode[partnerCode] = balance;
      })
      .addCase(fetchBalance.rejected, (state, action) => {
        const code = action.meta.arg;
        state.loading[code] = false;
        state.error = action.error?.message ?? 'Failed to load balance';
      })
      // ---- fetchBalanceAlerts ----
      .addCase(fetchBalanceAlerts.pending, (state, action) => {
        const code = action.meta.arg;
        state.alertsLoading[code] = true;
        state.error = null;
      })
      .addCase(fetchBalanceAlerts.fulfilled, (state, action) => {
        const { partnerCode, alerts } = action.payload;
        state.alertsLoading[partnerCode] = false;
        state.alertsByCode[partnerCode] = Array.isArray(alerts) ? alerts : [];
      })
      .addCase(fetchBalanceAlerts.rejected, (state, action) => {
        const code = action.meta.arg;
        state.alertsLoading[code] = false;
        state.error = action.error?.message ?? 'Failed to load balance alerts';
      });
  },
});

export const { clearBalanceError } = balanceSlice.actions;
export default balanceSlice.reducer;
