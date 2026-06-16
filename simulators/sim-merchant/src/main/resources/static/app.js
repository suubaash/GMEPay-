/**
 * sim-merchant UI — plain vanilla JS, no build step, no TypeScript.
 *
 * Three views:
 *   1. Register / pick shop
 *   2. Counter display (store QR — MPM_STATIC)
 *   3. POS / till (dynamic QR — MPM_DYNAMIC)
 *
 * Plus a persistent "Payments received" panel that polls every 2 s.
 */

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------
let currentShop = null;   // { merchantId, name, ... }
let lastSeq     = 0;
let pollTimer   = null;

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------
function api(path, opts) {
    return fetch(path, opts).then(r => r.json().then(body => ({ ok: r.ok, status: r.status, body })));
}

function fmt(amount, currency) {
    if (currency === 'KRW' || currency === 'KHR') {
        return (currency === 'KRW' ? '₩' : '฿') + Number(amount).toLocaleString();
    }
    return amount + ' ' + (currency || '');
}

function timeStr(at) {
    if (!at) return '';
    const d = new Date(at);
    return d.toLocaleTimeString('ko-KR', { timeZone: 'Asia/Seoul', hour12: false });
}

function renderQr(payload, containerId) {
    const container = document.getElementById(containerId);
    if (!container) return;
    container.innerHTML = '';
    try {
        // Use qrcode-generator (Kazuhiko Arase): qrcode(typeNumber 0=auto, errorLevel)
        const qr = qrcode(0, 'M');
        qr.addData(payload, 'Byte');
        qr.make();
        // createSvgTag returns a scalable SVG string
        const svg = qr.createSvgTag({ cellSize: 6, margin: 10, scalable: false });
        container.innerHTML = svg;
    } catch (e) {
        container.innerHTML = '<p style="color:red">QR render error: ' + e + '</p>';
    }
}

// ---------------------------------------------------------------------------
// View management
// ---------------------------------------------------------------------------
function showView(id) {
    document.querySelectorAll('.view').forEach(v => v.classList.remove('active'));
    document.getElementById(id).classList.add('active');
    document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
    const btn = document.querySelector(`.tab-btn[data-view="${id}"]`);
    if (btn) btn.classList.add('active');
}

// ---------------------------------------------------------------------------
// View 1 — Register / pick shop
// ---------------------------------------------------------------------------
async function loadShops() {
    const res = await api('/v1/merchant/shops');
    const list = document.getElementById('shop-list');
    list.innerHTML = '';
    if (!res.ok || !Array.isArray(res.body) || res.body.length === 0) {
        list.innerHTML = '<li class="empty">No shops registered yet.</li>';
        return;
    }
    res.body.forEach(shop => {
        const li = document.createElement('li');
        li.className = 'shop-item' + (currentShop && currentShop.merchantId === shop.merchantId ? ' selected' : '');
        li.innerHTML = `
            <span class="shop-name">${shop.name}</span>
            <span class="shop-meta">${shop.city} &middot; MCC ${shop.mcc}</span>
            <span class="shop-id">${shop.merchantId}</span>
            ${shop.merchantType ? `<span class="badge badge-${shop.merchantType.toLowerCase()}">${shop.merchantType}</span>` : ''}
        `;
        li.addEventListener('click', () => selectShop(shop));
        list.appendChild(li);
    });
}

function selectShop(shop) {
    currentShop = shop;
    lastSeq     = 0;
    document.getElementById('selected-shop').textContent = shop.name + ' (' + shop.merchantId + ')';
    document.querySelectorAll('.shop-item').forEach(li => li.classList.remove('selected'));
    // Highlight selected
    loadShops();
    startPolling();
    setStatus('register-status', `Active shop: ${shop.name}`, 'ok');
}

async function registerShop(e) {
    e.preventDefault();
    const form = e.target;
    const body = {
        name:                form.name_field.value.trim(),
        city:                form.city.value.trim(),
        mcc:                 form.mcc.value.trim(),
        businessRegNo:       form.businessRegNo.value.trim() || undefined,
        subMerchantId:       form.subMerchantId.value.trim() || undefined,
        kftcInstitutionCode: form.kftcInstitutionCode.value.trim() || undefined,
        merchantType:        form.merchantType.value || undefined,
    };
    setStatus('register-status', 'Registering...', 'info');
    const res = await api('/v1/merchant/shops', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
    });
    if (res.ok) {
        setStatus('register-status', `Registered: ${res.body.merchantId}`, 'ok');
        form.reset();
        await loadShops();
        selectShop(res.body);
    } else if (res.status === 503) {
        setStatus('register-status', 'sim-scheme is down — start it on :9102 first.', 'error');
    } else {
        setStatus('register-status', 'Error: ' + JSON.stringify(res.body), 'error');
    }
}

