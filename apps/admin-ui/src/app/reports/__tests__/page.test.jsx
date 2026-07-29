/**
 * Vitest tests for the Reports centre page (/reports).
 *
 *  1. Renders the report-runs table with fixture data.
 *  2. Type filter change dispatches fetchReports with the new type.
 *  3. "Generate" button opens a confirm dialog; confirming dispatches triggerGenerate.
 *  4. Download button dispatches downloadReportRun for runs with a local artifact.
 *  5. Shows EmptyState when no runs match.
 *  9. GAP T5-2: no run is presented as filed, and a retired SUBMITTED never looks like
 *     a success.
 *
 * All reportsApi calls are mocked — the page renders from Redux state only.
 */
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { Provider } from 'react-redux';
import { configureStore } from '@reduxjs/toolkit';
import reportsReducer from '@/store/reportsSlice';
import authReducer from '@/store/authSlice';
import { theme } from '@/theme/theme';

// ---------------------------------------------------------------------------
// Mock reportsApi BEFORE importing the page or the slice.
// ---------------------------------------------------------------------------
const mockListReports = vi.fn();
const mockGenerateReport = vi.fn();
const mockDownloadReport = vi.fn();

vi.mock('@/api/reportsApi', () => ({
  listReports: (...args) => mockListReports(...args),
  generateReport: (...args) => mockGenerateReport(...args),
  downloadReport: (...args) => mockDownloadReport(...args),
  FIXTURE_REPORT_RUNS: [],
}));

// Suppress ConfirmDialog backdrop and portal warnings in jsdom.
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }),
}));

import ReportsPage from '../page';

// ---------------------------------------------------------------------------
// Fixture data
// ---------------------------------------------------------------------------
/** The 3-lane board the BFF now carries on every ReportRun (GAP T5-2). */
const DARK_CHANNELS = ['BOK', 'KOFIU', 'HOMETAX'].map((lane) => ({
  lane,
  channelLive: false,
  reachableStatus: 'NOT_FILED_CHANNEL_UNAVAILABLE',
  reason: `${lane} channel is not configured.`,
}));

const RUNS = [
  {
    id: 'rpt-001',
    type: 'BOK_FX1014',
    period: '2025-05',
    // Terminal state for every lane today — generated locally, never transmitted.
    status: 'NOT_FILED_CHANNEL_UNAVAILABLE',
    recordCount: '1428',
    generatedAt: '2025-06-01T01:30:00Z',
    downloadUrl: '/v1/admin/reports/rpt-001/download',
    filingChannelUnavailableReason: 'BOK SFTP endpoint is not configured (OI-03).',
    filingChannels: DARK_CHANNELS,
  },
  {
    id: 'rpt-002',
    type: 'BOK_FX1015',
    period: '2025-05',
    status: 'GENERATED',
    recordCount: '312',
    generatedAt: '2025-06-01T02:15:00Z',
    downloadUrl: '/v1/admin/reports/rpt-002/download',
  },
  {
    id: 'rpt-003',
    type: 'KOFIU_STR',
    period: '2025-05',
    status: 'FAILED',
    recordCount: '0',
    generatedAt: '2025-06-01T04:05:00Z',
    downloadUrl: null,
  },
  {
    id: 'rpt-006',
    type: 'ZEROPAY_SETTLEMENT',
    period: '2025-05-31',
    status: 'GENERATED',
    recordCount: '2041',
    generatedAt: '2025-06-01T00:10:00Z',
    downloadUrl: '/v1/admin/reports/rpt-006/download',
  },
];

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------
function makeStore(preloadedReports = {}) {
  return configureStore({
    reducer: { reports: reportsReducer, auth: authReducer },
    preloadedState: {
      reports: {
        items: [],
        loading: false,
        generating: false,
        downloading: {},
        error: null,
        generateError: null,
        filters: { type: '', from: '', to: '' },
        ...preloadedReports,
      },
    },
  });
}

