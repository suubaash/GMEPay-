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
 *
 * Auth model (single, for every environment — gap register T0-4 / T1-2):
 *  - `Authorization: Bearer <Keycloak access_token>` on every call. The BFF is an
 *    OAuth2 resource server (default deny); a request without it gets 401.
 *  - The `{partnerId}` path segment is authorized against the token's own
 *    `partner_id` claim by `OpsRbacGuard.requirePartnerScope`. It is NOT an
 *    identity, and the old `X-Partner-Id` header is no longer sent at all: the
 *    BFF reads it nowhere, and sending it implied a trust that never existed.
 *  - A 401 triggers ONE silent refresh_token attempt; if that fails the local
 *    session is cleared so AuthGate bounces the partner back to Keycloak.
 *
 * Money fields on the wire are decimal strings (BigDecimal) + ISO-4217
 * currency. Never parse to Number in JS (precision loss).
 */
import {
  clearAuth,
  getPartnerId,
  getToken,
  logout as authLogout,
  refreshSession
} from './auth';

const BASE = process.env.NEXT_PUBLIC_BFF_BASE_URL || '';

function url(path) {
  // When BASE is empty (the supported setup), rely on the next.config.mjs rewrite
  // /api/* -> BFF_PROXY_TARGET. Setting NEXT_PUBLIC_BFF_BASE_URL makes the BROWSER
  // call the BFF cross-origin, and the BFF ships no CORS config — see next.config.mjs.
  if (!BASE) return `/api${path}`;
  return `${BASE}${path}`;
}

/**
 * Fetch with the bearer attached, retrying ONCE after a silent token refresh on
 * 401. On a second 401 the local session is dropped: the token is unusable and
 * keeping it only produces a page full of failed panels.
 */
async function authedFetch(target, init, headers) {
  const token = getToken();
  const withAuth = token
    ? { ...headers, Authorization: `Bearer ${token}` }
    : { ...headers };

  let res = await fetch(target, { ...init, headers: withAuth, cache: 'no-store' });
  if (res.status !== 401) return res;

  const refreshed = await refreshSession();
  if (!refreshed) {
    clearAuth();
    return res;
  }
  res = await fetch(target, {
    ...init,
    headers: { ...headers, Authorization: `Bearer ${refreshed}` },
    cache: 'no-store'
  });
  if (res.status === 401) clearAuth();
  return res;
}

