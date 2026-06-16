'use client';

import { useAppSelector } from '@/store';

/**
 * Permission that controls visibility of the live Request Inspector overlay.
 * Granted to the ADMIN role by default; an admin can grant it to other roles
 * via the RBAC page (see src/api/rbacApi.js FIXTURE_PERMISSIONS / FIXTURE_ROLES).
 */
export const INSPECTOR_PERMISSION = 'inspector.view';

/** Roles that always carry inspector access regardless of the RBAC grant map. */
export const INSPECTOR_ROLES = ['ADMIN'];

/**
 * Returns true when the signed-in operator is allowed to see the request/response
 * inspector. It is a role control: either the user's role is in {@link INSPECTOR_ROLES},
 * or (when the RBAC role->permission map is loaded) that role has been granted
 * {@link INSPECTOR_PERMISSION}. Everyone else gets the normal loading UI.
 */
export function useCanInspect() {
  const role = useAppSelector((s) => s.auth?.role) ?? null;
  const rbacRoles = useAppSelector((s) => s.rbac?.roles) ?? null;

  if (role && INSPECTOR_ROLES.includes(role)) return true;

  if (role && Array.isArray(rbacRoles)) {
    const match = rbacRoles.find((r) => r.role === role);
    if (match && Array.isArray(match.permissions)) {
      return match.permissions.includes(INSPECTOR_PERMISSION);
    }
  }
  return false;
}
