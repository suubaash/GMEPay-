/**
 * Thin fetch wrapper for the Partner Portal -> Ops/Partner BFF.
 *
 * Wire contract (docs/INTER_SERVICE_CONTRACTS.md):
 *  GET  /v1/portal/{partnerId}/overview      -> PartnerOverview
 *  GET  /v1/portal/{partnerId}/balance       -> BalanceView
 *  GET  /v1/portal/{partnerId}/transactions  -> List<TransactionSummary>
 *  GET  /v1/portal/{partnerId}/transactions/{txnId} -> TransactionDetail
 *  GET  /v1/portal/{partnerId}/webhooks      -> List<WebhookConfigView>
 *  GET  /v1/portal/{partnerId}/profile       -> PartnerProfile
 *  GET  /v1/portal/{partnerId}/api-keys      -> List<ApiKeyView>
 *  GET  /v1/portal/{partnerId}/statement?from&to -> text/csv (Content-Disposition: attachment)
 *  POST /v1/auth/login                       -> LoginResponse
 *
 * Auth model:
 *  - Production: partner identity established at api-gateway (HMAC); BFF reads
 *    identity from the request session.
 *  - Local dev / Phase 1: Authorization: Bearer <token> + X-Partner-Id from
 *    localStorage (set by login flow), falling back to NEXT_PUBLIC_PARTNER_ID.
 *    Production deploys MUST NOT trust X-Partner-Id.
 *
 * Money fields on the wire are decimal strings (BigDecimal) + ISO-4217
 * currency. Never parse to Number in JS (precision loss).
 */
import {
  getPartnerId,
  getToken,
  login as authLogin,
  logout as authLogout
} from './auth';

const BASE = process.env.NEXT_PUBLIC_BFF_BASE_URL || '';
const ENV_PARTNER_ID = process.env.NEXT_PUBLIC_PARTNER_ID || '';

function url(path) {
  // When BASE is empty (browser dev), rely on next.config.mjs rewrite /api/* -> BFF.
  if (!BASE) return `/api${path}`;
  return `${BASE}${path}`;
}

async function request(path, init) {
  const headers = {
    Accept: 'application/json',
    ...(init && init.headers ? init.headers : {})
  };
  const token = getToken();
  if (token) headers['Authorization'] = `Bearer ${token}`;

  const partnerId = getPartnerId() || ENV_PARTNER_ID;
  if (partnerId) headers['X-Partner-Id'] = partnerId;

  if (init && init.body && !headers['Content-Type']) {
    headers['Content-Type'] = 'application/json';
  }

  const res = await fetch(url(path), {
    ...init,
    headers,
    cache: 'no-store'
  });

  if (!res.ok) {
    let body;
    try {
      body = await res.json();
    } catch {
      body = await res.text();
    }
    const err = new Error(
      `BFF ${(init && init.method) || 'GET'} ${path} failed: ${res.status}`
    );
    err.status = res.status;
    err.body = body;
    throw err;
  }
  if (res.status === 204) return undefined;
  return await res.json();
}

/**
 * Like `request()` but returns the raw response as a Blob — used for the CSV
 * statement download. The BFF replies with `text/csv` and
 * `Content-Disposition: attachment; filename=...`.
 *
 * @param {string} path
 * @param {RequestInit} [init]
 * @returns {Promise<Blob>}
 */
async function requestBlob(path, init) {
  const headers = {
    Accept: 'text/csv,application/octet-stream;q=0.9,*/*;q=0.5',
    ...(init && init.headers ? init.headers : {})
  };
  const token = getToken();
  if (token) headers['Authorization'] = `Bearer ${token}`;

  const partnerId = getPartnerId() || ENV_PARTNER_ID;
  if (partnerId) headers['X-Partner-Id'] = partnerId;

  const res = await fetch(url(path), {
    ...init,
    headers,
    cache: 'no-store'
  });

  if (!res.ok) {
    let body;
    try {
      body = await res.text();
    } catch {
      body = '';
    }
    const err = new Error(
      `BFF ${(init && init.method) || 'GET'} ${path} failed: ${res.status}`
    );
    err.status = res.status;
    err.body = body;
    throw err;
  }
  return await res.blob();
}

