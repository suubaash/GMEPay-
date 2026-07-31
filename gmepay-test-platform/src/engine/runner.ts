import type { UseCaseResult } from '../shared/types';
import { Check, Recorder, AssertionError, BlockedError } from './assert';
import { GmePayClient, ServiceDownError } from './client';
import { AuthRejectedError, MissingCredentialError, TokenAcquisitionError } from './credentials';
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

  // Deliberately-removed behaviour: never run, never fail, always explain.
  if (uc.unsupportedReason) {
    rec.warn(`UNSUPPORTED: ${uc.unsupportedReason}`);
    return finish('UNSUPPORTED', uc.unsupportedReason);
  }

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
    /**
     * A credential the environment does not supply is a SETUP problem, not a
     * product defect — BLOCKED, with the exact variable to set. Same for a token
     * the identity provider would not issue.
     */
    if (e instanceof MissingCredentialError) {
      rec.warn(e.message);
      return finish('BLOCKED', e.message);
    }
    if (e instanceof TokenAcquisitionError) {
      rec.warn(e.message);
      return finish('BLOCKED', e.message);
    }
    /**
     * A credential WAS presented and the platform refused it. That is a real
     * finding — either the credential is wrong or the surface is more restricted
     * than the case assumes — so it FAILS, loudly, naming the credential.
     */
    if (e instanceof AuthRejectedError) {
      rec.fail(e.message, {
        status: e.status,
        service: e.service,
        path: e.path,
        credential: e.kind,
      });
      return finish('FAIL', e.message);
    }
    if (e instanceof BlockedError) return finish('BLOCKED', e.message);
    if (e instanceof AssertionError) return finish('FAIL', e.message);
    const msg = e instanceof Error ? e.message : String(e);
    rec.fail(`Unexpected error: ${msg}`);
    return finish('FAIL', msg);
  }
}
