export type Status =
  | 'PASS'
  | 'FAIL'
  | 'BLOCKED'
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
}

export interface ServiceHealth {
  name: string;
  port: number;
  url: string;
  up: boolean;
  status?: number;
  note?: string;
}
