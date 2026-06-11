/**
 * Tiny fetch-based client for the Ops/Partner BFF.
 *
 * In the browser we use the relative path `/api/...`, which Next.js rewrites to
 * `${NEXT_PUBLIC_BFF_BASE_URL}/...` (see next.config.mjs). In tests / SSR the
 * env var is read directly. All non-2xx responses throw {@link ApiError}.
 *
 * Authorization: when a JWT is present in localStorage under
 * `gmepay.adminToken` (see ./auth.js), every request automatically carries
 * `Authorization: Bearer <token>`. A 401 from the BFF clears the token and
 * the AuthGate then bounces the user to /login on the next render.
 *
 * NOTE: keep this file free of TS — JS only. Field names matching the BFF
 * are documented inline via JSDoc.
 */
import { TOKEN_KEY } from './auth';

/**
 * @typedef {Object} ApiErrorShape
 * @property {number} status
 * @property {string} url
 * @property {string} message
 */
export class ApiError extends Error {
  constructor(status, url, message) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.url = url;
  }
}

function baseUrl() {
  if (typeof window !== 'undefined') {
    return '/api';
  }
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

/**
 * Build the query-string from a flat object. Skips undefined/null/empty values,
 * URI-encodes keys and values. Returns "" if no params; otherwise prepended `?`.
 */
function qs(params) {
  if (!params) return '';
  const pairs = [];
  for (const [k, v] of Object.entries(params)) {
    if (v === undefined || v === null || v === '') continue;
    pairs.push(`${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`);
  }
  return pairs.length === 0 ? '' : `?${pairs.join('&')}`;
}

async function request(path, init) {
  const url = `${baseUrl()}${path}`;
  const token = readToken();
  const headers = {
    'Content-Type': 'application/json',
    Accept: 'application/json',
    ...(init && init.headers ? init.headers : {}),
  };
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }
  let res;
  try {
    res = await fetch(url, { ...init, headers });
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    throw new ApiError(0, url, msg || 'network error');
  }
  if (!res.ok) {
    if (res.status === 401 && typeof window !== 'undefined') {
      try {
        window.localStorage.removeItem(TOKEN_KEY);
      } catch {
        /* ignore */
      }
    }
    let text = '';
    try {
      text = await res.text();
    } catch {
      /* ignore */
    }
    throw new ApiError(res.status, url, text || `HTTP ${res.status}`);
  }
  if (res.status === 204) {
    return undefined;
  }
  return await res.json();
}

/**
 * Typed wrappers around every BFF endpoint the admin-ui hits. Field names in
 * comments below match what the BFF actually returns (verified against the
 * Java DTO sources under services/ops-partner-bff/).
 */
