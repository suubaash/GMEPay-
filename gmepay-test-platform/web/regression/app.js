const $ = (s) => document.querySelector(s);
const el = (t, cls, txt) => { const e = document.createElement(t); if (cls) e.className = cls; if (txt != null) e.textContent = txt; return e; };
async function api(path, opts) {
  const res = await fetch(path, opts);
  const body = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(body.error || res.statusText);
  return body;
}
const fmt = (v) => v === undefined ? '∅' : typeof v === 'object' ? JSON.stringify(v) : String(v);
const tShort = (iso) => iso.replace('T', ' ').replace(/\..*/, '');

let CASES = [];
const activeGroups = new Set();

async function loadCases() {
  const data = await api('/api/reg/cases');
  CASES = data.cases;
  $('#env').innerHTML = `host <b>${data.host}</b> · mockVersion <b>${data.mockVersion}</b>`;
  $('#caseCount').textContent = `(${CASES.length})`;

  const groups = [...new Set(CASES.map((c) => c.group))];
  groups.forEach((g) => activeGroups.add(g));
  const gc = $('#groups'); gc.innerHTML = '';
  groups.forEach((g) => {
    const lab = el('label'); const cb = el('input'); cb.type = 'checkbox'; cb.checked = true;
    cb.onchange = () => { cb.checked ? activeGroups.add(g) : activeGroups.delete(g); renderCases(); };
    lab.append(cb, document.createTextNode(g));
    gc.append(lab);
  });
  renderCases();
}

function renderCases() {
  const ul = $('#cases'); ul.innerHTML = '';
  CASES.filter((c) => activeGroups.has(c.group)).forEach((c) => {
    const li = el('li');
    li.append(el('span', `badge ${c.group}`, c.group));
    const nm = el('span', 'nm'); nm.append(el('div', null, c.name), el('div', 'id', c.id));
    li.append(nm);
    li.append(el('span', 'ro', c.readOnly ? 'read-only' : 'writes'));
    ul.append(li);
  });
}

async function loadRuns() {
  const { runs } = await api('/api/reg/runs');
  const ul = $('#runs'); ul.innerHTML = '';
  if (!runs.length) ul.append(el('li', 'empty', 'No runs yet — record one.'));
  runs.forEach((r) => {
    const li = el('li');
    li.append(el('span', 'rl', r.label), el('span', 'rt', tShort(r.startedAt)),
      el('span', null, `${r.okCount}/${r.caseCount} ok`));
    ul.append(li);
  });
  const opts = runs.map((r) => `<option value="${r.id}">${r.label} · ${tShort(r.startedAt)}</option>`).join('');
  $('#beforeSel').innerHTML = opts;
  $('#afterSel').innerHTML = opts;
  if (runs.length >= 2) { $('#afterSel').selectedIndex = 0; $('#beforeSel').selectedIndex = 1; }
}

async function record() {
  const btn = $('#recordBtn'); const msg = $('#recordMsg');
  const label = $('#label').value.trim() || 'run';
  const groups = [...activeGroups];
  btn.disabled = true; msg.className = 'msg'; msg.textContent = 'Recording…';
  try {
    const r = await api('/api/reg/run', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ label, groups }),
    });
    msg.className = 'msg ok'; msg.textContent = `Recorded "${r.label}" · ${r.caseCount} cases`;
    $('#label').value = label === 'before' ? 'after' : '';
    await loadRuns();
  } catch (e) {
    msg.className = 'msg err'; msg.textContent = e.message;
  } finally { btn.disabled = false; }
}

async function compare() {
  const before = $('#beforeSel').value, after = $('#afterSel').value;
  const rep = $('#report'), sum = $('#summary');
  sum.innerHTML = ''; rep.innerHTML = '';
  if (!before || !after) { rep.append(el('div', 'empty', 'Need two runs to compare.')); return; }
  if (before === after) { rep.append(el('div', 'empty', 'Pick two different runs.')); return; }
  try {
    const report = await api(`/api/reg/diff?before=${encodeURIComponent(before)}&after=${encodeURIComponent(after)}`);
    renderReport(report);
  } catch (e) {
    rep.append(el('div', 'empty', e.message));
  }
}

function renderReport(report) {
  const sum = $('#summary'); sum.innerHTML = '';
  const order = ['changed', 'error', 'only-after', 'only-before', 'unchanged'];
  order.forEach((k) => {
    if (!report.summary[k]) return;
    const chip = el('span', `chip ${k}`);
    chip.innerHTML = `<b>${report.summary[k]}</b> ${k}`;
    sum.append(chip);
  });

  const changedOnly = $('#changedOnly').checked;
  const rep = $('#report'); rep.innerHTML = '';
  const shown = report.cases.filter((c) => !changedOnly || c.verdict !== 'unchanged');
  if (!shown.length) { rep.append(el('div', 'empty', 'No differences. ✅')); return; }

  shown.forEach((c) => {
    const box = el('div', 'casebox');
    const hd = el('div', 'casehd');
    hd.append(el('span', `dot ${c.verdict}`));
    const nm = el('span', 'nm'); nm.append(document.createTextNode(c.name), el('span', `badge ${c.group}`, ` ${c.group}`));
    hd.append(nm, el('span', 'vr', c.verdict));
    box.append(hd);

    if (c.changes.length) {
      const wrap = el('div', 'changes');
      const tbl = el('table', 'diff');
      tbl.innerHTML = '<tr><th>step</th><th>field</th><th>before</th><th>after</th></tr>';
      c.changes.forEach((ch) => {
        const tr = el('tr');
        tr.append(el('td', 'kind', ch.step || '—'));
        tr.append(el('td', 'path', ch.path));
        tr.append(el('td', 'b', fmt(ch.before)));
        tr.append(el('td', 'a', fmt(ch.after)));
        tbl.append(tr);
      });
      wrap.append(tbl);
      wrap.style.display = c.verdict === 'unchanged' ? 'none' : 'block';
      hd.onclick = () => { wrap.style.display = wrap.style.display === 'none' ? 'block' : 'none'; };
      box.append(wrap);
    }
    rep.append(box);
  });
}

$('#recordBtn').onclick = record;
$('#compareBtn').onclick = compare;
$('#changedOnly').onchange = compare;
$('#label').value = 'before';

(async () => { await loadCases(); await loadRuns(); })();