// ---------------------------------------------------------------------------
// View 2 — Counter display (store QR)
// ---------------------------------------------------------------------------
async function loadCounterQr() {
    if (!currentShop) {
        setStatus('counter-status', 'Select a shop first.', 'error');
        return;
    }
    setStatus('counter-status', 'Fetching store QR...', 'info');
    const res = await api('/v1/merchant/shops/' + currentShop.merchantId + '/store-qr');
    if (res.status === 503) {
        setStatus('counter-status', 'sim-scheme is down — start it on :9102.', 'error');
        return;
    }
    if (!res.ok) {
        setStatus('counter-status', 'Error: ' + JSON.stringify(res.body), 'error');
        return;
    }
    const data = res.body;
    document.getElementById('counter-shop-name').textContent = data.merchantName || currentShop.name;
    document.getElementById('counter-scheme-badge').textContent = data.schemeId + ' · ' + data.currency;
    renderQr(data.qrPayload, 'counter-qr');
    document.getElementById('counter-payload-text').value = data.qrPayload;
    document.getElementById('counter-qr-section').style.display = '';
    setStatus('counter-status', 'Store QR ready — display this to customers.', 'ok');
}

function copyCounterPayload() {
    const txt = document.getElementById('counter-payload-text');
    navigator.clipboard.writeText(txt.value).then(() => {
        setStatus('counter-status', 'Payload copied to clipboard.', 'ok');
    });
}

// ---------------------------------------------------------------------------
// View 3 — POS / till
// ---------------------------------------------------------------------------
async function chargeAmount(e) {
    e.preventDefault();
    if (!currentShop) {
        setStatus('pos-status', 'Select a shop first (View 1).', 'error');
        return;
    }
    const amountStr = document.getElementById('pos-amount').value.trim();
    const currency  = document.getElementById('pos-currency').value || 'KRW';
    if (!amountStr || isNaN(amountStr) || Number(amountStr) <= 0) {
        setStatus('pos-status', 'Enter a valid amount.', 'error');
        return;
    }
    setStatus('pos-status', 'Generating dynamic QR...', 'info');
    const res = await api('/v1/merchant/shops/' + currentShop.merchantId + '/charge', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ amount: amountStr, currency }),
    });
    if (res.status === 503) {
        setStatus('pos-status', 'sim-scheme is down — start it on :9102.', 'error');
        return;
    }
    if (!res.ok) {
        setStatus('pos-status', 'Error: ' + JSON.stringify(res.body), 'error');
        return;
    }
    const data = res.body;
    document.getElementById('pos-qr-amount').textContent = fmt(data.amount, data.currency);
    renderQr(data.qrPayload, 'pos-qr');
    document.getElementById('pos-payload-text').value = data.qrPayload;
    document.getElementById('pos-qr-section').style.display = '';
    setStatus('pos-status', 'Show this QR to the customer.', 'ok');
}

function copyPosPayload() {
    const txt = document.getElementById('pos-payload-text');
    navigator.clipboard.writeText(txt.value).then(() => {
        setStatus('pos-status', 'Payload copied to clipboard.', 'ok');
    });
}

