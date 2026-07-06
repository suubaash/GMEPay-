'use client';

import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import * as usersApi from '@/api/usersApi';

/**
 * usersSlice — Redux state for the Operator User Management page (/users).
 *
 * State shape:
 *   items        : UserSummary[]   — full list from the BFF
 *   loading      : boolean
 *   saving       : boolean         — in-flight invite/patch/deactivate
 *   error        : string | null   — last user-visible error
 *   fromFixture  : boolean         — true when items came from the fixture fallback
 *
 * Wire shape (UserSummary):
 *   { id, name, email, roles: string[], status: 'ACTIVE'|'INVITED'|'DISABLED',
 *     lastLoginAt: ISO-8601 string | null }
 */

const initialState = {
  items: [],
  loading: false,
  saving: false,
  error: null,
  fromFixture: false,
};

// ---------------------------------------------------------------------------
// Thunks
// ---------------------------------------------------------------------------

export const fetchUsers = createAsyncThunk('users/fetchAll', async () => {
  return usersApi.listUsers();
});

/**
 * Demo-mode fallback for mutations.
 *
 * When the users backend is absent, the initial fetch falls back to fixtures
 * and flips `fromFixture` true. In that state a mutation cannot reach a real
 * backend, so instead of surfacing a raw HTTP error we apply the change
 * optimistically to local state and keep the page usable (the /users page
 * banner tells the operator these changes are local-only and not persisted).
 *
 * When a real backend IS present (`fromFixture` false), the error is re-thrown
 * so genuine failures still surface to the operator.
 */
function localFallback(getState, buildLocal, err) {
  if (!getState().users.fromFixture) throw err;
  return { ...buildLocal(), __local: true };
}

export const inviteUser = createAsyncThunk(
  'users/invite',
  async (body, { getState }) => {
    try {
      return await usersApi.inviteUser(body);
    } catch (err) {
      return localFallback(
        getState,
        () => ({
          id: `u-local-${Date.now()}`,
          name: body.email?.split('@')[0] ?? body.email,
          email: body.email,
          roles: body.roles ?? [],
          status: 'INVITED',
          lastLoginAt: null,
        }),
        err,
      );
    }
  },
);

export const updateUserRoles = createAsyncThunk(
  'users/updateRoles',
  async ({ id, roles }, { getState }) => {
    try {
      return await usersApi.updateUser(id, { roles });
    } catch (err) {
      const current = getState().users.items.find((u) => u.id === id);
      return localFallback(getState, () => ({ ...(current ?? { id }), id, roles }), err);
    }
  },
);

export const deactivateUser = createAsyncThunk(
  'users/deactivate',
  async (id, { getState }) => {
    try {
      return await usersApi.deactivateUser(id);
    } catch (err) {
      const current = getState().users.items.find((u) => u.id === id);
      return localFallback(
        getState,
        () => ({ ...(current ?? { id }), id, status: 'DISABLED' }),
        err,
      );
    }
  },
);

export const reactivateUser = createAsyncThunk(
  'users/reactivate',
  async (id, { getState }) => {
    try {
      return await usersApi.reactivateUser(id);
    } catch (err) {
      const current = getState().users.items.find((u) => u.id === id);
      return localFallback(
        getState,
        () => ({ ...(current ?? { id }), id, status: 'ACTIVE' }),
        err,
      );
    }
  },
);

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * Replace or append a UserSummary in the items array after a mutating action.
 * Matches on `id`.
 */
function upsertItem(items, updated) {
  if (!updated || !updated.id) return items;
  const idx = items.findIndex((u) => u.id === updated.id);
  if (idx === -1) return [...items, updated];
  const next = [...items];
  next[idx] = updated;
  return next;
}

// ---------------------------------------------------------------------------
// Slice
// ---------------------------------------------------------------------------

const usersSlice = createSlice({
  name: 'users',
  initialState,
  reducers: {
    clearError(state) {
      state.error = null;
    },
  },
  extraReducers: (builder) => {
    builder
      // ---- fetchUsers ----
      .addCase(fetchUsers.pending, (state) => {
        state.loading = true;
        state.error = null;
      })
      .addCase(fetchUsers.fulfilled, (state, action) => {
        state.loading = false;
        const { data, fromFixture, error } = action.payload;
        state.items = Array.isArray(data) ? data : [];
        state.fromFixture = !!fromFixture;
        // Surface backend-absent info as a non-fatal warning message
        if (fromFixture && error) {
          state.error = `Backend unavailable (${error}) — showing demo data`;
        }
      })
      .addCase(fetchUsers.rejected, (state, action) => {
        state.loading = false;
        state.error = action.error?.message ?? 'Failed to load users';
      })

      // ---- inviteUser ----
      .addCase(inviteUser.pending, (state) => {
        state.saving = true;
        state.error = null;
      })
      .addCase(inviteUser.fulfilled, (state, action) => {
        state.saving = false;
        state.items = upsertItem(state.items, action.payload);
      })
      .addCase(inviteUser.rejected, (state, action) => {
        state.saving = false;
        state.error = action.error?.message ?? 'Failed to invite user';
      })

      // ---- updateUserRoles ----
      .addCase(updateUserRoles.pending, (state) => {
        state.saving = true;
        state.error = null;
      })
      .addCase(updateUserRoles.fulfilled, (state, action) => {
        state.saving = false;
        state.items = upsertItem(state.items, action.payload);
      })
      .addCase(updateUserRoles.rejected, (state, action) => {
        state.saving = false;
        state.error = action.error?.message ?? 'Failed to update roles';
      })

      // ---- deactivateUser ----
      .addCase(deactivateUser.pending, (state) => {
        state.saving = true;
        state.error = null;
      })
      .addCase(deactivateUser.fulfilled, (state, action) => {
        state.saving = false;
        state.items = upsertItem(state.items, action.payload);
      })
      .addCase(deactivateUser.rejected, (state, action) => {
        state.saving = false;
        state.error = action.error?.message ?? 'Failed to deactivate user';
      })

      // ---- reactivateUser ----
      .addCase(reactivateUser.pending, (state) => {
        state.saving = true;
        state.error = null;
      })
      .addCase(reactivateUser.fulfilled, (state, action) => {
        state.saving = false;
        state.items = upsertItem(state.items, action.payload);
      })
      .addCase(reactivateUser.rejected, (state, action) => {
        state.saving = false;
        state.error = action.error?.message ?? 'Failed to reactivate user';
      });
  },
});

export const { clearError } = usersSlice.actions;
export default usersSlice.reducer;
