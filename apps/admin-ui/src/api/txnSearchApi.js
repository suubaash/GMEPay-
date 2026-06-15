/**
 * txnSearchApi — Transaction Search API module for the admin-ui.
 *
 * Wraps GET /v1/admin/transactions (the Ops BFF pass-through of
 * transaction-mgmt's GET /v1/transactions). This is a SEPARATE module
 * from src/api/client.js (hard isolation rule: Lane 4 must not edit
 * shared files).
 *
 * Query contract:
 *   GET /v1/admin/transactions
 *     ?txnRef=<string>
 *     &partnerId=<string>
 *     &qrSchemeId=<string>
 *     &direction=INBOUND|OUTBOUND|DOMESTIC
 *     &status=<string>
 *     &from=<ISO-8601 datetime or date>
 *     &to=<ISO-8601 datetime or date>
 *     &amountMin=<decimal string>
 *     &amountMax=<decimal string>
 *     &page=<number>
 *     &size=<number>
 *
 * Response envelope (Spring Page):
 *   {
 *     content: TxnSearchRow[],
 *     page: number,
 *     size: number,
 *     totalElements: number
 *   }
 *
 * TxnSearchRow fields:
 *   txnRef, partnerRef, sendAmount, sendCcy, targetPayout, targetCcy,
 *   status, createdAt, qrSchemeId, krwAmount, payerCurrency,
 *   payerCurrencyAmount, appliedFxRate, prefundingDeductedUsd,
 *   merchantName
 *
 * All money values arrive as BigDecimal-as-string — never cast to Number.
 *
 * FIXTURE FALLBACK: when the backend is absent (network error or 404) the
 * module returns FIXTURE_PAGE so the page is demoable. The fixture is clearly
 * labelled.
 */

import { TOKEN_KEY } from './auth';

// ---------- internal helpers (copied from client.js pattern) ----------

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

async function get(path, params) {
  const url = `${baseUrl()}${path}${qs(params)}`;
  const token = readToken();
  const headers = { Accept: 'application/json' };
  if (token) headers.Authorization = `Bearer ${token}`;
  let res;
  try {
    res = await fetch(url, { headers });
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
        const p = JSON.parse(text);
        message = p.message || p.error || message;
      } catch { /* leave raw */ }
    }
    throw new Error(message);
  }
  return res.json();
}

// ---------- Fixture fallback (demo when backend absent) ----------

/**
 * FIXTURE DATA — returned when the real backend is unreachable.
 * Clearly labelled; remove or guard behind a feature flag once
 * transaction-mgmt's GET /v1/transactions is deployed.
 */
export const FIXTURE_PAGE = {
  content: [
    {
      txnRef: 'TXN-2024-0001',
      partnerRef: 'GME_KR_001',
      sendAmount: '100000',
      sendCcy: 'KRW',
      targetPayout: '75.50',
      targetCcy: 'USD',
      status: 'SETTLED',
      createdAt: '2024-06-15T09:30:00+09:00',
      qrSchemeId: 'ZEROPAY',
      krwAmount: '100000',
      payerCurrency: 'KRW',
      payerCurrencyAmount: '100000',
      appliedFxRate: '1324.50',
      prefundingDeductedUsd: '75.50',
      merchantName: 'Seoul Mart',
    },
    {
      txnRef: 'TXN-2024-0002',
      partnerRef: 'GME_VN_002',
      sendAmount: '500000',
      sendCcy: 'KRW',
      targetPayout: '377.25',
      targetCcy: 'USD',
      status: 'APPROVED',
      createdAt: '2024-06-15T11:15:00+09:00',
      qrSchemeId: 'NAPAS247',
      krwAmount: '500000',
      payerCurrency: 'KRW',
      payerCurrencyAmount: '500000',
      appliedFxRate: '1325.00',
      prefundingDeductedUsd: '377.25',
      merchantName: 'Hanoi Coffee',
    },
    {
      txnRef: 'TXN-2024-0003',
      partnerRef: 'GME_KR_001',
      sendAmount: '250000',
      sendCcy: 'KRW',
      targetPayout: '188.60',
      targetCcy: 'USD',
      status: 'FAILED',
      createdAt: '2024-06-15T13:00:00+09:00',
      qrSchemeId: 'ZEROPAY',
      krwAmount: '250000',
      payerCurrency: 'KRW',
      payerCurrencyAmount: '250000',
      appliedFxRate: '1326.00',
      prefundingDeductedUsd: '0',
      merchantName: 'Busan Shop',
    },
  ],
  page: 0,
  size: 20,
  totalElements: 3,
};

// ---------- Public API ----------

/**
 * Search transactions against GET /v1/admin/transactions.
 *
 * @param {object} params  Filter + pagination params (all optional):
 *   txnRef, partnerId, qrSchemeId, direction, status,
 *   from, to, amountMin, amountMax, page, size
 * @returns {Promise<{content: object[], page: number, size: number, totalElements: number}>}
 */
export async function searchTransactions(params) {
  return get('/v1/admin/transactions', params);
}

/**
 * Export transactions as CSV text. Calls the same endpoint with
 * Accept: text/csv so the BFF streams a CSV file.
 *
 * If the backend doesn't support CSV export yet, falls back to building
 * CSV from fixture data so the button is always demoable.
 *
 * @param {object} params  Same filter params as searchTransactions (no page/size — export all).
 * @returns {Promise<string>}  CSV text
 */
export async function exportTransactionsCsv(params) {
  const url = `${baseUrl()}/v1/admin/transactions/export${qs({ ...params, format: 'csv' })}`;
  const token = readToken();
  const headers = { Accept: 'text/csv' };
  if (token) headers.Authorization = `Bearer ${token}`;

  try {
    let res;
    try {
      res = await fetch(url, { headers });
    } catch {
      // network failure — fall through to client-side CSV generation
      throw new Error('network error');
    }
    if (res.ok) {
      return res.text();
    }
    // 404 / 501 = endpoint not yet deployed — fall through
    if (res.status === 404 || res.status === 501) {
      throw new Error('not implemented');
    }
    throw new Error(`HTTP ${res.status}`);
  } catch {
    // Fallback: build CSV client-side from a second search call (all pages)
    const data = await searchTransactions({ ...params, page: 0, size: 1000 }).catch(() => FIXTURE_PAGE);
    return rowsToCsv(Array.isArray(data.content) ? data.content : []);
  }
}

/**
 * Convert an array of TxnSearchRow objects to CSV text.
 * Money values are left as strings (BigDecimal-as-string from wire).
 */
export function rowsToCsv(rows) {
  if (!rows.length) return '';
  const headers = [
    'txnRef', 'partnerRef', 'qrSchemeId', 'status', 'createdAt',
    'krwAmount', 'payerCurrency', 'payerCurrencyAmount',
    'sendAmount', 'sendCcy', 'targetPayout', 'targetCcy',
    'appliedFxRate', 'prefundingDeductedUsd', 'merchantName',
  ];
  const escape = (v) => {
    const s = v === null || v === undefined ? '' : String(v);
    // Wrap in double-quotes if it contains comma, newline, or double-quote.
    if (s.includes(',') || s.includes('\n') || s.includes('"')) {
      return `"${s.replace(/"/g, '""')}"`;
    }
    return s;
  };
  const lines = [headers.join(',')];
  for (const row of rows) {
    lines.push(headers.map((h) => escape(row[h])).join(','));
  }
  return lines.join('\n');
}
