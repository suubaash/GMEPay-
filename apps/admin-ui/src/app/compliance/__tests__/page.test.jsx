/**
 * Vitest coverage for the /compliance page (Lane 5).
 *
 *  1. Renders the overview table with fixture rows.
 *  2. KYB status filter hides non-matching rows.
 *  3. Sanctions filter hides non-matching rows.
 *  4. Lifecycle filter hides non-matching rows.
 *  5. Clicking a partner code opens the drill-down Drawer.
 *  6. Drill-down panel shows regulatory config + KYB + audit data.
 *  7. Audit log renders paged entries with KST timestamps.
 *  8. Audit pagination dispatches setAuditPage action.
 */
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { Provider } from 'react-redux';
import { configureStore } from '@reduxjs/toolkit';
import complianceReducer from '@/store/complianceSlice';
import { theme } from '@/theme/theme';

// ---------------------------------------------------------------------------
// Mock next/navigation
// ---------------------------------------------------------------------------
const push = vi.fn();
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push, replace: vi.fn(), back: vi.fn() }),
}));

// ---------------------------------------------------------------------------
// Mock complianceApi — the page and slice must never hit real network.
// ---------------------------------------------------------------------------
const mockGetComplianceOverview = vi.fn();
const mockGetRegulatoryConfig = vi.fn();
const mockGetPartnerKyb = vi.fn();
const mockGetAuditLog = vi.fn();

vi.mock('@/api/complianceApi', () => ({
  getComplianceOverview: (...args) => mockGetComplianceOverview(...args),
  getRegulatoryConfig: (...args) => mockGetRegulatoryConfig(...args),
  getPartnerKyb: (...args) => mockGetPartnerKyb(...args),
  getAuditLog: (...args) => mockGetAuditLog(...args),
  FIXTURE_OVERVIEW: [],
  FIXTURE_REGULATORY: {},
  FIXTURE_KYB: {},
  FIXTURE_AUDIT_PAGE: { content: [], page: 0, size: 20, total: 0 },
}));

// ---------------------------------------------------------------------------
// Fixtures
// ---------------------------------------------------------------------------
const OVERVIEW_ROWS = [
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
  {
    partnerCode: 'GME_PH_003',
    partnerName: 'GME Philippines Inc.',
    kybStatus: 'HIT',
    sanctionsResult: 'HIT',
    regulatoryConfig: { bokSet: false, hometaxSet: false, kofiuSet: false, travelRuleSet: false },
    lifecycleStatus: 'SUSPENDED',
  },
];

const REGULATORY = {
  bok: { txnCode: 'T-1021', fxReportingCategory: 'REMITTANCE', remitterType: 'INDIVIDUAL' },
  hometax: { hometaxIssuerCertId: 'HT-9981', vatTreatment: 'ZERO_RATED' },
  kofiu: { kofiuEntityId: 'KOFIU-GME-001', ctrThresholdKrw: '10000000' },
  pipa: { pipaJurisdictionAllowlist: ['KR', 'SG'] },
  travelRule: { protocol: 'IVMS101', endpointUrl: 'https://travel-rule.test', thresholdKrw: '1000000' },
};

const KYB = {
  partnerCode: 'GME_KR_001',
  riskRating: 'LOW',
  riskRationale: 'Established partner',
  nextReviewDate: '2027-01-15',
  licenseType: 'MSB',
  licenseNumber: 'KR-MSB-2021-0042',
  licenseAuthority: 'Financial Services Commission',
  licenseExpiry: '2026-12-31',
  uboList: [{ name: 'Park Ji-ho', ownershipPct: '51', isPep: false, country: 'KR' }],
  cbddqDocId: 'doc-001',
  screeningStatus: 'CLEAR',
  screeningProviderRef: 'OCT-2024-0091',
  screenedAt: '2024-10-15T03:00:00Z',
  screeningHits: [],
};

const AUDIT = {
  content: [
    { id: 'AUD-001', event: 'PARTNER_KYB_APPROVED', aggregate: 'GME_KR_001', actor: 'admin@gmeremit.com', at: '2024-10-15T03:12:00Z' },
    { id: 'AUD-002', event: 'REGULATORY_CONFIG_UPDATED', aggregate: 'GME_KR_001', actor: 'ops@gmeremit.com', at: '2024-10-14T09:30:00Z' },
  ],
  page: 0,
  size: 20,
  total: 2,
};

