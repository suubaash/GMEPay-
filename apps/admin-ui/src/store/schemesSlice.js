'use client';

import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { adminApi } from '@/api/client';

/**
 * Schemes slice — backs the /schemes table.
 *
 * Each item is a SchemeSummary:
 *   { schemeId, name, country, currency, mode, status }
 * Where `status` is a string like "ACTIVE" / "INACTIVE".
 */
const initialState = {
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
      .addCase(listSchemes.fulfilled, (state, action) => {
        state.loading = false;
        state.items = Array.isArray(action.payload) ? action.payload : [];
      })
      .addCase(listSchemes.rejected, (state, action) => {
        state.loading = false;
        state.error = action.error?.message ?? 'Failed to load schemes';
      });
  },
});

export default schemesSlice.reducer;
