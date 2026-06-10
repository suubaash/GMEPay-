'use client';

import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { adminApi } from '@/api/client';

/**
 * Audit slice — paginated audit log.
 *
 * Fed by GET /v1/admin/audit?page=&size= -> Page<AuditEntry>:
 *   { content, page, size, total }
 *
 * AuditEntry: { id, actor, action, target, at:ISO, detail }
 *
 * The BFF Page<T> uses `total` (NOT totalElements) — mirrors the
 * transactionsSlice convention.
 */
const initialState = {
  items: [],
  page: 0,
  size: 20,
  total: 0,
  loading: false,
  error: null,
};

export const fetchAuditPage = createAsyncThunk(
  'audit/fetchPage',
  async ({ page, size } = {}, { rejectWithValue }) => {
    try {
      return await adminApi.getAuditPage(page ?? 0, size ?? 20);
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      return rejectWithValue(msg);
    }
  },
);

const auditSlice = createSlice({
  name: 'audit',
  initialState,
  reducers: {
    clearError(state) {
      state.error = null;
    },
  },
  extraReducers: (builder) => {
    builder
      .addCase(fetchAuditPage.pending, (state) => {
        state.loading = true;
        state.error = null;
      })
      .addCase(fetchAuditPage.fulfilled, (state, action) => {
        state.loading = false;
        const pageResp = action.payload ?? {};
        state.items = Array.isArray(pageResp.content) ? pageResp.content : [];
        state.page = pageResp.page ?? 0;
        state.size = pageResp.size ?? 20;
        state.total = pageResp.total ?? 0;
      })
      .addCase(fetchAuditPage.rejected, (state, action) => {
        state.loading = false;
        state.error =
          (action.payload) ??
          action.error?.message ??
          'Failed to load audit log';
      });
  },
});

export const { clearError } = auditSlice.actions;
export default auditSlice.reducer;