const AUDIT_PAGE2 = {
  content: [
    { id: 'AUD-003', event: 'PARTNER_ACTIVATED', aggregate: 'GME_KR_001', actor: 'admin@gmeremit.com', at: '2024-09-01T06:00:00Z' },
  ],
  page: 1,
  size: 20,
  total: 21,
};

// ---------------------------------------------------------------------------
// Render helper
// ---------------------------------------------------------------------------
import CompliancePage from '../page';

function renderPage() {
  const store = configureStore({
    reducer: { compliance: complianceReducer },
  });
  return {
    store,
    ...render(
      <Provider store={store}>
        <ThemeProvider theme={theme}>
          <CompliancePage />
        </ThemeProvider>
      </Provider>,
    ),
  };
}

describe('CompliancePage', () => {
  beforeEach(() => {
    push.mockReset();
    mockGetComplianceOverview.mockReset();
    mockGetRegulatoryConfig.mockReset();
    mockGetPartnerKyb.mockReset();
    mockGetAuditLog.mockReset();
  });

  // -------------------------------------------------------------------------
  // 1. Renders overview table with fixture rows
  // -------------------------------------------------------------------------
  it('renders overview table with all partner rows', async () => {
    mockGetComplianceOverview.mockResolvedValue(OVERVIEW_ROWS);
    mockGetAuditLog.mockResolvedValue(AUDIT);

    renderPage();

    const table = await screen.findByRole('table', { name: /partner compliance overview/i });
    expect(within(table).getByText('GME_KR_001')).toBeInTheDocument();
    expect(within(table).getByText('GME_VN_002')).toBeInTheDocument();
    expect(within(table).getByText('GME_PH_003')).toBeInTheDocument();
  });

  // -------------------------------------------------------------------------
  // 2. KYB status filter
  // -------------------------------------------------------------------------
  it('filters rows by KYB status', async () => {
    mockGetComplianceOverview.mockResolvedValue(OVERVIEW_ROWS);
    mockGetAuditLog.mockResolvedValue(AUDIT);

    const { store } = renderPage();

    // Wait for rows to appear
    await screen.findByRole('table', { name: /partner compliance overview/i });

    // Dispatch filter action directly — avoids brittle MUI Select click mechanics
    const { setKybFilter } = await import('@/store/complianceSlice');
    store.dispatch(setKybFilter('APPROVED'));

    await waitFor(() => {
      const table = screen.getByRole('table', { name: /partner compliance overview/i });
      // Only GME_KR_001 is APPROVED
      expect(within(table).getByText('GME_KR_001')).toBeInTheDocument();
      expect(within(table).queryByText('GME_VN_002')).not.toBeInTheDocument();
      expect(within(table).queryByText('GME_PH_003')).not.toBeInTheDocument();
    });
  });

  // -------------------------------------------------------------------------
  // 3. Sanctions filter
  // -------------------------------------------------------------------------
  it('filters rows by sanctions result', async () => {
    mockGetComplianceOverview.mockResolvedValue(OVERVIEW_ROWS);
    mockGetAuditLog.mockResolvedValue(AUDIT);

    const { store } = renderPage();

    await screen.findByRole('table', { name: /partner compliance overview/i });

    const { setSanctionsFilter } = await import('@/store/complianceSlice');
    store.dispatch(setSanctionsFilter('HIT'));

    await waitFor(() => {
      const table = screen.getByRole('table', { name: /partner compliance overview/i });
      expect(within(table).getByText('GME_PH_003')).toBeInTheDocument();
      expect(within(table).queryByText('GME_KR_001')).not.toBeInTheDocument();
      expect(within(table).queryByText('GME_VN_002')).not.toBeInTheDocument();
    });
  });

  // -------------------------------------------------------------------------
  // 4. Lifecycle filter
  // -------------------------------------------------------------------------
  it('filters rows by lifecycle status', async () => {
    mockGetComplianceOverview.mockResolvedValue(OVERVIEW_ROWS);
    mockGetAuditLog.mockResolvedValue(AUDIT);

    const { store } = renderPage();

    await screen.findByRole('table', { name: /partner compliance overview/i });

    const { setLifecycleFilter } = await import('@/store/complianceSlice');
    store.dispatch(setLifecycleFilter('LIVE'));

    await waitFor(() => {
      const table = screen.getByRole('table', { name: /partner compliance overview/i });
      expect(within(table).getByText('GME_KR_001')).toBeInTheDocument();
      expect(within(table).queryByText('GME_VN_002')).not.toBeInTheDocument();
      expect(within(table).queryByText('GME_PH_003')).not.toBeInTheDocument();
    });
  });

  // -------------------------------------------------------------------------
  // 5. Clicking a partner opens the drill-down Drawer
  // -------------------------------------------------------------------------
  it('opens drill-down panel when a partner code button is clicked', async () => {
    mockGetComplianceOverview.mockResolvedValue(OVERVIEW_ROWS);
    mockGetAuditLog.mockResolvedValue(AUDIT);
    mockGetRegulatoryConfig.mockResolvedValue(REGULATORY);
    mockGetPartnerKyb.mockResolvedValue(KYB);

    const user = userEvent.setup();
    renderPage();

    const btn = await screen.findByRole('button', { name: /open drill-down for GME_KR_001/i });
    await user.click(btn);

    // The drawer should be visible — heading is the partner code.
    await waitFor(() => {
      expect(screen.getAllByText('GME_KR_001').length).toBeGreaterThanOrEqual(1);
    });
    // Close button present
    expect(screen.getByRole('button', { name: /close drill-down panel/i })).toBeInTheDocument();
  });

  // -------------------------------------------------------------------------
  // 6. Drill-down panel shows regulatory + KYB + audit
  // -------------------------------------------------------------------------
  it('drill-down panel renders regulatory config, KYB data and audit entries', async () => {
    mockGetComplianceOverview.mockResolvedValue(OVERVIEW_ROWS);
    mockGetAuditLog.mockResolvedValue(AUDIT);
    mockGetRegulatoryConfig.mockResolvedValue(REGULATORY);
    mockGetPartnerKyb.mockResolvedValue(KYB);

    const user = userEvent.setup();
    renderPage();

    const btn = await screen.findByRole('button', { name: /open drill-down for GME_KR_001/i });
    await user.click(btn);

    // Regulatory section
    await screen.findByText('T-1021');      // BOK txnCode
    expect(screen.getByText('IVMS101')).toBeInTheDocument();  // travelRule protocol

    // KYB section
    expect(screen.getByText('KR-MSB-2021-0042')).toBeInTheDocument();  // licenseNumber
    expect(screen.getByText('Park Ji-ho')).toBeInTheDocument();         // UBO

    // Audit entries from AUDIT fixture — may appear in both the global audit table
    // and the drill-down panel, so just assert at least one occurrence.
    expect(screen.getAllByText('PARTNER_KYB_APPROVED').length).toBeGreaterThanOrEqual(1);
  });

  // -------------------------------------------------------------------------
  // 7. Audit log renders entries with KST timestamps
  // -------------------------------------------------------------------------
  it('renders audit log entries with KST-formatted timestamps', async () => {
    mockGetComplianceOverview.mockResolvedValue([]);
    mockGetAuditLog.mockResolvedValue(AUDIT);

    renderPage();

    const auditTable = await screen.findByRole('table', { name: /audit log/i });
    // 2024-10-15T03:12:00Z => 2024-10-15 12:12:00 KST
    expect(within(auditTable).getByText(/2024-10-15 12:12:00 KST/i)).toBeInTheDocument();
    expect(within(auditTable).getByText('PARTNER_KYB_APPROVED')).toBeInTheDocument();
    expect(within(auditTable).getByText('admin@gmeremit.com')).toBeInTheDocument();
  });

  // -------------------------------------------------------------------------
  // 8. Audit pagination — next page triggers a new fetch
  // -------------------------------------------------------------------------
  it('advances audit page when next-page pagination button is clicked', async () => {
    mockGetComplianceOverview.mockResolvedValue([]);
    // First call returns 21 total so MUI shows a "Next page" button.
    mockGetAuditLog.mockResolvedValueOnce({ ...AUDIT, total: 21 });
    mockGetAuditLog.mockResolvedValueOnce(AUDIT_PAGE2);

    renderPage();

    await screen.findByRole('table', { name: /audit log/i });
    await waitFor(() => expect(mockGetAuditLog).toHaveBeenCalledTimes(1));

    // Click "next page" button (MUI TablePagination)
    const nextBtn = screen.getByRole('button', { name: /next page/i });
    fireEvent.click(nextBtn);

    await waitFor(() => expect(mockGetAuditLog).toHaveBeenCalledTimes(2));
    const secondCall = mockGetAuditLog.mock.calls[1][0];
    expect(secondCall.page).toBe(1);
  });
});
