import type { Step, StepLevel } from '../shared/types';

/** Collects an ordered log of everything a test did, for display in the UI. */
export class Recorder {
  steps: Step[] = [];
  private push(level: StepLevel, msg: string, detail?: unknown) {
    this.steps.push({ t: Date.now(), level, msg, detail });
  }
  info(msg: string, detail?: unknown) { this.push('info', msg, detail); }
  pass(msg: string, detail?: unknown) { this.push('pass', msg, detail); }
  warn(msg: string, detail?: unknown) { this.push('warn', msg, detail); }
  fail(msg: string, detail?: unknown) { this.push('fail', msg, detail); }
  request(msg: string, detail?: unknown) { this.push('request', msg, detail); }
}

/** Thrown by a failed assertion → the runner marks the use case FAILED. */
export class AssertionError extends Error {
  constructor(msg: string) {
    super(msg);
    this.name = 'AssertionError';
  }
}

/** Thrown when a precondition isn't met → the runner marks the use case BLOCKED (not failed). */
export class BlockedError extends Error {
  constructor(msg: string) {
    super(msg);
    this.name = 'BlockedError';
  }
}

/** Fluent assertions that also write a pass/fail step into the recorder. */
export class Check {
  constructor(private rec: Recorder) {}

  ok(cond: boolean, msg: string, detail?: unknown): void {
    if (cond) this.rec.pass(msg, detail);
    else {
      this.rec.fail(msg, detail);
      throw new AssertionError(msg);
    }
  }

  equal<T>(actual: T, expected: T, msg: string): void {
    this.ok(actual === expected, `${msg} — expected ${String(expected)}, got ${String(actual)}`, {
      actual,
      expected,
    });
  }

  /** Numeric closeness check (money math is BigDecimal on the server side). */
  closeTo(actual: number, expected: number, tolerance: number, msg: string): void {
    this.ok(
      Math.abs(actual - expected) <= tolerance,
      `${msg} — expected ~${expected} (±${tolerance}), got ${actual}`,
      { actual, expected, tolerance },
    );
  }

  blockedIf(cond: boolean, reason: string): void {
    if (cond) {
      this.rec.warn(`BLOCKED: ${reason}`);
      throw new BlockedError(reason);
    }
  }
}
