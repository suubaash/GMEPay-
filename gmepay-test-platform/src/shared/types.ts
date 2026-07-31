export type Status =
  | 'PASS'
  | 'FAIL'
  | 'BLOCKED'
  /**
   * The behaviour this case asserted can no longer be exercised at all, because the
   * platform deliberately removed the surface (e.g. `POST /v1/auth/login` +
   * `password=demo`, or the sandbox E2E runner). Distinct from BLOCKED, which means
   * "a precondition is missing but the capability exists". An UNSUPPORTED case is
   * kept — with its reason — rather than deleted, so the matrix stays honest.
   */
  | 'UNSUPPORTED'
  | 'NOT_AUTOMATED'
  | 'RUNNING'
  | 'IDLE';

export type StepLevel = 'info' | 'pass' | 'fail' | 'warn' | 'request';

export interface Step {
  t: number;
  level: StepLevel;
  msg: string;
  detail?: unknown;
}

export interface UseCaseResult {
  id: string;
  status: Status;
  durationMs: number;
  steps: Step[];
  error?: string;
  finishedAt: string;
}

export interface UseCaseMeta {
  id: string;
  bs: string;
  bsTitle: string;
  title: string;
  mvp: boolean;
  phase: string;
  services: string[];
  /** What the use case is *supposed* to do — the intended function under test. */
  intent: string;
  /** True when a real executable test exists (vs. matrix placeholder). */
  automated: boolean;
  /** 'use-case' = PRD acceptance test; 'feature' = endpoint-level test. */
  kind?: 'use-case' | 'feature';
  /**
   * Credentials this case must present against the now default-deny platform.
   * Drives the `--plan` dry run, which reports per case whether the environment
   * supplies what the case needs — without firing any HTTP.
   */
  credentials?: string[];
  /** Set when status is permanently UNSUPPORTED; explains what was removed and why. */
  unsupportedReason?: string;
}

/** One row of the `--plan` dry run: what a case would present, and whether it can. */
export interface PlanRow {
  id: string;
  title: string;
  kind: string;
  credentials: string[];
  /** True when every credential the case needs is configured. */
  ready: boolean;
  /** Env vars still needed for this case to run. */
  missing: string[];
  unsupportedReason?: string;
}

export interface ServiceHealth {
  name: string;
  port: number;
  url: string;
  up: boolean;
  status?: number;
  note?: string;
}
