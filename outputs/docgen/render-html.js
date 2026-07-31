/**
 * render-html.js — renders the content model to `outputs/feature_spec_artifact.html`.
 *
 * The stylesheet is inherited from the artifact this replaces (it was good); what is new is a
 * state chip vocabulary and a hard banner, both of which mirror `apps/admin-ui`'s discipline:
 * one place decides wording and colour, and the success tone is reserved for a fact nothing can
 * currently claim.
 */

'use strict';

const esc = (s) => String(s)
  .replace(/&/g, '&amp;')
  .replace(/</g, '&lt;')
  .replace(/>/g, '&gt;');

const TONE_CLASS = {
  ok: 'st-ok', info: 'st-info', warn: 'st-warn', bad: 'st-bad',
};

function chip(state) {
  if (!state) return '';
  return `<span class="st ${TONE_CLASS[state.tone]}" title="${esc(state.gloss)}">${esc(state.label)}</span>`;
}

function renderNode(n, STATE) {
  switch (n.t) {
    case 'lead':
      return `<p class="lead">${esc(n.text)}</p>`;
    case 'p':
      return `<p>${esc(n.text)}</p>`;
    case 'h3':
      return `<h3>${esc(n.text)}${n.state ? ` ${chip(n.state)}` : ''}</h3>`;
    case 'bullet':
      return `<ul class="plain"><li>${n.label ? `<b class="lab">${esc(n.label)}</b>` : ''}${esc(n.text)}</li></ul>`;
    case 'steps':
      return `<ol class="flow">${n.items
        .map(([label, text]) => `<li><span class="lab">${esc(label)}.</span> ${esc(text)}</li>`)
        .join('')}</ol>`;
    case 'note':
      return `<div class="note">${esc(n.text)}</div>`;
    case 'waterfall':
      return `<div class="fall">${n.nodes
        .map((label, i) => {
          const last = i === n.nodes.length - 1;
          const arrow = last ? '' : '<div class="arrow">&darr;</div>';
          return `<div class="node${last ? ' pay' : ''}">${esc(label)}</div>${arrow}`;
        })
        .join('')}</div>`;
    case 'table':
      return `<div class="scroll"><table class="grid"><thead><tr>${n.head
        .map((h) => `<th>${esc(h)}</th>`).join('')}</tr></thead><tbody>${n.rows
        .map((r) => `<tr>${r.map((c) => `<td>${esc(c)}</td>`).join('')}</tr>`)
        .join('')}</tbody></table></div>`;
    case 'services':
      return `<dl class="svc">${n.items
        .map((s) => `<div><dt>${esc(s.term)} ${chip(s.state)}</dt><dd>${esc(s.def)}</dd></div>`)
        .join('')}</dl>`;
    case 'status':
      return `<dl class="svc">${n.rows
        .map((r) => `<div><dt>${esc(r.capability)} ${chip(r.state)}</dt><dd>${esc(r.evidence)}</dd></div>`)
        .join('')}</dl>`;
    case 'legend':
      return `<div class="legend">${Object.values(STATE)
        .map((s) => `<div class="lgrow">${chip(s)}<span>${esc(s.gloss)}</span></div>`)
        .join('')}</div>`;
    case 'banner':
      return [
        '<div class="hardban">',
        `<div class="hardban-t">${esc(n.title)}</div>`,
        `<p class="hardban-i">${esc(n.intro)}</p>`,
        '<ul class="hardban-l">',
        ...n.lines.map(([label, text]) => `<li><b>${esc(label)}</b> ${esc(text)}</li>`),
        '</ul>',
        `<p class="hardban-c">${esc(n.closing)}</p>`,
        '</div>',
      ].join('');
    default:
      throw new Error(`html renderer has no case for node type '${n.t}'`);
  }
}

/**
 * Consecutive `bullet` nodes are merged into one list so the markup is a real list rather than
 * a run of one-item lists.
 */
function renderNodes(nodes, STATE) {
  const out = [];
  let bulletRun = [];
  const flush = () => {
    if (bulletRun.length === 0) return;
    out.push(`<ul class="plain">${bulletRun
      .map((b) => `<li>${b.label ? `<b class="lab">${esc(b.label)}</b>` : ''}${esc(b.text)}</li>`)
      .join('')}</ul>`);
    bulletRun = [];
  };
  nodes.forEach((n) => {
    if (n.t === 'bullet') { bulletRun.push(n); return; }
    flush();
    out.push(renderNode(n, STATE));
  });
  flush();
  return out.join('\n      ');
}

