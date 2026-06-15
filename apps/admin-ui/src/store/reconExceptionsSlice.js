'use client';

import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { adminApi } from '@/api/client';

/**
 * reconExceptionsSlice — Settlement Exceptions ops queue (UC-04-03, BS-04).
 *
 * Feeds /settlement/exceptions with ReconExceptionResponse[] from:
 *   GET /v1/settlement/exceptions?batchId=&exceptionStatus=&matchStatus=
 *
 * Each item is a ReconExceptionResponse (exact field names from the service):
 *   { id, batchId, merchantId,
 *     gmeAmount (BigDecimal string), schemeAmount (BigDecimal|null),
 *     discrepancyAmount (BigDecimal string),
 *     matchStatus: DISCREPANCY|MISSING_SCHEME|MISSING_INTERNAL,
 *     exceptionStatus: OPEN|RESOLVED|RE_RUN,
 *     operatorId, resolutionNote, resolutionAction, resolvedAt, createdAt }
 *
 * Money fields are BigDecimal-as-string — NEVER Number()-cast them.
 *
 * Ops actions:
 *   POST /v1/settlement/exceptions/{id}/resolve  { operatorId, note, resolutionAction }
 *   POST /v1/settlement/exceptions/{id}/re-run   { operatorId }
 */

const initialState = {
  /** ReconExceptionResponse[] currently displayed */
  items: [],
  loading: false,
  error: null,
  /** Filters last applied (used to re-fetch after an action) */
  filters: {},
  /** Per-row in-flight state: Record<id, 'resolving' | 'rerunning'> */
  acting: {},
  /** Per-row action error: Record<id, string> */
  actError: {},
};

// ---------- Thunks ----------

/**
 * List exceptions — wraps GET /v1/settlement/exceptions.
 * @param {{ batchId?: string, exceptionStatus?: string, matchStatus?: string }} filters
 */
export const listExceptions = createAsyncThunk(
  'reconExceptions/list',
  async (filters = {}) => adminApi.listReconExceptions(filters),
);

/**
 * Resolve one exception.
 * @param {{ id: number, operatorId: string, note: string, resolutionAction: string }} arg
 */
export const resolveException = createAsyncThunk(
  'reconExceptions/resolve',
  async ({ id, operatorId, note, resolutionAction }, { rejectWithValue }) => {
    try {
      return await adminApi.resolveReconException(id, { operatorId, note, resolutionAction });
    } catch (e) {
      return rejectWithValue({ id, message: e?.message ?? 'Resolve failed' });
    }
  },
);

/**
 * Re-run the recon diff for one exception.
 * @param {{ id: number, operatorId: string }} arg
 */
export const reRunException = createAsyncThunk(
  'reconExceptions/reRun',
  async ({ id, operatorId }, { rejectWithValue }) => {
    try {
      return await adminApi.reRunReconException(id, { operatorId });
    } catch (e) {
      return rejectWithValue({ id, message: e?.message ?? 'Re-run failed' });
    }
  },
);

// ---------- Slice ----------

const reconExceptionsSlice = createSlice({
  name: 'reconExceptions',
  initialState,
  reducers: {
    setFilters(state, action) {
      state.filters = action.payload ?? {};
    },
    clearActError(state, action) {
      const id = action.payload;
      if (id !== undefined) {
        delete state.actError[id];
      }
    },
  },
  extraReducers: (builder) => {
    // --- listExceptions ---
    builder
      .addCase(listExceptions.pending, (state, action) => {
        state.loading = true;
        state.error = null;
        state.filters = action.meta.arg ?? {};
      })
      .addCase(listExceptions.fulfilled, (state, action) => {
        state.loading = false;
        state.items = Array.isArray(action.payload) ? action.payload : [];
      })
      .addCase(listExceptions.rejected, (state, action) => {
        state.loading = false;
        state.error = action.error?.message ?? 'Failed to load exceptions';
      });

    // --- resolveException ---
    builder
      .addCase(resolveException.pending, (state, action) => {
        const { id } = action.meta.arg;
        state.acting[id] = 'resolving';
        delete state.actError[id];
      })
      .addCase(resolveException.fulfilled, (state, action) => {
        const { id } = action.meta.arg;
        delete state.acting[id];
        // Update the row in-place with the resolved response.
        const updated = action.payload;
        if (updated) {
          const idx = state.items.findIndex((it) => String(it.id) === String(id));
          if (idx !== -1) {
            state.items[idx] = updated;
          }
        }
      })
      .addCase(resolveException.rejected, (state, action) => {
        const { id, message } = action.payload ?? {};
        if (id !== undefined) {
          delete state.acting[id];
          state.actError[id] = message ?? action.error?.message ?? 'Resolve failed';
        }
      });

    // --- reRunException ---
    builder
      .addCase(reRunException.pending, (state, action) => {
        const { id } = action.meta.arg;
        state.acting[id] = 'rerunning';
        delete state.actError[id];
      })
      .addCase(reRunException.fulfilled, (state, action) => {
        const { id } = action.meta.arg;
        delete state.acting[id];
        const updated = action.payload;
        if (updated) {
          const idx = state.items.findIndex((it) => String(it.id) === String(id));
          if (idx !== -1) {
            state.items[idx] = updated;
          }
        }
      })
      .addCase(reRunException.rejected, (state, action) => {
        const { id, message } = action.payload ?? {};
        if (id !== undefined) {
          delete state.acting[id];
          state.actError[id] = message ?? action.error?.message ?? 'Re-run failed';
        }
      });
  },
});

export const { setFilters, clearActError } = reconExceptionsSlice.actions;
export default reconExceptionsSlice.reducer;
