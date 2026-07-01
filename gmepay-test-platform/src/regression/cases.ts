import { readdir, readFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import type { RegCase } from './types';

const __dirname = dirname(fileURLToPath(import.meta.url));
const CASES_DIR = join(__dirname, 'cases');

/**
 * Load every *.json case file. Cases are data, not code, so you add/edit
 * scenarios without touching the harness. See cases/README for the format.
 */
export async function loadCases(): Promise<RegCase[]> {
  if (!existsSync(CASES_DIR)) return [];
  const files = (await readdir(CASES_DIR)).filter((f) => f.endsWith('.json'));
  const cases: RegCase[] = [];
  for (const f of files) {
    const parsed = JSON.parse(await readFile(join(CASES_DIR, f), 'utf8'));
    const arr = Array.isArray(parsed) ? parsed : [parsed];
    for (const c of arr) cases.push(c as RegCase);
  }
  return cases.sort((a, b) => a.id.localeCompare(b.id));
}
