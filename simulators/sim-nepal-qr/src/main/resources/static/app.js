/**
 * sim-nepal-qr operator console — plain vanilla JS, no build step.
 *
 * Served BY the sim at its root, so all calls are same-origin (no CORS):
 *   POST /qrscan-thirdparty/parse/   — decode/resolve a QR (raw {qs} body)
 *   POST /sim/nepal-qr/ui/pay        — same-origin scan&pay convenience wrapper
 *                                       (runs the real pay logic + records it)
 *   GET  /sim/nepal-qr/records       — stored request/response inspection
 *
 * Amounts are entered in NPR and sent to the partner API in paisa (1 NPR = 100 paisa).
 */

// Sample Fonepay QR from API-DOCS/issuance-extension.txt (static, no amount tag).
const SAMPLE_QR =
    "00020101021126350011fonepay.com071640897200000017835204541253035245802NP"
  + "5914SudanMerchant6015AathraiTriveni62060702316304d60f";

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------
function api(path, opts) {
    return fetch(path, opts).then(r =>
        r.json().then(body => ({ ok: r.ok, status: r.status, body }))
                .catch(() => ({ ok: r.ok, status: r.status, body: null })));
}

function setStatus(id, msg, type) {
    const el = document.getElementById(id);
    if (!el) return;
    el.textContent = msg;
    el.className = 'status-msg status-' + type;
}

function escapeHtml(s) {
    return String(s == null ? '' : s).replace(/[&<>"]/g,
        c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
}

function timeStr(iso) {
    if (!iso) return '';
    const d = new Date(iso);
    return isNaN(d) ? iso : d.toLocaleTimeString('en-GB', { hour12: false });
}

function paisaToNpr(paisa) {
    if (paisa == null || paisa === '') return '';
    const n = Number(paisa);
    return 'रू ' + (n / 100).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function newReference() {
    return 'UI-' + Date.now().toString(36).toUpperCase()
        + '-' + Math.random().toString(36).slice(2, 8).toUpperCase();
}

// ---------------------------------------------------------------------------
// QR box — Decode
// ---------------------------------------------------------------------------
async function decodeQr() {
    const qs = document.getElementById('qr-input').value.trim();
    if (!qs) { setStatus('decode-status', 'Paste a QR string first.', 'error'); return; }
    setStatus('decode-status', 'Decoding…', 'info');

    const res = await api('/qrscan-thirdparty/parse/', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ qs }),
    });
    const grid = document.getElementById('decode-fields');

    if (!res.ok || !res.body) {
        grid.classList.remove('show');
        const detail = res.body && (res.body.detail || JSON.stringify(res.body));
        setStatus('decode-status', 'Decode failed: ' + (detail || res.status), 'error');
        return;
    }

    const b = res.body;
    // trxAmount is in rupees (string) or null for a static QR.
    const amountPaisa = b.trxAmount == null ? null : Math.round(Number(b.trxAmount) * 100);
    const kv = (k, v) => (v == null || v === '')
        ? '' : `<div class="kv"><span class="k">${k}</span><span class="v">${escapeHtml(v)}</span></div>`;
    grid.innerHTML =
        kv('network', networkFromExtra(b.merchantInfoExtra)) +
        kv('merchantName', b.merchantName) +
        kv('merchantId', firstMerchantId(b.merchantData)) +
        kv('merchantCity', b.merchantCity) +
        kv('merchantCountry', b.merchantCountry) +
        kv('MCC', b.merchantCategoryCode) +
        kv('currency', b.trxCurrency) +
        kv('initMethod', b.initMethod) +
        `<div class="kv"><span class="k">amount</span><span class="v amount">${
            amountPaisa == null ? 'static (no amount)' : paisaToNpr(amountPaisa) + ' · ' + amountPaisa + ' paisa'
        }</span></div>`;
    grid.classList.add('show');

    // Prefill the pay amount when the QR carries one (dynamic QR).
    if (amountPaisa != null) {
        document.getElementById('pay-amount').value = (amountPaisa / 100).toFixed(2);
    }
    setStatus('decode-status', 'Decoded ' + (b.merchantName || 'merchant') + '.', 'ok');
}

function networkFromExtra(guid) {
    const g = String(guid || '').toLowerCase();
    if (g.includes('fonepay')) return 'fonepay';
    if (g.includes('nepalpay')) return 'nepalpay';
    if (g.includes('unionpay') || g.includes('cup')) return 'unionpay';
    if (g.includes('smart')) return 'smartqr';
    return guid || 'fonepay';
}

function firstMerchantId(tags) {
    if (!tags) return '';
    // Merchant Account Info templates live in tags 26..51.
    for (let t = 26; t <= 51; t++) {
        const key = String(t).padStart(2, '0');
        if (tags[key]) return tags[key];
    }
    return '';
}

