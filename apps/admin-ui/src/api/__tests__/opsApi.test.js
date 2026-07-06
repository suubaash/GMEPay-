/**
 * Unit tests for opsApi.normalizeControlTower — the anti-corruption mapping that
 * flattens the BFF's nested ControlTowerView into the flat shape the
 * /operations page consumes. Guards against the "Objects are not valid as a
 * React child" crash caused by handing the nested `inFlight` object to a card.
 */
import { describe, expect, it } from 'vitest';
import { normalizeControlTower } from '@/api/opsApi';

// Mirrors the real BFF ControlTowerView (nested).
const BFF_RAW = {
  inFlight: { inFlightCount: 42, uncertainOrAgedCount: 3 },
  webhookBacklog: { pending: 5, dlq: 1, total: 6 },
  floatHeadroom: {
    partners: [
      { partnerId: 'GME_KR_001', currency: 'KRW', balance: '1000.00', threshold: '500.00', pctOfThreshold: 200, atRisk: false },
      { partnerId: 'GME_VN_002', currency: 'VND', balance: '100.00', threshold: '500.00', pctOfThreshold: 20, atRisk: true },
    ],
    lowest: { partnerId: 'GME_VN_002' },
  },
  health: { total: 8, up: 7, down: 1, degraded: 0, unhealthy: ['x'] },
  openReconExceptions: 2,
  operationalStatus: { systemPaused: true, maintenanceMode: false },
  recentAlerts: {
    total: 4,
    critical: 1,
    latest: [{ alertType: 'FLOAT_LOW', severity: 'WARNING', subjectRef: 'GME_VN_002' }],
  },
  degradedSections: [],
};

describe('normalizeControlTower', () => {
  it('flattens inFlight into scalar counts', () => {
    const out = normalizeControlTower(BFF_RAW);
    expect(out.inFlight).toBe(42);
    expect(out.uncertainOrAgedCount).toBe(3);
  });

  it('flattens floatHeadroom.partners and maps partnerId -> partner', () => {
    const out = normalizeControlTower(BFF_RAW);
    expect(Array.isArray(out.floatHeadroom)).toBe(true);
    expect(out.floatHeadroom).toHaveLength(2);
    expect(out.floatHeadroom[0].partner).toBe('GME_KR_001');
    expect(out.floatHeadroom[1].atRisk).toBe(true);
  });

  it('flattens recentAlerts.latest into an array', () => {
    const out = normalizeControlTower(BFF_RAW);
    expect(Array.isArray(out.recentAlerts)).toBe(true);
    expect(out.recentAlerts[0].alertType).toBe('FLOAT_LOW');
  });

  it('preserves matching sections (webhookBacklog, health, operationalStatus)', () => {
    const out = normalizeControlTower(BFF_RAW);
    expect(out.webhookBacklog).toEqual({ pending: 5, dlq: 1, total: 6 });
    expect(out.health.up).toBe(7);
    expect(out.operationalStatus.systemPaused).toBe(true);
    expect(out.openReconExceptions).toBe(2);
  });

  it('mirrors the inFlight degraded flag onto uncertainOrAgedCount', () => {
    const out = normalizeControlTower({
      inFlight: { inFlightCount: null, uncertainOrAgedCount: null },
      floatHeadroom: { partners: [], lowest: null },
      recentAlerts: { total: 0, critical: 0, latest: [] },
      degradedSections: ['inFlight'],
    });
    expect(out.inFlight).toBeNull();
    expect(out.uncertainOrAgedCount).toBeNull();
    expect(out.degradedSections).toContain('inFlight');
    expect(out.degradedSections).toContain('uncertainOrAgedCount');
  });

  it('is null-safe for empty/missing sections', () => {
    const out = normalizeControlTower({});
    expect(out.inFlight).toBeNull();
    expect(out.uncertainOrAgedCount).toBeNull();
    expect(out.floatHeadroom).toEqual([]);
    expect(out.recentAlerts).toEqual([]);
    expect(out.degradedSections).toEqual([]);
  });

  it('tolerates an already-flat response (idempotent-ish)', () => {
    const out = normalizeControlTower({
      floatHeadroom: [{ partner: 'P1', atRisk: false }],
      recentAlerts: [{ alertType: 'X' }],
    });
    expect(out.floatHeadroom[0].partner).toBe('P1');
    expect(out.recentAlerts[0].alertType).toBe('X');
  });
});
