/**
 * Auth boilerplate for the admin-ui.
 *
 * Tokens are stored in localStorage under {@link TOKEN_KEY}. The {@link client}
 * fetch wrapper reads the token via {@link getToken} and injects an
 * `Authorization: Bearer <token>` header on every request. The {@link AuthGate}
 * client component in the root layout consults {@link isAuthenticated} on
 * mount and redirects unauthenticated users to /login.
 *
 * NOTE: localStorage is a deliberate phase-C choice for simplicity in the
 * skeleton; phase-D will move to httpOnly cookies issued by the BFF.
 */
import { adminApi } from './client';
import type { LoginRequest, LoginResponse } from './types';

export const TOKEN_KEY = 'gmepay.adminToken';
export const USER_KEY = 'gmepay.adminUser';

/** Read the stored bearer token, or null when running on the server / logged out. */
export function getToken(): string | null {
  if (typeof window === 'undefined') return null;
  try {
    return window.localStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

/** Whether the user has a token stored. Does NOT validate the token's signature. */
export function isAuthenticated(): boolean {
  return getToken() !== null;
}

/** Persist the token; safe-no-op on the server. */
export function setToken(token: string): void {
  if (typeof window === 'undefined') return;
  try {
    window.localStorage.setItem(TOKEN_KEY, token);
  } catch {
    /* quota / disabled — ignore */
  }
}

/** Clear the token + cached username. */
export function clearAuth(): void {
  if (typeof window === 'undefined') return;
  try {
    window.localStorage.removeItem(TOKEN_KEY);
    window.localStorage.removeItem(USER_KEY);
  } catch {
    /* ignore */
  }
}

/** Cached username for the AppShell header. */
export function getUsername(): string | null {
  if (typeof window === 'undefined') return null;
  try {
    return window.localStorage.getItem(USER_KEY);
  } catch {
    return null;
  }
}

/**
 * Exchange username/password for a JWT.
 *
 * Stores the token on success so the next request through {@link client}
 * picks it up automatically.
 */
export async function login(req: LoginRequest): Promise<LoginResponse> {
  const res = await adminApi.login(req);
  setToken(res.token);
  if (typeof window !== 'undefined') {
    try {
      window.localStorage.setItem(USER_KEY, res.username);
    } catch {
      /* ignore */
    }
  }
  return res;
}

/** Clear the token + redirect to /login. */
export function logout(): void {
  clearAuth();
  if (typeof window !== 'undefined') {
    window.location.assign('/login');
  }
}
