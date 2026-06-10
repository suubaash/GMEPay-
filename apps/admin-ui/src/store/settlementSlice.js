'use client';

import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { adminApi } from '@/api/client';

/**
 * Settlement slice.
 *
 * GET /v1/admin/settlement/recent -> SettlementBatchSummary[]
 *   { batchId, partnerId, settlementDate, currency, amount, status }
 *
 * GET /v1/admin/settlement/{batchId} -> SettlementBatchDetail
 *   { batch: SettlementBatchSummary,
 *     lines: [{ txnRef, amount, currency, matched }] }
 *
 * `details` is keyed by batchId.
 */
const initialState = {
  items: [],
  details: {},
  loading: false,
  detailLoading: false,
  error: null,
};

export const listSettlements = createAsyncThunk(
  'settlement/list',
  async () => {
    return adminApi.listSettlements();
  },
);

export const getSettlement = createAsyncThunk(
  'settlement/get',
  async (batchId) => {
    return adminApi.getSettlement(batchId);
  },
);

const settlementSlice = createSlice({
  name: 'settlement',
  initialState,
  reducers: {},
  extraReducers: (builder) => {
    builder
      .addCase(listSettlements.pending, (state) => {
        state.loading = true;
        state.error = null;
      })
      .addCase(listSettlements.fulfilled, (state, action) => {
        state.loading = false;
        state.items = Array.isArray(action.payload) ? action.payload : [];
      })
      .addCase(listSettlements.rejected, (state, action) => {
        state.loading = false;
        state.error = action.error?.message ?? 'Failed to load settlements';
      })
      .addCase(getSettlement.pending, (state) => {
        state.detailLoading = true;
        state.error = null;
      })
      .addCase(getSettlement.fulfilled, (state, action) => {
        state.detailLoading = false;
        const detail = action.payload;
        const id = detail?.batch?.batchId;
        if (id) {
          state.details[id] = detail;
        }
      })
      .addCase(getSettlement.rejected, (state, action) => {
        state.detailLoading = false;
        state.error = action.error?.message ?? 'Failed to load settlement batch';
      });
  },
});

export default settlementSlice.reducer;
