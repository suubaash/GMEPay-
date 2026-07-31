/* Headless screenshot driver via Chrome DevTools Protocol (Node 22 global WebSocket, no deps).
 * Launches against an Edge instance already running with --remote-debugging-port=9222.
 * Captures the premium admin-ui: login (no auth) + authed pages (injects a real BFF token). */
const http = require('http');
const fs = require('fs');

const OUT = 'D:\\GMEPay+\\code\\.smoke\\shots';
const BASE = 'http://localhost:3011';
const TOKEN = process.env.GMEPAY_TOKEN || 'dev';

function getJson(path) {
  return new Promise((res, rej) => {
    const r = http.get('http://127.0.0.1:9222' + path, (x) => {
      let b = ''; x.on('data', (d) => (b += d)); x.on('end', () => { try { res(JSON.parse(b)); } catch (e) { rej(e); } });
    });
    r.on('error', rej); r.setTimeout(4000, () => r.destroy(new Error('cdp http timeout')));
  });
}
async function findPage() {
  for (let i = 0; i < 50; i++) {
    try { const t = await getJson('/json/list'); const p = t.find((x) => x.type === 'page' && x.webSocketDebuggerUrl); if (p) return p; } catch (e) {}
    await new Promise((r) => setTimeout(r, 500));
  }
  throw new Error('no CDP page target');
}
(async () => {
  const page = await findPage();
  const ws = new WebSocket(page.webSocketDebuggerUrl);
  let id = 0; const pending = {}, evs = {};
  ws.addEventListener('message', (e) => {
    const m = JSON.parse(e.data);
    if (m.id && pending[m.id]) { pending[m.id](m.result); delete pending[m.id]; }
    else if (m.method && evs[m.method] && evs[m.method].length) { (evs[m.method].shift())(m.params); }
  });
  const send = (method, params = {}) => new Promise((r) => { const i = ++id; pending[i] = r; ws.send(JSON.stringify({ id: i, method, params })); });
  const once = (method) => new Promise((r) => { (evs[method] = evs[method] || []).push(r); });
  await new Promise((r) => ws.addEventListener('open', r));
  await send('Page.enable'); await send('Runtime.enable');
  await send('Emulation.setDeviceMetricsOverride', { width: 1512, height: 950, deviceScaleFactor: 1, mobile: false });
  async function nav(url, waitMs = 3000) {
    const l = once('Page.loadEventFired');
    await send('Page.navigate', { url });
    await Promise.race([l, new Promise((r) => setTimeout(r, 7000))]);
    await new Promise((r) => setTimeout(r, waitMs));
  }
  async function shot(name) {
    const r = await send('Page.captureScreenshot', { format: 'png' });
    const buf = Buffer.from(r.data, 'base64');
    fs.writeFileSync(OUT + '\\' + name, buf);
    console.log('shot ' + name + ' ' + Math.round(buf.length / 1024) + 'KB');
  }
  // 1) login (no auth needed)
  await nav(BASE + '/login'); await shot('01-login.png');
  // 2) inject a real BFF session, then authed pages
  await nav(BASE + '/login', 1000);
  await send('Runtime.evaluate', { expression:
    "localStorage.setItem('gmepay.adminToken'," + JSON.stringify(TOKEN) + ");" +
    "localStorage.setItem('gmepay.adminUser','operator');" +
    "localStorage.setItem('gmepay.adminRole','ADMIN');" +
    "localStorage.setItem('gmepay.adminTokenExpiresAt',String(Date.now()+3600000));" });
  await nav(BASE + '/', 3400); await shot('02-dashboard.png');
  await nav(BASE + '/partners', 3400); await shot('03-partners.png');
  await nav(BASE + '/audit', 3200); await shot('04-audit.png');
  ws.close(); setTimeout(() => process.exit(0), 300);
})().catch((e) => { console.error('ERR ' + e.message); process.exit(1); });
