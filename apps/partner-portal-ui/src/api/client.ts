/**
 * Thin fetch wrapper for the Partner Portal -> BFF.
 *
 * Auth model:
 *  - Production: partner identity is established at the edge (api-gateway HMAC)
 *    and propagated server-side; the BFF reads the identity from the request session.
 *  - Local dev: we send `X-Partner-Id` from NEXT_PUBLIC_PARTNER_ID so a developer
 *    can switch partners by changing one env var. Production deploys MUST NOT
 *    trust this header.
 */
import type {
  BalanceDto,
  PagedResponse,
  PartnerProfileDto,
  TransactionDetailDto,
  TransactionSummaryDto,
  WebhookConfigDto
} from './types';

const BASE = process.env.NEXT_PUBLIC_BFF_BASE_URL || '';
const PARTNER_ID = process.env.NEXT_PUBLIC_PARTNER_ID || '';

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
  if (PARTNER_ID) headers['X-Partner-Id'] = PARTNER_ID;

  const res = await fetch(url(path), {
    ...init,
    headers,
    // Read-only Phase 1: no credentials/cookies needed yet.
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
  return (await res.json()) as T;
}

export const portalApi = {
  getBalance(partnerId: string): Promise<BalanceDto> {
    return request<BalanceDto>(`/v1/portal/${encodeURIComponent(partnerId)}/balance`);
  },

  listTransactions(
    partnerId: string,
    page = 0,
    size = 25
  ): Promise<PagedResponse<TransactionSummaryDto>> {
    const qs = new URLSearchParams({ page: String(page), size: String(size) });
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
  }
};

export function currentPartnerId(): string {
  return PARTNER_ID;
}
