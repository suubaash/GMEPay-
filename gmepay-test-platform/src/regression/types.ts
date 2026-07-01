/**
 * Before/after regression harness — types.
 *
 * Concept: record a "before" run, publish your change, record an "after" run
 * against the SAME environment, then diff the two normalized captures.
 * Determinism (scrubbing volatile fields + pinning rates) is what makes the
 * diff mean "my code changed this value" rather than "time passed".
 */

/** One HTTP call inside a case. Flows chain several. */
export interface CaseStep {
  name: string;
  method: string;
  /** Path appended to the target base URL. Ignored when target === 'mock'. */
  path: string;
  headers?: Record<string, string>;
  body?: unknown;
  /**
   * Template tokens `{{steps.<step>.<dot.path>}}` and `{{fixtures.<key>}}` in
   * path/headers/body strings are resolved from earlier steps + FIXTURES.
   */
}

export interface RegCase {
  id: string;
  name: string;
  /** calc = value math · contract = single endpoint · flow = multi-step · mock = built-in demo */
  group: 'calc' | 'contract' | 'flow' | 'mock';
  /** Service key from config.ts SERVICES, or "url:http://..." for an explicit host, or "mock". */
  target: string;
  steps: CaseStep[];
  /** Field names replaced with a stable token anywhere in the response tree. */
  scrubFields?: string[];
  /** Auto-scrub uuids/timestamps in string values (default true). */
  scrubAuto?: boolean;
  /** true = safe to re-run (GET/calc). false = creates data — see README gotcha #2. */
  readOnly?: boolean;
  note?: string;
}

export interface StepCapture {
  name: string;
  method: string;
  url: string;
  status: number;
  ms: number;
  ok: boolean;
  /** Original response body (for display). */
  raw: unknown;
  /** Scrubbed body used for diffing. */
  normalized: unknown;
  error?: string;
}

export interface CaseCapture {
  caseId: string;
  name: string;
  group: RegCase['group'];
  ok: boolean;
  error?: string;
  steps: StepCapture[];
}

export interface Run {
  id: string;
  label: string;
  startedAt: string;
  host: string;
  mockVersion: string | null;
  cases: CaseCapture[];
}

export interface RunSummary {
  id: string;
  label: string;
  startedAt: string;
  caseCount: number;
  okCount: number;
}

export type ChangeKind = 'changed' | 'added' | 'removed' | 'type-changed';

export interface FieldChange {
  step: string;
  path: string;
  kind: ChangeKind;
  before: unknown;
  after: unknown;
}

export type CaseVerdict = 'unchanged' | 'changed' | 'error' | 'only-before' | 'only-after';

export interface CaseDiff {
  caseId: string;
  name: string;
  group: RegCase['group'];
  verdict: CaseVerdict;
  changes: FieldChange[];
}

export interface DiffReport {
  beforeId: string;
  afterId: string;
  summary: Record<CaseVerdict, number>;
  cases: CaseDiff[];
}
