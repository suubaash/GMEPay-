'use client';

import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { adminApi } from '@/api/client';

/**
 * Settlement slice.
 *
 * GET /v1/admin/settlement/recent            -> SettlementBatchSummary[]
 * GET /v1/admin/settlement/batches?from&to   -> SettlementBatchSummary[]  (date-ranged)
 *   { batchId, partnerId, settlementDate, currency, amount, status,
 *     transmissionState, transmissionReason, transmittedAt }
 *
 * GET /v1/admin/settlement/{batchId} -> SettlementBatchDetail
 *   { batch: SettlementBatchSummary,
 *     lines: [{ txnRef, amount, currency, matched }],
 *     matchedCount, openCount }
 *
 * GET /v1/admin/settlement/transmission-channel -> { live, reachableState, reason }
 *
 * `details` is keyed by batchId.
 *
 * GAP T4-5: `status` (lifecycle) and `transmissionState` (did the file leave?) are two
 * independent axes and are stored verbatim — the slice normalises neither, because
 * `@/api/settlementStatus` is the single place that decides wording and colour.
 *
 * `channel` is `null` until the board has been read, and stays `null` when the read fails
 * — an absent board means "unknown", which the UI must never render as availability. A
 * failed channel read deliberately does NOT set `error`: it must not replace a loaded list
 * with a page-level failure, and an unknown board is already rendered as unknown.
 */
const initialState = {
  items: [],
  details: {},
  channel: null,
  loading: false,
  detailLoading: false,
  channelLoading: false,
  error: null,
};

export const listSettlements = createAsyncThunk(
  'settlement/list',
  async () => {
    return adminApi.listSettlements();
  },
);

/**
 * Date-ranged batches. `{ from, to, partnerId?, limit? }`; blank values are dropped by the
 * client's query builder, so calling it with no window is legal and lets upstream anchor it.
 */
export const listSettlementBatches = createAsyncThunk(
  'settlement/listRange',
  async (filters) => {
    return adminApi.listSettlementBatches(filters);
  },
);

export const getSettlement = createAsyncThunk(
  'settlement/get',
  async (batchId) => {
    return adminApi.getSettlement(batchId);
  },
);

export const fetchTransmissionChannel = createAsyncThunk(
  'settlement/transmissionChannel',
  async () => {
    return adminApi.getSettlementTransmissionChannel();
  },
);

/** Shared reducer body: both list thunks land the same payload shape. */
function storeList(state, action) {
  state.loading = false;
  state.items = Array.isArray(action.payload) ? action.payload : [];
}

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
      .addCase(listSettlements.fulfilled, storeList)
      .addCase(listSettlements.rejected, (state, action) => {
        state.loading = false;
        state.error = action.error?.message ?? 'Failed to load settlements';
      })
      .addCase(listSettlementBatches.pending, (state) => {
        state.loading = true;
        state.error = null;
      })
      .addCase(listSettlementBatches.fulfilled, storeList)
      .addCase(listSettlementBatches.rejected, (state, action) => {
        state.loading = false;
        state.error = action.error?.message ?? 'Failed to load settlement batches';
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
      })
      .addCase(fetchTransmissionChannel.pending, (state) => {
        state.channelLoading = true;
      })
      .addCase(fetchTransmissionChannel.fulfilled, (state, action) => {
        state.channelLoading = false;
        const board = action.payload;
        // Only an object-shaped board is a board. Anything else is unknown, and unknown is
        // null — never coerced into a `{ live: false }` we invented, and never into a
        // truthy value that could read as availability.
        state.channel = board && typeof board === 'object' && !Array.isArray(board)
          ? board
          : null;
      })
      .addCase(fetchTransmissionChannel.rejected, (state) => {
        state.channelLoading = false;
        state.channel = null;
      });
  },
});

export default settlementSlice.reducer;
