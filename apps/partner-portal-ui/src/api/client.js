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
