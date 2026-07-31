// NOTE: these types mirror src/shared/types.ts. The dashboard is bundled separately
// from the API, so it cannot import across that boundary — keep the two in sync.
export type Status =
  | 'PASS'
  | 'FAIL'
  | 'BLOCKED'
  | 'UNSUPPORTED'
  | 'NOT_AUTOMATED'
  | 'RUNNING'
  | 'IDLE';

export interface Step {
  t: number;
  level: 'info' | 'pass' | 'fail' | 'warn' | 'request';
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
  intent: string;
  automated: boolean;
  kind?: 'use-case' | 'feature';
  /** Credentials this case must present against the default-deny platform. */
  credentials?: string[];
  /** Set when the asserted capability was deliberately withdrawn from the platform. */
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

export async function fetchUseCases(): Promise<{
  useCases: UseCaseMeta[];
  lastResults: Record<string, UseCaseResult>;
}> {
  const r = await fetch('/api/usecases');
  return r.json();
}

export async function fetchHealth(): Promise<ServiceHealth[]> {
  const r = await fetch('/api/health');
  return (await r.json()).services;
}

export async function runOne(id: string): Promise<UseCaseResult> {
  const r = await fetch(`/api/run/${id}`, { method: 'POST' });
  return r.json();
}

/** Streams results via SSE. Calls onRunning/onResult as events arrive. */
export function runStream(
  query: string,
  handlers: {
    onRunning?: (id: string) => void;
    onResult?: (r: UseCaseResult) => void;
    onDone?: () => void;
  },
): EventSource {
  const es = new EventSource(`/api/run/stream?${query}`);
  es.addEventListener('running', (e) => handlers.onRunning?.(JSON.parse((e as MessageEvent).data).id));
  es.addEventListener('result', (e) => handlers.onResult?.(JSON.parse((e as MessageEvent).data)));
  es.addEventListener('done', () => {
    handlers.onDone?.();
    es.close();
  });
  es.onerror = () => es.close();
  return es;
}
