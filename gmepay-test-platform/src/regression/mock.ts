import type { CaseStep } from './types';

/**
 * Built-in mock target so the whole record → publish → record → diff loop is
 * demonstrable WITHOUT any GMEPay+ service running.
 *
 * It fakes a tiny FX quote whose output depends on REG_MOCK_VERSION. To see a
 * real before/after diff:
 *   1. start with (default) REG_MOCK_VERSION=1  → record run "before"
 *   2. stop, set REG_MOCK_VERSION=2, restart    → this is your "publish"
 *   3. record run "after"  → compare → the payout value + a new field change
 *
 * Replace these mock cases with real ones (target = a service key) once you
 * point the harness at your running environment.
 */
export const MOCK_VERSION = process.env.REG_MOCK_VERSION ?? '1';

interface MockResult {
  ok: boolean;
  status: number;
  ms: number;
  json: unknown;
}

export function runMock(step: CaseStep, body: unknown): MockResult {
  const v = Number(MOCK_VERSION);
  const amount = Number((body as any)?.sendAmount ?? 1000);

  // Deterministic pinned rate; v2 pretends a margin formula changed.
  const rate = 1330.5;
  const marginBps = v >= 2 ? 45 : 50; // "publish" tightened the margin
  const usdCost = amount / rate;
  const payout = round2(amount - (amount * marginBps) / 10000);

  const json: Record<string, unknown> = {
    // volatile fields on purpose — the scrubber must neutralize these:
    quoteId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
    issuedAt: new Date().toISOString(),
    // stable business values — these are what the diff should surface:
    sendAmount: amount,
    rate,
    marginBps,
    usdCost: round4(usdCost),
    payoutAmount: payout,
  };
  if (v >= 2) json.revenueSplit = { phase1: round2((amount * marginBps) / 10000), phase2: 0 };

  return { ok: true, status: 200, ms: 1, json };
}

const round2 = (n: number) => Math.round(n * 100) / 100;
const round4 = (n: number) => Math.round(n * 10000) / 10000;
