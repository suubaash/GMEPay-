/**
 * reportsApi — BFF calls for the Reports centre (/reports).
 *
 * Endpoints (intended contract — BFF may not exist yet; fixture fallback used
 * when the fetch rejects with a network error or 404):
 *
 *   GET  /v1/admin/reports?type=&from=&to=
 *     -> ReportRun[]
 *
 *   POST /v1/admin/reports/{type}/generate
 *     body: { period?: string }   (ISO date range string, e.g. "2025-06-01/2025-06-30")
 *     -> ReportRun
 *
 *   GET  /v1/admin/reports/{id}/download
 *     -> Blob  (Content-Disposition: attachment; filename=...)
 *
 * ReportRun shape:
 *   {
 *     id:          string,
 *     type:        'BOK_FX1014' | 'BOK_FX1015' | 'HOMETAX_ETAX' |
 *                  'KOFIU_CTR'  | 'KOFIU_STR'  | 'ZEROPAY_SETTLEMENT',
 *     period:      string,     // e.g. "2025-06" or "2025-06-01"
 *     status:      'PENDING' | 'GENERATED' | 'VALIDATED' |
 *                  'NOT_FILED_CHANNEL_UNAVAILABLE' |
 *                  'TRANSMITTED' | 'ACKNOWLEDGED' | 'FAILED' | 'UNKNOWN',
 *     recordCount: string,     // BigDecimal-as-string on wire
 *     generatedAt: string,     // ISO-8601 UTC; render in KST
 *     downloadUrl: string | null,
 *     filingChannelUnavailableReason: string | null,   // why this run cannot be filed
 *     filingChannels: Array<{                          // per-lane board; null when unreported
 *       lane: 'BOK' | 'KOFIU' | 'HOMETAX',
 *       channelLive: boolean,
 *       reachableStatus: string,
 *       reason: string | null,
 *     }> | null,
 *   }
 *
 * FILING HONESTY (GAP T5-2). `status` is the run's filing status as reported by
 * reporting-compliance and passed through by the BFF — it is never synthesized on
 * the way to this app. The retired `SUBMITTED` / `CONFIRMED` / `ACCEPTED` / `FILED`
 * vocabulary is gone: those values were produced by stubs for reports that never
 * left the JVM, are now rejected by a DB CHECK constraint, and reporting-compliance
 * reclassified the historical rows to NOT_FILED_CHANNEL_UNAVAILABLE. `TRANSMITTED`
 * and `ACKNOWLEDGED` are defined but UNREACHABLE today (BOK SFTP / NTS mTLS / KoFIU
 * endpoint are all externally gated), so every real run terminates at
 * NOT_FILED_CHANNEL_UNAVAILABLE with a reason. See @/api/filingStatus for how the
 * vocabulary is rendered, and never colour a non-filed status as success.
 *
 * IMPORTANT: Do NOT import from @/api/client — this is an isolated module per
 * the parallel-lane HARD ISOLATION RULE.
 */

const TOKEN_KEY = 'gmepay.adminToken';

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

