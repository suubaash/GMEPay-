/**
 * complianceApi — API module for the /compliance page (Lane 5).
 *
 * All calls go through the BFF at /v1/admin/compliance/... and
 * /v1/admin/partners/{code}/... endpoints. This module is intentionally
 * isolated — it does NOT import from or append to src/api/client.js.
 *
 * Fixture fallback:
 *   When the BFF returns 404 or the call fails with a network error the
 *   helpers fall back to the FIXTURE_* constants below so the page is
 *   demoable before the backend lands.
 *
 * Endpoint contract (intended BFF surface):
 *
 *   GET /v1/admin/compliance/overview
 *     -> ComplianceRow[] where ComplianceRow = {
 *          partnerCode:     string,
 *          partnerName:     string,
 *          kybStatus:       'APPROVED' | 'PENDING' | 'REVIEW' | 'HIT',
 *          sanctionsResult: 'CLEAR' | 'NEEDS_REVIEW' | 'HIT' | null,
 *          regulatoryConfig: {
 *            bokSet:        boolean,
 *            hometaxSet:    boolean,
 *            kofiuSet:      boolean,
 *            travelRuleSet: boolean,
 *          },
 *          lifecycleStatus: 'LIVE' | 'SUSPENDED' | 'ONBOARDING' | 'TERMINATED',
 *        }
 *
 *   GET /v1/admin/partners/{code}/regulatory
 *     -> RegulatoryConfigView = {
 *          bok:        { txnCode, fxReportingCategory, remitterType } | null,
 *          hometax:    { hometaxIssuerCertId, vatTreatment }          | null,
 *          kofiu:      { kofiuEntityId, ctrThresholdKrw }             | null,  // BigDecimal strings
 *          pipa:       { pipaJurisdictionAllowlist: string[] }         | null,
 *          travelRule: { protocol, endpointUrl, thresholdKrw }        | null,  // BigDecimal string
 *        }
 *
 *   GET /v1/admin/partners/{code}/kyb
 *     -> KybView (same shape as src/store/kybSlice.js)
 *
 *   GET /v1/admin/audit?aggregate={code}&from={ISO}&to={ISO}&page={n}
 *     -> Page<AuditEntry> = {
 *          content: AuditEntry[],
 *          page:    number,
 *          size:    number,
 *          total:   number,
 *        }
 *        AuditEntry = { id, event, aggregate, actor, at: ISO }
 *
 * Money fields (ctrThresholdKrw, thresholdKrw) are BigDecimal-as-string on
 * the wire — render as-is, NEVER Number()-cast.
 * Timestamps are ISO-8601 UTC — format in KST for display.
 */

import { TOKEN_KEY } from './auth';

function baseUrl() {
  if (typeof window !== 'undefined') return '/api';
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
  if (token) headers.Authorization = `Bearer ${token}`;

  let res;
  try {
    res = await fetch(url, { ...init, headers });
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    throw new Error(msg || 'network error');
  }
  if (!res.ok) {
    let text = '';
    try { text = await res.text(); } catch { /* ignore */ }
    let message = text || `HTTP ${res.status}`;
    if (text && text.trim().startsWith('{')) {
      try {
        const parsed = JSON.parse(text);
        message = parsed.message || parsed.error || message;
      } catch { /* leave raw */ }
    }
    const err = new Error(message);
    err.status = res.status;
    throw err;
  }
  if (res.status === 204) return undefined;
  return res.json();
}

// ---------------------------------------------------------------------------
// Fixtures — used when the BFF has not yet landed (404 / network error).
// ---------------------------------------------------------------------------

