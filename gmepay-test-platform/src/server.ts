import Fastify from 'fastify';
import cors from '@fastify/cors';
import fastifyStatic from '@fastify/static';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { existsSync } from 'node:fs';

import { API_PORT } from './config';
import { USE_CASES, findUseCase } from './engine/registry';
import { runUseCase } from './engine/runner';
import { checkHealth } from './engine/health';
import { registerRegression } from './regression/routes';
import type { UseCaseResult } from './shared/types';

const __dirname = dirname(fileURLToPath(import.meta.url));

/** Last result per use case, kept in memory so the dashboard survives reloads. */
const lastResults = new Map<string, UseCaseResult>();

const app = Fastify({ logger: false });
await app.register(cors, { origin: true });

// The trigger endpoints carry no body; accept any/empty content-type so a run can
// be kicked off from the browser, curl, or PowerShell without a 415.
app.addContentTypeParser('*', (_req, _payload, done) => done(null, undefined));

// Static metadata for the matrix (strip the run fn — not serialisable).
const meta = USE_CASES.map(({ run, ...m }) => m);

app.get('/api/usecases', async () => ({
  useCases: meta,
  lastResults: Object.fromEntries(lastResults),
}));

app.get('/api/health', async () => ({ services: await checkHealth() }));

// Before/after regression harness: API under /api/reg + standalone UI at /regression.
await registerRegression(app);
const regWeb = join(__dirname, '..', 'web', 'regression');
if (existsSync(regWeb)) {
  await app.register(fastifyStatic, { root: regWeb, prefix: '/regression/', decorateReply: false });
}

/** Run a single use case and return its result. */
app.post('/api/run/:id', async (req, reply) => {
  const id = (req.params as { id: string }).id;
  const uc = findUseCase(id);
  if (!uc) return reply.code(404).send({ error: `Unknown use case ${id}` });
  const result = await runUseCase(uc);
  lastResults.set(id, result);
  return result;
});

/**
 * Stream a run of many use cases via Server-Sent Events. Query: ?ids=a,b,c or
 * ?filter=mvp|all. Emits one `result` event per use case, then `done`.
 */
app.get('/api/run/stream', async (req, reply) => {
  const q = req.query as { ids?: string; filter?: string };
  let targets = USE_CASES;
  if (q.ids) {
    const set = new Set(q.ids.split(',').map((s) => s.trim()));
    targets = USE_CASES.filter((u) => set.has(u.id));
  } else if (q.filter === 'mvp') {
    targets = USE_CASES.filter((u) => u.mvp);
  }

  reply.raw.writeHead(200, {
    'Content-Type': 'text/event-stream',
    'Cache-Control': 'no-cache',
    Connection: 'keep-alive',
  });
  const send = (event: string, data: unknown) =>
    reply.raw.write(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`);

  send('start', { total: targets.length });
  for (const uc of targets) {
    send('running', { id: uc.id });
    const result = await runUseCase(uc);
    lastResults.set(uc.id, result);
    send('result', result);
  }
  send('done', { total: targets.length });
  reply.raw.end();
});

// Serve the built dashboard if it exists (production `npm run build` + `npm start`).
const webDist = join(__dirname, '..', 'dist-web');
if (existsSync(webDist)) {
  await app.register(fastifyStatic, { root: webDist });
  app.setNotFoundHandler((req, reply) => {
    if (req.url.startsWith('/api')) return reply.code(404).send({ error: 'not found' });
    return reply.sendFile('index.html');
  });
}

const address = await app.listen({ port: API_PORT, host: '0.0.0.0' });
console.log(`\n  GMEPay+ Test Platform API → ${address}`);
console.log(`  ${USE_CASES.length} use cases registered`);
console.log(`  Regression harness UI → ${address}/regression/`);
if (existsSync(webDist)) console.log(`  Dashboard served at ${address}\n`);
else console.log(`  Dev dashboard: run "npm run web" → http://localhost:5173\n`);
