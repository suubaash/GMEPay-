/*
 * GMEPay+ Transparency Console
 * --------------------------------------------------------------------------
 * Two views under one menu:
 *   • Live Calls  — every inter-service HTTP call (tap proxies), categorised by
 *                   feature group (Admin Actions / Txn Flow / Partner Setup /
 *                   Audit / Schedulers / Other); filter by group or see totality.
 *   • Databases   — every table in every service DB, with contents, via each
 *                   service's read-only /__data introspection endpoint.
 *
 * Extensible: POST /ingest lets services/schedulers push internal events that
 * aren't plain inter-service HTTP (e.g. scheduler ticks) into the same feed.
 *
 * Dependency-free Node. Run: node trace-console.js
 */
const http = require('http');

const DASH_PORT = 7099;
const MAX_CALLS = 4000;
const MAX_BODY = 24 * 1024;

// caller -> callee tap proxies. listen = proxy port, {host,port} = real service.
const EDGES = [
  { listen: 7101, host: '127.0.0.1', port: 8095, caller: 'admin-ui',         callee: 'ops-partner-bff' },
  { listen: 7102, host: '127.0.0.1', port: 18081, caller: 'ops-partner-bff',  callee: 'config-registry' },
  { listen: 7103, host: '127.0.0.1', port: 18082, caller: 'ops-partner-bff',  callee: 'transaction-mgmt' },
  { listen: 7104, host: '127.0.0.1', port: 8084, caller: 'sim-gmeremit',     callee: 'payment-executor' },
  { listen: 7105, host: '127.0.0.1', port: 9102, caller: 'sim-gmeremit',     callee: 'sim-scheme' },
  { listen: 7106, host: '127.0.0.1', port: 8090, caller: 'payment-executor', callee: 'scheme-adapter' },
  { listen: 7107, host: '127.0.0.1', port: 8083, caller: 'payment-executor', callee: 'transaction-mgmt' },
  { listen: 7108, host: '127.0.0.1', port: 9102, caller: 'scheme-adapter',   callee: 'sim-scheme' },
  // SendMN (Mongolia) + 9Pay (Vietnam) corridor edges — QR_SCHEME_ACCOMMODATION_PLAN Phase 5.
  // Ports are the run-fleet.ps1 assignments (adapters 18096/18097, sims 9108/9107). Like all
  // taps these capture only when a caller is pointed at the tap port; in normal fleet runs the
  // lib-errors /ingest self-reporter in both adapters covers these edges (the sims have no
  // lib-errors — standalone builds — so sim-side traffic is seen via the adapters' reports).
  { listen: 7109, host: '127.0.0.1', port: 18096, caller: 'payment-executor',       callee: 'scheme-adapter-sendmn' },
  { listen: 7110, host: '127.0.0.1', port: 9108,  caller: 'scheme-adapter-sendmn',  callee: 'sim-sendmn' },
  { listen: 7111, host: '127.0.0.1', port: 9107,  caller: 'scheme-adapter-ninepay', callee: 'sim-ninepay' },
  { listen: 7112, host: '127.0.0.1', port: 18097, caller: 'sim-ninepay',            callee: 'scheme-adapter-ninepay' }, // IPN push-back
];

// services exposing the read-only /__data introspection endpoint
const DB_SERVICES = { 'config-registry': 8080, 'transaction-mgmt': 8083, 'payment-executor': 8084, 'scheme-adapter': 8090 };

const CATEGORIES = ['Admin Actions', 'Partner / Config Setup', 'Txn Flow', 'Audit', 'Schedulers', 'Other'];

