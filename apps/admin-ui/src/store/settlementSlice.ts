'use client';

import { createAsyncThunk, createSlice, PayloadAction } from '@reduxjs/toolkit';
import { adminApi } from '@/api/client';
import type { SettlementBatch, SettlementBatchDetail } from '@/api/types';

export interface SettlementState {
  items: SettlementBatch[];
  details: Record<string, SettlementBatchDetail>;
  loading: boolean;
  detailLoading: boolean;
  error: string | null;
}

const initialState: SettlementState = {
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
  async (batchId: string) => {
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
      .addCase(
        listSettlements.fulfilled,
        (state, action: PayloadAction<SettlementBatch[]>) => {
          state.loading = false;
          state.items = action.payload;
        },
      )
      .addCase(listSettlements.rejected, (state, action) => {
        state.loading = false;
        state.error = action.error.message ?? 'Failed to load settlements';
      })
      .addCase(getSettlement.pending, (state) => {
        state.detailLoading = true;
        state.error = null;
      })
      .addCase(
        getSettlement.fulfilled,
        (state, action: PayloadAction<SettlementBatchDetail>) => {
          state.detailLoading = false;
          state.details[action.payload.batchId] = action.payload;
        },
      )
      .addCase(getSettlement.rejected, (state, action) => {
        state.detailLoading = false;
        state.error = action.error.message ?? 'Failed to load settlement batch';
      });
  },
});

export default settlementSlice.reducer;
