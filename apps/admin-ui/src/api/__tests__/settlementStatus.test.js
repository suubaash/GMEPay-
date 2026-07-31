/**
 * GAP T4-5 — the settlement status vocabulary must never promote an unknown claim.
 *
 * Mirrors the guarantees `filingStatus` gives the regulatory lane (T5-2):
 *   - green means transmitted, and only transmitted;
 *   - no lifecycle value is ever a success, not even RECONCILED;
 *   - absent / unrecognised / retired-invented resolves to UNKNOWN in warning colour.
 */
import { describe, expect, it } from 'vitest';
import {
  RETIRED_SETTLEMENT_STATUSES,
  SETTLEMENT_LIFECYCLE,
  TRANSMISSION_STATE,
  isTransmitted,
  notTransmittedSummary,
  settlementLifecycleMeta,
  transmissionReasonFor,
  transmissionStateMeta,
} from '@/api/settlementStatus';

describe('transmissionStateMeta', () => {
  it('gives a success colour ONLY to TRANSMITTED', () => {
    const successes = Object.values(TRANSMISSION_STATE).filter(
      (s) => transmissionStateMeta(s).color === 'success',
    );
    expect(successes).toEqual([TRANSMISSION_STATE.TRANSMITTED]);
    expect(transmissionStateMeta('TRANSMITTED').transmitted).toBe(true);
  });

  it('treats an absent state as UNKNOWN, never as NOT_TRANSMITTED and never as sent', () => {
    for (const absent of [undefined, null, '', '   ']) {
      const meta = transmissionStateMeta(absent);
      expect(meta.state).toBe(TRANSMISSION_STATE.UNKNOWN);
      expect(meta.transmitted).toBe(false);
      expect(meta.color).toBe('warning');
    }
  });

  it('treats an unrecognised state as UNKNOWN in warning colour, never a success', () => {
    const meta = transmissionStateMeta('SENT_OK');
    expect(meta.transmitted).toBe(false);
    expect(meta.color).toBe('warning');
    expect(meta.label).toContain('unrecognised');
    expect(meta.shortLabel).toBe('Unknown');
  });

  it('refuses to promote the retired invented vocabulary', () => {
    for (const retired of RETIRED_SETTLEMENT_STATUSES) {
      const meta = transmissionStateMeta(retired);
      expect(meta.transmitted).toBe(false);
      expect(meta.color).not.toBe('success');
    }
  });

  it('normalises case and surrounding whitespace', () => {
    expect(transmissionStateMeta('  transmitted ').transmitted).toBe(true);
    expect(transmissionStateMeta('not_transmitted_channel_unavailable').state).toBe(
      TRANSMISSION_STATE.NOT_TRANSMITTED_CHANNEL_UNAVAILABLE,
    );
  });

  it('says a local directory is not a channel on the state every batch is in today', () => {
    const meta = transmissionStateMeta(TRANSMISSION_STATE.NOT_TRANSMITTED_CHANNEL_UNAVAILABLE);
    expect(meta.color).toBe('warning');
    expect(meta.description).toMatch(/local directory is not a channel/i);
  });
});

describe('settlementLifecycleMeta', () => {
  it('never colours any lifecycle value as a success — RECONCILED included', () => {
    for (const status of Object.values(SETTLEMENT_LIFECYCLE)) {
      expect(settlementLifecycleMeta(status).color).not.toBe('success');
    }
    expect(settlementLifecycleMeta('RECONCILED').description).toMatch(/NOT a transmission/i);
  });

  it('marks the legacy lifecycle TRANSMITTED as no evidence a file left', () => {
    const meta = settlementLifecycleMeta('TRANSMITTED');
    expect(meta.color).toBe('warning');
    expect(meta.description).toMatch(/NOT evidence/i);
  });

  it('renders COMPLETED — the old BFF hardcode — as not a valid state', () => {
    const meta = settlementLifecycleMeta('COMPLETED');
    expect(meta.label).toContain('not a valid state');
    expect(meta.color).toBe('warning');
  });

  it('renders an absent or unrecognised status as UNKNOWN', () => {
    expect(settlementLifecycleMeta(null).status).toBe(SETTLEMENT_LIFECYCLE.UNKNOWN);
    expect(settlementLifecycleMeta('CLOSED').label).toContain('unrecognised');
  });
});

describe('isTransmitted', () => {
  it('is true only for the literal TRANSMITTED state', () => {
    expect(isTransmitted('TRANSMITTED')).toBe(true);
    for (const other of ['NOT_TRANSMITTED', 'NOT_TRANSMITTED_CHANNEL_UNAVAILABLE',
      'TRANSMISSION_FAILED', 'UNKNOWN', 'COMPLETED', '', null, undefined]) {
      expect(isTransmitted(other)).toBe(false);
    }
  });
});

describe('notTransmittedSummary', () => {
  const notSent = { transmissionState: 'NOT_TRANSMITTED_CHANNEL_UNAVAILABLE' };

  it('states the fact when nothing on the page was transmitted', () => {
    const line = notTransmittedSummary([notSent, { transmissionState: 'UNKNOWN' }]);
    expect(line).toMatch(/No settlement file on this page has been transmitted/);
    expect(line).toMatch(/RECONCILED does not mean anything was sent/);
  });

  it('stands down as soon as one batch genuinely reports TRANSMITTED', () => {
    expect(notTransmittedSummary([notSent, { transmissionState: 'TRANSMITTED' }])).toBeNull();
  });

  it('makes no claim about an empty or non-array list', () => {
    expect(notTransmittedSummary([])).toBeNull();
    expect(notTransmittedSummary(null)).toBeNull();
  });

  it('does not stand down for a RECONCILED lifecycle status', () => {
    expect(
      notTransmittedSummary([{ status: 'RECONCILED', transmissionState: 'NOT_TRANSMITTED' }]),
    ).not.toBeNull();
  });
});

describe('transmissionReasonFor', () => {
  it('prefers the backend reason over our own wording', () => {
    expect(
      transmissionReasonFor({
        transmissionState: 'NOT_TRANSMITTED_CHANNEL_UNAVAILABLE',
        transmissionReason: '  endpoint file:/tmp/zeropay is local, so it is not a channel  ',
      }),
    ).toBe('endpoint file:/tmp/zeropay is local, so it is not a channel');
  });

  it('falls back to the state description when the backend gave no reason', () => {
    expect(transmissionReasonFor({ transmissionState: 'UNKNOWN' })).toMatch(
      /nothing may be assumed sent/i,
    );
  });
});
