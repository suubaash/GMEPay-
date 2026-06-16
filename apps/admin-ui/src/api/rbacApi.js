/**
 * rbacApi — RBAC page API module.
 *
 * Calls:
 *   GET  /v1/admin/rbac/roles         → RoleSummary[]
 *   GET  /v1/admin/rbac/permissions   → PermissionDef[]
 *   PUT  /v1/admin/rbac/roles/{role}/permissions  body: { grants: string[] }
 *   POST /v1/admin/rbac/roles         body: { name, basePermissions: string[] }
 *
 * Wire shapes
 * -----------
 * RoleSummary:
 *   { role: string, description: string, userCount: number, permissions: string[] }
 *
 * PermissionDef:
 *   { permission: string, resource: string, action: string, description: string }
 *
 * RolePermissionsResponse (PUT response):
 *   { role: string, permissions: string[] }
 *
 * CreateRoleResponse (POST response):
 *   { role: string, description: string, userCount: number, permissions: string[] }
 *
 * NOTE: This module does NOT import from @/api/client (hard isolation rule).
 * It maintains its own lightweight fetch wrapper so this lane can run in
 * parallel with other admin-ui workflows. The fixture fallback makes the page
 * demoable while the BFF endpoint is absent.
 */

import { TOKEN_KEY } from '@/api/auth';

/**
 * Fixture data — used when the backend returns 404 / network error.
 * Labeled clearly so demo vs live data is obvious in logs.
 */
export const FIXTURE_ROLES = [
  {
    role: 'ADMIN',
    description: 'Full system access — all resources and actions.',
    userCount: 3,
    permissions: [
      'partner.activate',
      'partner.view',
      'settlement.resolve_exception',
      'settlement.view',
      'report.generate',
      'report.view',
      'txn.refund',
      'txn.view',
      'rbac.manage',
      'inspector.view',
    ],
  },
  {
    role: 'OPS',
    description: 'Operational tasks — partner activation, transaction management.',
    userCount: 8,
    permissions: [
      'partner.activate',
      'partner.view',
      'settlement.resolve_exception',
      'settlement.view',
      'txn.refund',
      'txn.view',
    ],
  },
  {
    role: 'COMPLIANCE',
    description: 'Compliance monitoring — reporting and audit access.',
    userCount: 4,
    permissions: ['report.generate', 'report.view', 'txn.view', 'partner.view'],
  },
  {
    role: 'FINANCE',
    description: 'Finance operations — settlements and revenue reporting.',
    userCount: 5,
    permissions: [
      'settlement.resolve_exception',
      'settlement.view',
      'report.generate',
      'report.view',
    ],
  },
  {
    role: 'READ_ONLY',
    description: 'Read-only access to all resources. No mutations permitted.',
    userCount: 12,
    permissions: ['partner.view', 'settlement.view', 'report.view', 'txn.view'],
  },
];

export const FIXTURE_PERMISSIONS = [
  {
    permission: 'inspector.view',
    resource: 'inspector',
    action: 'view',
    description: 'View the live request/response inspector overlay (developer/ops tool).',
  },
  {
    permission: 'partner.activate',
    resource: 'partner',
    action: 'activate',
    description: 'Activate or deactivate a partner.',
  },
  {
    permission: 'partner.view',
    resource: 'partner',
    action: 'view',
    description: 'View partner details and configuration.',
  },
  {
    permission: 'settlement.resolve_exception',
    resource: 'settlement',
    action: 'resolve_exception',
    description: 'Resolve settlement reconciliation exceptions.',
  },
  {
    permission: 'settlement.view',
    resource: 'settlement',
    action: 'view',
    description: 'View settlement batches and status.',
  },
  {
    permission: 'report.generate',
    resource: 'report',
    action: 'generate',
    description: 'Generate compliance and revenue reports.',
  },
  {
    permission: 'report.view',
    resource: 'report',
    action: 'view',
    description: 'View generated reports.',
  },
  {
    permission: 'txn.refund',
    resource: 'txn',
    action: 'refund',
    description: 'Initiate a transaction refund.',
  },
  {
    permission: 'txn.view',
    resource: 'txn',
    action: 'view',
    description: 'View transaction details and history.',
  },
  {
    permission: 'rbac.manage',
    resource: 'rbac',
    action: 'manage',
    description: 'Manage roles and permissions (super-admin only).',
  },
];

// ---------- Internals ----------

function baseUrl() {
  if (typeof window !== 'undefined') return '/api';
  return process.env.NEXT_PUBLIC_BFF_BASE_URL ?? 'http://localhost:8095';
}

function readToken() {
  if (typeof window === 'undefined') return null;
  try {
    return window.localStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

async function rbacRequest(path, init = {}) {
  const url = `${baseUrl()}${path}`;
  const token = readToken();
  const headers = {
    'Content-Type': 'application/json',
    Accept: 'application/json',
    ...(init.headers ?? {}),
  };
  if (token) headers.Authorization = `Bearer ${token}`;
  const res = await fetch(url, { ...init, headers });
  if (!res.ok) {
    let msg = `HTTP ${res.status}`;
    try {
      const body = await res.json();
      msg = body?.message ?? msg;
    } catch {
      /* ignore */
    }
    throw new Error(msg);
  }
  return res.json();
}

// ---------- Public API ----------

/**
 * GET /v1/admin/rbac/roles
 * Falls back to FIXTURE_ROLES when the backend is absent.
 * @returns {Promise<import('./rbacApi').RoleSummary[]>}
 */
export async function getRoles() {
  try {
    return await rbacRequest('/v1/admin/rbac/roles');
  } catch (e) {
    if (process.env.NODE_ENV !== 'test') {
      // eslint-disable-next-line no-console
      console.warn('[rbacApi] GET /roles fallback to fixture:', e.message);
    }
    return FIXTURE_ROLES;
  }
}

/**
 * GET /v1/admin/rbac/permissions
 * Falls back to FIXTURE_PERMISSIONS when the backend is absent.
 * @returns {Promise<import('./rbacApi').PermissionDef[]>}
 */
export async function getPermissions() {
  try {
    return await rbacRequest('/v1/admin/rbac/permissions');
  } catch (e) {
    if (process.env.NODE_ENV !== 'test') {
      // eslint-disable-next-line no-console
      console.warn('[rbacApi] GET /permissions fallback to fixture:', e.message);
    }
    return FIXTURE_PERMISSIONS;
  }
}

/**
 * PUT /v1/admin/rbac/roles/{role}/permissions
 * @param {string} role
 * @param {string[]} grants  Full permission list to assign to the role.
 * @returns {Promise<{ role: string, permissions: string[] }>}
 */
export async function putRolePermissions(role, grants) {
  return rbacRequest(`/v1/admin/rbac/roles/${encodeURIComponent(role)}/permissions`, {
    method: 'PUT',
    body: JSON.stringify({ grants }),
  });
}

/**
 * POST /v1/admin/rbac/roles
 * @param {{ name: string, basePermissions: string[] }} body
 * @returns {Promise<import('./rbacApi').RoleSummary>}
 */
export async function createRole(body) {
  return rbacRequest('/v1/admin/rbac/roles', {
    method: 'POST',
    body: JSON.stringify(body),
  });
}
