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
 *     status:      'PENDING' | 'GENERATED' | 'SUBMITTED' | 'FAILED',
 *     recordCount: string,     // BigDecimal-as-string on wire
 *     generatedAt: string,     // ISO-8601 UTC; render in KST
 *     downloadUrl: string | null,
 *   }
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
// ---------------------------------------------------------------------------

/** @type {import('./reportsApi').ReportRun[]} */
export const FIXTURE_REPORT_RUNS = [
  {
    id: 'rpt-001',
    type: 'BOK_FX1014',
    period: '2025-05',
    status: 'SUBMITTED',
    recordCount: '1428',
    generatedAt: '2025-06-01T01:30:00Z',
    downloadUrl: null,
  },
  {
    id: 'rpt-002',
    type: 'BOK_FX1015',
    period: '2025-05',
    status: 'GENERATED',
    recordCount: '312',
    generatedAt: '2025-06-01T02:15:00Z',
    downloadUrl: '/v1/admin/reports/rpt-002/download',
  },
  {
    id: 'rpt-003',
    type: 'HOMETAX_ETAX',
    period: '2025-05',
    status: 'GENERATED',
    recordCount: '876',
    generatedAt: '2025-06-01T03:00:00Z',
    downloadUrl: '/v1/admin/reports/rpt-003/download',
  },
  {
    id: 'rpt-004',
    type: 'KOFIU_CTR',
    period: '2025-05',
    status: 'SUBMITTED',
    recordCount: '23',
    generatedAt: '2025-06-01T04:00:00Z',
    downloadUrl: null,
  },
  {
    id: 'rpt-005',
    type: 'KOFIU_STR',
    period: '2025-05',
    status: 'FAILED',
    recordCount: '0',
    generatedAt: '2025-06-01T04:05:00Z',
    downloadUrl: null,
  },
  {
    id: 'rpt-006',
    type: 'ZEROPAY_SETTLEMENT',
    period: '2025-05-31',
    status: 'GENERATED',
    recordCount: '2041',
    generatedAt: '2025-06-01T00:10:00Z',
    downloadUrl: '/v1/admin/reports/rpt-006/download',
  },
  {
    id: 'rpt-007',
    type: 'BOK_FX1014',
    period: '2025-04',
    status: 'SUBMITTED',
    recordCount: '1390',
    generatedAt: '2025-05-01T01:30:00Z',
    downloadUrl: null,
  },
  {
    id: 'rpt-008',
    type: 'ZEROPAY_SETTLEMENT',
    period: '2025-05-30',
    status: 'PENDING',
    recordCount: '0',
    generatedAt: '2025-05-31T00:05:00Z',
    downloadUrl: null,
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
    // BFF accepted asynchronously — return a synthetic PENDING run.
    return {
      id: `pending-${Date.now()}`,
      type,
      period: body.period ?? '',
      status: 'PENDING',
      recordCount: '0',
      generatedAt: new Date().toISOString(),
      downloadUrl: null,
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
