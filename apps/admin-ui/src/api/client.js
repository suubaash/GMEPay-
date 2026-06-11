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
    // Spring's default error JSON is `{timestamp, status, error, path, message?}` —
    // surface the message when present so the snackbar reads "partner X already
    // exists" rather than the whole envelope.
    let message = text || `HTTP ${res.status}`;
    if (text && text.trim().startsWith('{')) {
      try {
        const parsed = JSON.parse(text);
        message = parsed.message || parsed.error || message;
      } catch {
        /* leave as raw text */
      }
    }
    throw new ApiError(res.status, url, message);
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

  // ---------- Partner drafts (Slice 1, ADR-012) ----------
  /**
   * GET /v1/admin/partners/drafts -> PartnerView[]
   * Each PartnerView carries:
   *   { id, partnerCode, type, settlementCurrency, settlementRoundingMode,
   *     legalNameLocal, legalNameRomanized, taxId, taxIdType,
   *     countryOfIncorporation, legalForm, registeredAddress, operatingAddress,
   *     lei, status, validFrom, validTo, recordedAt }
   * Slice 1 returns rows in status=ONBOARDING.
   */
  listPartnerDrafts: () => request('/v1/admin/partners/drafts'),

  /**
   * POST /v1/admin/partners/draft
   * body: DraftPartnerRequest — all fields nullable; the wizard's
   *   "Start a new partner" step typically only sends { partnerCode }.
   * -> 201 PartnerView with status=ONBOARDING and bitemporal stamps populated.
   */
  createPartnerDraft: (body) =>
    request('/v1/admin/partners/draft', {
      method: 'POST',
      body: JSON.stringify(body ?? {}),
    }),

  /** GET /v1/admin/partners/draft/{partnerCode} -> PartnerView (404 when unknown). */
  getPartnerDraft: (partnerCode) =>
    request(`/v1/admin/partners/draft/${encodeURIComponent(partnerCode)}`),

  /**
   * Aliases requested by the wizard scaffold (agent 1D.1).
   *
   * These mirror the names used in the slice plan's API surface
   * (createDraft / getDraft / listDrafts) so the wizard imports read
   * naturally; the implementations are thin pass-throughs to the
   * canonical methods above. Keep both — older call sites use the
   * verbose `Partner*` names.
   */
  createDraft(body) {
    return this.createPartnerDraft(body);
  },
  getDraft(partnerCode) {
    return this.getPartnerDraft(partnerCode);
  },
  listDrafts() {
    return this.listPartnerDrafts();
  },

  /**
   * GET /v1/admin/partners/{partnerCode}/contacts
   * -> PartnerContactView[] (Slice 2, agent 2A.1 backend)
   * PartnerContactView: { id, role, name, email, phoneE164, isAuthorizedSignatory, notes }
   */
  getPartnerContacts: (partnerCode) =>
    request(`/v1/admin/partners/${encodeURIComponent(partnerCode)}/contacts`),

  /**
   * PATCH /v1/admin/partners/draft/{partnerCode}/step-{n}
   *  -> PartnerView with refreshed bitemporal stamps.
   *
   * Slice 1 only implements Step 1 (Identity) on the backend. To keep the
   * wizard from triggering noisy 404s when an operator clicks Next on a
   * stub step, calls with n in 2..8 are rejected client-side with an
   * {@link ApiError} status=501.
   *
   * @param {1|2|3|4|5|6|7|8} step
   * @param {string} partnerCode
   * @param {object} body — DraftPartnerStep{N}Request, see ops-partner-bff
   */
  patchDraftStep(step, partnerCode, body) {
    const n = Number(step);
    if (!Number.isInteger(n) || n < 1 || n > 8) {
      return Promise.reject(
        new ApiError(0, '', `patchDraftStep: invalid step ${step} (expected 1..8)`),
      );
    }
    if (n !== 1 && n !== 2) {
      return Promise.reject(
        new ApiError(
          501,
          `/v1/admin/partners/draft/${partnerCode}/step-${n}`,
          `Step ${n} is not implemented yet — coming in a later Slice.`,
        ),
      );
    }
    return request(
      `/v1/admin/partners/draft/${encodeURIComponent(partnerCode)}/step-${n}`,
      {
        method: 'PATCH',
        body: JSON.stringify(body ?? {}),
      },
    );
  },

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

  /**
   * GET /v1/admin/audit-trail?aggregateType=&aggregateId=&page=&size=
   * -> { entries:[{recordedAt,actorId,eventType,beforeJson,afterJson}],
   *      chainValid:boolean, page:number, size:number, total:number }
   *
   * Agent 2C.1 (backend) exposes this endpoint. chainValid is true when
   * the SHA-256 hash chain over all entries is intact (ADR-007).
   */
  getAuditTrail: (aggregateType, aggregateId, page = 0, size = 20) =>
    request(
      `/v1/admin/audit-trail${qs({ aggregateType, aggregateId, page, size })}`,
    ),

  // ---------- System health ----------
  /**
   * GET /v1/admin/system/health -> SystemHealth
   * { checkedAt:ISO, services:[ServiceHealth] }
   * ServiceHealth { name, status:"UP"|"DOWN"|"DEGRADED", lastSeenAt, uptimeSec }
   */
  getSystemHealth: () => request('/v1/admin/system/health'),

  // ---------- Change-request approvals (Slice 2, agent 2B.1 backend) ----------
  /**
   * GET /v1/admin/change-requests?state=PROPOSED
   * -> ChangeRequestSummary[]
   * ChangeRequestSummary: { id, aggregate, proposer, proposedAt, payload }
   *   aggregate: string (e.g. "Partner:GME_KR_001")
   *   proposer:  operator id / username who raised the request
   *   proposedAt: ISO-8601 instant
   *   payload:   object — whatever the PATCH step put into the change_request row
   */
  listPendingChangeRequests: () =>
    request('/v1/admin/change-requests?state=PROPOSED'),

  /**
   * POST /v1/admin/change-requests/{id}/approve
   * body: { approvedBy: string }
   * -> ChangeRequestSummary (state transitions to APPROVED)
   */
  approveChangeRequest: (id, approvedBy) =>
    request(`/v1/admin/change-requests/${encodeURIComponent(id)}/approve`, {
      method: 'POST',
      body: JSON.stringify({ approvedBy }),
    }),

  /**
   * POST /v1/admin/change-requests/{id}/reject
   * body: { rejectedBy: string, reason: string }
   * -> ChangeRequestSummary (state transitions to REJECTED)
   */
  rejectChangeRequest: (id, rejectedBy, reason) =>
    request(`/v1/admin/change-requests/${encodeURIComponent(id)}/reject`, {
      method: 'POST',
      body: JSON.stringify({ rejectedBy, reason }),
    }),
};
