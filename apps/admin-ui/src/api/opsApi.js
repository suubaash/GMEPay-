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
 *   - Every request carries `Authorization: Bearer <token>` when a JWT is in
 *     localStorage (see ./auth.js), exactly like client.js.
 *   - Money-affecting ACTIONS (pause/resume/maintenance/suspend/unsuspend,
 *     txn resolve, webhook replay, recon re-run) additionally send
 *     `X-Gme-Permissions: ops:operate`. The BFF now FAILS CLOSED on these —
 *     without that permission it returns 403.
 *
 *     DEV NOTE: hard-coding the permission header here is a dev/tunnel
 *     convenience only. In production the operator's real permissions come
 *     from their token / the PDP (lib-rbac); the BFF derives X-Gme-Permissions
 *     from the verified JWT, NOT from a client-supplied header. Do not ship
 *     this hard-coded header to prod.
 *
 * Endpoint contract (BFF surface):
 *
 *   GET  /v1/admin/ops/control-tower  -> ControlTower (see below)
 *   GET  /v1/admin/ops/alerts?severity=&type=&limit=  -> OpsAlert[]
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
 * Permission required by the BFF for money-affecting ops actions.
 * Sent via the X-Gme-Permissions header on ACTION calls only (reads omit it).
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
 *   When `init.operate` is true, attaches the X-Gme-Permissions: ops:operate
 *   header the fail-closed BFF requires for money-affecting actions.
 */
async function request(path, init = {}) {
  const url = `${baseUrl()}${path}`;
  const token = readToken();
  const { operate, headers: extra, ...rest } = init;
  const headers = {
    'Content-Type': 'application/json',
    Accept: 'application/json',
    ...(extra || {}),
  };
  if (token) headers.Authorization = `Bearer ${token}`;
  // DEV-only: the fail-closed BFF requires ops:operate for dangerous actions.
  // In prod this is derived server-side from the operator's verified token,
  // not from a client header — see the module header note.
  if (operate) headers['X-Gme-Permissions'] = OPS_OPERATE_PERMISSION;

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
 *   inFlight: number,
 *   uncertainOrAgedCount: number,
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
 * -> OpsAlert[]  { alertType, severity, subjectRef, detail, occurredAt }
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
// Actions (money-affecting) — all send X-Gme-Permissions: ops:operate.
// ---------------------------------------------------------------------------

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