// ---------------------------------------------------------------------------
// View 4 — ZeroPay 전문 (jeonmun) / spec-faithful wire
// ---------------------------------------------------------------------------
function escapeHtml(s) {
    return String(s == null ? '' : s).replace(/[&<>"]/g,
        c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
}

function zpErr(res) {
    const b = res.body;
    if (res.status === 404) return 'No shop selected / shop not registered in this terminal.';
    return 'Error ' + res.status + ': ' + (b && (b.message || JSON.stringify(b)));
}

function renderQrFields(containerId, qr) {
    const kv = (k, v) => (v == null || v === '')
        ? '' : `<div class="kv"><span class="k">${k}</span><span class="v">${escapeHtml(v)}</span></div>`;
    document.getElementById(containerId).innerHTML =
        kv('mode', qr.mode) +
        kv('QR구분 (division)', qr.qrDivision) +
        kv('등록기관ID (registrar)', qr.registrarId) +
        kv('가맹점ID (merchant)', qr.merchantId) +
        kv('거래일련번호 (serial)', qr.qrSerial) +
        kv('체크문자 (check)', qr.checkChar) +
        kv('통화 (currency)', (qr.currencyAlpha || '') + ' / ' + (qr.currencyNumeric || '')) +
        kv('금액 (amount)', qr.amount == null ? '' : ('₩' + Number(qr.amount).toLocaleString()));
}

function renderWire(containerId, wire) {
    const rows = (wire.fields || []).map(f => `
        <tr>
          <td class="num">${f.no}</td>
          <td>${escapeHtml(f.name)}</td>
          <td>${escapeHtml(f.key)}</td>
          <td>${f.type}</td>
          <td class="num">${f.offset}</td>
          <td class="num">${f.length}</td>
          <td class="val">${escapeHtml(f.value)}</td>
        </tr>`).join('');
    document.getElementById(containerId).innerHTML = `
        <div class="wire-meta">
          <b>${escapeHtml(wire.protocol)}</b><br>
          거래구분 ${wire.txnDivision} · 전문구분 ${wire.messageType} · ${escapeHtml(wire.description)}<br>
          ${wire.lengthBytes} bytes · ${wire.charset}
        </div>
        <table class="wire-table">
          <thead><tr>
            <th>No</th><th>필드</th><th>key</th><th>type</th><th>off</th><th>len</th><th>value</th>
          </tr></thead>
          <tbody>${rows}</tbody>
        </table>
        <div class="wire-hex" title="raw ${wire.charset} frame, hex">${wire.hex}</div>`;
}

async function zpStatic() {
    if (!currentShop) { setStatus('jeonmun-status', 'Select a shop first (View 1).', 'error'); return; }
    setStatus('jeonmun-status', 'Building static QR...', 'info');
    const res = await api('/v1/merchant/zeropay/' + currentShop.merchantId + '/static-qr');
    if (!res.ok) { setStatus('jeonmun-status', zpErr(res), 'error'); return; }
    document.getElementById('zp-static-out').style.display = '';
    renderQr(res.body.qrPayload, 'zp-static-qr');
    renderQrFields('zp-static-fields', res.body);
    setStatus('jeonmun-status', 'Static QR (QR구분 1) built.', 'ok');
}

async function zpDynamic() {
    if (!currentShop) { setStatus('jeonmun-status', 'Select a shop first (View 1).', 'error'); return; }
    const amt = document.getElementById('zp-dyn-amount').value.trim();
    if (!amt || isNaN(amt) || Number(amt) <= 0) {
        setStatus('jeonmun-status', 'Enter a valid amount.', 'error'); return;
    }
    setStatus('jeonmun-status', 'Building dynamic charge + 420000 전문...', 'info');
    const res = await api('/v1/merchant/zeropay/' + currentShop.merchantId + '/dynamic-qr', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ amount: amt }),
    });
    if (!res.ok) { setStatus('jeonmun-status', zpErr(res), 'error'); return; }
    document.getElementById('zp-dyn-out').style.display = '';
    renderQr(res.body.qr.qrPayload, 'zp-dyn-qr');
    renderQrFields('zp-dyn-fields', res.body.qr);
    renderWire('zp-dyn-wire', res.body.wire);
    setStatus('jeonmun-status', 'Dynamic charge QR (QR구분 2) + 420000 전문 built.', 'ok');
}

async function zpResult() {
    if (!currentShop) { setStatus('jeonmun-status', 'Select a shop first (View 1).', 'error'); return; }
    const amt = document.getElementById('zp-res-amount').value.trim();
    if (!amt || isNaN(amt) || Number(amt) <= 0) {
        setStatus('jeonmun-status', 'Enter a valid amount.', 'error'); return;
    }
    const approval = document.getElementById('zp-res-approval').value.trim();
    const body = { amount: amt };
    if (approval) body.approvalNo = approval;
    setStatus('jeonmun-status', 'Building 500000 결제결과등록 전문...', 'info');
    const res = await api('/v1/merchant/zeropay/' + currentShop.merchantId + '/static-result', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
    });
    if (!res.ok) { setStatus('jeonmun-status', zpErr(res), 'error'); return; }
    document.getElementById('zp-res-out').style.display = '';
    renderWire('zp-res-wire', res.body);
    setStatus('jeonmun-status', 'Static-result 500000 전문 built.', 'ok');
}