function categorize(caller, callee, path) {
  const p = (path || '').toLowerCase();
  if (caller && caller.indexOf('admin-ui') === 0) return 'Admin Actions';
  if (callee === 'ops-partner-bff') return 'Admin Actions';
  if (p.indexOf('/audit') >= 0) return 'Audit';
  if (callee === 'config-registry') return 'Partner / Config Setup';
  if (p.indexOf('/pay') >= 0 || p.indexOf('/payment') >= 0 || p.indexOf('/transaction') >= 0 ||
      p.indexOf('/scheme') >= 0 || p.indexOf('/qr') >= 0 ||
      callee === 'transaction-mgmt' || callee === 'scheme-adapter' ||
      (callee || '').indexOf('sim-scheme') === 0 ||
      // per-scheme adapter/sim edges (nepal, sendmn, ninepay, ...) are all money-path
      (callee || '').indexOf('scheme-adapter-') === 0 || (caller || '').indexOf('scheme-adapter-') === 0 ||
      (callee || '').indexOf('sim-sendmn') === 0 || (callee || '').indexOf('sim-ninepay') === 0 ||
      caller === 'sim-ninepay' ||
      caller === 'payment-executor' || caller === 'scheme-adapter' || caller === 'sim-gmeremit') return 'Txn Flow';
  return 'Other';
}

let seq = 0;
const calls = [];
function record(c) {
  c.id = ++seq;
  if (!c.category) c.category = categorize(c.caller, c.callee, c.path);
  calls.push(c);
  if (calls.length > MAX_CALLS) calls.shift();
}
function clip(buf) {
  if (!buf || buf.length === 0) return '';
  const s = Buffer.isBuffer(buf) ? buf.toString('utf8') : String(buf);
  return s.length > MAX_BODY ? s.slice(0, MAX_BODY) + '\n…[truncated]' : s;
}

// ---- tap proxies ----
for (const edge of EDGES) {
  http.createServer((cReq, cRes) => {
    const started = Date.now();
    const startedAt = new Date();
    const reqChunks = [];
    cReq.on('data', (d) => reqChunks.push(d));
    cReq.on('end', () => {
      const reqBody = Buffer.concat(reqChunks);
      const headers = Object.assign({}, cReq.headers);
      headers.host = edge.host + ':' + edge.port;
      const pReq = http.request({ host: edge.host, port: edge.port, method: cReq.method, path: cReq.url, headers }, (pRes) => {
        const resChunks = [];
        pRes.on('data', (d) => resChunks.push(d));
        pRes.on('end', () => {
          const resBody = Buffer.concat(resChunks);
          record({ ts: startedAt.toISOString(), caller: edge.caller, callee: edge.callee, method: cReq.method, path: cReq.url,
            status: pRes.statusCode, latencyMs: Date.now() - started, reqCt: cReq.headers['content-type'] || '',
            resCt: pRes.headers['content-type'] || '', reqBody: clip(reqBody), resBody: clip(resBody) });
          cRes.writeHead(pRes.statusCode, pRes.headers);
          cRes.end(resBody);
        });
      });
      pReq.on('error', (err) => {
        record({ ts: startedAt.toISOString(), caller: edge.caller, callee: edge.callee, method: cReq.method, path: cReq.url,
          status: 0, latencyMs: Date.now() - started, reqBody: clip(reqBody), resBody: 'PROXY ERROR: ' + err.message });
        cRes.writeHead(502, { 'content-type': 'application/json' });
        cRes.end(JSON.stringify({ error: 'trace-proxy down', detail: err.message }));
      });
      if (reqBody.length) pReq.write(reqBody);
      pReq.end();
    });
  }).listen(edge.listen, '127.0.0.1', () => console.log('tap ' + edge.listen + '  ' + edge.caller + ' -> ' + edge.callee));
}

function relay(port, path, res) {
  const r = http.request({ host: '127.0.0.1', port, path, method: 'GET', timeout: 9000 }, (pr) => {
    const b = []; pr.on('data', (d) => b.push(d));
    pr.on('end', () => { res.writeHead(pr.statusCode, { 'content-type': pr.headers['content-type'] || 'application/json' }); res.end(Buffer.concat(b)); });
  });
  r.on('error', (e) => { res.writeHead(502, { 'content-type': 'application/json' }); res.end(JSON.stringify({ error: 'service unreachable', detail: e.message })); });
  r.on('timeout', () => r.destroy());
  r.end();
}

