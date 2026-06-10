/**
 * Thin fetch wrapper for the Partner Portal -> BFF.
 *
 * Auth model:
 *  - Production: partner identity is established at the edge (api-gateway HMAC)
 *    and propagated server-side; the BFF reads the identity from the request session.
 *  - Local dev / Phase 1: we send `Authorization: Bearer <token>` (from localStorage
 *    via `api/auth`) plus `X-Partner-Id` from the stored partner id (falling back
 *    to NEXT_PUBLIC_PARTNER_ID so a developer without a token can still browse).
 *    Production deploys MUST NOT trust the `X-Partner-Id` header.
 */
import type {
  BalanceDto,
  OverviewDto,
  PagedResponse,
  PartnerProfileDto,
  TransactionDetailDto,
  TransactionSummaryDto,
  WebhookConfigDto
} from './types';
import { getPartnerId, getToken, login as authLogin, logout as authLogout } from './auth';
import type { LoginRequest, LoginResponse } from './auth';

const BASE = process.env.NEXT_PUBLIC_BFF_BASE_URL || '';
const ENV_PARTNER_ID = process.env.NEXT_PUBLIC_PARTNER_ID || '';

function url(path: string): string {
  // When BASE is empty (browser dev), rely on next.config.mjs rewrite /api/* -> BFF.
  if (!BASE) return `/api${path}`;
  return `${BASE}${path}`;
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const headers: Record<string, string> = {
    Accept: 'application/json',
    ...(init?.headers as Record<string, string> | undefined)
  };
  const token = getToken();
  if (token) headers['Authorization'] = `Bearer ${token}`;

  const partnerId = getPartnerId() || ENV_PARTNER_ID;
  if (partnerId) headers['X-Partner-Id'] = partnerId;

  if (init?.body && !headers['Content-Type']) {
    headers['Content-Type'] = 'application/json';
  }

  const res = await fetch(url(path), {
    ...init,
    headers,
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
      `BFF ${init?.method || 'GET'} ${path} failed: ${res.status}`
    ) as Error & { status?: number; body?: unknown };
    err.status = res.status;
    err.body = body;
    throw err;
  }
  if (res.status === 204) return undefined as unknown as T;
  return (await res.json()) as T;
}

export const portalApi = {
  getOverview(partnerId: string): Promise<OverviewDto> {
    return request<OverviewDto>(`/v1/portal/${encodeURIComponent(partnerId)}/overview`);
  },

  getBalance(partnerId: string): Promise<BalanceDto> {
    return request<BalanceDto>(`/v1/portal/${encodeURIComponent(partnerId)}/balance`);
  },

  listTransactions(
    partnerId: string,
    page = 0,
    size = 25,
    sort = 'createdAt,desc'
  ): Promise<PagedResponse<TransactionSummaryDto>> {
    const qs = new URLSearchParams({
      page: String(page),
      size: String(size),
      sort
    });
    return request<PagedResponse<TransactionSummaryDto>>(
      `/v1/portal/${encodeURIComponent(partnerId)}/transactions?${qs.toString()}`
    );
  },

  getTransaction(partnerId: string, txnId: string): Promise<TransactionDetailDto> {
    return request<TransactionDetailDto>(
      `/v1/portal/${encodeURIComponent(partnerId)}/transactions/${encodeURIComponent(txnId)}`
    );
  },

  listWebhooks(partnerId: string): Promise<WebhookConfigDto[]> {
    return request<WebhookConfigDto[]>(
      `/v1/portal/${encodeURIComponent(partnerId)}/webhooks`
    );
  },

  getProfile(partnerId: string): Promise<PartnerProfileDto> {
    return request<PartnerProfileDto>(`/v1/portal/${encodeURIComponent(partnerId)}/profile`);
  },

  login(req: LoginRequest): Promise<LoginResponse> {
    return authLogin(req);
  },

  /** Phase 1 stub — placeholder for token refresh wiring. */
  refreshToken(): Promise<LoginResponse> {
    return Promise.reject(new Error('refreshToken not implemented in Phase 1'));
  },

  logout(): void {
    authLogout();
  }
};

/**
 * The active partner id used by the UI. Prefers the persisted (logged-in)
 * value, falls back to the dev env var so a fresh checkout can still browse
 * without going through the login screen.
 */
export function currentPartnerId(): string {
  return getPartnerId() || ENV_PARTNER_ID;
}
