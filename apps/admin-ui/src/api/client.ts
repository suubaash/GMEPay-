/**
 * Tiny fetch-based client for the Ops/Partner BFF.
 *
 * In the browser we use the relative path `/api/...`, which Next.js rewrites to
 * `${NEXT_PUBLIC_BFF_BASE_URL}/...` (see next.config.mjs). In tests / SSR the
 * env var is read directly. All non-2xx responses throw {@link ApiError}.
 */
import type {
  AdminDashboard,
  PartnerCreateRequest,
  PartnerDetail,
  PartnerSummary,
  QrScheme,
  RecentTxn,
  RevenueSummary,
  RoundingMode,
  SettlementBatch,
} from './types';

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly url: string,
    message: string,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

function baseUrl(): string {
  // In the browser, prefer the rewritten /api path; on the server, hit the BFF directly.
  if (typeof window !== 'undefined') {
    return '/api';
  }
  return process.env.NEXT_PUBLIC_BFF_BASE_URL ?? 'http://localhost:8095';
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const url = `${baseUrl()}${path}`;
  const res = await fetch(url, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
      ...(init?.headers ?? {}),
    },
  });
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    throw new ApiError(res.status, url, text || `HTTP ${res.status}`);
  }
  // 204 No Content
  if (res.status === 204) {
    return undefined as unknown as T;
  }
  return (await res.json()) as T;
}

export const adminApi = {
  fetchDashboard: () => request<AdminDashboard>('/v1/admin/dashboard'),

  listPartners: () => request<PartnerSummary[]>('/v1/admin/partners'),
  getPartner: (id: string) =>
    request<PartnerDetail>(`/v1/admin/partners/${encodeURIComponent(id)}`),
  createPartner: (body: PartnerCreateRequest) =>
    request<PartnerDetail>('/v1/admin/partners', {
      method: 'POST',
      body: JSON.stringify(body),
    }),
  updatePartnerRoundingMode: (id: string, mode: RoundingMode) =>
    request<PartnerDetail>(
      `/v1/admin/partners/${encodeURIComponent(id)}/rounding-mode`,
      { method: 'PUT', body: JSON.stringify({ mode }) },
    ),

  listSchemes: () => request<QrScheme[]>('/v1/admin/schemes'),
  listRecentTxns: () => request<RecentTxn[]>('/v1/admin/transactions/recent'),
  listSettlementBatches: () =>
    request<SettlementBatch[]>('/v1/admin/settlement/recent'),
  fetchRevenueSummary: () => request<RevenueSummary>('/v1/admin/revenue/summary'),
};