/** @type {import('./complianceApi').ComplianceRow[]} */
export const FIXTURE_OVERVIEW = [
  {
    partnerCode: 'GME_KR_001',
    partnerName: 'GME Korea Co., Ltd.',
    kybStatus: 'APPROVED',
    sanctionsResult: 'CLEAR',
    regulatoryConfig: { bokSet: true, hometaxSet: true, kofiuSet: true, travelRuleSet: true },
    lifecycleStatus: 'LIVE',
  },
  {
    partnerCode: 'GME_VN_002',
    partnerName: 'GME Vietnam Pte.',
    kybStatus: 'PENDING',
    sanctionsResult: 'NEEDS_REVIEW',
    regulatoryConfig: { bokSet: false, hometaxSet: false, kofiuSet: false, travelRuleSet: false },
    lifecycleStatus: 'ONBOARDING',
  },
  {
    partnerCode: 'GME_PH_003',
    partnerName: 'GME Philippines Inc.',
    kybStatus: 'REVIEW',
    sanctionsResult: 'HIT',
    regulatoryConfig: { bokSet: true, hometaxSet: true, kofiuSet: false, travelRuleSet: false },
    lifecycleStatus: 'SUSPENDED',
  },
  {
    partnerCode: 'GME_SG_004',
    partnerName: 'GME Singapore Ltd.',
    kybStatus: 'HIT',
    sanctionsResult: 'CLEAR',
    regulatoryConfig: { bokSet: true, hometaxSet: false, kofiuSet: true, travelRuleSet: true },
    lifecycleStatus: 'LIVE',
  },
];

export const FIXTURE_REGULATORY = {
  bok: { txnCode: 'T-1021', fxReportingCategory: 'REMITTANCE', remitterType: 'INDIVIDUAL' },
  hometax: { hometaxIssuerCertId: 'HT-9981', vatTreatment: 'ZERO_RATED' },
  kofiu: { kofiuEntityId: 'KOFIU-GME-001', ctrThresholdKrw: '10000000' },
  pipa: { pipaJurisdictionAllowlist: ['KR', 'SG', 'VN'] },
  travelRule: { protocol: 'IVMS101', endpointUrl: 'https://travel-rule.gmeremit.com', thresholdKrw: '1000000' },
};

export const FIXTURE_KYB = {
  partnerCode: 'GME_KR_001',
  riskRating: 'LOW',
  riskRationale: 'Well-established Korean financial institution with full documentation.',
  nextReviewDate: '2027-01-15',
  licenseType: 'MSB',
  licenseNumber: 'KR-MSB-2021-0042',
  licenseAuthority: 'Financial Services Commission',
  licenseExpiry: '2026-12-31',
  uboList: [
    { name: 'Park Ji-ho', ownershipPct: '51', isPep: false, country: 'KR' },
    { name: 'Lee Sun-young', ownershipPct: '25', isPep: false, country: 'KR' },
  ],
  cbddqDocId: 'doc-cbddq-001',
  screeningStatus: 'CLEAR',
  screeningProviderRef: 'OCT-2024-0091',
  screenedAt: '2024-10-15T03:00:00Z',
  screeningHits: [],
};

export const FIXTURE_AUDIT_PAGE = {
  content: [
    { id: 'AUD-001', event: 'PARTNER_KYB_APPROVED', aggregate: 'GME_KR_001', actor: 'admin@gmeremit.com', at: '2024-10-15T03:12:00Z' },
    { id: 'AUD-002', event: 'REGULATORY_CONFIG_UPDATED', aggregate: 'GME_KR_001', actor: 'ops@gmeremit.com', at: '2024-10-14T09:30:00Z' },
    { id: 'AUD-003', event: 'SANCTIONS_SCREEN_RUN', aggregate: 'GME_KR_001', actor: 'system', at: '2024-10-13T00:00:00Z' },
    { id: 'AUD-004', event: 'PARTNER_ACTIVATED', aggregate: 'GME_KR_001', actor: 'admin@gmeremit.com', at: '2024-09-01T06:00:00Z' },
    { id: 'AUD-005', event: 'PARTNER_CREATED', aggregate: 'GME_KR_001', actor: 'admin@gmeremit.com', at: '2024-08-20T02:00:00Z' },
  ],
  page: 0,
  size: 20,
  total: 5,
};