// ---------------------------------------------------------------------------
// Payment feed polling
// ---------------------------------------------------------------------------
function startPolling() {
    if (pollTimer) clearInterval(pollTimer);
    pollTimer = setInterval(pollPayments, 2000);
    pollPayments(); // immediate first call
}

async function pollPayments() {
    if (!currentShop) return;
    const res = await api('/v1/merchant/shops/' + currentShop.merchantId + '/payments?since=' + lastSeq)
        .catch(() => null);
    if (!res || !res.ok) return;
    const data = res.body;
    if (!data.events || data.events.length === 0) return;
    data.events.forEach(ev => appendEvent(ev));
    lastSeq = data.latestSeq;
}

function appendEvent(ev) {
    const feed = document.getElementById('feed-list');
    const empty = feed.querySelector('.empty');
    if (empty) empty.remove();

    // Find existing row for this authId to update it in-place
    const existingRow = feed.querySelector(`[data-auth-id="${ev.authId}"]`);
    const statusClass = ev.status === 'APPROVED' ? 'status-approved'
                      : ev.status === 'CAPTURED' ? 'status-captured'
                      : 'status-refunded';

    if (existingRow) {
        existingRow.querySelector('.ev-status').textContent = ev.status;
        existingRow.querySelector('.ev-status').className = 'ev-status ' + statusClass;
        existingRow.querySelector('.ev-time').textContent = timeStr(ev.at);
        if (ev.schemeTxnRef) {
            existingRow.querySelector('.ev-txnref').textContent = ev.schemeTxnRef;
        }
    } else {
        const li = document.createElement('li');
        li.className = 'feed-item';
        li.setAttribute('data-auth-id', ev.authId);
        li.innerHTML = `
            <span class="ev-amount">${fmt(ev.amount, ev.currency)}</span>
            <span class="ev-status ${statusClass}">${ev.status}</span>
            <span class="ev-time">${timeStr(ev.at)}</span>
            <span class="ev-payer" title="${ev.payerRef}">${ev.payerRef.substring(0, 16)}</span>
            <span class="ev-txnref">${ev.schemeTxnRef || ''}</span>
        `;
        feed.prepend(li);
    }
}

function clearFeed() {
    lastSeq = 0;
    document.getElementById('feed-list').innerHTML = '<li class="empty">No payments yet.</li>';
}

// ---------------------------------------------------------------------------
// Utility
// ---------------------------------------------------------------------------
function setStatus(id, msg, type) {
    const el = document.getElementById(id);
    if (!el) return;
    el.textContent = msg;
    el.className = 'status-msg status-' + type;
}

// ---------------------------------------------------------------------------
// Boot
// ---------------------------------------------------------------------------
document.addEventListener('DOMContentLoaded', () => {
    // Tab navigation
    document.querySelectorAll('.tab-btn').forEach(btn => {
        btn.addEventListener('click', () => {
            const view = btn.getAttribute('data-view');
            showView(view);
            if (view === 'view-counter' && currentShop) loadCounterQr();
        });
    });

    // Register form
    document.getElementById('register-form').addEventListener('submit', registerShop);

    // Counter view
    document.getElementById('btn-refresh-qr').addEventListener('click', loadCounterQr);
    document.getElementById('btn-copy-counter').addEventListener('click', copyCounterPayload);

    // POS view
    document.getElementById('pos-form').addEventListener('submit', chargeAmount);
    document.getElementById('btn-copy-pos').addEventListener('click', copyPosPayload);

    // ZeroPay 전문 view
    document.getElementById('zp-static-btn').addEventListener('click', zpStatic);
    document.getElementById('zp-dyn-btn').addEventListener('click', zpDynamic);
    document.getElementById('zp-res-btn').addEventListener('click', zpResult);

    // Feed
    document.getElementById('btn-clear-feed').addEventListener('click', clearFeed);

    // Load initial shop list
    loadShops();
    showView('view-register');
});
