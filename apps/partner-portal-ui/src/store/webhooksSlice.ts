'use client';
import { createAsyncThunk, createSlice, type PayloadAction } from '@reduxjs/toolkit';
import { portalApi } from '@/api/client';
import type { WebhookConfigDto } from '@/api/types';
import type { LoadState } from './overviewSlice';

interface WebhooksState {
  data: WebhookConfigDto[] | null;
  status: LoadState;
  error: string | null;
}

const initialState: WebhooksState = { data: null, status: 'idle', error: null };

export const fetchWebhooks = createAsyncThunk(
  'webhooks/fetch',
  async (partnerId: string) => portalApi.listWebhooks(partnerId)
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
      .addCase(fetchWebhooks.fulfilled, (s, a: PayloadAction<WebhookConfigDto[]>) => {
        s.status = 'succeeded';
        s.data = a.payload;
      })
      .addCase(fetchWebhooks.rejected, (s, a) => {
        s.status = 'failed';
        s.error = a.error.message ?? 'Failed to load webhooks';
      });
  }
});

export const { resetWebhooks } = slice.actions;
export default slice.reducer;