const STYLE = `
  :root{
    --ground:#F6F7F9; --surface:#FFFFFF; --ink:#14263F; --body:#3A4653;
    --muted:#6B7683; --accent:#0E7C7B; --accent-2:#0B5F5E; --tint:#E7F1F0;
    --hair:#E4E7EC; --warn-bg:#FBF5E9; --warn-bd:#E8D6A8; --warn-ink:#7A5B14;
    --bad-bg:#FCEDEC; --bad-bd:#E9C3BF; --bad-ink:#8A2F26;
    --info-bg:#EDF3FA; --info-bd:#C6D8ED; --info-ink:#254B76;
    --ok-bg:#EAF5EE; --ok-bd:#BFDECB; --ok-ink:#1F6136;
    --sans:ui-sans-serif,-apple-system,"Segoe UI",Roboto,Helvetica,Arial,sans-serif;
    --mono:ui-monospace,SFMono-Regular,Menlo,Consolas,"Liberation Mono",monospace;
  }
  *{box-sizing:border-box}
  body{margin:0;background:var(--ground);color:var(--body);font-family:var(--sans);
    font-size:16px;line-height:1.62;-webkit-text-size-adjust:100%;}
  .wrap{max-width:760px;margin:0 auto;padding:22px 18px 72px;}
  .card{background:var(--surface);border:1px solid var(--hair);border-radius:14px;
    padding:20px 18px;margin:14px 0;}
  header{padding:14px 2px 6px;}
  .kicker{font-family:var(--mono);font-size:11.5px;letter-spacing:.14em;text-transform:uppercase;
    color:var(--accent);font-weight:600;}
  h1{font-size:30px;line-height:1.12;color:var(--ink);margin:.28em 0 .18em;font-weight:800;
    letter-spacing:-.01em;text-wrap:balance;}
  .sub{color:var(--muted);font-size:15px;margin:0 0 12px;text-wrap:balance;}
  .meta{font-family:var(--mono);font-size:11.5px;color:var(--muted);border-top:1px solid var(--hair);
    padding-top:10px;margin-top:8px;}
  nav{margin:16px 0;}
  nav a{display:block;text-decoration:none;color:var(--ink);font-size:14.5px;padding:9px 12px;
    border:1px solid var(--hair);border-radius:9px;background:var(--surface);margin:6px 0;}
  nav a .n{font-family:var(--mono);color:var(--accent);font-weight:600;margin-right:9px;font-size:12.5px;}
  nav a:active{background:var(--tint);}
  nav a.alarm{border-color:var(--bad-bd);background:var(--bad-bg);color:var(--bad-ink);font-weight:700;}
  nav a.alarm .n{color:var(--bad-ink);}
  h2{font-size:13px;font-family:var(--mono);letter-spacing:.1em;text-transform:uppercase;color:var(--accent-2);
    margin:34px 0 4px;font-weight:700;}
  h2 .big{display:block;font-family:var(--sans);font-size:22px;letter-spacing:-.01em;text-transform:none;
    color:var(--ink);font-weight:800;margin-top:5px;line-height:1.18;text-wrap:balance;}
  h3{font-size:17px;color:var(--ink);margin:22px 0 4px;font-weight:750;line-height:1.25;text-wrap:balance;}
  p{margin:.5em 0;}
  .lead{color:var(--body);font-size:16.5px;}
  ul.plain{list-style:none;margin:.4em 0;padding:0;}
  ul.plain>li{position:relative;padding:7px 0 7px 20px;border-bottom:1px solid var(--hair);}
  ul.plain>li:last-child{border-bottom:0;}
  ul.plain>li::before{content:"";position:absolute;left:2px;top:15px;width:7px;height:7px;border-radius:2px;
    background:var(--accent);}
  b.lab{color:var(--ink);}
  ol.flow{list-style:none;margin:.6em 0;padding:0;counter-reset:s;}
  ol.flow>li{counter-increment:s;position:relative;padding:4px 0 14px 44px;}
  ol.flow>li::before{content:counter(s);position:absolute;left:0;top:1px;width:28px;height:28px;
    display:flex;align-items:center;justify-content:center;background:var(--ink);color:#fff;
    font-family:var(--mono);font-size:13px;font-weight:600;border-radius:8px;}
  ol.flow>li::after{content:"";position:absolute;left:13px;top:30px;bottom:2px;width:2px;background:var(--hair);}
  ol.flow>li:last-child::after{display:none;}
  ol.flow .lab{color:var(--ink);font-weight:700;}
  .note{background:var(--warn-bg);border:1px solid var(--warn-bd);color:var(--warn-ink);
    border-radius:10px;padding:10px 13px;font-size:14px;margin:10px 0 2px;}
  .note b{color:var(--warn-ink);}
  dl.svc{margin:.4em 0;padding:0;}
  dl.svc div{padding:11px 0;border-bottom:1px solid var(--hair);}
  dl.svc div:last-child{border-bottom:0;}
  dl.svc dt{font-family:var(--mono);font-size:13.5px;color:var(--accent-2);font-weight:600;
    display:flex;flex-wrap:wrap;gap:6px;align-items:center;}
  dl.svc dd{margin:3px 0 0;font-size:14.5px;color:var(--body);}
  .st{display:inline-block;font-family:var(--mono);font-size:10.5px;letter-spacing:.05em;
    border-radius:999px;padding:2px 8px;font-weight:700;white-space:nowrap;border:1px solid;}
  .st-ok{background:var(--ok-bg);border-color:var(--ok-bd);color:var(--ok-ink);}
  .st-info{background:var(--info-bg);border-color:var(--info-bd);color:var(--info-ink);}
  .st-warn{background:var(--warn-bg);border-color:var(--warn-bd);color:var(--warn-ink);}
  .st-bad{background:var(--bad-bg);border-color:var(--bad-bd);color:var(--bad-ink);}
  .legend{margin:.6em 0 .2em;border:1px solid var(--hair);border-radius:10px;padding:10px 12px;}
  .lgrow{display:flex;gap:9px;align-items:flex-start;padding:5px 0;font-size:13.5px;color:var(--muted);}
  .lgrow .st{flex:0 0 auto;margin-top:1px;}
  .scroll{overflow-x:auto;margin:.6em 0;}
  table.grid{border-collapse:collapse;width:100%;font-size:14px;min-width:520px;}
  table.grid th{text-align:left;background:var(--tint);color:var(--accent-2);border:1px solid var(--hair);
    padding:8px 10px;font-family:var(--mono);font-size:12px;letter-spacing:.04em;text-transform:uppercase;}
  table.grid td{border:1px solid var(--hair);padding:8px 10px;vertical-align:top;}
  .fall{margin:14px 0 2px;}
  .fall .node{background:var(--tint);border:1px solid #CFE4E2;color:var(--ink);border-radius:10px;
    padding:11px 14px;font-family:var(--mono);font-size:13.5px;text-align:center;font-weight:600;}
  .fall .arrow{color:var(--accent);text-align:center;font-size:18px;line-height:1;margin:5px 0;}
  .fall .node.pay{background:var(--ink);color:#fff;border-color:var(--ink);}
  .hardban{background:var(--bad-bg);border:2px solid var(--bad-ink);border-radius:14px;
    padding:16px 16px 14px;margin:16px 0;color:var(--bad-ink);}
  .hardban-t{font-family:var(--mono);font-size:13px;letter-spacing:.09em;font-weight:800;
    text-transform:uppercase;line-height:1.3;}
  .hardban-i{font-size:14.5px;margin:.6em 0 .4em;}
  .hardban-l{list-style:none;margin:.2em 0;padding:0;}
  .hardban-l>li{padding:8px 0;border-top:1px solid var(--bad-bd);font-size:14px;}
  .hardban-l>li b{display:block;font-size:14.5px;}
  .hardban-c{font-size:14.5px;font-weight:600;margin:.7em 0 0;padding-top:.7em;
    border-top:1px solid var(--bad-bd);}
  .foot{color:var(--muted);font-size:12.5px;font-family:var(--mono);text-align:center;margin-top:40px;
    border-top:1px solid var(--hair);padding-top:16px;}
  a.up{color:var(--accent);text-decoration:none;font-family:var(--mono);font-size:12px;}
  @media (prefers-color-scheme: dark){
    :root{--ground:#0E1621;--surface:#151F2B;--ink:#E8EEF5;--body:#C3CEDA;--muted:#8B98A6;
      --hair:#26323F;--tint:#122A29;--accent:#4FBDBB;--accent-2:#7FD3D1;
      --warn-bg:#2A2415;--warn-bd:#4E4326;--warn-ink:#E9CE86;
      --bad-bg:#2C1917;--bad-bd:#553330;--bad-ink:#F0A79C;
      --info-bg:#16233A;--info-bd:#2C4162;--info-ink:#9FC2EB;
      --ok-bg:#132A1D;--ok-bd:#2A4A34;--ok-ink:#93CFA7;}
    .fall .node.pay{background:#1D2C3D;border-color:#2C4258;color:var(--ink);}
    .hardban{border-color:var(--bad-ink);}
  }
`;

