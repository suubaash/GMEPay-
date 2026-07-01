/**
 * Vitest coverage for the /operations Operations console.
 *
 *  1. Control Tower renders rollups from a mocked opsApi client.
 *  2. Operational-status banner surfaces paused/maintenance/reason.
 *  3. A kill-switch action (Pause) POSTs through opsApi after confirm.
 *  4. Alerts tab renders the alert list from the client.
 */
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { theme } from '@/theme/theme';

// Quiet snackbar.
const success = vi.fn();
const error = vi.fn();
vi.mock('@/components/SnackbarProvider', () => ({
  useSnackbar: () => ({ success, error, info: vi.fn(), warning: vi.fn() }),
}));

// Mock the ops API client — the page must never hit real network.
const mockGetControlTower = vi.fn();
const mockGetAlerts = vi.fn();
const mockSearchTransactions = vi.fn();
const mockPause = vi.fn();
vi.mock('@/api/opsApi', () => ({
  getControlTower: (...a) => mockGetControlTower(...a),
  getAlerts: (...a) => mockGetAlerts(...a),
  searchTransactions: (...a) => mockSearchTransactions(...a),
  pause: (...a) => mockPause(...a),
  resume: vi.fn(),
  setMaintenance: vi.fn(),
  suspend: vi.fn(),
  unsuspend: vi.fn(),
  resolveTransaction: vi.fn(),
  replayWebhook: vi.fn(),
  rerunRecon: vi.fn(),
  OPS_OPERATE_PERMISSION: 'ops:operate',
}));

const CONTROL_TOWER = {
  inFlight: 42,
  uncertainOrAgedCount: 3,
  webhookBacklog: { pending: 5, dlq: 1, total: 6 },
  floatHeadroom: [
    { partner: 'GME_KR_001', balance: '1000.00', threshold: '500.00', pctOfThreshold: 200, atRisk: false },
    { partner: 'GME_VN_002', balance: '100.00', threshold: '500.00', pctOfThreshold: 20, atRisk: true },
  ],
  health: { total: 8, up: 7, down: 1, degraded: 0 },
  openReconExceptions: 2,
  operationalStatus: {
    systemPaused: true,
    maintenanceMode: false,
    suspendedPartners: ['GME_PH_003'],
    suspendedSchemes: [],
    suspendedRoutes: [],
    reason: 'Incident #501',
    since: '2026-07-02T01:00:00Z',
  },
  recentAlerts: [
    { alertType: 'FLOAT_LOW', severity: 'WARNING', subjectRef: 'GME_VN_002', detail: 'Below threshold', occurredAt: '2026-07-02T00:00:00Z' },
  ],
  degradedSections: [],
};

const ALERTS = [
  { alertType: 'FLOAT_LOW', severity: 'WARNING', subjectRef: 'GME_VN_002', detail: 'Below threshold', occurredAt: '2026-07-02T00:00:00Z' },
  { alertType: 'WEBHOOK_DLQ', severity: 'CRITICAL', subjectRef: 'wh-99', detail: 'Dead-lettered', occurredAt: '2026-07-02T00:05:00Z' },
];

import OperationsPage from '../page';

function renderPage() {
  return render(
    <ThemeProvider theme={theme}>
      <OperationsPage />
    </ThemeProvider>,
  );
}

describe('OperationsPage', () => {
  beforeEach(() => {
    success.mockReset();
    error.mockReset();
    mockGetControlTower.mockReset();
    mockGetAlerts.mockReset();
    mockSearchTransactions.mockReset();
    mockPause.mockReset();
    mockGetControlTower.mockResolvedValue(CONTROL_TOWER);
    mockGetAlerts.mockResolvedValue(ALERTS);
    mockSearchTransactions.mockResolvedValue({ content: [], page: 0, size: 20, total: 0 });
    mockPause.mockResolvedValue(undefined);
  });

  it('renders control tower rollups and float table from the client', async () => {
    renderPage();
    // in-flight value
    expect(await screen.findByText('42')).toBeInTheDocument();
    // float table shows both partners, at-risk one flagged
    const table = await screen.findByRole('table', { name: /float headroom/i });
    expect(within(table).getByText('GME_KR_001')).toBeInTheDocument();
    expect(within(table).getByText('GME_VN_002')).toBeInTheDocument();
    expect(within(table).getByText('AT RISK')).toBeInTheDocument();
  });

  it('surfaces the operational-status banner (paused + reason)', async () => {
    renderPage();
    const banner = await screen.findByLabelText('operational-status-banner');
    expect(within(banner).getByText(/SYSTEM PAUSED/i)).toBeInTheDocument();
    expect(within(banner).getByText(/Incident #501/i)).toBeInTheDocument();
  });

  it('kill-switch Pause action POSTs through opsApi after confirm', async () => {
    // Not paused so the Pause button is enabled.
    mockGetControlTower.mockResolvedValue({
      ...CONTROL_TOWER,
      operationalStatus: { ...CONTROL_TOWER.operationalStatus, systemPaused: false },
    });
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByRole('tab', { name: /kill-switch/i }));
    await user.click(await screen.findByRole('button', { name: /^Pause$/i }));

    // Confirm dialog needs a reason.
    const reason = await screen.findByLabelText('action reason');
    await user.type(reason, 'planned failover');
    await user.click(screen.getByRole('button', { name: /confirm action/i }));

    await waitFor(() => expect(mockPause).toHaveBeenCalledWith('planned failover'));
    await waitFor(() => expect(success).toHaveBeenCalled());
  });

  it('alerts tab renders the alert list', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(screen.getByRole('tab', { name: /alerts/i }));

    const table = await screen.findByRole('table', { name: /ops alerts/i });
    expect(within(table).getByText('FLOAT_LOW')).toBeInTheDocument();
    expect(within(table).getByText('WEBHOOK_DLQ')).toBeInTheDocument();
  });
});
