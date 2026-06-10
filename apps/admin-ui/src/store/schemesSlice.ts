'use client';

import { createAsyncThunk, createSlice, PayloadAction } from '@reduxjs/toolkit';
import { adminApi } from '@/api/client';
import type { QrScheme } from '@/api/types';

export interface SchemesState {
  items: QrScheme[];
  loading: boolean;
  error: string | null;
}

const initialState: SchemesState = {
  items: [],
  loading: false,
  error: null,
};

export const listSchemes = createAsyncThunk('schemes/list', async () => {
  return adminApi.listSchemes();
});

const schemesSlice = createSlice({
  name: 'schemes',
  initialState,
  reducers: {},
  extraReducers: (builder) => {
    builder
      .addCase(listSchemes.pending, (state) => {
        state.loading = true;
        state.error = null;
      })
      .addCase(
        listSchemes.fulfilled,
        (state, action: PayloadAction<QrScheme[]>) => {
          state.loading = false;
          state.items = action.payload;
        },
      )
      .addCase(listSchemes.rejected, (state, action) => {
        state.loading = false;
        state.error = action.error.message ?? 'Failed to load schemes';
      });
  },
});

export default schemesSlice.reducer;