function authHeaders() {
  const token = readToken();
  return token ? { Authorization: `Bearer ${token}` } : {};
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

// ---------------------------------------------------------------------------
// Fixture data — returned when the BFF endpoint is absent.
//
// Offline data must not claim a filing either (GAP T5-2). Four of these rows used
// to say `SUBMITTED`, i.e. "delivered to the regulator", for reports that never
// existed. They now carry the reachable states only, with the reason attached and
// all three lanes reported dark — the same shape the BFF's StubReportingClient
// produces, so the offline and online views tell the same story.
// ---------------------------------------------------------------------------

/** Why nothing in the fixture is filed — stated per row, never left to be inferred. */
const FIXTURE_NOT_FILED_REASON =
  'Offline fixture data: the reporting BFF endpoint is absent, so nothing has been generated or '
  + 'filed. No regulatory lane has a live filing channel (BOK SFTP endpoint OI-03, NTS mTLS '
  + 'certificate OI-02, KoFIU endpoint + file spec are externally gated).';

/** All three regulatory lanes, dark, for the readiness board. */
export const FIXTURE_FILING_CHANNELS = ['BOK', 'KOFIU', 'HOMETAX'].map((lane) => ({
  lane,
  channelLive: false,
  reachableStatus: 'NOT_FILED_CHANNEL_UNAVAILABLE',
  reason: FIXTURE_NOT_FILED_REASON,
}));

/** @type {import('./reportsApi').ReportRun[]} */
export const FIXTURE_REPORT_RUNS = [
  {
    id: 'rpt-001',
    type: 'BOK_FX1014',
    period: '2025-05',
    status: 'NOT_FILED_CHANNEL_UNAVAILABLE',
    recordCount: '1428',
    generatedAt: '2025-06-01T01:30:00Z',
    downloadUrl: '/v1/admin/reports/rpt-001/download',
    filingChannelUnavailableReason: FIXTURE_NOT_FILED_REASON,
    filingChannels: FIXTURE_FILING_CHANNELS,
  },
  {
    id: 'rpt-002',
    type: 'BOK_FX1015',
    period: '2025-05',
    status: 'GENERATED',
    recordCount: '312',
    generatedAt: '2025-06-01T02:15:00Z',
    downloadUrl: '/v1/admin/reports/rpt-002/download',
    filingChannelUnavailableReason: FIXTURE_NOT_FILED_REASON,
    filingChannels: FIXTURE_FILING_CHANNELS,
  },
  {
    id: 'rpt-003',
    type: 'HOMETAX_ETAX',
    period: '2025-05',
    status: 'VALIDATED',
    recordCount: '876',
    generatedAt: '2025-06-01T03:00:00Z',
    downloadUrl: '/v1/admin/reports/rpt-003/download',
    filingChannelUnavailableReason: FIXTURE_NOT_FILED_REASON,
    filingChannels: FIXTURE_FILING_CHANNELS,
  },
  {
    id: 'rpt-004',
    type: 'KOFIU_CTR',
    period: '2025-05',
    status: 'NOT_FILED_CHANNEL_UNAVAILABLE',
    recordCount: '23',
    generatedAt: '2025-06-01T04:00:00Z',
    downloadUrl: '/v1/admin/reports/rpt-004/download',
    filingChannelUnavailableReason: FIXTURE_NOT_FILED_REASON,
    filingChannels: FIXTURE_FILING_CHANNELS,
  },
  {
    id: 'rpt-005',
    type: 'KOFIU_STR',
    period: '2025-05',
    status: 'FAILED',
    recordCount: '0',
    generatedAt: '2025-06-01T04:05:00Z',
    downloadUrl: null,
    filingChannelUnavailableReason: FIXTURE_NOT_FILED_REASON,
    filingChannels: FIXTURE_FILING_CHANNELS,
  },
  {
    id: 'rpt-006',
    type: 'ZEROPAY_SETTLEMENT',
    period: '2025-05-31',
    status: 'GENERATED',
    recordCount: '2041',
    generatedAt: '2025-06-01T00:10:00Z',
    downloadUrl: '/v1/admin/reports/rpt-006/download',
    // ZeroPay settlement is a scheme reconciliation artifact, not a regulatory
    // filing, so there is no filing channel to report on for it.
    filingChannelUnavailableReason: null,
    filingChannels: null,
  },
  {
    id: 'rpt-007',
    type: 'BOK_FX1014',
    period: '2025-04',
    status: 'NOT_FILED_CHANNEL_UNAVAILABLE',
    recordCount: '1390',
    generatedAt: '2025-05-01T01:30:00Z',
    downloadUrl: '/v1/admin/reports/rpt-007/download',
    filingChannelUnavailableReason: FIXTURE_NOT_FILED_REASON,
    filingChannels: FIXTURE_FILING_CHANNELS,
  },
  {
    id: 'rpt-008',
    type: 'ZEROPAY_SETTLEMENT',
    period: '2025-05-30',
    status: 'PENDING',
    recordCount: '0',
    generatedAt: '2025-05-31T00:05:00Z',
    downloadUrl: null,
    filingChannelUnavailableReason: null,
    filingChannels: null,
  },
];

// ---------------------------------------------------------------------------
// API functions
// ---------------------------------------------------------------------------

/**
 * List report runs, optionally filtered by type and date range.
 *
 * Falls back to FIXTURE_REPORT_RUNS (filtered in-memory) when the BFF is absent.
 *
 * @param {{ type?: string, from?: string, to?: string }} params
 * @returns {Promise<ReportRun[]>}
 */
export async function listReports(params = {}) {
  const url = `${baseUrl()}/v1/admin/reports${qs(params)}`;
  try {
    const res = await fetch(url, {
      headers: { Accept: 'application/json', ...authHeaders() },
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    return await res.json();
  } catch {
    // BFF absent — apply the same filters in-memory against the fixture.
    let rows = FIXTURE_REPORT_RUNS;
    if (params.type) {
      rows = rows.filter((r) => r.type === params.type);
    }
    if (params.from) {
      rows = rows.filter((r) => r.period >= params.from);
    }
    if (params.to) {
      rows = rows.filter((r) => r.period <= params.to);
    }
    return rows;
  }
}

/**
 * Trigger a new report generation run for the given type.
 *
 * @param {string} type         One of the REPORT_TYPES keys.
 * @param {{ period?: string }} body
 * @returns {Promise<ReportRun>}
 */
export async function generateReport(type, body = {}) {
  const url = `${baseUrl()}/v1/admin/reports/${encodeURIComponent(type)}/generate`;
  const res = await fetch(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
      ...authHeaders(),
    },
    body: JSON.stringify(body),
  });
  if (!res.ok) {
    let message = `HTTP ${res.status}`;
    try {
      const text = await res.text();
      if (text.trim().startsWith('{')) {
        const parsed = JSON.parse(text);
        message = parsed.message || parsed.error || message;
      } else if (text) {
        message = text;
      }
    } catch {
      /* ignore */
    }
    throw new Error(message);
  }
  if (res.status === 202 || res.status === 204) {
    // The BFF returned no run body. Reports are recomputed on read — no job is
    // enqueued — so a "PENDING run" (what this used to synthesize) does not exist
    // to be polled. Say UNKNOWN and why, rather than invent a lifecycle.
    return {
      id: `unreported-${Date.now()}`,
      type,
      period: body.period ?? '',
      status: 'UNKNOWN',
      recordCount: '0',
      generatedAt: new Date().toISOString(),
      downloadUrl: null,
      filingChannelUnavailableReason:
        'The reporting service returned no run for this type/period. Reports are recomputed on '
        + 'read rather than queued, so there is no job in progress — re-run with a period that '
        + 'contains committed transactions.',
      filingChannels: null,
    };
  }
  return await res.json();
}

/**
 * Fetch the download blob for a completed report run.
 *
 * @param {string} id   Report run ID.
 * @returns {Promise<Blob>}
 */
export async function downloadReport(id) {
  const url = `${baseUrl()}/v1/admin/reports/${encodeURIComponent(id)}/download`;
  const res = await fetch(url, {
    headers: { Accept: '*/*', ...authHeaders() },
  });
  if (!res.ok) {
    throw new Error(`HTTP ${res.status}`);
  }
  return await res.blob();
}