export const adminApi = {
  // ---------- Auth ----------
  /**
   * POST /v1/auth/login  body { username, password }
   * Returns { token, expiresAt, role }.
   */
  login: (body) =>
    request('/v1/auth/login', {
      method: 'POST',
      body: JSON.stringify(body),
    }),
  /**
   * POST /v1/auth/refresh body { token } -> { token, expiresAt, role }
   */
  refreshToken: (token) =>
    request('/v1/auth/refresh', {
      method: 'POST',
      body: JSON.stringify({ token }),
    }),

  // ---------- Dashboard ----------
  /**
   * GET /v1/admin/dashboard
   * Returns { recentTxnCount, partnerCount, lowBalanceCount, todayRevenueUsd }.
   * todayRevenueUsd is a BigDecimal — comes through as a JSON number from the
   * Java side; treat it as opaque numeric and format defensively.
   */
  fetchDashboard: () => request('/v1/admin/dashboard'),

  // ---------- Partners ----------
  /**
   * GET /v1/admin/partners -> PartnerSummary[]
   * PartnerSummary: { partnerId, type, settlementCurrency, settlementRoundingMode }
   */
  listPartners: () => request('/v1/admin/partners'),
  /** GET /v1/admin/partners/{id} -> PartnerSummary (404 when unknown). */
  getPartner: (id) =>
    request(`/v1/admin/partners/${encodeURIComponent(id)}`),
  /**
   * POST /v1/admin/partners body { partnerId, type, settlementCurrency, settlementRoundingMode }
   * -> 201 PartnerSummary.
   */
  createPartner: (body) =>
    request('/v1/admin/partners', {
      method: 'POST',
      body: JSON.stringify(body),
    }),
  /** PUT /v1/admin/partners/{id}/rounding-mode body { mode } -> PartnerSummary. */
  updateRoundingMode: (id, mode) =>
    request(`/v1/admin/partners/${encodeURIComponent(id)}/rounding-mode`, {
      method: 'PUT',
      body: JSON.stringify({ mode }),
    }),

  // ---------- Schemes ----------
  /**
   * GET /v1/admin/schemes -> SchemeSummary[]
   * SchemeSummary: { schemeId, name, country, currency, mode, status }
   */
  listSchemes: () => request('/v1/admin/schemes'),

  // ---------- Transactions ----------
  /**
   * GET /v1/admin/transactions?partnerId&schemeId&status&fromDate&toDate&page&size
   * -> Page<TransactionSummary> { content, page, size, total }
   * TransactionSummary: { txnId, partnerId, state, amount (string decimal), currency, committedAt }
   */
  searchTransactions: (filters) =>
    request(`/v1/admin/transactions${qs(filters)}`),
  /**
   * GET /v1/admin/transactions/{txnId} -> TransactionDetail
   * { summary: TransactionSummary, schemeTxnRef, schemeApprovalCode,
   *   prefundDeductedUsd, approvedAt, bookedSettlementAmount,
   *   settlementRoundingMode, roundingResidual }
   */
  getTransaction: (id) =>
    request(`/v1/admin/transactions/${encodeURIComponent(id)}`),
  /**
   * GET /v1/admin/transactions/recent -> TransactionSummary[]
   */
  listRecentTxns: () => request('/v1/admin/transactions/recent'),

  // ---------- Settlement ----------
  /**
   * GET /v1/admin/settlement/recent -> SettlementBatchSummary[]
   * { batchId, partnerId, settlementDate (LocalDate), currency, amount, status }
   */
  listSettlements: () => request('/v1/admin/settlement/recent'),
  /**
   * GET /v1/admin/settlement/{batchId} -> SettlementBatchDetail
   * { batch: SettlementBatchSummary, lines: [{ txnRef, amount, currency, matched }] }
   */
  getSettlement: (batchId) =>
    request(`/v1/admin/settlement/${encodeURIComponent(batchId)}`),

  // ---------- Revenue ----------
  /**
   * GET /v1/admin/revenue/summary?from=YYYY-MM-DD&to=YYYY-MM-DD -> RevenueSummary
   * { date, totalRevenueUsd, feeRevenueUsd, marginRevenueUsd }
   */
  getRevenueSummary: (range) =>
    request(`/v1/admin/revenue/summary${qs(range)}`),
  /**
   * GET /v1/admin/revenue/breakdown?from&to -> RevenueBreakdown
   * { byPartner: {string -> string}, byScheme: {...}, byCurrency: {...} }
   * The map VALUES are BigDecimal serialised as JSON strings.
   */
  getRevenueBreakdown: (range) =>
    request(`/v1/admin/revenue/breakdown${qs(range)}`),

  // ---------- Rate quote preview (manual operator quote) ----------
  /**
   * POST /v1/admin/rates/preview
   * body: { fromCcy, toCcy, amount, direction:"INBOUND"|"OUTBOUND", partnerId }
   * -> RateQuotePreview {
   *      collectionAmount, collectionCurrency,
   *      payoutAmount, payoutCurrency,
   *      collectionUsd, payoutUsdCost,
   *      collectionMarginUsd, payoutMarginUsd,
   *      offerRateColl, crossRate,
   *      shortCircuit:boolean, quotedAt:ISO
   *    }
   * All monetary fields are decimal strings; rates are decimal strings; the
   * shortCircuit flag is `true` when fromCcy === toCcy (no cross-rate path).
   */
  previewRate: (req) =>
    request('/v1/admin/rates/preview', {
      method: 'POST',
      body: JSON.stringify(req),
    }),

  // ---------- Audit log ----------
  /**
   * GET /v1/admin/audit?page=0&size=20 -> Page<AuditEntry>
   * Page<T>: { content, page, size, total }
   * AuditEntry: { id, actor, action, target, at:ISO, detail }
   */
  getAuditPage: (page, size) =>
    request(`/v1/admin/audit${qs({ page, size })}`),

  // ---------- System health ----------
  /**
   * GET /v1/admin/system/health -> SystemHealth
   * { checkedAt:ISO, services:[ServiceHealth] }
   * ServiceHealth { name, status:"UP"|"DOWN"|"DEGRADED", lastSeenAt, uptimeSec }
   */
  getSystemHealth: () => request('/v1/admin/system/health'),
};
