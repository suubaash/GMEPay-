/**
 * Unit tests for complianceSlice (Lane 5).
 *
 * Covers:
 *  1. Initial state shape.
 *  2. fetchComplianceOverview: fulfilled stores overview[], loading cleared.
 *  3. fetchComplianceOverview: rejected stores error message.
 *  4. fetchRegulatoryConfig: sets selectedPartnerCode + regulatory.
 *  5. fetchPartnerKyb: stores kyb data.
 *  6. fetchAuditLog: stores page content + meta (page, size, total).
 *  7. fetchAuditLog: graceful empty payload.
 *  8. selectPartner / clearSelection reducers clear panel state.
 *  9. Filter reducers: setKybFilter, setSanctionsFilter, setLifecycleFilter.
 * 10. Audit filter reducers reset auditCurrentPage when aggregate/from/to change.
 */
import { describe, expect, it } from 'vitest';
import reducer, {
  fetchComplianceOverview,
  fetchRegulatoryConfig,
  fetchPartnerKyb,
  fetchAuditLog,
  selectPartner,
  clearSelection,
  setKybFilter,
  setSanctionsFilter,
  setLifecycleFilter,
  setAuditAggregate,
  setAuditFrom,
  setAuditTo,
  setAuditPage,
} from '@/store/complianceSlice';

// ---------------------------------------------------------------------------
// Fixtures
// ---------------------------------------------------------------------------
const OVERVIEW = [
  {
    partnerCode: 'GME_KR_001',
    partnerName: 'GME Korea Co., Ltd.',
    kybStatus: 'APPROVED',
    sanctionsResult: 'CLEAR',
    regulatoryConfig: { bokSet: true, hometaxSet: true, kofiuSet: true, travelRuleSet: true },
    lifecycleStatus: 'LIVE',
  },
  {
    partnerCode: 'GME_VN_002',
    partnerName: 'GME Vietnam Pte.',
    kybStatus: 'PENDING',
    sanctionsResult: 'NEEDS_REVIEW',
    regulatoryConfig: { bokSet: false, hometaxSet: false, kofiuSet: false, travelRuleSet: false },
    lifecycleStatus: 'ONBOARDING',
  },
];

const REGULATORY = {
  bok: { txnCode: 'T-1021', fxReportingCategory: 'REMITTANCE', remitterType: 'INDIVIDUAL' },
  hometax: { hometaxIssuerCertId: 'HT-9981', vatTreatment: 'ZERO_RATED' },
  kofiu: { kofiuEntityId: 'KOFIU-GME-001', ctrThresholdKrw: '10000000' },
  pipa: { pipaJurisdictionAllowlist: ['KR'] },
  travelRule: { protocol: 'IVMS101', endpointUrl: 'https://tr.test', thresholdKrw: '1000000' },
};

const KYB = {
  partnerCode: 'GME_KR_001',
  riskRating: 'LOW',
  riskRationale: 'Established partner',
  screeningStatus: 'CLEAR',
  screenedAt: '2024-10-15T03:00:00Z',
  screeningHits: [],
  uboList: [],
};

