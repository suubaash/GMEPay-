import type { FastifyInstance } from 'fastify';
import { loadCases } from './cases';
import { runCorpus } from './runner';
import { saveRun, loadRun, listRuns } from './store';
import { buildReport } from './diff';
import { MOCK_VERSION } from './mock';
import { HOST } from '../config';

/** Mount the before/after regression API under /api/reg. */
export async function registerRegression(app: FastifyInstance) {
  // List available cases (metadata only).
  app.get('/api/reg/cases', async () => {
    const cases = await loadCases();
    return {
      host: HOST,
      mockVersion: MOCK_VERSION,
      cases: cases.map((c) => ({
        id: c.id, name: c.name, group: c.group, target: c.target,
        readOnly: c.readOnly ?? false, steps: c.steps.length, note: c.note,
      })),
    };
  });

  // Record a run. Body: { label, ids?: string[], groups?: string[] }
  app.post('/api/reg/run', async (req, reply) => {
    const b = (req.body ?? {}) as { label?: string; ids?: string[]; groups?: string[] };
    const label = (b.label ?? 'run').trim() || 'run';
    let cases = await loadCases();
    if (b.ids?.length) cases = cases.filter((c) => b.ids!.includes(c.id));
    if (b.groups?.length) cases = cases.filter((c) => b.groups!.includes(c.group));
    if (!cases.length) return reply.code(400).send({ error: 'No cases match the filter' });

    const run = await runCorpus(cases, label);
    await saveRun(run);
    return { id: run.id, label: run.label, startedAt: run.startedAt, caseCount: run.cases.length };
  });

  app.get('/api/reg/runs', async () => ({ runs: await listRuns() }));

  app.get('/api/reg/run/:id', async (req, reply) => {
    const id = (req.params as { id: string }).id;
    const run = await loadRun(id);
    if (!run) return reply.code(404).send({ error: `Unknown run ${id}` });
    return run;
  });

  // Diff two runs. Query: ?before=<id>&after=<id>
  app.get('/api/reg/diff', async (req, reply) => {
    const q = req.query as { before?: string; after?: string };
    if (!q.before || !q.after) return reply.code(400).send({ error: 'before and after query params required' });
    const [before, after] = await Promise.all([loadRun(q.before), loadRun(q.after)]);
    if (!before) return reply.code(404).send({ error: `Unknown before run ${q.before}` });
    if (!after) return reply.code(404).send({ error: `Unknown after run ${q.after}` });
    return buildReport(before, after);
  });
}
