import type {
  Run,
  CaseDiff,
  DiffReport,
  FieldChange,
  CaseVerdict,
  CaseCapture,
} from './types';

/** Recursive structural diff producing dot-path changes. */
export function deepDiff(before: unknown, after: unknown, base = ''): FieldChange[] {
  const changes: FieldChange[] = [];

  const walk = (b: unknown, a: unknown, path: string) => {
    if (b === a) return;
    const bType = kindOf(b);
    const aType = kindOf(a);

    if (bType !== aType) {
      changes.push({ step: '', path, kind: 'type-changed', before: b, after: a });
      return;
    }

    if (bType === 'object') {
      const bo = b as Record<string, unknown>;
      const ao = a as Record<string, unknown>;
      const keys = new Set([...Object.keys(bo), ...Object.keys(ao)]);
      for (const k of keys) {
        const p = path ? `${path}.${k}` : k;
        if (!(k in bo)) changes.push({ step: '', path: p, kind: 'added', before: undefined, after: ao[k] });
        else if (!(k in ao)) changes.push({ step: '', path: p, kind: 'removed', before: bo[k], after: undefined });
        else walk(bo[k], ao[k], p);
      }
      return;
    }

    if (bType === 'array') {
      const ba = b as unknown[];
      const aa = a as unknown[];
      const len = Math.max(ba.length, aa.length);
      for (let i = 0; i < len; i++) {
        const p = `${path}[${i}]`;
        if (i >= ba.length) changes.push({ step: '', path: p, kind: 'added', before: undefined, after: aa[i] });
        else if (i >= aa.length) changes.push({ step: '', path: p, kind: 'removed', before: ba[i], after: undefined });
        else walk(ba[i], aa[i], p);
      }
      return;
    }

    // primitive value changed
    changes.push({ step: '', path, kind: 'changed', before: b, after: a });
  };

  walk(before, after, base);
  return changes;
}

function kindOf(v: unknown): string {
  if (Array.isArray(v)) return 'array';
  if (v === null) return 'null';
  return typeof v;
}

function diffCase(before: CaseCapture, after: CaseCapture): CaseDiff {
  const changes: FieldChange[] = [];
  let verdict: CaseVerdict = 'unchanged';

  if (!before.ok || !after.ok) verdict = 'error';

  const stepNames = new Set([
    ...before.steps.map((s) => s.name),
    ...after.steps.map((s) => s.name),
  ]);

  for (const name of stepNames) {
    const bStep = before.steps.find((s) => s.name === name);
    const aStep = after.steps.find((s) => s.name === name);
    if (!bStep || !aStep) {
      changes.push({ step: name, path: '(step)', kind: bStep ? 'removed' : 'added', before: !!bStep, after: !!aStep });
      continue;
    }
    if (bStep.status !== aStep.status) {
      changes.push({ step: name, path: 'status', kind: 'changed', before: bStep.status, after: aStep.status });
    }
    for (const c of deepDiff(bStep.normalized, aStep.normalized)) {
      changes.push({ ...c, step: name });
    }
  }

  if (verdict !== 'error' && changes.length > 0) verdict = 'changed';
  return { caseId: before.caseId, name: before.name, group: before.group, verdict, changes };
}

export function buildReport(before: Run, after: Run): DiffReport {
  const beforeIds = new Map(before.cases.map((c) => [c.caseId, c]));
  const afterIds = new Map(after.cases.map((c) => [c.caseId, c]));
  const allIds = new Set([...beforeIds.keys(), ...afterIds.keys()]);

  const cases: CaseDiff[] = [];
  for (const id of allIds) {
    const b = beforeIds.get(id);
    const a = afterIds.get(id);
    if (b && !a) cases.push({ caseId: id, name: b.name, group: b.group, verdict: 'only-before', changes: [] });
    else if (!b && a) cases.push({ caseId: id, name: a.name, group: a.group, verdict: 'only-after', changes: [] });
    else if (b && a) cases.push(diffCase(b, a));
  }

  const summary: Record<CaseVerdict, number> = {
    unchanged: 0, changed: 0, error: 0, 'only-before': 0, 'only-after': 0,
  };
  for (const c of cases) summary[c.verdict]++;

  // Changed/error first so the report leads with what needs attention.
  const order: Record<CaseVerdict, number> = {
    changed: 0, error: 1, 'only-after': 2, 'only-before': 3, unchanged: 4,
  };
  cases.sort((x, y) => order[x.verdict] - order[y.verdict] || x.caseId.localeCompare(y.caseId));

  return { beforeId: before.id, afterId: after.id, summary, cases };
}
