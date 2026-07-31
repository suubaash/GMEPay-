/**
 * opsApi — API module for the /operations Operations console.
 *
 * All calls go through the BFF at /v1/admin/ops/... plus a few /v1/admin/...
 * transaction/webhook/settlement endpoints. This module is intentionally
 * isolated (mirrors complianceApi.js) — it does NOT import from or append to
 * src/api/client.js, so the ops surface can evolve without churning the big
 * adminApi object.
 *
 * Auth:
 *   - Every request carries `Authorization: Bearer <Keycloak access_token>` from
 *     localStorage (see ./auth.js), exactly like client.js.
 *   - Money-affecting ACTIONS (pause/resume/maintenance/suspend/unsuspend, txn
 *     resolve, webhook replay, recon re-run) require the `ops:operate`
 *     permission, which the BFF reads from the token's `permissions` claim
 *     (`security/TokenClaims` → `OpsRbacGuard`).
 *
 *     The `X-Gme-Permissions: ops:operate` header this module used to send is
 *     GONE (gap T0-3). It was never a permission — it was a self-declaration the
 *     BFF used to trust, i.e. anyone who could reach port 8095 could pause the
 *     platform with one curl. The BFF now ignores the header entirely, so
 *     sending it would only mislead the next reader. `init.operate` is kept as a
 *     documentation marker on the dangerous calls; if the operator's token lacks
 *     `ops:operate` the BFF returns 403 and that is correct.
 *
 * Endpoint contract (BFF surface):
 *
 *   GET  /v1/admin/ops/control-tower  -> ControlTower (see below)
 *   GET  /v1/admin/ops/alerts?severity=&type=&limit=  -> OpsAlert[]
 *   POST /v1/admin/ops/alerts/{id}/ack { operator?, note? }  -> OpsAlert
 *   POST /v1/admin/ops/pause          { reason }
 *   POST /v1/admin/ops/resume         {}
 *   POST /v1/admin/ops/maintenance    { on, reason }
 *   POST /v1/admin/ops/suspend        { entityType, entityId, reason }
 *   POST /v1/admin/ops/unsuspend      { entityType, entityId }
 *   GET  /v1/admin/transactions/search?txnRef=&partnerId=&status=&from=&to=
 *        -> Page<TransactionSummary> { content, page, size, total }
 *   POST /v1/admin/transactions/{ref}/resolve  { resolution, reason }
 *   POST /v1/admin/webhooks/{id}/replay         {}
 *   POST /v1/admin/settlements/recon/rerun      { batchId | settlementDate }
 *
 * Money fields on the wire are decimal strings — render as-is, never Number()-cast.
 * Timestamps are ISO-8601 UTC.
 */

import { TOKEN_KEY } from './auth';

/**
 * Permission the BFF requires (from the token's `permissions` claim) for
 * money-affecting ops actions. Exported for display/diagnostics only — it is NOT
 * sent on the wire; a client cannot grant itself a permission.
 */
export const OPS_OPERATE_PERMISSION = 'ops:operate';

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

/**
 * @param {string} path
 * @param {RequestInit & { operate?: boolean }} [init]
 *   `init.operate` marks a money-affecting action that the BFF authorizes from
 *   the token's `permissions` claim (`ops:operate`). It adds NO header: the
 *   caller cannot self-authorize.
 */
async function request(path, init = {}) {
  const url = `${baseUrl()}${path}`;
  const token = readToken();
  // `operate` is destructured off so it never leaks into fetch's RequestInit.
  const { operate: _operate, headers: extra, ...rest } = init;
  const headers = {
    'Content-Type': 'application/json',
    Accept: 'application/json',
    ...(extra || {}),
  };
  if (token) headers.Authorization = `Bearer ${token}`;

  let res;
  try {
    res = await fetch(url, { ...rest, headers });
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    const err = new Error(msg || 'network error');
    err.status = 0;
    throw err;
  }
  if (!res.ok) {
    let text = '';
    try {
      text = await res.text();
    } catch {
      /* ignore */
    }
    let message = text || `HTTP ${res.status}`;
    if (text && text.trim().startsWith('{')) {
      try {
        const parsed = JSON.parse(text);
        message = parsed.message || parsed.error || message;
      } catch {
        /* leave raw */
      }
    }
    const err = new Error(message);
    err.status = res.status;
    throw err;
  }
  if (res.status === 204) return undefined;
  return res.json();
}

// ---------------------------------------------------------------------------
// Reads
// ---------------------------------------------------------------------------

