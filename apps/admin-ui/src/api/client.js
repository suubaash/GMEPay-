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
import { startRequest, endRequest } from './requestLog';

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
  return _doFetch(url, { ...init, headers });
}

/**
 * Multipart POST — does NOT set Content-Type (the browser must set it with
 * the correct multipart boundary). Passes the FormData body as-is.
 *
 * @param {string} path   Relative path, e.g. "/v1/admin/partners/ABC/documents"
 * @param {FormData} formData
 * @returns {Promise<object>}
 */
async function multipartRequest(path, formData) {
  const url = `${baseUrl()}${path}`;
  const token = readToken();
  // Only set Authorization; let the browser set Content-Type with boundary.
  const headers = { Accept: 'application/json' };
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }
  return _doFetch(url, { method: 'POST', body: formData, headers });
}

async function _doFetch(url, init) {
  // Record into requestLog so the role-gated RequestInspector overlay can show
  // the live request/response (see ./requestLog.js + components/RequestInspector).
  const startedAt = Date.now();
  const logId = startRequest({
    method: (init && init.method) || 'GET',
    url,
    reqBody: init && init.body,
  });
  let res;
  try {
    res = await fetch(url, init);
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    endRequest(logId, { status: 0, error: msg || 'network error', durationMs: Date.now() - startedAt });
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
    endRequest(logId, { status: res.status, resBody: text, error: message, durationMs: Date.now() - startedAt });
    throw new ApiError(res.status, url, message);
  }
  if (res.status === 204) {
    endRequest(logId, { status: 204, resBody: undefined, durationMs: Date.now() - startedAt });
    return undefined;
  }
  const json = await res.json();
  endRequest(logId, { status: res.status, resBody: json, durationMs: Date.now() - startedAt });
  return json;
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
    if (n !== 1 && n !== 2 && n !== 3 && n !== 4 && n !== 5 && n !== 6 && n !== 7) {
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

  // ---------- KYB (Slice 3, ADR-009 KybProvider port) ----------
  /**
   * GET /v1/admin/partners/{code}/kyb -> KybView
   * KybView: { partnerCode, riskRating, riskRationale, nextReviewDate,
   *   licenseType, licenseNumber, licenseAuthority, licenseExpiry,
   *   uboList:[{name,ownershipPct,isPep,country}], cbddqDocId,
   *   screeningStatus:'CLEAR'|'NEEDS_REVIEW'|'HIT'|null,
   *   screeningProviderRef:string|null, screenedAt:ISO|null,
   *   screeningHits:[{name,matchScore,matchType,source}]|null }
   */
  getKyb: (partnerCode) =>
    request(`/v1/admin/partners/${encodeURIComponent(partnerCode)}/kyb`),

  /**
   * POST /v1/admin/partners/{code}/kyb/screen -> KybView (refreshed)
   * Triggers AML/PEP screening via the KybProvider port (ADR-009).
   * Stubbed until Octa Solution sandbox creds arrive (ADR-014).
   */
  runKybScreening: (partnerCode) =>
    request(
      `/v1/admin/partners/${encodeURIComponent(partnerCode)}/kyb/screen`,
      { method: 'POST', body: JSON.stringify({}) },
    ),

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

  // ---------- Prefunding config (Slice 5A.1 backend) ----------
  /**
   * GET /v1/admin/partners/draft/{partnerCode}/prefunding-config
   * -> PrefundingConfigView {
   *      fundingModel:            'PREFUNDED'|'POSTPAID'|'HYBRID',
   *      openingBalanceUsd:       string (decimal),
   *      lowBalanceThresholdUsd:  string (decimal),
   *      alertTier70:             boolean,
   *      alertTier85:             boolean,
   *      alertTier95:             boolean,
   *      creditLimitUsd:          string (decimal),
   *      autoSuspendOnBreach:     boolean,
   *      floatTopUpBankAccountId: string | null,
   *      topUpReferencePattern:   string,
   *      collateralAmountUsd:     string (decimal),
   *    }
   *
   * Money fields are decimal strings per docs/MONEY_CONVENTION.md.
   */
  getPrefundingConfig: (partnerCode) =>
    request(
      `/v1/admin/partners/draft/${encodeURIComponent(partnerCode)}/prefunding-config`,
    ),

  // ---------- Commercial terms (Slice 6B.1 backend) ----------
  /**
   * GET /v1/admin/partners/draft/{partnerCode}/commercial
   * -> CommercialTermsView {
   *      feeSchedule: { scheme, direction, fixedFeeUsd, bpsFee,
   *                     tiers:[{fromVolumeUsd, bpsOverride}] },
   *      fxConfig:    { marginBps, referenceRateSource, quoteHoldSeconds },
   *      limits:      { perTxnMinUsd, perTxnMaxUsd, dailyCapUsd,
   *                     monthlyCapUsd, annualCapUsd, licenseType },
   *      contract:    { effectiveFrom, effectiveTo, autoRenewal,
   *                     noticePeriodDays, refundChargebackPolicy,
   *                     terminationReason }
   *    }
   *
   * Money fields are decimal strings per docs/MONEY_CONVENTION.md.
   */
  getCommercialTerms: (partnerCode) =>
    request(
      `/v1/admin/partners/draft/${encodeURIComponent(partnerCode)}/commercial`,
    ),

  /**
   * PATCH /v1/admin/partners/draft/{partnerCode}/step-6-commercial
   * body: { feeSchedule, fxConfig, limits, contract }
   * -> PartnerView with refreshed bitemporal stamps.
   *
   * Slice 6B.1 backend (agent 6B.1) exposes this endpoint. The generic
   * patchDraftStep route (n=6) calls this same path; this alias provides a
   * more readable call site for the commercialTermsSlice thunk.
   */
  patchDraftStep6Commercial: (partnerCode, body) =>
    request(
      `/v1/admin/partners/draft/${encodeURIComponent(partnerCode)}/step-6-commercial`,
      { method: 'PATCH', body: JSON.stringify(body ?? {}) },
    ),

  // ---------- Pricing rules (Slice 6A.1 backend) ----------------------------
  /**
   * GET /v1/admin/partners/{partnerCode}/rules
   * -> RuleView[]
   *
   * RuleView: {
   *   id:               number,
   *   schemeId:         string,
   *   direction:        'INBOUND' | 'OUTBOUND' | 'BOTH',
   *   mA:               string (decimal fraction, e.g. "0.0150" = 1.50%),
   *   mB:               string (decimal fraction),
   *   serviceChargeUsd: string (decimal),
   *   validFrom:        ISO instant,
   *   validTo:          ISO instant | null,
   *   recordedAt:       ISO instant
   * }
   *
   * A partner with no rules returns an empty array. An unknown partner
   * surfaces a 404 ApiError.
   * Slice 6A.1 backend (agent 6A.1) exposes this endpoint.
   */
  getRules: (partnerCode) =>
    request(`/v1/admin/partners/${encodeURIComponent(partnerCode)}/rules`),

  /**
   * PATCH /v1/admin/partners/draft/{partnerCode}/step-6-rules
   * body: { rules: RuleCommand[] }
   * -> RuleView[] (fresh current set after bulk replace)
   *
   * RuleCommand: { schemeId, direction, mA, mB, serviceChargeUsd }
   * Margins and money are decimal STRINGS on the wire per
   * docs/MONEY_CONVENTION.md. Bulk-replace semantics: the FULL desired rule
   * set must be sent; an empty array clears all rules.
   * Returns 200 with the fresh set; 400 on validation failure (bad direction /
   * negative margins / duplicate keys / lib-domain mA+mB>=2% invariant for
   * cross-border pairs); 404 unknown draft; 409 partner not ONBOARDING.
   * Slice 6A.1 backend (agent 6A.1) exposes this endpoint.
   *
   * NOTE: do NOT remove step 6 from the 501 list in patchDraftStep — the
   * generic step-6 route is still guarded there. This method calls the
   * dedicated step-6-rules sub-resource, which is always available when 6A.1
   * has landed.
   *
   * @param {string} partnerCode
   * @param {Array}  rules  Full desired rule set (bulk replace). Empty array = clear.
   */
  patchDraftStep6Rules: (partnerCode, rules) =>
    request(
      `/v1/admin/partners/draft/${encodeURIComponent(partnerCode)}/step-6-rules`,
      { method: 'PATCH', body: JSON.stringify({ rules: rules ?? [] }) },
    ),

  // ---------- Step-7: Schemes & Corridors (Slice 7) ----------
  /**
   * GET /v1/admin/partners/draft/{partnerCode}/step-7/schemes
   * -> PartnerSchemeView[]
   * PartnerSchemeView: {
   *   schemeId, enabled, direction, role,
   *   zeropayMerchantId, zeropaySubMerchantId, kftcInstitutionCode,
   *   partnerTypeChar, approvalMethodCpm, approvalMethodMpm
   * }
   */
  listPartnerSchemes: (partnerCode) =>
    request(
      `/v1/admin/partners/draft/${encodeURIComponent(partnerCode)}/step-7/schemes`,
    ),

  /**
   * GET /v1/admin/partners/draft/{partnerCode}/step-7/corridors
   * -> PartnerCorridorView[]
   * PartnerCorridorView: { id, srcCountry, srcCcy, dstCountry, dstCcy,
   *   goLiveDate, active }
   */
  listPartnerCorridors: (partnerCode) =>
    request(
      `/v1/admin/partners/draft/${encodeURIComponent(partnerCode)}/step-7/corridors`,
    ),

  /**
   * PATCH /v1/admin/partners/draft/{partnerCode}/step-7-schemes
   * body: { schemes: PartnerSchemeCommand[] }
   * -> PartnerView with refreshed bitemporal stamps.
   *
   * PartnerSchemeCommand: {
   *   schemeId, enabled, direction, role,
   *   zeropayMerchantId?, zeropaySubMerchantId?, kftcInstitutionCode?,
   *   partnerTypeChar?, approvalMethodCpm?, approvalMethodMpm?
   * }
   */
  patchDraftStep7Schemes: (partnerCode, body) =>
    request(
      `/v1/admin/partners/draft/${encodeURIComponent(partnerCode)}/step-7-schemes`,
      { method: 'PATCH', body: JSON.stringify(body ?? {}) },
    ),

  /**
   * PATCH /v1/admin/partners/draft/{partnerCode}/step-7-corridors
   * body: { corridors: PartnerCorridorCommand[] }
   * -> PartnerView with refreshed bitemporal stamps.
   *
   * PartnerCorridorCommand: {
   *   srcCountry, srcCcy, dstCountry, dstCcy, goLiveDate, active
   * }
   */
  patchDraftStep7Corridors: (partnerCode, body) =>
    request(
      `/v1/admin/partners/draft/${encodeURIComponent(partnerCode)}/step-7-corridors`,
      { method: 'PATCH', body: JSON.stringify(body ?? {}) },
    ),

  /**
   * GET /v1/admin/schemes/{schemeId}/operating-hours
   * -> OperatingHoursView[]
   * OperatingHoursView: {
   *   dayOfWeek: 'MON'|'TUE'|'WED'|'THU'|'FRI'|'SAT'|'SUN',
   *   openTime:  'HH:mm' | null,
   *   closeTime: 'HH:mm' | null,
   *   closed:    boolean
   * }
   */
  listSchemeOperatingHours: (schemeId) =>
    request(
      `/v1/admin/schemes/${encodeURIComponent(schemeId)}/operating-hours`,
    ),

  // ---------- Step-8: Regulatory & Credentials (Slice 8) ----------
  /**
   * GET /v1/admin/partners/{code}/lifecycle/preconditions
   * -> PreconditionView[]
   * PreconditionView: { key: string, description: string, met: boolean }
   */
  getActivationPreconditions: (partnerCode) =>
    request(
      `/v1/admin/partners/${encodeURIComponent(partnerCode)}/lifecycle/preconditions`,
    ),

  /**
   * POST /v1/admin/partners/{code}/lifecycle/activate  (first-operator click)
   * -> 202 { status: 'PROPOSED', proposedAt: ISO }
   */
  proposePartnerActivation: (partnerCode) =>
    request(
      `/v1/admin/partners/${encodeURIComponent(partnerCode)}/lifecycle/activate`,
      { method: 'POST', body: JSON.stringify({}) },
    ),

  /**
   * POST /v1/admin/partners/{code}/lifecycle/activate  (second-operator confirm)
   * -> 201 IssuedCredentialBundle {
   *      keyId, keyPrefix, keyLast4,
   *      plaintextApiKey, plaintextHmac, plaintextWebhookSecret
   *    }
   * NOTE: plaintextApiKey / plaintextHmac / plaintextWebhookSecret are
   * returned EXACTLY ONCE and must never be stored anywhere except the
   * operator's secret manager. The Redux store holds them only long enough
   * for the OneTimeCredentialModal to display; dismissBundle() wipes them.
   */
  executePartnerActivation: (partnerCode) =>
    request(
      `/v1/admin/partners/${encodeURIComponent(partnerCode)}/lifecycle/activate`,
      { method: 'POST', body: JSON.stringify({}) },
    ),

  /**
   * PATCH /v1/admin/partners/draft/{partnerCode}/step-8/ip-allowlist
   * body: { env: 'sandbox'|'production', cidrs: string[] }
   * -> PartnerView
   */
  patchDraftStep8IpAllowlist: (partnerCode, body) =>
    request(
      `/v1/admin/partners/draft/${encodeURIComponent(partnerCode)}/step-8/ip-allowlist`,
      { method: 'PATCH', body: JSON.stringify(body ?? {}) },
    ),

  /**
   * PATCH /v1/admin/partners/draft/{partnerCode}/step-8/mtls-cert
   * body: { pemCertificate: string }
   * -> MtlsCertParsedView { subjectDn, issuerDn, notBefore, notAfter, fingerprint }
   */
  patchDraftStep8MtlsCert: (partnerCode, body) =>
    request(
      `/v1/admin/partners/draft/${encodeURIComponent(partnerCode)}/step-8/mtls-cert`,
      { method: 'PATCH', body: JSON.stringify(body ?? {}) },
    ),

  /**
   * PATCH /v1/admin/partners/draft/{partnerCode}/step-8/regulatory
   * body: {
   *   bok:        { txnCode, fxReportingCategory, remitterType },
   *   hometax:    { hometaxIssuerCertId, vatTreatment },
   *   kofiu:      { kofiuEntityId, ctrThresholdKrw },        // BigDecimal strings
   *   pipa:       { pipaJurisdictionAllowlist: string[] },
   *   travelRule: { protocol, endpointUrl, thresholdKrw }    // BigDecimal string
   * }
   * -> PartnerView
   */
  patchDraftStep8Regulatory: (partnerCode, body) =>
    request(
      `/v1/admin/partners/draft/${encodeURIComponent(partnerCode)}/step-8/regulatory`,
      { method: 'PATCH', body: JSON.stringify(body ?? {}) },
    ),

  /**
   * PATCH /v1/admin/partners/draft/{partnerCode}/step-8/webhook-subscription
   * body: { url: string, eventTypes: string[] }
   * -> PartnerView
   */
  patchDraftStep8WebhookSubscription: (partnerCode, body) =>
    request(
      `/v1/admin/partners/draft/${encodeURIComponent(partnerCode)}/step-8/webhook-subscription`,
      { method: 'PATCH', body: JSON.stringify(body ?? {}) },
    ),

  // ---------- Settlement exceptions (UC-04-03, BS-04) ----------
  /**
   * GET /v1/settlement/exceptions?batchId=&exceptionStatus=&matchStatus=
   * -> ReconExceptionResponse[]
   *
   * ReconExceptionResponse:
   *   { id, batchId, merchantId,
   *     gmeAmount (BigDecimal string), schemeAmount (BigDecimal|null),
   *     discrepancyAmount (BigDecimal string),
   *     matchStatus: DISCREPANCY|MISSING_SCHEME|MISSING_INTERNAL,
   *     exceptionStatus: OPEN|RESOLVED|RE_RUN,
   *     operatorId:string|null, resolutionNote:string|null,
   *     resolutionAction:string|null, resolvedAt:ISO|null, createdAt:ISO }
   *
   * Money fields are BigDecimal serialised as strings. Never Number()-cast.
   */
  listReconExceptions: (filters) =>
    request(`/v1/settlement/exceptions${qs(filters)}`),

  /**
   * POST /v1/settlement/exceptions/{id}/resolve
   * body: { operatorId, note, resolutionAction }
   * -> ReconExceptionResponse (updated row with exceptionStatus=RESOLVED)
   */
  resolveReconException: (id, body) =>
    request(`/v1/settlement/exceptions/${encodeURIComponent(id)}/resolve`, {
      method: 'POST',
      body: JSON.stringify(body),
    }),

  /**
   * POST /v1/settlement/exceptions/{id}/re-run
   * body: { operatorId }
   * -> ReconExceptionResponse (updated row with exceptionStatus=RE_RUN)
   */
  reRunReconException: (id, body) =>
    request(`/v1/settlement/exceptions/${encodeURIComponent(id)}/re-run`, {
      method: 'POST',
      body: JSON.stringify(body),
    }),

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

  // ---------- Document vault (Slice 3A.2, ADR-006 MinIO) ----------
  /**
   * GET /v1/admin/partners/{partnerCode}/documents -> DocumentView[]
   * DocumentView: {
   *   id, docType, filename, contentType, version, sha256,
   *   expiryDate (YYYY-MM-DD|null), verifiedBy (string|null),
   *   verifiedAt (ISO|null), recordedAt (ISO)
   * }
   */
  getDocuments: (partnerCode) =>
    request(`/v1/admin/partners/${encodeURIComponent(partnerCode)}/documents`),

  /**
   * POST /v1/admin/partners/{partnerCode}/documents (multipart/form-data)
   * FormData fields: file (Blob), docType (string), expiryDate? (YYYY-MM-DD)
   * -> 201 DocumentView
   *
   * IMPORTANT: Do NOT set Content-Type manually — the browser must set it
   * with the multipart boundary so the server can parse the body.
   */
  uploadDocument: (partnerCode, formData) =>
    multipartRequest(
      `/v1/admin/partners/${encodeURIComponent(partnerCode)}/documents`,
      formData,
    ),

  /**
   * Returns the URL to GET the raw binary content of a document.
   * Consumers can use this as an <a href> for direct browser download or
   * pass it to fetch() themselves.
   *
   * GET /v1/admin/partners/{partnerCode}/documents/{docId}/content
   */
  downloadDocumentUrl: (partnerCode, docId) =>
    `${baseUrl()}/v1/admin/partners/${encodeURIComponent(partnerCode)}/documents/${encodeURIComponent(docId)}/content`,

  // ---------- Bank accounts (Slice 4A.1 backend) ----------
  /**
   * GET /v1/admin/partners/{partnerCode}/bank-accounts -> BankAccountView[]
   * BankAccountView: {
   *   id, currency, bankName, bicSwift, ibanOrAccountNumber,
   *   accountHolderName, bankCountry, intermediaryBic,
   *   swiftChargeBearer: 'OUR'|'BEN'|'SHA',
   *   purpose: 'PAYOUT'|'FLOAT_TOPUP'|'REFUND',
   *   isPrimary,
   *   verificationStatus: 'UNVERIFIED'|'KFTC_VERIFIED'|'BANK_LETTER'|'MICRO_DEPOSIT',
   *   verificationDate: ISO date string|null
   * }
   */
  getBankAccounts: (partnerCode) =>
    request(`/v1/admin/partners/${encodeURIComponent(partnerCode)}/bank-accounts`),

  /**
   * POST /v1/admin/partners/{partnerCode}/bank-accounts/{accountId}/verify
   * -> BankAccountView (refreshed with updated verificationStatus and verificationDate)
   */
  verifyBankAccount: (partnerCode, accountId) =>
    request(
      `/v1/admin/partners/${encodeURIComponent(partnerCode)}/bank-accounts/${encodeURIComponent(accountId)}/verify`,
      { method: 'POST', body: JSON.stringify({}) },
    ),

  // ---------- Settlement config (Slice 4B.1 backend) ----------
  /**
   * GET /v1/admin/partners/draft/{partnerCode}/settlement-config
   * -> SettlementConfigView {
   *      cycleTPlusN: number,          // 0..5
   *      cutoffTime: string,           // "HH:mm"
   *      cutoffTimezone: string,       // e.g. "Asia/Seoul"
   *      settlementMethod: string      // SWIFT|ACH|FPS|RTGS|SEPA|CHAPS|OTHER
   *    }
   */
  getSettlementConfig: (partnerCode) =>
    request(
      `/v1/admin/partners/draft/${encodeURIComponent(partnerCode)}/settlement-config`,
    ),

  /**
   * GET /v1/admin/partners/draft/{partnerCode}/settlement-preview?txnInstant=ISO
   * -> SettlementPreviewView {
   *      payoutDate: string,          // "YYYY-MM-DD"
   *      explanation: string[]        // e.g. ["Sat: skip", "Sun: skip", "Mon: payout"]
   *    }
   */
  getSettlementPreview: (partnerCode, txnInstant) =>
    request(
      `/v1/admin/partners/draft/${encodeURIComponent(partnerCode)}/settlement-preview${qs({ txnInstant })}`,
    ),

  /**
   * PATCH /v1/admin/partners/draft/{partnerCode}/step-4-settlement
   * body: { cycleTPlusN, cutoffTime, cutoffTimezone, settlementMethod }
   * -> PartnerView with refreshed bitemporal stamps.
   *
   * This differs from the generic patchDraftStep path because the settlement
   * sub-step is a separate PATCH endpoint (Slice 4B.1 backend, agent 4B.1).
   */
  patchDraftStep4Settlement: (partnerCode, body) =>
    request(
      `/v1/admin/partners/draft/${encodeURIComponent(partnerCode)}/step-4-settlement`,
      { method: 'PATCH', body: JSON.stringify(body ?? {}) },
    ),

  // ---------- Partner lifecycle FSM (Slice 8) ----------
  /**
   * POST /v1/admin/partners/{code}/lifecycle/propose
   * body: { action:'SUSPEND'|'REACTIVATE'|'TERMINATE', reason, notes? }
   * -> LifecycleChangeRequestView { changeRequestId, action, status, proposedBy, proposedAt }
   */
  proposeLifecycleTransition: (partnerCode, body) =>
    request(
      `/v1/admin/partners/${encodeURIComponent(partnerCode)}/lifecycle/propose`,
      { method: 'POST', body: JSON.stringify(body ?? {}) },
    ),

  /**
   * POST /v1/admin/partners/{code}/lifecycle/execute
   * body: { changeRequestId }
   * -> PartnerView with updated status
   */
  executeLifecycleTransition: (partnerCode, changeRequestId) =>
    request(
      `/v1/admin/partners/${encodeURIComponent(partnerCode)}/lifecycle/execute`,
      { method: 'POST', body: JSON.stringify({ changeRequestId }) },
    ),

  // ---------- Partner credentials (Slice 8) ----------
  /**
   * GET /v1/admin/partners/{code}/credentials
   * -> PartnerCredentialView[] { id, env, kind, prefix, last4, issuedAt, expiresAt, status }
   */
  getPartnerCredentials: (partnerCode) =>
    request(`/v1/admin/partners/${encodeURIComponent(partnerCode)}/credentials`),

  /**
   * POST /v1/admin/partners/{code}/credentials/rotate
   * body: { credentialId }
   * -> OneTimeCredentialView { id, env, kind, prefix, last4, issuedAt, expiresAt,
   *                            plaintextSecret, allCredentials:[PartnerCredentialView] }
   * plaintextSecret is shown once — never persist.
   */
  rotatePartnerCredential: (partnerCode, credentialId) =>
    request(
      `/v1/admin/partners/${encodeURIComponent(partnerCode)}/credentials/rotate`,
      { method: 'POST', body: JSON.stringify({ credentialId }) },
    ),

  /**
   * GET /v1/admin/partners/{code}/audit?page=&size=
   * -> Page<AuditEntry> { content, page, size, total }
   * Scoped to one partner; mirrors global audit but filtered by partnerCode.
   */
  getPartnerAuditPage: (partnerCode, page = 0, size = 20) =>
    request(
      `/v1/admin/partners/${encodeURIComponent(partnerCode)}/audit${qs({ page, size })}`,
    ),

  // ---------- Prefunding balance (Slice 5B.1 backend) ----------
  /**
   * GET /v1/admin/partners/{partnerCode}/balance -> BalanceView
   * BalanceView: {
   *   currency:        string  (ISO-4217),
   *   balance:         string  (BigDecimal decimal string),
   *   threshold:       string  (BigDecimal decimal string),
   *   pctOfThreshold:  number  (0-100, current balance as % of threshold)
   * }
   */
  getPartnerBalance: (partnerCode) =>
    request(`/v1/admin/partners/${encodeURIComponent(partnerCode)}/balance`),

  /**
   * GET /v1/admin/partners/{partnerCode}/balance-alerts -> BalanceAlertView[]
   * BalanceAlertView: {
   *   tier:         'WARNING'|'CRITICAL',
   *   balanceUsd:   string  (BigDecimal decimal string),
   *   thresholdUsd: string  (BigDecimal decimal string),
   *   raisedAt:     ISO-8601 instant string,
   *   acknowledged: boolean
   * }
   */
  getBalanceAlerts: (partnerCode) =>
    request(`/v1/admin/partners/${encodeURIComponent(partnerCode)}/balance-alerts`),
};
