'use client';

/**
 * Partner Portal auth helpers.
 *
 * Phase 1: simple token + partnerId stored in localStorage, sent as
 * `Authorization: Bearer <token>` and `X-Partner-Id: <partnerId>` headers.
 *
 * Phase 2 will replace this with proper OAuth2 / partner SSO and the token
 * will live in an httpOnly cookie. The hook surface (login/logout/getToken)
 * is stable across that change so callers don't need to be rewritten.
 *
 * Storage keys match the spec (`gmepay.partnerToken`, `gmepay.partnerId`).
 */

export const TOKEN_KEY = 'gmepay.partnerToken';
export const PARTNER_ID_KEY = 'gmepay.partnerId';

export interface LoginRequest {
  partnerId: string;
  password: string;
}

export interface LoginResponse {
  token: string;
  partnerId: string;
  expiresAt?: string;
}

function safeLocalStorage(): Storage | null {
  if (typeof window === 'undefined') return null;
  try {
    return window.localStorage;
  } catch {
    return null;
  }
}

/** Persisted bearer token, or null if not signed in / SSR. */
export function getToken(): string | null {
  const ls = safeLocalStorage();
  return ls ? ls.getItem(TOKEN_KEY) : null;
}

/** Persisted partner id, or null if not signed in / SSR. */
export function getPartnerId(): string | null {
  const ls = safeLocalStorage();
  return ls ? ls.getItem(PARTNER_ID_KEY) : null;
}

export function isAuthenticated(): boolean {
  return Boolean(getToken() && getPartnerId());
}

function setToken(token: string): void {
  const ls = safeLocalStorage();
  if (ls) ls.setItem(TOKEN_KEY, token);
}

function setPartnerId(id: string): void {
  const ls = safeLocalStorage();
  if (ls) ls.setItem(PARTNER_ID_KEY, id);
}

/**
 * Sign in. Calls `POST /v1/auth/login` via the BFF and, on success, stores
 * the token + partnerId. Throws on non-2xx with a `.status` field so the
 * login form can show "Invalid credentials" vs "Service unavailable".
 */
export async function login(req: LoginRequest): Promise<LoginResponse> {
  const base = process.env.NEXT_PUBLIC_BFF_BASE_URL || '';
  const url = base ? `${base}/v1/auth/login` : `/api/v1/auth/login`;
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: JSON.stringify(req),
    cache: 'no-store'
  });

  if (!res.ok) {
    let body: unknown;
    try {
      body = await res.json();
    } catch {
      body = await res.text();
    }
    const err = new Error(
      res.status === 401 || res.status === 403
        ? 'Invalid partner id or password'
        : `Login failed (HTTP ${res.status})`
    ) as Error & { status?: number; body?: unknown };
    err.status = res.status;
    err.body = body;
    throw err;
  }

  const data = (await res.json()) as LoginResponse;
  if (!data.token || !data.partnerId) {
    throw new Error('Login response missing token/partnerId');
  }
  setToken(data.token);
  setPartnerId(data.partnerId);
  return data;
}

/** Clear local credentials. Does not call the server (Phase 1). */
export function logout(): void {
  const ls = safeLocalStorage();
  if (!ls) return;
  ls.removeItem(TOKEN_KEY);
  ls.removeItem(PARTNER_ID_KEY);
}
