'use client';

import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { adminApi } from '@/api/client';

/**
 * Rates slice — manual quote preview state for /rates.
 *
 * Fed by POST /v1/admin/rates/preview. The response (RateQuotePreview) is
 * stored verbatim in `preview` so the page can render the 5-step pivot
 * fields directly. The submitted request is mirrored under `request` so
 * the page can show "Last quoted: <amount> <ccy> @ <when>" without an
 * extra round-trip.
 *
 * Slice shape:
 *   {
 *     preview: RateQuotePreview | null,
 *     request: PreviewRequest | null,
 *     loading: boolean,
 *     error: string | null,
 *   }
 *
 * RateQuotePreview fields (decimal strings except shortCircuit and quotedAt):
 *   { collectionAmount, collectionCurrency,
 *     payoutAmount, payoutCurrency,
 *     collectionUsd, payoutUsdCost,
 *     collectionMarginUsd, payoutMarginUsd,
 *     offerRateColl, crossRate,
 *     shortCircuit:boolean, quotedAt:ISO }
 */
const initialState = {
  preview: null,
  request: null,
  loading: false,
  error: null,
};

export const previewRate = createAsyncThunk(
  'rates/preview',
  async (req, { rejectWithValue }) => {
    try {
      return await adminApi.previewRate(req);
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      return rejectWithValue(msg);
    }
  },
);

const ratesSlice = createSlice({
  name: 'rates',
  initialState,
  reducers: {
    clearPreview(state) {
      state.preview = null;
      state.request = null;
      state.error = null;
    },
  },
  extraReducers: (builder) => {
    builder
      .addCase(previewRate.pending, (state, action) => {
        state.loading = true;
        state.error = null;
        state.request = action.meta?.arg ?? null;
      })
      .addCase(previewRate.fulfilled, (state, action) => {
        state.loading = false;
        state.preview = action.payload ?? null;
      })
      .addCase(previewRate.rejected, (state, action) => {
        state.loading = false;
        state.error =
          (action.payload) ??
          action.error?.message ??
          'Failed to preview rate';
      });
  },
});

export const { clearPreview } = ratesSlice.actions;
export default ratesSlice.reducer;
