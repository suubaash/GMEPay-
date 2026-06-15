/**
 * usersApi — BFF calls for the Operator User Management page (/users).
 *
 * Endpoint contract (BFF → auth-identity service):
 *   GET    /v1/admin/users                        → UserSummary[]
 *   POST   /v1/admin/users/invite                 → UserSummary  (body: InviteUserRequest)
 *   PATCH  /v1/admin/users/{id}                   → UserSummary  (body: UpdateUserRequest)
 *   POST   /v1/admin/users/{id}/deactivate        → UserSummary
 *   POST   /v1/admin/users/{id}/reactivate        → UserSummary
 *
 * UserSummary wire shape:
 *   { id, name, email, roles: string[], status: 'ACTIVE'|'INVITED'|'DISABLED',
 *     lastLoginAt: ISO-8601 string | null }
 *
 * InviteUserRequest:  { email: string, roles: string[] }
 * UpdateUserRequest:  { roles: string[] }
 *
 * The backend (auth-identity) may not be deployed yet.  All methods tolerate
 * a network / HTTP error by returning the FIXTURE_USERS list so the page
 * remains demoable without a running backend.  The caller (usersSlice) is
 * responsible for surfacing a friendly error alongside the fixture data.
 */

const BASE = '/v1/admin/users';

/** Fixture list — returned as a fallback when the backend is absent. */
export const FIXTURE_USERS = [
  {
    id: 'u-001',
    name: 'Subash Sharma',
    email: 'subash@gmeremit.com',
    roles: ['ADMIN', 'OPS'],
    status: 'ACTIVE',
    lastLoginAt: '2026-06-15T09:12:00+09:00',
  },
  {
    id: 'u-002',
    name: 'Ji-yeon Park',
    email: 'jiyeon.park@gmeremit.com',
    roles: ['COMPLIANCE', 'FINANCE'],
    status: 'ACTIVE',
    lastLoginAt: '2026-06-14T17:45:00+09:00',
  },
  {
    id: 'u-003',
    name: 'Arjun Thapa',
    email: 'arjun.thapa@gmeremit.com',
    roles: ['OPS'],
    status: 'ACTIVE',
    lastLoginAt: '2026-06-13T11:00:00+09:00',
  },
  {
    id: 'u-004',
    name: 'Mei Lin',
    email: 'mei.lin@gmeremit.com',
    roles: ['READ_ONLY'],
    status: 'INVITED',
    lastLoginAt: null,
  },
  {
    id: 'u-005',
    name: 'Carlos Reyes',
    email: 'carlos.reyes@gmeremit.com',
    roles: ['OPS', 'FINANCE'],
    status: 'DISABLED',
    lastLoginAt: '2026-05-30T08:00:00+09:00',
  },
];

/**
 * Shared fetch wrapper.  Resolves to parsed JSON on 2xx; rejects with an
 * Error whose message is the HTTP status line on 4xx/5xx.
 */
async function apiFetch(url, options = {}) {
  const res = await fetch(url, {
    headers: { 'Content-Type': 'application/json', ...(options.headers ?? {}) },
    ...options,
  });
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    throw new Error(`HTTP ${res.status}: ${text || res.statusText}`);
  }
  return res.json();
}

/**
 * Fetch the full operator user list.
 * Falls back to FIXTURE_USERS when the backend is absent.
 * Returns { data: UserSummary[], fromFixture: boolean }.
 */
export async function listUsers() {
  try {
    const data = await apiFetch(BASE);
    return { data: Array.isArray(data) ? data : [], fromFixture: false };
  } catch (err) {
    return { data: FIXTURE_USERS, fromFixture: true, error: err.message };
  }
}

/**
 * Invite a new operator user.
 * body: { email: string, roles: string[] }
 */
export async function inviteUser(body) {
  return apiFetch(`${BASE}/invite`, {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

/**
 * Update a user's roles (or other mutable fields).
 * body: { roles: string[] }
 */
export async function updateUser(id, body) {
  return apiFetch(`${BASE}/${encodeURIComponent(id)}`, {
    method: 'PATCH',
    body: JSON.stringify(body),
  });
}

/**
 * Deactivate (DISABLE) an operator user.
 * Requires 4-eyes: the operator must not deactivate themselves.
 */
export async function deactivateUser(id) {
  return apiFetch(`${BASE}/${encodeURIComponent(id)}/deactivate`, {
    method: 'POST',
  });
}

/**
 * Reactivate a previously DISABLED operator user.
 */
export async function reactivateUser(id) {
  return apiFetch(`${BASE}/${encodeURIComponent(id)}/reactivate`, {
    method: 'POST',
  });
}
