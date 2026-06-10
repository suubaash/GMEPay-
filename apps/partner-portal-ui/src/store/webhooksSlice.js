'use client';
import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { portalApi } from '@/api/client';

/**
 * Webhooks slice.
 *
 * Wire shape — GET /v1/portal/{partnerId}/webhooks returns
 *   Array<WebhookConfigView>
 *     { url, eventTypes, status, lastDeliveredAt }
 *
 * Note: there is NO id/active/createdAt/lastDeliveryStatus on the wire — the
 * page renders status as a chip and treats absent lastDeliveredAt as "—".
 */

const initialState = { data: null, status: 'idle', error: null };

export const fetchWebhooks = createAsyncThunk(
  'webhooks/fetch',
  async (partnerId) => portalApi.listWebhooks(partnerId)
);

const slice = createSlice({
  name: 'webhooks',
  initialState,
  reducers: {
    resetWebhooks: () => initialState
  },
  extraReducers: (builder) => {
    builder
      .addCase(fetchWebhooks.pending, (s) => {
        s.status = 'loading';
        s.error = null;
      })
      .addCase(fetchWebhooks.fulfilled, (s, a) => {
        s.status = 'succeeded';
        s.data = Array.isArray(a.payload) ? a.payload : [];
      })
      .addCase(fetchWebhooks.rejected, (s, a) => {
        s.status = 'failed';
        s.error = a.error.message ?? 'Failed to load webhooks';
      });
  }
});

export const { resetWebhooks } = slice.actions;
export default slice.reducer;
