/**
 * usersApi — BFF calls for the Operator User Management page (/users).
 *
 * Endpoint contract (admin-ui → BFF → auth-identity service). Note the browser
 * MUST hit these under the `/api` prefix so Next.js rewrites them to the BFF
 * (see next.config.mjs — the `/api/:path*` rewrite). A bare `/v1/...` path is
 * served by the Next app itself and returns its 404 not-found HTML page.
 *   GET    /api/v1/admin/users                    → UserSummary[]
 *   POST   /api/v1/admin/users/invite             → UserSummary  (body: InviteUserRequest)
 *   PATCH  /api/v1/admin/users/{id}               → UserSummary  (body: UpdateUserRequest)
 *   POST   /api/v1/admin/users/{id}/deactivate    → UserSummary
 *   POST   /api/v1/admin/users/{id}/reactivate    → UserSummary
 *
 * UserSummary wire shape:
 *   { id, name, email, roles: string[], status: 'ACTIVE'|'INVITED'|'DISABLED',
 *     lastLoginAt: ISO-8601 string | null }
 *
 * InviteUserRequest:  { email: string, roles: string[] }
 * UpdateUserRequest:  { roles: string[] }
 *
 * The backend (auth-identity users controller) may not be deployed yet. The GET
 * tolerates a network / HTTP error by returning the FIXTURE_USERS list so the
 * page renders; the mutation thunks (usersSlice) apply an optimistic local
 * change when the page is already in fixture/demo mode. The caller is
 * responsible for surfacing a friendly error alongside the fixture data.
 */

import { TOKEN_KEY } from '@/api/auth';

// Browser calls go through the same-origin `/api` rewrite to the BFF; SSR/tests
// read the BFF base URL directly (mirrors @/api/client and @/api/rbacApi).
function baseUrl() {
  if (typeof window !== 'undefined') return '/api';
  return process.env.NEXT_PUBLIC_BFF_BASE_URL ?? 'http://127.0.0.1:8095';
}

const PATH = '/v1/admin/users';

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

function readToken() {
  if (typeof window === 'undefined') return null;
  try {
    return window.localStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

/**
 * Shared fetch wrapper.  Resolves to parsed JSON on 2xx; rejects with an Error
 * whose message is `HTTP <status>: <detail>` on 4xx/5xx. The detail is the
 * BFF's Spring error `message` when the body is JSON — never a raw HTML page
 * (a Next.js 404 dump would otherwise flood the error snackbar).
 *
 * @param {string} path  relative path under the users resource, e.g. '' or '/invite'
 */
async function apiFetch(path, options = {}) {
  const token = readToken();
  const headers = {
    'Content-Type': 'application/json',
    Accept: 'application/json',
    ...(options.headers ?? {}),
  };
  if (token) headers.Authorization = `Bearer ${token}`;
  const res = await fetch(`${baseUrl()}${PATH}${path}`, { ...options, headers });
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    let detail = res.statusText;
    if (text && text.trim().startsWith('{')) {
      try {
        const parsed = JSON.parse(text);
        detail = parsed.message || parsed.error || detail;
      } catch {
        /* leave as statusText */
      }
    }
    throw new Error(`HTTP ${res.status}: ${detail || 'request failed'}`);
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
    const data = await apiFetch('');
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
  return apiFetch('/invite', {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

/**
 * Update a user's roles (or other mutable fields).
 * body: { roles: string[] }
 */
export async function updateUser(id, body) {
  return apiFetch(`/${encodeURIComponent(id)}`, {
    method: 'PATCH',
    body: JSON.stringify(body),
  });
}

/**
 * Deactivate (DISABLE) an operator user.
 * Requires 4-eyes: the operator must not deactivate themselves.
 */
export async function deactivateUser(id) {
  return apiFetch(`/${encodeURIComponent(id)}/deactivate`, {
    method: 'POST',
  });
}

/**
 * Reactivate a previously DISABLED operator user.
 */
export async function reactivateUser(id) {
  return apiFetch(`/${encodeURIComponent(id)}/reactivate`, {
    method: 'POST',
  });
}
