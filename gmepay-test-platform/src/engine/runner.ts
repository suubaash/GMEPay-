import type { UseCaseResult } from '../shared/types';
import { Check, Recorder, AssertionError, BlockedError } from './assert';
import { GmePayClient, ServiceDownError } from './client';
import { UseCase } from './registry';

/** Executes one use case end-to-end and classifies the outcome. */
export async function runUseCase(uc: UseCase): Promise<UseCaseResult> {
  const rec = new Recorder();
  const check = new Check(rec);
  const client = new GmePayClient(rec);
  const started = Date.now();
  const finish = (status: UseCaseResult['status'], error?: string): UseCaseResult => ({
    id: uc.id,
    status,
    durationMs: Date.now() - started,
    steps: rec.steps,
    error,
    finishedAt: new Date().toISOString(),
  });

  if (!uc.run) {
    rec.info('No automated test yet — placeholder in the traceability matrix.');
    return finish('NOT_AUTOMATED');
  }

  try {
    await uc.run({ client, check, rec });
    return finish('PASS');
  } catch (e) {
    if (e instanceof ServiceDownError) {
      rec.warn(e.message);
      return finish('BLOCKED', e.message);
    }
    if (e instanceof BlockedError) return finish('BLOCKED', e.message);
    if (e instanceof AssertionError) return finish('FAIL', e.message);
    const msg = e instanceof Error ? e.message : String(e);
    rec.fail(`Unexpected error: ${msg}`);
    return finish('FAIL', msg);
  }
}
