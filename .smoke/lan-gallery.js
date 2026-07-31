const http = require('http'), fs = require('fs'), path = require('path');
const DIR = 'D:\\GMEPay+\\code\\.smoke\\shots\\jpg';
const files = ['02-dashboard.jpg', '01-login.jpg', '03-partners.jpg', '04-audit.jpg'];
const labels = { '02-dashboard.jpg': 'Dashboard', '01-login.jpg': 'Login', '03-partners.jpg': 'Partners', '04-audit.jpg': 'Audit log' };
http.createServer((req, res) => {
  const u = req.url.split('?')[0];
  if (u === '/' || u === '/index') {
    res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
    res.end('<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><title>GMEPay+ UI</title>' +
      '<body style="margin:0;background:#0d0d10;font-family:system-ui,sans-serif">' +
      '<div style="padding:14px 12px;color:#fff;font-size:18px;font-weight:700">GMEPay+ — premium <span style="color:#ff4d63">red/white</span> admin UI</div>' +
      files.map(f => '<div style="color:#9a9aa2;padding:10px 12px 4px;font-size:13px">' + (labels[f] || f) + '</div><img src="/' + f + '" style="width:100%;display:block;border-top:1px solid #222">').join('') +
      '<div style="padding:18px 12px;color:#666;font-size:12px">Served from your PC over LAN · ephemeral</div></body>');
    return;
  }
  const p = path.join(DIR, path.basename(u));
  if (fs.existsSync(p)) { res.writeHead(200, { 'content-type': 'image/jpeg' }); fs.createReadStream(p).pipe(res); }
  else { res.writeHead(404); res.end('not found'); }
}).listen(8800, '0.0.0.0', () => console.log('LAN gallery on 0.0.0.0:8800'));
