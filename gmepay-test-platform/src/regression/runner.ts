import { http, NetworkError } from '../engine/http';
import { baseUrl, HOST, FIXTURES } from '../config';
import { normalize, DEFAULT_SCRUB_FIELDS } from './scrub';
import { runMock, MOCK_VERSION } from './mock';
import type { RegCase, CaseCapture, StepCapture, Run } from './types';

/** Resolve `{{steps.x.a.b}}` / `{{fixtures.key}}` tokens in a string. */
function interpolate(input: string, ctx: Record<string, unknown>): string {
  return input.replace(/\{\{\s*([^}]+?)\s*\}\}/g, (_m, expr: string) => {
    const val = getPath(ctx, expr.trim());
    return val === undefined || val === null ? '' : String(val);
  });
}

function getPath(obj: unknown, path: string): unknown {
  return path.split('.').reduce<unknown>((acc, key) => {
    if (acc == null) return undefined;
    return (acc as Record<string, unknown>)[key];
  }, obj);
}

/** Deep-interpolate every string in a value (path/headers/body). */
function interpolateDeep(value: unknown, ctx: Record<string, unknown>): unknown {
  if (typeof value === 'string') return interpolate(value, ctx);
  if (Array.isArray(value)) return value.map((v) => interpolateDeep(v, ctx));
  if (value && typeof value === 'object') {
    const out: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(value)) out[k] = interpolateDeep(v, ctx);
    return out;
  }
  return value;
}

function resolveBase(target: string): string | null {
  if (target === 'mock') return null;
  if (target.startsWith('url:')) return target.slice(4).replace(/\/$/, '');
  return baseUrl(target);
}

async function runCase(c: RegCase): Promise<CaseCapture> {
  const scrubFields = [...DEFAULT_SCRUB_FIELDS, ...(c.scrubFields ?? [])];
  const auto = c.scrubAuto ?? true;
  const ctx: Record<string, unknown> = { fixtures: FIXTURES, steps: {} };
  const steps: StepCapture[] = [];
  let caseOk = true;

  for (const step of c.steps) {
    const path = interpolate(step.path, ctx);
    const headers = interpolateDeep(step.headers ?? {}, ctx) as Record<string, string>;
    const body = step.body === undefined ? undefined : interpolateDeep(step.body, ctx);

    try {
      let status: number, ok: boolean, ms: number, raw: unknown, url: string;

      if (c.target === 'mock') {
        url = `mock:${path}`;
        const r = runMock(step, body);
        status = r.status; ok = r.ok; ms = r.ms; raw = r.json;
      } else {
        const base = resolveBase(c.target)!;
        url = base + path;
        const r = await http(step.method, url, body, headers);
        status = r.status; ok = r.ok; ms = r.ms; raw = r.json ?? r.text;
      }

      if (!ok) caseOk = false;
      (ctx.steps as Record<string, unknown>)[step.name] = raw;
      steps.push({
        name: step.name, method: step.method, url, status, ms, ok,
        raw, normalized: normalize(raw, scrubFields, auto),
      });
    } catch (e) {
      caseOk = false;
      const msg = e instanceof NetworkError ? e.message : (e as Error).message;
      steps.push({
        name: step.name, method: step.method, url: c.target + path, status: 0, ms: 0,
        ok: false, raw: null, normalized: null, error: msg,
      });
      break; // a failed step breaks the chain
    }
  }

  return { caseId: c.id, name: c.name, group: c.group, ok: caseOk, steps };
}

export async function runCorpus(cases: RegCase[], label: string): Promise<Run> {
  const caps: CaseCapture[] = [];
  for (const c of cases) caps.push(await runCase(c));
  return {
    id: `${sanitize(label)}-${Date.now()}`,
    label,
    startedAt: new Date().toISOString(),
    host: HOST,
    mockVersion: cases.some((c) => c.target === 'mock') ? MOCK_VERSION : null,
    cases: caps,
  };
}

function sanitize(s: string): string {
  return s.trim().toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '') || 'run';
}
