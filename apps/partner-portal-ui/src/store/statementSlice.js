'use client';
import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { portalApi } from '@/api/client';

/**
 * Statement (CSV download) slice.
 *
 * Tracks the in-flight download state + the metadata of the last successful
 * download so the page can show "Last download: <filename> at <when>". The
 * Blob itself is consumed at the call site (anchor + object URL) and is NOT
 * stored in Redux — Blobs are non-serializable and shouldn't sit in state.
 *
 * Wire shape — GET /v1/portal/{partnerId}/statement?from&to returns a CSV
 * body (text/csv). On the thunk we return only metadata { from, to, sizeBytes,
 * downloadedAt } and hand the Blob to the page via a side channel.
 *
 * State:
 *   {
 *     status: 'idle' | 'loading' | 'succeeded' | 'failed',
 *     error: string | null,
 *     lastDownload: {
 *       from: string,
 *       to: string,
 *       sizeBytes: number,
 *       downloadedAt: string  // ISO instant
 *     } | null
 *   }
 */

const initialState = {
  status: 'idle',
  error: null,
  lastDownload: null
};

/**
 * Fetch the CSV and report metadata back to the slice. The Blob itself is
 * passed to the caller via `meta.arg.onBlob(blob)` so the page can trigger
 * the browser download without storing non-serializable data in Redux.
 */
export const downloadStatementThunk = createAsyncThunk(
  'statement/download',
  async ({ partnerId, from, to, onBlob }, { rejectWithValue }) => {
    try {
      const blob = await portalApi.downloadStatement(partnerId, from, to);
      if (typeof onBlob === 'function') onBlob(blob);
      return {
        from,
        to,
        sizeBytes: blob && typeof blob.size === 'number' ? blob.size : 0,
        downloadedAt: new Date().toISOString()
      };
    } catch (e) {
      return rejectWithValue(e instanceof Error ? e.message : 'Download failed');
    }
  }
);

const slice = createSlice({
  name: 'statement',
  initialState,
  reducers: {
    resetStatement: () => initialState,
    clearStatementError: (s) => {
      s.error = null;
    }
  },
  extraReducers: (builder) => {
    builder
      .addCase(downloadStatementThunk.pending, (s) => {
        s.status = 'loading';
        s.error = null;
      })
      .addCase(downloadStatementThunk.fulfilled, (s, a) => {
        s.status = 'succeeded';
        s.lastDownload = a.payload ?? null;
      })
      .addCase(downloadStatementThunk.rejected, (s, a) => {
        s.status = 'failed';
        s.error = a.payload ?? a.error.message ?? 'Failed to download statement';
      });
  }
});

export const { resetStatement, clearStatementError } = slice.actions;
export default slice.reducer;
