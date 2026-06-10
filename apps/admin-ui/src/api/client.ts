/**
 * Tiny fetch-based client for the Ops/Partner BFF.
 *
 * In the browser we use the relative path `/api/...`, which Next.js rewrites to
 * `${NEXT_PUBLIC_BFF_BASE_URL}/...` (see next.config.mjs). In tests / SSR the
 * env var is read directly. All non-2xx responses throw {@link ApiError}.
 *
 * Authorization: when a JWT is present in localStorage under
 * `gmepay.adminToken` (see ./auth.ts), every request automatically carries
 * `Authorization: Bearer <token>`. A 401 from the BFF clears the token and
 * redirects to /login.
 */
import type {
  AdminDashboard,
  DateRange,
  LoginRequest,
  LoginResponse,
  Page,
  PartnerCreateRequest,
  PartnerDetail,
  PartnerSummary,
  QrScheme,
  RecentTxn,
  RevenueBreakdown,
  RevenueSummary,
  RoundingMode,
  SettlementBatch,
  SettlementBatchDetail,
  TransactionDetail,
  TransactionSearchFilters,
  WebhookConfigView,
} from './types';
import { TOKEN_KEY } from './auth';

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

/** Read the auth token straight from localStorage to avoid an import cycle. */
function readToken(): string | null {
  if (typeof window === 'undefined') return null;
  try {
    return window.localStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

/**
 * Build the query-string from a flat object. Skips `undefined`/`null` values,
 * URI-encodes keys and values. Returns "" if no params; otherwise prepended `?`.
 */
function qs(params?: Record<string, string | number | undefined | null>): string {
  if (!params) return '';
  const pairs: string[] = [];
  for (const [k, v] of Object.entries(params)) {
    if (v === undefined || v === null || v === '') continue;
    pairs.push(`${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`);
  }
  return pairs.length === 0 ? '' : `?${pairs.join('&')}`;
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const url = `${baseUrl()}${path}`;
  const token = readToken();
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    Accept: 'application/json',
    ...((init?.headers as Record<string, string> | undefined) ?? {}),
  };
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }
  const res = await fetch(url, { ...init, headers });
  if (!res.ok) {
    // 401 -> clear the token; the AuthGate will bounce the user to /login on the
    // next render. We intentionally don't redirect here because the caller may
    // want to surface the error message (e.g. on the login page itself).
    if (res.status === 401 && typeof window !== 'undefined') {
      try {
        window.localStorage.removeItem(TOKEN_KEY);
      } catch {
        /* ignore */
      }
    }
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
  // ---------- Auth ----------
  login: (body: LoginRequest) =>
    request<LoginResponse>('/v1/auth/login', {
      method: 'POST',
      body: JSON.stringify(body),
    }),
  refreshToken: () => request<LoginResponse>('/v1/auth/refresh', { method: 'POST' }),

  // ---------- Dashboard ----------
  fetchDashboard: () => request<AdminDashboard>('/v1/admin/dashboard'),
  getDashboard: () => request<AdminDashboard>('/v1/admin/dashboard'),

  // ---------- Partners ----------
  listPartners: () => request<PartnerSummary[]>('/v1/admin/partners'),
  getPartner: (id: string) =>
    request<PartnerDetail>(`/v1/admin/partners/${encodeURIComponent(id)}`),
  createPartner: (body: PartnerCreateRequest) =>
    request<PartnerDetail>('/v1/admin/partners', {
      method: 'POST',
      body: JSON.stringify(body),
    }),
  updateRoundingMode: (id: string, mode: RoundingMode) =>
    request<PartnerDetail>(
      `/v1/admin/partners/${encodeURIComponent(id)}/rounding-mode`,
      { method: 'PUT', body: JSON.stringify({ mode }) },
    ),
  /** @deprecated alias retained for callers that still use the old name. */
  updatePartnerRoundingMode: (id: string, mode: RoundingMode) =>
    request<PartnerDetail>(
      `/v1/admin/partners/${encodeURIComponent(id)}/rounding-mode`,
      { method: 'PUT', body: JSON.stringify({ mode }) },
    ),

  // ---------- Schemes ----------
  listSchemes: () => request<QrScheme[]>('/v1/admin/schemes'),

  // ---------- Transactions ----------
  searchTransactions: (filters: TransactionSearchFilters) =>
    request<Page<RecentTxn>>(`/v1/admin/transactions${qs(filters as Record<string, string | number | undefined | null>)}`),
  getTransaction: (id: string) =>
    request<TransactionDetail>(`/v1/admin/transactions/${encodeURIComponent(id)}`),
  /** @deprecated kept so the home page can still render the original recent feed. */
  listRecentTxns: () => request<RecentTxn[]>('/v1/admin/transactions/recent'),

  // ---------- Settlement ----------
  listSettlements: () => request<SettlementBatch[]>('/v1/admin/settlement/recent'),
  getSettlement: (batchId: string) =>
    request<SettlementBatchDetail>(
      `/v1/admin/settlement/${encodeURIComponent(batchId)}`,
    ),
  /** @deprecated alias retained for the dashboard skeleton. */
  listSettlementBatches: () => request<SettlementBatch[]>('/v1/admin/settlement/recent'),

  // ---------- Revenue ----------
  getRevenueSummary: (range?: DateRange) =>
    request<RevenueSummary>(`/v1/admin/revenue/summary${qs(range as Record<string, string | undefined>)}`),
  getRevenueBreakdown: (range?: DateRange & { dimension?: 'partner' | 'scheme' | 'currency' }) =>
    request<RevenueBreakdown>(`/v1/admin/revenue/breakdown${qs(range as Record<string, string | undefined>)}`),
  /** @deprecated kept for the original skeleton call site. */
  fetchRevenueSummary: () => request<RevenueSummary>('/v1/admin/revenue/summary'),

  // ---------- Webhooks (read-only listing for now) ----------
  listWebhooks: () => request<WebhookConfigView[]>('/v1/admin/webhooks'),
};
