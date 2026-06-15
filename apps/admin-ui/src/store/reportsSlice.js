'use client';

import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import {
  listReports,
  generateReport,
  downloadReport,
} from '@/api/reportsApi';

/**
 * Reports slice — backs the /reports page.
 *
 * State shape:
 *   items          : ReportRun[]     — filtered list from the BFF
 *   loading        : boolean
 *   generating     : boolean         — true while a generate POST is in flight
 *   downloading    : { [id]: true }  — tracks per-row download in-flight state
 *   error          : string | null
 *   generateError  : string | null
 *   filters        : { type: string, from: string, to: string }
 *
 * ReportRun wire shape (BigDecimal-as-string, timestamps in UTC ISO-8601):
 *   { id, type, period, status, recordCount, generatedAt, downloadUrl }
 *
 * NOTE: Do NOT import from @/store/index.js or @/api/client.js — isolated lane.
 */

const initialState = {
  items: [],
  loading: false,
  generating: false,
  downloading: {},
  error: null,
  generateError: null,
  filters: {
    type: '',
    from: '',
    to: '',
  },
};

// ---------------------------------------------------------------------------
// Thunks
// ---------------------------------------------------------------------------

/**
 * Fetch report runs.  Passes current filter values from the action argument
 * so callers can pass the latest filter state without relying on a selector.
 *
 * @param {{ type?: string, from?: string, to?: string }} params
 */
export const fetchReports = createAsyncThunk(
  'reports/fetch',
  async (params = {}) => {
    return listReports(params);
  },
);

/**
 * Trigger a new report generation run.
 *
 * @param {{ type: string, period?: string }} arg
 */
export const triggerGenerate = createAsyncThunk(
  'reports/generate',
  async ({ type, period }) => {
    return generateReport(type, period ? { period } : {});
  },
);

/**
 * Download a report blob and trigger a browser save dialog.
 * Returns the id so the reducer can clear the downloading flag.
 *
 * @param {{ id: string, filename?: string }} arg
 */
export const downloadReportRun = createAsyncThunk(
  'reports/download',
  async ({ id, filename }) => {
    const blob = await downloadReport(id);
    const objectUrl = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = objectUrl;
    anchor.download = filename ?? `report-${id}.zip`;
    document.body.appendChild(anchor);
    anchor.click();
    document.body.removeChild(anchor);
    URL.revokeObjectURL(objectUrl);
    return id;
  },
);

// ---------------------------------------------------------------------------
// Slice
// ---------------------------------------------------------------------------

const reportsSlice = createSlice({
  name: 'reports',
  initialState,
  reducers: {
    setFilters(state, action) {
      state.filters = { ...state.filters, ...action.payload };
    },
    clearError(state) {
      state.error = null;
    },
    clearGenerateError(state) {
      state.generateError = null;
    },
  },
  extraReducers: (builder) => {
    builder
      // ---- fetchReports ----
      .addCase(fetchReports.pending, (state) => {
        state.loading = true;
        state.error = null;
      })
      .addCase(fetchReports.fulfilled, (state, action) => {
        state.loading = false;
        state.items = Array.isArray(action.payload) ? action.payload : [];
      })
      .addCase(fetchReports.rejected, (state, action) => {
        state.loading = false;
        state.error = action.error?.message ?? 'Failed to load reports';
      })

      // ---- triggerGenerate ----
      .addCase(triggerGenerate.pending, (state) => {
        state.generating = true;
        state.generateError = null;
      })
      .addCase(triggerGenerate.fulfilled, (state, action) => {
        state.generating = false;
        // Prepend the new run so it surfaces at the top of the list.
        const newRun = action.payload;
        if (newRun && newRun.id) {
          state.items = [
            newRun,
            ...state.items.filter((r) => r.id !== newRun.id),
          ];
        }
      })
      .addCase(triggerGenerate.rejected, (state, action) => {
        state.generating = false;
        state.generateError =
          action.error?.message ?? 'Failed to generate report';
      })

      // ---- downloadReportRun ----
      .addCase(downloadReportRun.pending, (state, action) => {
        const id = action.meta.arg?.id;
        if (id) state.downloading[id] = true;
      })
      .addCase(downloadReportRun.fulfilled, (state, action) => {
        const id = action.payload;
        if (id) delete state.downloading[id];
      })
      .addCase(downloadReportRun.rejected, (state, action) => {
        const id = action.meta.arg?.id;
        if (id) delete state.downloading[id];
        state.error = action.error?.message ?? 'Download failed';
      });
  },
});

export const { setFilters, clearError, clearGenerateError } =
  reportsSlice.actions;
export default reportsSlice.reducer;