/**
 * @param {{META: object, SECTIONS: Array, STATE: object}} model
 * @returns {string} complete HTML document body (self-contained, no external requests)
 */
function renderHtml({ META, SECTIONS, STATE }) {
  const nav = SECTIONS.map((s) => {
    const alarm = s.id === 's0' ? ' class="alarm"' : '';
    return `    <a href="#${s.id}"${alarm}><span class="n">${esc(s.num)}</span>${esc(s.navTitle)}</a>`;
  }).join('\n');

  const sections = SECTIONS.map((s) => [
    `  <section id="${s.id}" class="card">`,
    `    <h2>Section ${esc(s.num)}<span class="big">${esc(s.title)}</span></h2>`,
    `      ${renderNodes(s.nodes, STATE)}`,
    '  </section>',
  ].join('\n')).join('\n\n');

  return `<title>${esc(META.title)} — ${esc(META.subtitle)}</title>
<style>${STYLE}</style>

<div class="wrap">
  <header>
    <div class="kicker">${esc(META.subtitle)} · ${esc(META.version)}</div>
    <h1>${esc(META.title)}</h1>
    <p class="sub">${esc(META.tagline)}</p>
    <div class="meta">${esc(META.provenance)}</div>
  </header>

  <nav aria-label="Contents">
${nav}
  </nav>

${sections}

  <div class="foot">${esc(META.title)} · ${esc(META.subtitle)} · generated from Documentation/GAP_REGISTER.md by outputs/docgen/build.js — do not hand-edit<br><a class="up" href="#s0">&uarr; back to top</a></div>
</div>
`;
}

module.exports = { renderHtml };