const AUDIT_PAGE = {
  content: [
    { id: 'AUD-001', event: 'PARTNER_KYB_APPROVED', aggregate: 'GME_KR_001', actor: 'admin@gmeremit.com', at: '2024-10-15T03:12:00Z' },
    { id: 'AUD-002', event: 'REGULATORY_CONFIG_UPDATED', aggregate: 'GME_KR_001', actor: 'ops@gmeremit.com', at: '2024-10-14T09:30:00Z' },
  ],
  page: 0,
  size: 20,
  total: 42,
};

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------
describe('complianceSlice', () => {
  // 1. Initial state shape
  it('has the correct initial state shape', () => {
    const state = reducer(undefined, { type: '@@INIT' });
    expect(state.overview).toEqual([]);
    expect(state.overviewLoading).toBe(false);
    expect(state.overviewError).toBeNull();
    expect(state.selectedPartnerCode).toBeNull();
    expect(state.regulatory).toBeNull();
    expect(state.kyb).toBeNull();
    expect(state.auditPage).toEqual([]);
    expect(state.auditMeta).toEqual({ page: 0, size: 20, total: 0 });
    expect(state.kybFilter).toBe('ALL');
    expect(state.sanctionsFilter).toBe('ALL');
    expect(state.lifecycleFilter).toBe('ALL');
    expect(state.auditCurrentPage).toBe(0);
    expect(state.auditPageSize).toBe(20);
  });

  // 2. fetchComplianceOverview fulfilled
  it('stores overview rows on fetchComplianceOverview.fulfilled', () => {
    const state = reducer(undefined, {
      type: fetchComplianceOverview.fulfilled.type,
      payload: OVERVIEW,
    });
    expect(state.overviewLoading).toBe(false);
    expect(state.overviewError).toBeNull();
    expect(state.overview).toHaveLength(2);
    expect(state.overview[0].partnerCode).toBe('GME_KR_001');
    expect(state.overview[1].kybStatus).toBe('PENDING');
  });

  // 3. fetchComplianceOverview rejected
  it('stores error message on fetchComplianceOverview.rejected', () => {
    const state = reducer(undefined, {
      type: fetchComplianceOverview.rejected.type,
      error: { message: 'BFF unavailable' },
    });
    expect(state.overviewLoading).toBe(false);
    expect(state.overviewError).toBe('BFF unavailable');
    expect(state.overview).toEqual([]);
  });

  // 4. fetchRegulatoryConfig sets selectedPartnerCode + regulatory
  it('stores regulatory config and selectedPartnerCode on fetchRegulatoryConfig.fulfilled', () => {
    const pending = reducer(undefined, {
      type: fetchRegulatoryConfig.pending.type,
      meta: { arg: 'GME_KR_001' },
    });
    expect(pending.selectedPartnerCode).toBe('GME_KR_001');
    expect(pending.regulatoryLoading).toBe(true);

    const fulfilled = reducer(pending, {
      type: fetchRegulatoryConfig.fulfilled.type,
      payload: REGULATORY,
    });
    expect(fulfilled.regulatoryLoading).toBe(false);
    expect(fulfilled.regulatory.bok.txnCode).toBe('T-1021');
    expect(fulfilled.regulatory.travelRule.protocol).toBe('IVMS101');
    // ctrThresholdKrw must stay as string — never Number()-cast
    expect(fulfilled.regulatory.kofiu.ctrThresholdKrw).toBe('10000000');
    expect(typeof fulfilled.regulatory.kofiu.ctrThresholdKrw).toBe('string');
  });

  // 5. fetchPartnerKyb stores kyb data
  it('stores KYB view on fetchPartnerKyb.fulfilled', () => {
    const state = reducer(undefined, {
      type: fetchPartnerKyb.fulfilled.type,
      payload: KYB,
    });
    expect(state.kybLoading).toBe(false);
    expect(state.kyb.partnerCode).toBe('GME_KR_001');
    expect(state.kyb.screeningStatus).toBe('CLEAR');
    expect(state.kyb.screenedAt).toBe('2024-10-15T03:00:00Z');
  });

  // 6. fetchAuditLog stores page content + meta
  it('stores audit page content and pagination meta on fetchAuditLog.fulfilled', () => {
    const state = reducer(undefined, {
      type: fetchAuditLog.fulfilled.type,
      payload: AUDIT_PAGE,
    });
    expect(state.auditLoading).toBe(false);
    expect(state.auditError).toBeNull();
    expect(state.auditPage).toHaveLength(2);
    expect(state.auditPage[0].id).toBe('AUD-001');
    expect(state.auditPage[0].event).toBe('PARTNER_KYB_APPROVED');
    expect(state.auditMeta.page).toBe(0);
    expect(state.auditMeta.size).toBe(20);
    expect(state.auditMeta.total).toBe(42);
  });

  // 7. fetchAuditLog graceful empty payload
  it('handles empty payload in fetchAuditLog.fulfilled gracefully', () => {
    const state = reducer(undefined, {
      type: fetchAuditLog.fulfilled.type,
      payload: {},
    });
    expect(state.auditPage).toEqual([]);
    expect(state.auditMeta.total).toBe(0);
    expect(state.auditMeta.page).toBe(0);
    expect(state.auditMeta.size).toBe(20);
  });

  // 8. selectPartner / clearSelection
  it('selectPartner sets selectedPartnerCode and clears panel state', () => {
    // Pre-load some state
    let state = reducer(undefined, {
      type: fetchRegulatoryConfig.fulfilled.type,
      payload: REGULATORY,
    });
    state = reducer(state, {
      type: fetchPartnerKyb.fulfilled.type,
      payload: KYB,
    });

    // Select a different partner
    state = reducer(state, selectPartner('GME_VN_002'));
    expect(state.selectedPartnerCode).toBe('GME_VN_002');
    // Panel data reset on partner change
    expect(state.regulatory).toBeNull();
    expect(state.kyb).toBeNull();
    expect(state.regulatoryError).toBeNull();
    expect(state.kybError).toBeNull();
  });

  it('clearSelection nullifies selectedPartnerCode and panel data', () => {
    let state = reducer(undefined, selectPartner('GME_KR_001'));
    state = reducer(state, clearSelection());
    expect(state.selectedPartnerCode).toBeNull();
    expect(state.regulatory).toBeNull();
    expect(state.kyb).toBeNull();
  });

  // 9. Filter reducers
  it('setKybFilter updates kybFilter', () => {
    const state = reducer(undefined, setKybFilter('PENDING'));
    expect(state.kybFilter).toBe('PENDING');
  });

  it('setSanctionsFilter updates sanctionsFilter', () => {
    const state = reducer(undefined, setSanctionsFilter('HIT'));
    expect(state.sanctionsFilter).toBe('HIT');
  });

  it('setLifecycleFilter updates lifecycleFilter', () => {
    const state = reducer(undefined, setLifecycleFilter('SUSPENDED'));
    expect(state.lifecycleFilter).toBe('SUSPENDED');
  });

  // 10. Audit filter reducers reset auditCurrentPage
  it('setAuditAggregate resets auditCurrentPage to 0', () => {
    let state = reducer(undefined, setAuditPage(3));
    expect(state.auditCurrentPage).toBe(3);
    state = reducer(state, setAuditAggregate('GME_KR_001'));
    expect(state.auditAggregate).toBe('GME_KR_001');
    expect(state.auditCurrentPage).toBe(0);
  });

  it('setAuditFrom and setAuditTo reset auditCurrentPage to 0', () => {
    let state = reducer(undefined, setAuditPage(2));
    state = reducer(state, setAuditFrom('2024-01-01'));
    expect(state.auditFrom).toBe('2024-01-01');
    expect(state.auditCurrentPage).toBe(0);

    state = reducer(state, setAuditPage(2));
    state = reducer(state, setAuditTo('2024-12-31'));
    expect(state.auditTo).toBe('2024-12-31');
    expect(state.auditCurrentPage).toBe(0);
  });

  it('setAuditPage advances auditCurrentPage without resetting filters', () => {
    let state = reducer(undefined, setAuditAggregate('GME_KR_001'));
    state = reducer(state, setAuditPage(5));
    expect(state.auditCurrentPage).toBe(5);
    // filter not reset
    expect(state.auditAggregate).toBe('GME_KR_001');
  });
});
