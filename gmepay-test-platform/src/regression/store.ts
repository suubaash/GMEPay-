import { readdir, readFile, writeFile, mkdir } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import type { Run, RunSummary } from './types';

const __dirname = dirname(fileURLToPath(import.meta.url));
/** Runs live at <repo-root>/runs (gitignored). */
const RUNS_DIR = join(__dirname, '..', '..', 'runs');

async function ensureDir() {
  if (!existsSync(RUNS_DIR)) await mkdir(RUNS_DIR, { recursive: true });
}

export async function saveRun(run: Run): Promise<void> {
  await ensureDir();
  await writeFile(join(RUNS_DIR, `${run.id}.json`), JSON.stringify(run, null, 2), 'utf8');
}

export async function loadRun(id: string): Promise<Run | null> {
  const file = join(RUNS_DIR, `${sanitizeId(id)}.json`);
  if (!existsSync(file)) return null;
  return JSON.parse(await readFile(file, 'utf8')) as Run;
}

export async function listRuns(): Promise<RunSummary[]> {
  await ensureDir();
  const files = (await readdir(RUNS_DIR)).filter((f) => f.endsWith('.json'));
  const runs: RunSummary[] = [];
  for (const f of files) {
    try {
      const run = JSON.parse(await readFile(join(RUNS_DIR, f), 'utf8')) as Run;
      runs.push({
        id: run.id,
        label: run.label,
        startedAt: run.startedAt,
        caseCount: run.cases.length,
        okCount: run.cases.filter((c) => c.ok).length,
      });
    } catch {
      /* skip malformed */
    }
  }
  return runs.sort((a, b) => b.startedAt.localeCompare(a.startedAt));
}

/** Guard against path traversal — ids are our own sanitized `<label>-<ms>`. */
function sanitizeId(id: string): string {
  return id.replace(/[^a-zA-Z0-9._-]/g, '');
}