// ---- dashboard + API ----
http.createServer((req, res) => {
  const u = new URL(req.url, 'http://localhost');
  const parts = u.pathname.split('/').filter(Boolean);
  if (u.pathname === '/api/calls') {
    const since = parseInt(u.searchParams.get('since') || '0', 10);
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ calls: calls.filter((c) => c.id > since), lastId: seq, categories: CATEGORIES }));
    return;
  }
  if (u.pathname === '/api/clear') { calls.length = 0; res.writeHead(200, { 'content-type': 'application/json' }); res.end('{"ok":true}'); return; }
  if (u.pathname === '/ingest' && req.method === 'POST') {
    const b = []; req.on('data', (d) => b.push(d));
    req.on('end', () => { try { const e = JSON.parse(Buffer.concat(b).toString('utf8') || '{}');
      record({ ts: e.ts || new Date().toISOString(), caller: e.caller || 'system', callee: e.callee || '-', method: e.method || 'EVENT',
        path: e.path || e.event || '', status: e.status == null ? 200 : e.status, latencyMs: e.latencyMs || 0,
        reqBody: typeof e.detail === 'string' ? e.detail : JSON.stringify(e.detail || e.req || {}, null, 2),
        resBody: typeof e.result === 'string' ? e.result : JSON.stringify(e.result || e.res || {}, null, 2),
        source: e.source || 'ingest',
        // Self-reported HTTP carries no category → let categorize() bucket it the same way
        // tapped calls are bucketed; only explicit (e.g. scheduler) categories are kept.
        category: e.category });
      res.writeHead(202, { 'content-type': 'application/json' }); res.end('{"ok":true}');
    } catch (err) { res.writeHead(400); res.end('{"error":"bad json"}'); } });
    return;
  }
  if (u.pathname === '/db/services') { res.writeHead(200, { 'content-type': 'application/json' }); res.end(JSON.stringify(DB_SERVICES)); return; }
  if (parts[0] === 'db' && parts[2] === 'tables' && parts.length === 3) { // /db/:svc/tables
    const port = DB_SERVICES[parts[1]]; if (!port) { res.writeHead(404); res.end('{}'); return; } relay(port, '/__data/tables', res); return;
  }
  if (parts[0] === 'db' && parts[2] === 'table' && parts.length === 4) { // /db/:svc/table/:name
    const port = DB_SERVICES[parts[1]]; if (!port) { res.writeHead(404); res.end('{}'); return; } relay(port, '/__data/tables/' + encodeURIComponent(parts[3]), res); return;
  }
  res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' }); res.end(PAGE);
}).listen(DASH_PORT, '127.0.0.1', () => console.log('\nGMEPay+ Transparency Console -> http://localhost:' + DASH_PORT + '\n'));

