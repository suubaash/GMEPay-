'use client';

import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import {
  getRoles,
  getPermissions,
  putRolePermissions,
  createRole,
} from '@/api/rbacApi';

/**
 * rbacSlice — Roles & Permissions matrix (RBAC page, Lane 2).
 *
 * State
 * -----
 *   roles        : RoleSummary[]   — fetched from GET /v1/admin/rbac/roles
 *   permissions  : PermissionDef[] — fetched from GET /v1/admin/rbac/permissions
 *   loading      : boolean
 *   saving       : boolean  (PUT in flight)
 *   creating     : boolean  (POST create-role in flight)
 *   error        : string | null
 *   saveError    : string | null
 *
 * RoleSummary:  { role, description, userCount, permissions: string[] }
 * PermissionDef: { permission, resource, action, description }
 *
 * 4-eyes note: role-permission changes are advisory-only at the UI layer.
 * The BFF is expected to validate that the requesting user holds rbac.manage
 * and to create a PROPOSED change-request for a second approver.
 */

const initialState = {
  roles: [],
  permissions: [],
  loading: false,
  saving: false,
  creating: false,
  error: null,
  saveError: null,
};

// ---------- Thunks ----------

export const fetchRbacData = createAsyncThunk(
  'rbac/fetchRbacData',
  async (_, { rejectWithValue }) => {
    try {
      const [roles, permissions] = await Promise.all([
        getRoles(),
        getPermissions(),
      ]);
      return { roles, permissions };
    } catch (e) {
      return rejectWithValue(e?.message ?? 'Failed to load RBAC data');
    }
  },
);

/**
 * Save a single role's full permission grant set.
 * @param {{ role: string, grants: string[] }} arg
 */
export const saveRolePermissions = createAsyncThunk(
  'rbac/saveRolePermissions',
  async ({ role, grants }, { rejectWithValue }) => {
    try {
      const updated = await putRolePermissions(role, grants);
      return updated;
    } catch (e) {
      return rejectWithValue(e?.message ?? 'Failed to save permissions');
    }
  },
);

/**
 * Create a new role with a given name and base permission set.
 * @param {{ name: string, basePermissions: string[] }} arg
 */
export const createNewRole = createAsyncThunk(
  'rbac/createNewRole',
  async ({ name, basePermissions }, { rejectWithValue }) => {
    try {
      const created = await createRole({ name, basePermissions });
      return created;
    } catch (e) {
      return rejectWithValue(e?.message ?? 'Failed to create role');
    }
  },
);

// ---------- Slice ----------

const rbacSlice = createSlice({
  name: 'rbac',
  initialState,
  reducers: {
    clearError(state) {
      state.error = null;
    },
    clearSaveError(state) {
      state.saveError = null;
    },
  },
  extraReducers: (builder) => {
    builder
      // ---- fetchRbacData ----
      .addCase(fetchRbacData.pending, (state) => {
        state.loading = true;
        state.error = null;
      })
      .addCase(fetchRbacData.fulfilled, (state, action) => {
        state.loading = false;
        state.roles = action.payload.roles ?? [];
        state.permissions = action.payload.permissions ?? [];
      })
      .addCase(fetchRbacData.rejected, (state, action) => {
        state.loading = false;
        state.error = action.payload ?? action.error?.message ?? 'Failed to load RBAC data';
      })

      // ---- saveRolePermissions ----
      .addCase(saveRolePermissions.pending, (state) => {
        state.saving = true;
        state.saveError = null;
      })
      .addCase(saveRolePermissions.fulfilled, (state, action) => {
        state.saving = false;
        // Merge the updated permissions back into the roles list.
        const updated = action.payload;
        if (updated && updated.role) {
          state.roles = state.roles.map((r) =>
            r.role === updated.role
              ? { ...r, permissions: updated.permissions ?? r.permissions }
              : r,
          );
        }
      })
      .addCase(saveRolePermissions.rejected, (state, action) => {
        state.saving = false;
        state.saveError = action.payload ?? action.error?.message ?? 'Failed to save permissions';
      })

      // ---- createNewRole ----
      .addCase(createNewRole.pending, (state) => {
        state.creating = true;
        state.saveError = null;
      })
      .addCase(createNewRole.fulfilled, (state, action) => {
        state.creating = false;
        const created = action.payload;
        if (created && created.role) {
          // Avoid duplicates if the server echoes an existing role name.
          state.roles = [
            ...state.roles.filter((r) => r.role !== created.role),
            created,
          ];
        }
      })
      .addCase(createNewRole.rejected, (state, action) => {
        state.creating = false;
        state.saveError = action.payload ?? action.error?.message ?? 'Failed to create role';
      });
  },
});

export const { clearError, clearSaveError } = rbacSlice.actions;
export default rbacSlice.reducer;
