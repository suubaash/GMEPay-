'use client';

import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { adminApi } from '@/api/client';

/**
 * auditTrailSlice — per-aggregate paginated audit trail.
 *
 * Fed by:
 *   GET /api/v1/admin/audit-trail?aggregateType={t}&aggregateId={id}&page=&size=
 *   -> { entries:[{recordedAt,actorId,eventType,beforeJson,afterJson}],
 *        chainValid:boolean, page:number, size:number, total:number }
 *
 * The slice is keyed by a string `${aggregateType}:${aggregateId}` so multiple
 * aggregates can be cached independently in the same Redux store without
 * interfering with the global audit log slice (auditSlice).
 *
 * Shape per cache key:
 *   { entries, chainValid, page, size, total, loading, error }
 */

const PER_KEY_INITIAL = {
  entries: [],
  chainValid: null,  // null = not yet loaded
  page: 0,
  size: 20,
  total: 0,
  loading: false,
  error: null,
};

const initialState = {
  // Record<cacheKey, PER_KEY_INITIAL>
  byKey: {},
};

/** Build the Redux state cache key. */
export function trailKey(aggregateType, aggregateId) {
  return `${aggregateType}:${aggregateId}`;
}

/**
 * Fetch a page of the audit trail for one aggregate.
 *
 * Payload expected from BFF:
 *   { entries, chainValid, page, size, total }
 */
export const fetchAuditTrail = createAsyncThunk(
  'auditTrail/fetch',
  async ({ aggregateType, aggregateId, page = 0, size = 20 }, { rejectWithValue }) => {
    try {
      const data = await adminApi.getAuditTrail(aggregateType, aggregateId, page, size);
      return { aggregateType, aggregateId, ...data };
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      return rejectWithValue({ aggregateType, aggregateId, msg });
    }
  },
);

const auditTrailSlice = createSlice({
  name: 'auditTrail',
  initialState,
  reducers: {
    clearTrail(state, action) {
      const { aggregateType, aggregateId } = action.payload ?? {};
      if (aggregateType && aggregateId) {
        delete state.byKey[trailKey(aggregateType, aggregateId)];
      }
    },
  },
  extraReducers: (builder) => {
    builder
      .addCase(fetchAuditTrail.pending, (state, action) => {
        const { aggregateType, aggregateId } = action.meta.arg;
        const key = trailKey(aggregateType, aggregateId);
        const prev = state.byKey[key] ?? { ...PER_KEY_INITIAL };
        state.byKey[key] = { ...prev, loading: true, error: null };
      })
      .addCase(fetchAuditTrail.fulfilled, (state, action) => {
        const { aggregateType, aggregateId, entries, chainValid, page, size, total } =
          action.payload ?? {};
        const key = trailKey(aggregateType, aggregateId);
        state.byKey[key] = {
          entries: Array.isArray(entries) ? entries : [],
          chainValid: chainValid ?? null,
          page: page ?? 0,
          size: size ?? 20,
          total: total ?? 0,
          loading: false,
          error: null,
        };
      })
      .addCase(fetchAuditTrail.rejected, (state, action) => {
        const { aggregateType, aggregateId, msg } = action.payload ?? {};
        const key = trailKey(aggregateType ?? '', aggregateId ?? '');
        const prev = state.byKey[key] ?? { ...PER_KEY_INITIAL };
        state.byKey[key] = {
          ...prev,
          loading: false,
          error: msg ?? action.error?.message ?? 'Failed to load audit trail',
        };
      });
  },
});

export const { clearTrail } = auditTrailSlice.actions;
export default auditTrailSlice.reducer;