async function request(path, init) {
  const headers = {
    Accept: 'application/json',
    ...(init && init.headers ? init.headers : {})
  };

  if (init && init.body && !headers['Content-Type']) {
    headers['Content-Type'] = 'application/json';
  }

  const res = await authedFetch(url(path), init, headers);

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

  const res = await authedFetch(url(path), init, headers);

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
   * Real data from auth-identity's api_keys registry (gap T1-3). `name`, `scopes`
   * and `lastUsedAt` have no source in the platform and are always null/empty.
   *
   * @returns {Promise<Array<{
   *   keyId: string,
   *   name: null,
   *   prefix: string,
   *   scopes: string[],            // always empty
   *   createdAt: string,           // ISO instant
   *   lastUsedAt: null,
   *   status: 'ACTIVE' | 'PENDING_EXPIRY' | 'REVOKED',
   *   environment: 'SANDBOX' | 'PRODUCTION',
   *   expiresAt: string | null     // ISO instant or null
   * }>>}
   */
  listApiKeys(partnerId) {
    return request(`/v1/portal/${encodeURIComponent(partnerId)}/api-keys`);
  },

  /**
   * GET /v1/portal/{partnerId}/settlements?from&to&includeLines
   *
   * The partner's SETTLEMENT STATEMENT (gap T4-5) — the settled record, read from
   * settlement-reconciliation's persisted settlement_batches/settlement_lines. Distinct from
   * `downloadStatement`, which is the CSV of the partner's TRANSACTIONS.
   *
   * Read-only: there is no settlement write surface for partners (dispute / adjust / request payout
   * are an open product decision, gap T1-5), so this module deliberately exposes no POST/PATCH here.
   *
   * `transmissionState` is a SEPARATE axis from the batch's `status`. A batch may be RECONCILED —
   * GMEPay+ booked it and it tied out against the scheme's confirmation file — while never having
   * been transmitted to the scheme, because no settlement transmission channel is configured
   * anywhere (scheme SFTP credentials + certification are externally gated). Never render `status`
   * alone as "settled and sent".
   *
   * @param {string} partnerId
   * @param {{ from?: string, to?: string, includeLines?: boolean }} [opts] - ISO YYYY-MM-DD bounds
   * @returns {Promise<{
   *   partnerId:string, from:string|null, to:string|null, currency:string|null,
   *   entries:Array<{
   *     batch:{ batchId:string, partnerId:string, settlementDate:string, currency:string,
   *             amount:string, status:string, transmissionState:string,
   *             transmissionReason:string|null, transmittedAt:string|null },
   *     netSettlementAmount:string, paymentAmount:string, clawbackAmount:string,
   *     lineCount:number, openLineCount:number,
   *     lines:Array<{ txnRef:string, amount:string, currency:string, matched:boolean }>
   *   }>,
   *   netSettlementAmount:string, paymentAmount:string, clawbackAmount:string,
   *   lineCount:number, openLineCount:number, transmittedEntryCount:number,
   *   transmissionChannel:{ live:boolean, reachableState:string, reason:string|null }
   * }>}
   */
  getSettlements(partnerId, opts = {}) {
    const qs = new URLSearchParams();
    if (opts.from) qs.set('from', opts.from);
    if (opts.to) qs.set('to', opts.to);
    if (opts.includeLines !== undefined) qs.set('includeLines', String(opts.includeLines));
    const suffix = qs.toString() ? `?${qs.toString()}` : '';
    return request(`/v1/portal/${encodeURIComponent(partnerId)}/settlements${suffix}`);
  },

  /**
   * POST /v1/portal/{partnerId}/sandbox-keys
   *
   * Self-serve issuance of a SANDBOX API key for the Get-Started flow. Returns
   * the ONE-TIME plaintext `apiKey` — it is shown to the partner exactly once
   * and never returned again (the backend stores only a hash). The key is
   * SANDBOX-scoped and cannot authorize production calls.
   *
   * @param {string} partnerId
   * @param {string} [name] - optional human label for the key
   * @returns {Promise<{ keyId:string, apiKey:string, prefix:string, scope:'SANDBOX', createdAt:string }>}
   */
  issueSandboxKey(partnerId, name) {
    return request(`/v1/portal/${encodeURIComponent(partnerId)}/sandbox-keys`, {
      method: 'POST',
      body: JSON.stringify({ name: name ?? null })
    });
  },

  /**
   * GET /v1/portal/{partnerId}/sandbox-keys
   *
   * Lists the SANDBOX keys already minted for this partner — id, prefix, scope,
   * createdAt. Never returns the plaintext secret.
   *
   * @param {string} partnerId
   * @returns {Promise<Array<{ keyId:string, prefix:string, scope:'SANDBOX', createdAt:string }>>}
   */
  listSandboxKeys(partnerId) {
    return request(`/v1/portal/${encodeURIComponent(partnerId)}/sandbox-keys`);
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

  /**
   * Silent token refresh against Keycloak (refresh_token grant). Resolves to the
   * new access token, or null when there is no usable refresh token — there is no
   * BFF refresh endpoint any more (`/v1/auth/refresh` was deleted with T0-1).
   */
  refreshToken() {
    return refreshSession();
  },

  logout() {
    authLogout();
  }
};

/**
 * The active partner id used by the UI: the `partner_id` claim of the signed-in
 * token, persisted by `storeOidcSession`. Empty string when there is no session
 * or the token carries no partner scope (see `isPartnerScopeMissing`) — the old
 * `NEXT_PUBLIC_PARTNER_ID` fallback is gone, because a hardcoded id that does not
 * match the token's claim can only produce a 403.
 */
export function currentPartnerId() {
  return getPartnerId() || '';
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