// ---------------------------------------------------------------------------
// API functions
// ---------------------------------------------------------------------------

/**
 * GET /v1/admin/compliance/overview
 * Falls back to FIXTURE_OVERVIEW when the endpoint is absent (404 / network).
 * @returns {Promise<ComplianceRow[]>}
 */
export async function getComplianceOverview() {
  try {
    return await request('/v1/admin/compliance/overview');
  } catch (e) {
    if (e.status === 404 || e.status === 0 || !e.status) {
      return FIXTURE_OVERVIEW;
    }
    throw e;
  }
}

/**
 * GET /v1/admin/partners/{code}/regulatory
 * Falls back to FIXTURE_REGULATORY when absent.
 * @param {string} partnerCode
 * @returns {Promise<RegulatoryConfigView>}
 */
export async function getRegulatoryConfig(partnerCode) {
  try {
    return await request(`/v1/admin/partners/${encodeURIComponent(partnerCode)}/regulatory`);
  } catch (e) {
    if (e.status === 404 || e.status === 0 || !e.status) {
      return { ...FIXTURE_REGULATORY };
    }
    throw e;
  }
}

/**
 * GET /v1/admin/partners/{code}/kyb
 * Falls back to FIXTURE_KYB when absent.
 * @param {string} partnerCode
 * @returns {Promise<KybView>}
 */
export async function getPartnerKyb(partnerCode) {
  try {
    return await request(`/v1/admin/partners/${encodeURIComponent(partnerCode)}/kyb`);
  } catch (e) {
    if (e.status === 404 || e.status === 0 || !e.status) {
      return { ...FIXTURE_KYB, partnerCode };
    }
    throw e;
  }
}

/**
 * The partner's REAL audit trail (#78): GET /v1/admin/audit-trail?aggregateType=partner
 * &aggregateId={code}&page&size — the SHA-256 hash-chained per-aggregate trail (ADR-007),
 * not the old operator-action log. The response
 * {@code { entries:[{recordedAt, actorId, eventType, beforeJson, afterJson}], chainValid,
 * page, size, total }} is mapped to the page's {@code AuditEntry} row shape; {@code chainValid}
 * is surfaced so the operator sees a tamper-evidence signal.
 *
 * <p>The trail is strictly per-aggregate, so without a selected partner there is nothing to
 * query — an empty page is returned rather than 400-ing the required-param endpoint. The
 * server-side trail is full-history paged; client {@code from}/{@code to} are not applied.
 * Falls back to FIXTURE_AUDIT_PAGE only when the endpoint is absent (404 / network).
 *
 * @param {{ aggregate?: string, from?: string, to?: string, page?: number, size?: number }} filters
 * @returns {Promise<Page<AuditEntry> & { chainValid: boolean }>}
 */
export async function getAuditLog(filters = {}) {
  const { aggregate, page = 0, size = 20 } = filters;
  if (!aggregate) {
    return { content: [], page, size, total: 0, chainValid: true };
  }
  try {
    const trail = await request(
      `/v1/admin/audit-trail${qs({ aggregateType: 'partner', aggregateId: aggregate, page, size })}`,
    );
    const entries = Array.isArray(trail?.entries) ? trail.entries : [];
    return {
      content: entries.map((e, i) => ({
        id: `${aggregate}:${trail?.page ?? page}:${i}`,
        event: e.eventType,
        aggregate,
        actor: e.actorId,
        at: e.recordedAt,
      })),
      page: trail?.page ?? page,
      size: trail?.size ?? size,
      total: trail?.total ?? entries.length,
      chainValid: trail?.chainValid !== false,
    };
  } catch (e) {
    if (e.status === 404 || e.status === 0 || !e.status) {
      return FIXTURE_AUDIT_PAGE;
    }
    throw e;
  }
}
