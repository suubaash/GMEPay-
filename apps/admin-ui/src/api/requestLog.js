/**
 * requestLog — a tiny, framework-agnostic capture of recent BFF HTTP calls.
 *
 * The fetch client ({@link ./client.js} `_doFetch`) records every request here
 * (method, url, request body) and updates it with the response (status, body,
 * duration). The {@link ../components/RequestInspector.jsx} overlay subscribes
 * via React's `useSyncExternalStore`. Kept out of Redux on purpose: the client
 * is plain (non-React) and must not import the store (circular-import risk).
 *
 * Bodies are captured as-is for display only — this is a developer/ops tool
 * gated behind the `inspector.view` permission (see ./inspector or the hook),
 * not a general telemetry sink. A bounded ring buffer keeps memory flat.
 */

const MAX_ENTRIES = 25;
const EMPTY = [];

let seq = 0;
let entries = [];
let snapshot = EMPTY; // cached reference for useSyncExternalStore (only changes on mutation)
const listeners = new Set();

function emit() {
  snapshot = entries.length === 0 ? EMPTY : entries.slice();
  for (const l of listeners) l();
}

function safeBody(b) {
  if (b == null) return null;
  if (typeof b === 'string') return b;
  if (typeof FormData !== 'undefined' && b instanceof FormData) return '[multipart form-data]';
  try {
    return String(b);
  } catch {
    return '[unserialisable body]';
  }
}

export function subscribe(cb) {
  listeners.add(cb);
  return () => {
    listeners.delete(cb);
  };
}

export function getSnapshot() {
  return snapshot;
}

/** Stable server snapshot so SSR/hydration doesn't loop (overlay renders client-side only). */
export function getServerSnapshot() {
  return EMPTY;
}

/** Record the start of a request; returns an id to pass to {@link endRequest}. */
export function startRequest({ method, url, reqBody }) {
  const id = ++seq;
  const entry = {
    id,
    method: String(method || 'GET').toUpperCase(),
    url: url || '',
    reqBody: safeBody(reqBody),
    inFlight: true,
    status: null,
    resBody: undefined,
    error: null,
    startedAt: Date.now(),
    durationMs: null,
  };
  entries = [...entries, entry].slice(-MAX_ENTRIES);
  emit();
  return id;
}

/** Complete a previously-started request with its outcome. */
export function endRequest(id, { status, resBody, error, durationMs } = {}) {
  entries = entries.map((e) =>
    e.id === id
      ? {
          ...e,
          inFlight: false,
          status: status ?? null,
          resBody,
          error: error ?? null,
          durationMs: durationMs ?? null,
        }
      : e,
  );
  emit();
}

export function clearRequests() {
  entries = [];
  emit();
}