const PAGE = `<!doctype html><html><head><meta charset="utf-8"/><title>GMEPay+ Transparency Console</title><style>
:root{--bg:#0d1117;--card:#161b22;--line:#30363d;--fg:#e6edf3;--mut:#8b949e;--accent:#58a6ff}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:13px/1.5 ui-monospace,Menlo,Consolas,monospace}
header{position:sticky;top:0;background:#010409;border-bottom:1px solid var(--line);padding:9px 16px;display:flex;gap:14px;align-items:center;flex-wrap:wrap;z-index:6}
h1{font-size:15px;margin:0;font-weight:600;color:#fff}h1 span{color:var(--accent)}
.nav{display:flex;gap:4px} .nav button{font-size:13px;padding:6px 14px}
.nav button.on{background:var(--accent);color:#04101f;border-color:var(--accent);font-weight:700}
.pill{background:var(--card);border:1px solid var(--line);border-radius:20px;padding:3px 10px;color:var(--mut);font-size:12px}
.dot{display:inline-block;width:8px;height:8px;border-radius:50%;background:#3fb950;margin-right:5px;animation:pulse 1.4s infinite}
@keyframes pulse{0%,100%{opacity:1}50%{opacity:.3}}
input,button,select{background:var(--card);border:1px solid var(--line);color:var(--fg);border-radius:6px;padding:5px 9px;font:inherit}
button{cursor:pointer}button:hover{border-color:var(--accent)}input{min-width:200px}
.chips{display:flex;gap:6px;flex-wrap:wrap;padding:8px 16px;border-bottom:1px solid var(--line);background:#0b1017;position:sticky;top:48px;z-index:5}
.chip{border:1px solid var(--line);border-radius:20px;padding:3px 11px;cursor:pointer;font-size:12px;color:var(--mut);user-select:none}
.chip.on{color:#fff;border-color:currentColor}
.cat-Admin{color:#d2a8ff}.cat-Partner{color:#58a6ff}.cat-Txn{color:#7ee787}.cat-Audit{color:#39c5cf}.cat-Sched{color:#ffa657}.cat-Other{color:#8b949e}
table{width:100%;border-collapse:collapse}th{position:sticky;top:90px;background:#010409;text-align:left;padding:7px 10px;color:var(--mut);font-weight:600;border-bottom:1px solid var(--line);font-size:11px;text-transform:uppercase}
td{padding:6px 10px;border-bottom:1px solid #21262d;vertical-align:top}
tr.call{cursor:pointer}tr.call:hover{background:#1c2330}tr.new td{animation:flash 1.3s ease-out}
@keyframes flash{from{background:#1f6feb55}to{background:transparent}}
.catchip{padding:1px 7px;border-radius:10px;font-size:11px;border:1px solid currentColor;white-space:nowrap}
.edge{white-space:nowrap}.caller{color:#d2a8ff}.callee{color:#7ee787}.arrow{color:var(--mut);margin:0 5px}
.m{font-weight:700;padding:1px 6px;border-radius:4px;font-size:11px;background:#21262d}.m.GET{color:#7ee787}.m.POST{color:#ffa657}.m.PATCH,.m.PUT{color:#58a6ff}.m.DELETE,.m.EVENT{color:#ff7b72}
.s2{color:#3fb950}.s3{color:#58a6ff}.s4{color:#d29922}.s5,.s0{color:#f85149;font-weight:700}
.path{color:var(--fg)}.lat{color:var(--mut)}.t{color:var(--mut);white-space:nowrap}
.detail td{padding:0;background:#0b0f14}.bodies{display:grid;grid-template-columns:1fr 1fr;gap:1px;background:var(--line)}
.body{background:#0b0f14;padding:10px 14px;overflow:auto;max-height:360px}.body h4{margin:0 0 6px;color:var(--mut);font-size:11px;text-transform:uppercase}
pre{margin:0;white-space:pre-wrap;word-break:break-word;color:#cdd9e5}
.wrap{padding:0 0 40px}.dbwrap{padding:14px 16px;display:grid;grid-template-columns:230px 1fr;gap:16px}
.svc,.tbl{display:flex;flex-direction:column;gap:4px}.svc button,.tbl button{text-align:left}
.tbl button.on,.svc button.on{border-color:var(--accent);color:#fff}
.grid{overflow:auto;border:1px solid var(--line);border-radius:8px;max-height:78vh}
.grid table{font-size:12px}.grid th{position:sticky;top:0;top:0}.grid td{white-space:nowrap;max-width:380px;overflow:hidden;text-overflow:ellipsis}
.muted{color:var(--mut)}.hidden{display:none}h3{margin:4px 0 8px;font-size:13px;color:var(--mut)}
</style></head><body>
<header>
  <h1>GME<span>Pay+</span> Transparency Console</h1>
  <div class="nav"><button id="navCalls" class="on" onclick="show('calls')">▶ Live Calls</button><button id="navDb" onclick="show('db')">▦ Databases</button></div>
  <span class="pill" id="livepill"><span class="dot"></span><span id="status">live</span></span>
  <span class="pill" id="count">0 calls</span>
  <input id="filter" placeholder="search path / service / body…" style="flex:1;max-width:320px"/>
  <label class="pill"><input type="checkbox" id="auto" checked style="min-width:auto"/> autoscroll</label>
  <button id="pause" onclick="togglePause()">⏸ pause</button>
  <button onclick="clearCalls()">clear</button>
</header>

<div id="view-calls">
  <div class="chips" id="chips"></div>
  <div class="wrap"><table><thead><tr><th style="width:92px">time</th><th style="width:150px">group</th><th style="width:210px">edge</th><th style="width:56px">method</th><th>path</th><th style="width:50px">status</th><th style="width:60px">latency</th></tr></thead><tbody id="rows"></tbody></table></div>
</div>

<div id="view-db" class="hidden">
  <div class="dbwrap">
    <div><h3>SERVICE DBs</h3><div class="svc" id="svcList"></div><h3 style="margin-top:16px">TABLES</h3><div class="tbl" id="tblList"><span class="muted">pick a service</span></div></div>
    <div><h3 id="gridTitle">TABLE CONTENTS</h3><div class="grid" id="grid"><span class="muted" style="padding:14px;display:block">pick a table to view all its rows</span></div></div>
  </div>
</div>

<script>
let last=0,paused=false,rows=[],activeCat='all';
const tbody=document.getElementById('rows'),filterEl=document.getElementById('filter');
const fmt=t=>{const d=new Date(t);return d.toLocaleTimeString('en-GB')+'.'+String(d.getMilliseconds()).padStart(3,'0')};
const sClass=s=>s===0?'s0':'s'+String(s)[0];
const esc=s=>(s||'').replace(/[&<>]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;'}[c]));
const catKey=c=>({'Admin Actions':'Admin','Partner / Config Setup':'Partner','Txn Flow':'Txn','Audit':'Audit','Schedulers':'Sched','Other':'Other'}[c]||'Other');
function pretty(b,ct){if(!b)return '<span class="muted">(empty)</span>';if((ct||'').includes('json')||/^[\\[{]/.test(b.trim())){try{return esc(JSON.stringify(JSON.parse(b),null,2))}catch(e){}}return esc(b)}
function show(v){document.getElementById('view-calls').className=v==='calls'?'':'hidden';document.getElementById('view-db').className=v==='db'?'':'hidden';document.getElementById('navCalls').className=v==='calls'?'on':'';document.getElementById('navDb').className=v==='db'?'on':'';if(v==='db')loadServices()}
function match(c){if(activeCat!=='all'&&c.category!==activeCat)return false;const f=filterEl.value.toLowerCase();if(!f)return true;return (c.caller+' '+c.callee+' '+c.method+' '+c.path+' '+c.status+' '+c.category+' '+c.reqBody+' '+c.resBody).toLowerCase().includes(f)}
function renderChips(){const counts={all:rows.length};rows.forEach(c=>counts[c.category]=(counts[c.category]||0)+1);const cats=['all'].concat(window._cats||[]);document.getElementById('chips').innerHTML=cats.map(c=>{const on=activeCat===c?'on':'';const k=c==='all'?'':('cat-'+catKey(c));const n=c==='all'?'All (totality)':c;return '<span class="chip '+k+' '+on+'" onclick="setCat(\\''+c+'\\')">'+n+' '+(counts[c]||0)+'</span>'}).join('')}
function setCat(c){activeCat=c;renderChips();render()}
function render(){tbody.innerHTML='';rows.filter(match).slice().sort((a,b)=>(new Date(a.ts)-new Date(b.ts))||(a.id-b.id)).forEach(c=>{
  const tr=document.createElement('tr');tr.className='call'+(c._new?' new':'');
  tr.innerHTML='<td class="t">'+fmt(c.ts)+'</td>'+
    '<td><span class="catchip cat-'+catKey(c.category)+'">'+esc(c.category)+'</span></td>'+
    '<td class="edge"><span class="caller">'+esc(c.caller)+'</span><span class="arrow">→</span><span class="callee">'+esc(c.callee)+'</span></td>'+
    '<td><span class="m '+c.method+'">'+c.method+'</span></td>'+
    '<td class="path">'+esc(c.path)+'</td>'+
    '<td class="'+sClass(c.status)+'">'+(c.status||'ERR')+'</td>'+
    '<td class="lat">'+c.latencyMs+'ms</td>';
  const det=document.createElement('tr');det.className='detail';det.style.display='none';
  det.innerHTML='<td colspan="7"><div class="bodies"><div class="body"><h4>▶ request</h4><pre>'+pretty(c.reqBody,c.reqCt)+'</pre></div><div class="body"><h4>◀ response · '+(c.status||'ERR')+'</h4><pre>'+pretty(c.resBody,c.resCt)+'</pre></div></div></td>';
  tr.onclick=()=>{det.style.display=det.style.display==='none'?'':'none'};tbody.appendChild(tr);tbody.appendChild(det);c._new=false})}
async function poll(){if(paused)return;try{const r=await fetch('/api/calls?since='+last);const j=await r.json();window._cats=j.categories;
  if(j.calls.length){j.calls.forEach(c=>c._new=true);rows=rows.concat(j.calls).slice(-4000);last=j.lastId;document.getElementById('count').textContent=rows.length+' calls';renderChips();render();
    if(document.getElementById('auto').checked&&!document.getElementById('view-calls').classList.contains('hidden'))window.scrollTo(0,document.body.scrollHeight)}
  document.getElementById('status').textContent='live'}catch(e){document.getElementById('status').textContent='disconnected'}}
function togglePause(){paused=!paused;document.getElementById('pause').textContent=paused?'▶ resume':'⏸ pause'}
async function clearCalls(){await fetch('/api/clear');rows=[];last=0;render();renderChips();document.getElementById('count').textContent='0 calls'}
filterEl.oninput=render;setInterval(poll,750);poll();

// ---- Databases ----
let curSvc=null;
async function loadServices(){const s=await (await fetch('/db/services')).json();document.getElementById('svcList').innerHTML=Object.keys(s).map(n=>'<button onclick="loadTables(\\''+n+'\\')" id="svc-'+n+'">'+n+' <span class="muted">:'+s[n]+'</span></button>').join('')}
async function loadTables(svc){curSvc=svc;document.querySelectorAll('#svcList button').forEach(b=>b.className=b.id==='svc-'+svc?'on':'');
  document.getElementById('tblList').innerHTML='<span class="muted">loading…</span>';
  try{const t=await (await fetch('/db/'+svc+'/tables')).json();
    document.getElementById('tblList').innerHTML=(Array.isArray(t)?t:[]).map(x=>'<button onclick="loadTable(\\''+x.name+'\\',this)">'+x.name+' <span class="muted">('+(x.rowCount!=null?x.rowCount:'?')+')</span></button>').join('')||'<span class="muted">no tables</span>'}
  catch(e){document.getElementById('tblList').innerHTML='<span class="muted">error: '+e.message+'</span>'}}
async function loadTable(name,btn){document.querySelectorAll('#tblList button').forEach(b=>b.className='');if(btn)btn.className='on';
  document.getElementById('gridTitle').textContent=curSvc+' · '+name;document.getElementById('grid').innerHTML='<span class="muted" style="padding:14px;display:block">loading…</span>';
  try{const d=await (await fetch('/db/'+curSvc+'/table/'+encodeURIComponent(name))).json();
    if(!d.columns){document.getElementById('grid').innerHTML='<pre style="padding:14px">'+esc(JSON.stringify(d,null,2))+'</pre>';return}
    let h='<table><thead><tr>'+d.columns.map(c=>'<th>'+esc(c)+'</th>').join('')+'</tr></thead><tbody>';
    h+=d.rows.map(r=>'<tr>'+r.map(v=>'<td title="'+esc(String(v))+'">'+esc(v==null?'∅':String(v))+'</td>').join('')+'</tr>').join('');
    h+='</tbody></table>';document.getElementById('grid').innerHTML=h;
    document.getElementById('gridTitle').textContent=curSvc+' · '+name+'  ('+d.rowCount+' rows'+(d.truncated?', showing first 500':'')+')'}
  catch(e){document.getElementById('grid').innerHTML='<span class="muted" style="padding:14px;display:block">error: '+e.message+'</span>'}}
</script></body></html>`;