export const portalApi = {
  /**
   * GET /v1/portal/{partnerId}/overview
   * @returns {Promise<{ partnerId:string, balance:{ partnerId:string, currency:string, balance:string, lowBalanceThreshold:string }, recentTxnCount:number, lastSettlementDate:string|null }>}
   */
  getOverview(partnerId) {
    return request(`/v1/portal/${encodeURIComponent(partnerId)}/overview`);
  },

  /**
   * GET /v1/portal/{partnerId}/balance
   * @returns {Promise<{ partnerId:string, currency:string, balance:string, lowBalanceThreshold:string }>}
   */
  getBalance(partnerId) {
    return request(`/v1/portal/${encodeURIComponent(partnerId)}/balance`);
  },

  /**
   * GET /v1/portal/{partnerId}/transactions
   *
   * Phase-1 portal endpoint returns a plain List (not the Admin Page<T>
   * envelope). Optional `limit` arg.
   *
   * @returns {Promise<Array<{ txnId:string, partnerId:string, state:string, amount:string, currency:string, committedAt:string }>>}
   */
  listTransactions(partnerId, limit = 20) {
    const qs = new URLSearchParams({ limit: String(limit) });
    return request(
      `/v1/portal/${encodeURIComponent(partnerId)}/transactions?${qs.toString()}`
    );
  },

  /**
   * GET /v1/portal/{partnerId}/transactions/{txnId}
   * @returns {Promise<object>} TransactionDetail wire shape — see store/transactionsSlice.js for the field list.
   */
  getTransaction(partnerId, txnId) {
    return request(
      `/v1/portal/${encodeURIComponent(partnerId)}/transactions/${encodeURIComponent(txnId)}`
    );
  },

  /**
   * GET /v1/portal/{partnerId}/webhooks
   * @returns {Promise<Array<{ url:string, eventTypes:string[], status:string, lastDeliveredAt:string|null }>>}
   */
  listWebhooks(partnerId) {
    return request(`/v1/portal/${encodeURIComponent(partnerId)}/webhooks`);
  },

  /**
   * GET /v1/portal/{partnerId}/profile
   * @returns {Promise<{ partnerId:string, type:string, settlementCurrency:string, settlementRoundingMode:string, onboardedAt:string }>}
   */
  getProfile(partnerId) {
    return request(`/v1/portal/${encodeURIComponent(partnerId)}/profile`);
  },

  /**
   * GET /v1/portal/{partnerId}/api-keys
   *
   * Read-only listing of API keys provisioned for this partner. Phase 1: no
   * rotate/revoke endpoints — those land in Phase 2 (Ops/Admin or auth-identity).
   *
   * @returns {Promise<Array<{
   *   keyId: string,
   *   name: string,
   *   prefix: string,
   *   scopes: string[],
   *   createdAt: string,           // ISO instant
   *   lastUsedAt: string | null,   // ISO instant or null
   *   status: 'ACTIVE' | 'ROTATING' | 'REVOKED'
   * }>>}
   */
  listApiKeys(partnerId) {
    return request(`/v1/portal/${encodeURIComponent(partnerId)}/api-keys`);
  },

  /**
   * GET /v1/portal/{partnerId}/statement?from=YYYY-MM-DD&to=YYYY-MM-DD
   *
   * Returns the partner's transaction statement as CSV (text/csv with
   * Content-Disposition: attachment). Caller is responsible for triggering the
   * browser download (anchor + object URL).
   *
   * @param {string} partnerId
   * @param {string} from - inclusive YYYY-MM-DD
   * @param {string} to   - inclusive YYYY-MM-DD
   * @returns {Promise<Blob>}
   */
  downloadStatement(partnerId, from, to) {
    const qs = new URLSearchParams({ from, to });
    return requestBlob(
      `/v1/portal/${encodeURIComponent(partnerId)}/statement?${qs.toString()}`
    );
  },

  /** POST /v1/auth/login */
  login(req) {
    return authLogin(req);
  },

  /** Phase 1 stub — placeholder for token refresh wiring. */
  refreshToken() {
    return Promise.reject(new Error('refreshToken not implemented in Phase 1'));
  },

  logout() {
    authLogout();
  }
};

/**
 * The active partner id used by the UI. Prefers the persisted (logged-in)
 * value, falls back to the dev env var so a fresh checkout can still browse
 * without going through the login screen.
 */
export function currentPartnerId() {
  return getPartnerId() || ENV_PARTNER_ID;
}

/**
 * Convenience wrapper around `portalApi.listApiKeys` that resolves the
 * partner id from local auth state. Exposed because the API-keys page reads
 * it as the spec'd top-level function.
 *
 * @param {string} [partnerId] - optional override; defaults to `currentPartnerId()`.
 * @returns {Promise<Array>}
 */
export function listApiKeys(partnerId) {
  const id = partnerId || currentPartnerId();
  return portalApi.listApiKeys(id);
}

/**
 * Convenience wrapper around `portalApi.downloadStatement` that resolves the
 * partner id from local auth state. Returns the CSV body as a Blob; the
 * caller triggers the browser download.
 *
 * @param {string} from - inclusive YYYY-MM-DD
 * @param {string} to   - inclusive YYYY-MM-DD
 * @param {string} [partnerId] - optional override
 * @returns {Promise<Blob>}
 */
export function downloadStatement(from, to, partnerId) {
  const id = partnerId || currentPartnerId();
  return portalApi.downloadStatement(id, from, to);
}