// ---------------------------------------------------------------------------
// Pay panel
// ---------------------------------------------------------------------------
async function pay() {
    const nprStr = document.getElementById('pay-amount').value.trim();
    const npr = Number(nprStr);
    if (!nprStr || isNaN(npr) || npr <= 0) {
        setStatus('pay-status', 'Enter a valid NPR amount.', 'error'); return;
    }
    const amountPaisa = Math.round(npr * 100);

    let reference = document.getElementById('pay-reference').value.trim();
    if (!reference) { reference = newReference(); document.getElementById('pay-reference').value = reference; }

    const body = {
        qs:          document.getElementById('qr-input').value.trim() || undefined,
        amountPaisa: amountPaisa,
        reference:   reference,
        mobile:      document.getElementById('pay-mobile').value.trim() || undefined,
        purpose:     document.getElementById('pay-purpose').value.trim() || undefined,
        remarks:     document.getElementById('pay-remarks').value.trim() || undefined,
        outcome:     document.getElementById('pay-outcome').value || undefined,
    };

    setStatus('pay-status', 'Paying…', 'info');
    const res = await api('/sim/nepal-qr/ui/pay', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
    });

    const result = document.getElementById('pay-result');
    document.getElementById('pay-json').textContent = JSON.stringify(res.body, null, 2);
    result.classList.add('show');

    if (!res.ok || !res.body || !res.body.idx) {
        document.getElementById('pay-idx').textContent = '—';
        const pill = document.getElementById('pay-status-pill');
        pill.textContent = 'REJECTED'; pill.className = 'pill pill-REJECTED';
        document.getElementById('pay-amount-out').textContent = '';
        const detail = res.body && (res.body.detail || JSON.stringify(res.body));
        setStatus('pay-status', 'Pay failed: ' + (detail || res.status), 'error');
        // A duplicate reference etc. is still recorded — refresh so it shows.
        loadRecords();
        return;
    }

    const b = res.body;
    document.getElementById('pay-idx').textContent = b.idx;
    const pill = document.getElementById('pay-status-pill');
    pill.textContent = b.status || 'APPROVED';
    pill.className = 'pill pill-' + (b.status || 'APPROVED');
    document.getElementById('pay-amount-out').textContent = paisaToNpr(b.amount) + ' (' + b.amount + ' paisa)';
    setStatus('pay-status', 'Paid: ' + b.idx + ' · ' + (b.status || 'APPROVED'), 'ok');

    // Auto-refresh records after a pay, and roll a fresh reference for the next one.
    loadRecords();
    document.getElementById('pay-reference').value = newReference();
}

// ---------------------------------------------------------------------------
// Records panel
// ---------------------------------------------------------------------------
async function loadRecords() {
    const list = document.getElementById('records-list');
    const res = await api('/sim/nepal-qr/records');
    if (!res.ok || !Array.isArray(res.body)) {
        list.innerHTML = '<li class="empty">Could not load records.</li>';
        return;
    }
    if (res.body.length === 0) {
        list.innerHTML = '<li class="empty">No records yet — decode &amp; pay to populate.</li>';
        return;
    }
    list.innerHTML = '';
    res.body.forEach(rec => list.appendChild(recordItem(rec)));
}

function recordItem(rec) {
    const li = document.createElement('li');
    li.className = 'rec-item';

    const statusClass = rec.responseStatus >= 200 && rec.responseStatus < 300 ? 'rec-2xx' : 'rec-4xx';
    const meta = [
        rec.reference ? 'ref=' + rec.reference : null,
        rec.idx ? 'idx=' + rec.idx : null,
        rec.state ? rec.state : null,
        timeStr(rec.receivedAt),
    ].filter(Boolean).join(' · ');

    const summary = document.createElement('div');
    summary.className = 'rec-summary';
    summary.innerHTML = `
        <span class="rec-endpoint">${escapeHtml(rec.endpoint)}</span>
        <span class="rec-status ${statusClass}">${rec.responseStatus}</span>
        <span class="rec-meta">${escapeHtml(meta)}</span>`;

    const detail = document.createElement('div');
    detail.className = 'rec-detail';
    let html = '';
    if (rec.rawRequestBody) {
        html += '<h4>Request body (raw)</h4><pre class="json">' + escapeHtml(rec.rawRequestBody) + '</pre>';
    }
    if (rec.decodedPayload) {
        html += '<h4>Decoded payload</h4><pre class="json">'
            + escapeHtml(JSON.stringify(rec.decodedPayload, null, 2)) + '</pre>';
    }
    html += '<h4>Response (' + rec.responseStatus + ')</h4><pre class="json">'
        + escapeHtml(JSON.stringify(rec.responseBody, null, 2)) + '</pre>';
    detail.innerHTML = html;

    summary.addEventListener('click', () => detail.classList.toggle('show'));
    li.appendChild(summary);
    li.appendChild(detail);
    return li;
}

// ---------------------------------------------------------------------------
// Boot
// ---------------------------------------------------------------------------
document.addEventListener('DOMContentLoaded', () => {
    document.getElementById('qr-input').value = SAMPLE_QR;
    document.getElementById('pay-reference').value = newReference();

    document.getElementById('btn-decode').addEventListener('click', decodeQr);
    document.getElementById('btn-reset-qr').addEventListener('click', () => {
        document.getElementById('qr-input').value = SAMPLE_QR;
        setStatus('decode-status', 'Reset to sample Fonepay QR.', 'info');
    });
    document.getElementById('btn-pay').addEventListener('click', pay);
    document.getElementById('btn-new-ref').addEventListener('click', () => {
        document.getElementById('pay-reference').value = newReference();
    });
    document.getElementById('btn-refresh-records').addEventListener('click', loadRecords);

    loadRecords();
});
