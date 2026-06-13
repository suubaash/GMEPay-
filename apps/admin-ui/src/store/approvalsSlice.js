'use client';

import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { adminApi } from '@/api/client';

/**
 * approvalsSlice — 4-eyes approval queue (Slice 2, agent 2B.2).
 *
 * Feeds the /approvals page with PROPOSED change requests from:
 *   GET /api/v1/admin/change-requests?state=PROPOSED
 *
 * Each item is a ChangeRequestSummary:
 *   { id, aggregate, proposer, proposedAt, payload }
 *
 * State transitions are driven by the backend (agent 2B.1):
 *   POST /v1/admin/change-requests/{id}/approve  { approvedBy }
 *   POST /v1/admin/change-requests/{id}/reject   { rejectedBy, reason }
 *
 * On approve / reject success the item is removed from the local list so the
 * operator's queue updates immediately without a full refetch.
 */

const initialState = {
  /** PROPOSED change requests currently displayed in the queue. */
  items: [],
  loading: false,
  error: null,
  /** Track per-row in-flight state to disable buttons while a request is live. */
  acting: {},   // Record<id, 'approving' | 'rejecting'>
  actError: {}, // Record<id, string>
};

// ---------- Thunks ----------

/**
 * Fetch all PROPOSED change requests.
 */
export const fetchPending = createAsyncThunk(
  'approvals/fetchPending',
  async () => adminApi.listPendingChangeRequests(),
);

/**
 * Approve one change request.
 * @param {{ id: string|number, approvedBy: string }} arg
 */
export const approve = createAsyncThunk(
  'approvals/approve',
  async ({ id, approvedBy }, { rejectWithValue }) => {
    try {
      return await adminApi.approveChangeRequest(id, approvedBy);
    } catch (e) {
      return rejectWithValue({ id, message: e?.message ?? 'Approval failed' });
    }
  },
);

/**
 * Reject one change request with a mandatory reason.
 * @param {{ id: string|number, rejectedBy: string, reason: string }} arg
 */
export const reject = createAsyncThunk(
  'approvals/reject',
  async ({ id, rejectedBy, reason }, { rejectWithValue }) => {
    try {
      return await adminApi.rejectChangeRequest(id, rejectedBy, reason);
    } catch (e) {
      return rejectWithValue({ id, message: e?.message ?? 'Rejection failed' });
    }
  },
);

// ---------- Slice ----------

const approvalsSlice = createSlice({
  name: 'approvals',
  initialState,
  reducers: {
    clearActError(state, action) {
      const id = action.payload;
      if (id !== undefined) {
        delete state.actError[id];
      }
    },
  },
  extraReducers: (builder) => {
    // --- fetchPending ---
    builder
      .addCase(fetchPending.pending, (state) => {
        state.loading = true;
        state.error = null;
      })
      .addCase(fetchPending.fulfilled, (state, action) => {
        state.loading = false;
        state.items = Array.isArray(action.payload) ? action.payload : [];
      })
      .addCase(fetchPending.rejected, (state, action) => {
        state.loading = false;
        state.error = action.error?.message ?? 'Failed to load pending approvals';
      });

    // --- approve ---
    builder
      .addCase(approve.pending, (state, action) => {
        const { id } = action.meta.arg;
        state.acting[id] = 'approving';
        delete state.actError[id];
      })
      .addCase(approve.fulfilled, (state, action) => {
        const { id } = action.meta.arg;
        delete state.acting[id];
        // Remove from queue — it's no longer PROPOSED.
        state.items = state.items.filter((cr) => String(cr.id) !== String(id));
      })
      .addCase(approve.rejected, (state, action) => {
        const { id, message } = action.payload ?? {};
        if (id !== undefined) {
          delete state.acting[id];
          state.actError[id] = message ?? action.error?.message ?? 'Approval failed';
        }
      });

    // --- reject ---
    builder
      .addCase(reject.pending, (state, action) => {
        const { id } = action.meta.arg;
        state.acting[id] = 'rejecting';
        delete state.actError[id];
      })
      .addCase(reject.fulfilled, (state, action) => {
        const { id } = action.meta.arg;
        delete state.acting[id];
        // Remove from queue — it's no longer PROPOSED.
        state.items = state.items.filter((cr) => String(cr.id) !== String(id));
      })
      .addCase(reject.rejected, (state, action) => {
        const { id, message } = action.payload ?? {};
        if (id !== undefined) {
          delete state.acting[id];
          state.actError[id] = message ?? action.error?.message ?? 'Rejection failed';
        }
      });
  },
});

export const { clearActError } = approvalsSlice.actions;
export default approvalsSlice.reducer;