/**
 * GET /v1/admin/ops/control-tower
 * -> {
 *   inFlight: { inFlightCount: number, uncertainOrAgedCount: number },
 *   webhookBacklog: { pending, dlq, total },
 *   floatHeadroom: [{ partner, balance, threshold, pctOfThreshold, atRisk }] + lowest,
 *   health: { total, up, down, degraded },
 *   openReconExceptions: number,
 *   operationalStatus: {
 *     systemPaused, maintenanceMode,
 *     suspendedPartners[], suspendedSchemes[], suspendedRoutes[],
 *     reason, since
 *   },
 *   recentAlerts: OpsAlert[],
 *   degradedSections: string[]   // section keys the BFF could not compute
 * }
 */
export function getControlTower() {
  return request('/v1/admin/ops/control-tower');
}

/**
 * GET /v1/admin/ops/alerts?severity=&type=&limit=
 * -> OpsAlert[]  {
 *   id, alertType, severity, subjectRef, detail, occurredAt,
 *   pagingStatus,          // 'DELIVERED' | 'FAILED' | 'SUPPRESSED' | 'NOT_PAGED' | null
 *   acked,                 // boolean
 *   open,                  // boolean
 *   ackedBy, ackedAt, ackNote   // present when acked
 * }
 * Older alerts may omit the paging/ack fields — callers must be null-safe.
 */
export function getAlerts(filters) {
  return request(`/v1/admin/ops/alerts${qs(filters)}`);
}

/**
 * GET /v1/admin/transactions/search?txnRef=&partnerId=&status=&from=&to=
 * -> Page<TransactionSummary> { content, page, size, total }
 * TransactionSummary: { txnId, txnRef, partnerId, state|status, amount(string),
 *   currency, committedAt }
 */
export function searchTransactions(filters) {
  return request(`/v1/admin/transactions/search${qs(filters)}`);
}

// ---------------------------------------------------------------------------
// Actions (money-affecting) — all require the ops:operate permission in the
// caller's token; the BFF (OpsRbacGuard) enforces it, no header is sent.
// ---------------------------------------------------------------------------

/**
 * POST /v1/admin/ops/alerts/{id}/ack { note? }
 * Acknowledge an open alert. The BFF derives the operator from the verified
 * token; a free-text `note` may accompany the ack. Money-affecting-adjacent
 * on-call action — requires ops:operate in the token (fail-closed BFF).
 * Returns the updated OpsAlert (acked=true, ackedBy/ackedAt populated).
 */
export function ackAlert(id, { note } = {}) {
  return request(`/v1/admin/ops/alerts/${encodeURIComponent(id)}/ack`, {
    method: 'POST',
    body: JSON.stringify({ note }),
    operate: true,
  });
}

/** POST /v1/admin/ops/pause { reason } */
export function pause(reason) {
  return request('/v1/admin/ops/pause', {
    method: 'POST',
    body: JSON.stringify({ reason }),
    operate: true,
  });
}

/** POST /v1/admin/ops/resume */
export function resume() {
  return request('/v1/admin/ops/resume', {
    method: 'POST',
    body: JSON.stringify({}),
    operate: true,
  });
}

/** POST /v1/admin/ops/maintenance { on, reason } */
export function setMaintenance(on, reason) {
  return request('/v1/admin/ops/maintenance', {
    method: 'POST',
    body: JSON.stringify({ on, reason }),
    operate: true,
  });
}

/** POST /v1/admin/ops/suspend { entityType, entityId, reason } */
export function suspend(entityType, entityId, reason) {
  return request('/v1/admin/ops/suspend', {
    method: 'POST',
    body: JSON.stringify({ entityType, entityId, reason }),
    operate: true,
  });
}

/** POST /v1/admin/ops/unsuspend { entityType, entityId } */
export function unsuspend(entityType, entityId) {
  return request('/v1/admin/ops/unsuspend', {
    method: 'POST',
    body: JSON.stringify({ entityType, entityId }),
    operate: true,
  });
}

/**
 * POST /v1/admin/transactions/{ref}/resolve { resolution, reason }
 * resolution: 'COMPLETED' | 'REVERSED'
 */
export function resolveTransaction(ref, resolution, reason) {
  return request(`/v1/admin/transactions/${encodeURIComponent(ref)}/resolve`, {
    method: 'POST',
    body: JSON.stringify({ resolution, reason }),
    operate: true,
  });
}

/** POST /v1/admin/webhooks/{id}/replay */
export function replayWebhook(id) {
  return request(`/v1/admin/webhooks/${encodeURIComponent(id)}/replay`, {
    method: 'POST',
    body: JSON.stringify({}),
    operate: true,
  });
}

/**
 * POST /v1/admin/settlements/recon/rerun { batchId | settlementDate }
 * Pass whichever the operator supplied; empty values are dropped by the caller.
 */
export function rerunRecon(body) {
  return request('/v1/admin/settlements/recon/rerun', {
    method: 'POST',
    body: JSON.stringify(body ?? {}),
    operate: true,
  });
}
