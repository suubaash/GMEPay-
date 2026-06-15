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

    // Feed
    document.getElementById('btn-clear-feed').addEventListener('click', clearFeed);

    // Load initial shop list
    loadShops();
    showView('view-register');
});