function renderPage(store) {
  return render(
    <Provider store={store ?? makeStore()}>
      <ThemeProvider theme={theme}>
        <ReportsPage />
      </ThemeProvider>
    </Provider>,
  );
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------
describe('ReportsPage', () => {
  beforeEach(() => {
    mockListReports.mockReset();
    mockGenerateReport.mockReset();
    mockDownloadReport.mockReset();
  });

  // 1. Renders table with fixture rows
  it('renders report runs in the table', async () => {
    mockListReports.mockResolvedValue(RUNS);

    renderPage();

    const table = await screen.findByRole('table', { name: /report runs/i });

    // Type labels
    expect(
      within(table).getByText(/BOK FX1014/i),
    ).toBeInTheDocument();
    expect(
      within(table).getByText(/BOK FX1015/i),
    ).toBeInTheDocument();
    expect(
      within(table).getByText(/KoFIU STR/i),
    ).toBeInTheDocument();

    // Record counts rendered as-is (string, not Number()-cast)
    expect(within(table).getByText('1428')).toBeInTheDocument();
    expect(within(table).getByText('312')).toBeInTheDocument();
    expect(within(table).getByText('2041')).toBeInTheDocument();

    // Status chips — the honest vocabulary; "SUBMITTED" cannot appear at all now.
    expect(within(table).queryByText('SUBMITTED')).not.toBeInTheDocument();
    expect(within(table).getAllByText('NOT FILED — no channel').length).toBeGreaterThan(0);
    expect(within(table).getAllByText('GENERATED (local)').length).toBeGreaterThan(0);
    expect(within(table).getAllByText('FAILED').length).toBeGreaterThan(0);
    // Column header names the fact being reported.
    expect(within(table).getByText('Filing status')).toBeInTheDocument();
  });

  // 1b. GAP T5-2 — the page states that nothing has been filed, with the lane board
  it('shows a "nothing has been filed" banner with the per-lane channel board', async () => {
    mockListReports.mockResolvedValue(RUNS);

    renderPage();

    const banner = await screen.findByTestId('not-filed-banner');
    expect(within(banner).getByText(/nothing on this page has been filed/i)).toBeInTheDocument();
    expect(
      within(banner).getByText(/no regulatory lane has a live filing channel/i),
    ).toBeInTheDocument();
    const board = within(banner).getByTestId('filing-channel-board');
    expect(within(board).getByText('BOK: no filing channel')).toBeInTheDocument();
    expect(within(board).getByText('HOMETAX: no filing channel')).toBeInTheDocument();
  });

  // 1c. A retired/unknown status must never be dressed up as a success
  it('renders a stale SUBMITTED as "not a valid state", not as filed', async () => {
    mockListReports.mockResolvedValue([
      {
        ...RUNS[0],
        id: 'rpt-stale',
        status: 'SUBMITTED',
      },
    ]);

    renderPage();

    const table = await screen.findByRole('table', { name: /report runs/i });
    expect(within(table).getByText('SUBMITTED — not a valid state')).toBeInTheDocument();
    // Still flagged as unfiled at the page level.
    expect(screen.getByTestId('not-filed-banner')).toBeInTheDocument();
  });

  // 1d. A run the backend reported as filed suppresses the blanket banner
  it('drops the banner when a run really reports TRANSMITTED', async () => {
    mockListReports.mockResolvedValue([{ ...RUNS[0], id: 'rpt-tx', status: 'TRANSMITTED' }]);

    renderPage();

    const table = await screen.findByRole('table', { name: /report runs/i });
    expect(within(table).getByText('TRANSMITTED')).toBeInTheDocument();
    expect(screen.queryByTestId('not-filed-banner')).not.toBeInTheDocument();
  });

  // 2. Type filter triggers a re-fetch
  it('changes type filter and re-fetches reports', async () => {
    mockListReports.mockResolvedValue(RUNS);
    const user = userEvent.setup();

    renderPage();
    await screen.findByRole('table', { name: /report runs/i });

    // MUI Select — open + pick an option
    const select = screen.getByTestId('report-type-select');
    // Use the combobox role to open the dropdown
    const combobox = screen.getByRole('combobox');
    await user.click(combobox);

    // The listbox should appear with option text
    const listbox = await screen.findByRole('listbox');
    const option = within(listbox).getByText(/BOK FX1014/i);
    await user.click(option);

    await waitFor(() => {
      // listReports should have been called at least twice:
      // once on mount, once after filter change
      expect(mockListReports.mock.calls.length).toBeGreaterThanOrEqual(2);
      const lastCall =
        mockListReports.mock.calls[mockListReports.mock.calls.length - 1][0];
      expect(lastCall.type).toBe('BOK_FX1014');
    });
  });

  // 3a. Generate button opens confirm dialog
  it('clicking Generate opens the confirm dialog', async () => {
    mockListReports.mockResolvedValue(RUNS);
    const user = userEvent.setup();

    renderPage();
    await screen.findByRole('table', { name: /report runs/i });

    // Click the first Generate button
    const generateBtns = screen.getAllByRole('button', { name: /generate/i });
    // The first "Generate" button in the table body (not the confirm dialog)
    await user.click(generateBtns[0]);

    expect(
      await screen.findByRole('dialog'),
    ).toBeInTheDocument();
    expect(
      screen.getByText(/trigger a new generation run/i),
    ).toBeInTheDocument();
  });

  // 3b. Confirming generate dispatches triggerGenerate
  it('confirming generate dispatches triggerGenerate and refreshes', async () => {
    mockListReports.mockResolvedValue(RUNS);
    const newRun = {
      id: 'rpt-new',
      type: 'BOK_FX1014',
      period: '2025-05',
      status: 'PENDING',
      recordCount: '0',
      generatedAt: new Date().toISOString(),
      downloadUrl: null,
    };
    mockGenerateReport.mockResolvedValue(newRun);
    const user = userEvent.setup();

    renderPage();
    await screen.findByRole('table', { name: /report runs/i });

    // Open dialog
    const generateBtns = screen.getAllByRole('button', { name: /generate/i });
    await user.click(generateBtns[0]);
    await screen.findByRole('dialog');

    // Confirm
    const confirmBtn = screen.getByRole('button', { name: /^generate$/i });
    await user.click(confirmBtn);

    await waitFor(() => {
      expect(mockGenerateReport).toHaveBeenCalledTimes(1);
    });

    // Dialog should close
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    });

    // List should have been refreshed after generate
    await waitFor(() => {
      expect(mockListReports.mock.calls.length).toBeGreaterThanOrEqual(2);
    });
  });

  // 3c. Cancelling generate dismisses the dialog without calling API
  it('cancelling generate dialog does not call generateReport', async () => {
    mockListReports.mockResolvedValue(RUNS);
    const user = userEvent.setup();

    renderPage();
    await screen.findByRole('table', { name: /report runs/i });

    const generateBtns = screen.getAllByRole('button', { name: /generate/i });
    await user.click(generateBtns[0]);
    await screen.findByRole('dialog');

    const cancelBtn = screen.getByRole('button', { name: /cancel/i });
    await user.click(cancelBtn);

    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    });
    expect(mockGenerateReport).not.toHaveBeenCalled();
  });

  // 4. Download button dispatches downloadReportRun for rows with a local artifact
  //    (GENERATED / VALIDATED / NOT_FILED_CHANNEL_UNAVAILABLE — never keyed off the
  //    retired SUBMITTED, which used to be the only other allowed value)
  it('download button is rendered for rows with an artifact and triggers download', async () => {
    mockListReports.mockResolvedValue(RUNS);
    // downloadReport returns a Blob — stub minimally
    const fakeBlob = new Blob(['fake'], { type: 'application/zip' });
    mockDownloadReport.mockResolvedValue(fakeBlob);

    // Need URL.createObjectURL + document.createElement('a') in jsdom
    const mockObjectUrl = 'blob:mock-url';
    URL.createObjectURL = vi.fn().mockReturnValue(mockObjectUrl);
    URL.revokeObjectURL = vi.fn();
    const mockAnchorClick = vi.fn();
    const origCreate = document.createElement.bind(document);
    vi.spyOn(document, 'createElement').mockImplementation((tag) => {
      if (tag === 'a') {
        const a = origCreate('a');
        a.click = mockAnchorClick;
        return a;
      }
      return origCreate(tag);
    });

    const user = userEvent.setup();
    renderPage();
    await screen.findByRole('table', { name: /report runs/i });

    // rpt-002 (BOK_FX1015, GENERATED) and rpt-006 (ZEROPAY_SETTLEMENT, GENERATED)
    // have downloadUrl set — their Download icon buttons should be present.
    const downloadBtns = screen.getAllByRole('button', { name: /download/i });
    expect(downloadBtns.length).toBeGreaterThanOrEqual(1);

    await user.click(downloadBtns[0]);

    await waitFor(() => {
      expect(mockDownloadReport).toHaveBeenCalledTimes(1);
    });

    vi.restoreAllMocks();
  });

  // 5. Shows EmptyState when list is empty
  it('shows empty state when no runs are returned', async () => {
    mockListReports.mockResolvedValue([]);

    renderPage();

    expect(
      await screen.findByText(/no report runs found/i),
    ).toBeInTheDocument();
  });

  // 6. Shows error alert when fetch fails
  it('shows error alert when listReports rejects', async () => {
    mockListReports.mockRejectedValue(new Error('BFF unavailable'));

    renderPage();

    expect(
      await screen.findByText(/could not load reports/i),
    ).toBeInTheDocument();
  });
});
