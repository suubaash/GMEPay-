/**
 * Contract lock for rbacSlice (RBAC page, Lane 2).
 *
 * Covers:
 *  - fetchRbacData.pending/fulfilled/rejected
 *  - saveRolePermissions.pending/fulfilled/rejected — merges updated permissions
 *    back into the roles list.
 *  - createNewRole.pending/fulfilled/rejected — appends new role; deduplicates
 *    if role name already exists.
 *  - clearError / clearSaveError reducers.
 */
import { describe, expect, it } from 'vitest';
import reducer, {
  fetchRbacData,
  saveRolePermissions,
  createNewRole,
  clearError,
  clearSaveError,
} from '@/store/rbacSlice';

const ROLES = [
  {
    role: 'ADMIN',
    description: 'Full access',
    userCount: 2,
    permissions: ['partner.activate', 'rbac.manage'],
  },
  {
    role: 'READ_ONLY',
    description: 'Read only',
    userCount: 5,
    permissions: ['partner.view'],
  },
];

const PERMISSIONS = [
  { permission: 'partner.activate', resource: 'partner', action: 'activate', description: '' },
  { permission: 'partner.view', resource: 'partner', action: 'view', description: '' },
  { permission: 'rbac.manage', resource: 'rbac', action: 'manage', description: '' },
];

// Helper: build state with loaded data.
function loadedState() {
  return reducer(undefined, {
    type: fetchRbacData.fulfilled.type,
    payload: { roles: ROLES, permissions: PERMISSIONS },
  });
}

describe('rbacSlice', () => {
  // ---- Initial state ----
  it('starts with empty roles, permissions and no loading/error flags', () => {
    const state = reducer(undefined, { type: '@@INIT' });
    expect(state.roles).toEqual([]);
    expect(state.permissions).toEqual([]);
    expect(state.loading).toBe(false);
    expect(state.saving).toBe(false);
    expect(state.creating).toBe(false);
    expect(state.error).toBeNull();
    expect(state.saveError).toBeNull();
  });

  // ---- fetchRbacData ----
  it('fetchRbacData.pending sets loading true and clears error', () => {
    const start = reducer(undefined, {
      type: fetchRbacData.rejected.type,
      payload: 'old error',
    });
    const next = reducer(start, { type: fetchRbacData.pending.type });
    expect(next.loading).toBe(true);
    expect(next.error).toBeNull();
  });

  it('fetchRbacData.fulfilled stores roles and permissions', () => {
    const state = loadedState();
    expect(state.loading).toBe(false);
    expect(state.roles).toEqual(ROLES);
    expect(state.permissions).toEqual(PERMISSIONS);
    expect(state.error).toBeNull();
  });

  it('fetchRbacData.rejected stores error and clears loading', () => {
    const state = reducer(undefined, {
      type: fetchRbacData.rejected.type,
      payload: 'Network timeout',
    });
    expect(state.loading).toBe(false);
    expect(state.error).toBe('Network timeout');
  });

  it('fetchRbacData.rejected uses error.message when no payload', () => {
    const state = reducer(undefined, {
      type: fetchRbacData.rejected.type,
      error: { message: 'BFF down' },
    });
    expect(state.error).toBe('BFF down');
  });

  // ---- saveRolePermissions ----
  it('saveRolePermissions.pending sets saving true and clears saveError', () => {
    const start = reducer(undefined, {
      type: saveRolePermissions.rejected.type,
      payload: 'old save error',
    });
    const next = reducer(start, { type: saveRolePermissions.pending.type });
    expect(next.saving).toBe(true);
    expect(next.saveError).toBeNull();
  });

  it('saveRolePermissions.fulfilled merges updated permissions into roles list', () => {
    const start = loadedState();
    const updated = { role: 'ADMIN', permissions: ['partner.activate'] };

    const next = reducer(start, {
      type: saveRolePermissions.fulfilled.type,
      payload: updated,
    });

    expect(next.saving).toBe(false);
    const admin = next.roles.find((r) => r.role === 'ADMIN');
    expect(admin.permissions).toEqual(['partner.activate']);
    // Other roles untouched
    const readOnly = next.roles.find((r) => r.role === 'READ_ONLY');
    expect(readOnly.permissions).toEqual(['partner.view']);
  });

  it('saveRolePermissions.fulfilled preserves other role fields (description, userCount)', () => {
    const start = loadedState();
    const next = reducer(start, {
      type: saveRolePermissions.fulfilled.type,
      payload: { role: 'ADMIN', permissions: ['rbac.manage'] },
    });
    const admin = next.roles.find((r) => r.role === 'ADMIN');
    expect(admin.description).toBe('Full access');
    expect(admin.userCount).toBe(2);
  });

  it('saveRolePermissions.rejected stores saveError', () => {
    const next = reducer(undefined, {
      type: saveRolePermissions.rejected.type,
      payload: 'Permission denied',
    });
    expect(next.saving).toBe(false);
    expect(next.saveError).toBe('Permission denied');
  });

  // ---- createNewRole ----
  it('createNewRole.pending sets creating true and clears saveError', () => {
    const start = reducer(undefined, {
      type: createNewRole.rejected.type,
      payload: 'old error',
    });
    const next = reducer(start, { type: createNewRole.pending.type });
    expect(next.creating).toBe(true);
    expect(next.saveError).toBeNull();
  });

  it('createNewRole.fulfilled appends the new role to the list', () => {
    const start = loadedState();
    const newRole = {
      role: 'AUDIT',
      description: 'Audit access',
      userCount: 0,
      permissions: ['partner.view'],
    };
    const next = reducer(start, {
      type: createNewRole.fulfilled.type,
      payload: newRole,
    });
    expect(next.creating).toBe(false);
    expect(next.roles).toHaveLength(3);
    expect(next.roles.find((r) => r.role === 'AUDIT')).toEqual(newRole);
  });

  it('createNewRole.fulfilled deduplicates by role name', () => {
    const start = loadedState();
    // Server echoes the same role name that already exists (e.g. conflict resolved server-side)
    const updated = { role: 'ADMIN', description: 'Updated', userCount: 3, permissions: [] };
    const next = reducer(start, {
      type: createNewRole.fulfilled.type,
      payload: updated,
    });
    expect(next.roles.filter((r) => r.role === 'ADMIN')).toHaveLength(1);
    expect(next.roles.find((r) => r.role === 'ADMIN').description).toBe('Updated');
  });

  it('createNewRole.rejected stores saveError', () => {
    const next = reducer(undefined, {
      type: createNewRole.rejected.type,
      payload: 'Role already exists',
    });
    expect(next.creating).toBe(false);
    expect(next.saveError).toBe('Role already exists');
  });

  // ---- Reducers ----
  it('clearError nullifies state.error', () => {
    const start = reducer(undefined, {
      type: fetchRbacData.rejected.type,
      payload: 'some error',
    });
    expect(start.error).toBe('some error');
    const next = reducer(start, clearError());
    expect(next.error).toBeNull();
  });

  it('clearSaveError nullifies state.saveError', () => {
    const start = reducer(undefined, {
      type: saveRolePermissions.rejected.type,
      payload: 'save failed',
    });
    expect(start.saveError).toBe('save failed');
    const next = reducer(start, clearSaveError());
    expect(next.saveError).toBeNull();
  });
});
