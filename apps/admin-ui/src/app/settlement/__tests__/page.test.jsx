/**
 * Vitest tests for the Settlement list page (/settlement).
 *
 * GAP T4-5: this page used to render one "Status" column, so a batch reading `RECONCILED`
 * was taken as "sent to the scheme" when nothing has ever been transmitted. What is pinned
 * here:
 *
 *  1. transmission is its OWN column, distinct from the lifecycle column;
 *  2. the standing banner names what has NOT happened, and is driven by the channel board
 *     endpoint rather than hardcoded — a live channel removes it;
 *  3. an absent/unrecognised transmissionState renders as unknown, never as sent;
 *  4. the date-range endpoint (`listSettlementBatches`) is what the page reads, with a
 *     default window.
 */
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import { Provider } from 'react-redux';
import { configureStore } from '@reduxjs/toolkit';
import { theme } from '@/theme/theme';

const mockListSettlementBatches = vi.fn();
const mockGetSettlementTransmissionChannel = vi.fn();

vi.mock('@/api/client', () => ({
  adminApi: {
    listSettlements: (...a) => mockListSettlementBatches(...a),
    listSettlementBatches: (...a) => mockListSettlementBatches(...a),
    getSettlement: vi.fn(),
    getSettlementTransmissionChannel: (...a) => mockGetSettlementTransmissionChannel(...a),
  },
}));

const mockPush = vi.fn();
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: mockPush, replace: vi.fn(), back: vi.fn() }),
}));

import settlementReducer from '@/store/settlementSlice';
import SettlementPage from '../page';

/** The board every environment reports today: no channel, and why. */
const DARK_CHANNEL = {
  live: false,
  reachableState: 'NOT_TRANSMITTED_CHANNEL_UNAVAILABLE',
  reason:
    'settlement transport endpoint is a local directory (file:/tmp/zeropay-out); a local '
    + 'directory is not a channel.',
};

const BATCHES = [
  {
    batchId: 'SB-2026-07-20-REQ-001',
    partnerId: 'ZEROPAY',
    settlementDate: '2026-07-20',
    currency: 'KRW',
    amount: '61000',
    status: 'RECONCILED',
    transmissionState: 'NOT_TRANSMITTED_CHANNEL_UNAVAILABLE',
    transmissionReason: 'no transmission channel is configured (V013)',
    transmittedAt: null,
  },
  {
    batchId: 'SB-2026-07-21-REQ-002',
    partnerId: 'ZEROPAY',
    settlementDate: '2026-07-21',
    currency: 'KRW',
    amount: '42000',
    status: 'GENERATED',
    // Upstream said nothing — must read as UNKNOWN, not NOT_TRANSMITTED and not sent.
    transmissionState: null,
    transmissionReason: null,
    transmittedAt: null,
  },
];

function renderPage() {
  const store = configureStore({ reducer: { settlement: settlementReducer } });
  return render(
    <Provider store={store}>
      <ThemeProvider theme={theme}>
        <SettlementPage />
      </ThemeProvider>
    </Provider>,
  );
}

describe('SettlementPage — transmission honesty (T4-5)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockListSettlementBatches.mockResolvedValue(BATCHES);
    mockGetSettlementTransmissionChannel.mockResolvedValue(DARK_CHANNEL);
  });

  it('reads the DATE-RANGED endpoint with a default window, not the single-date one', async () => {
    renderPage();
    await waitFor(() => expect(mockListSettlementBatches).toHaveBeenCalled());
    const arg = mockListSettlementBatches.mock.calls[0][0];
    expect(arg).toMatchObject({ limit: 0 });
    expect(arg.from).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect(arg.to).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect(arg.from < arg.to).toBe(true);
    expect(screen.getByLabelText('Settlement date from')).toBeInTheDocument();
    expect(screen.getByLabelText('Settlement date to')).toBeInTheDocument();
  });

  it('renders transmission as its own column, separate from the lifecycle column', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByText('SB-2026-07-20-REQ-001')).toBeInTheDocument());

    expect(screen.getByRole('columnheader', { name: 'Lifecycle' })).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: 'Sent to scheme' })).toBeInTheDocument();
    // The old single "Status" header is gone — that header was the whole defect.
    expect(screen.queryByRole('columnheader', { name: 'Status' })).toBeNull();

    // The RECONCILED batch shows RECONCILED on the lifecycle axis AND "not sent" on the
    // transmission axis: both facts, in two places, on the same row.
    expect(screen.getByLabelText('Lifecycle status RECONCILED')).toBeInTheDocument();
    expect(
      screen.getByLabelText('Transmission state NOT_TRANSMITTED_CHANNEL_UNAVAILABLE'),
    ).toBeInTheDocument();
    expect(screen.getByText('Not sent — no channel')).toBeInTheDocument();
  });

  it('never presents a RECONCILED batch as sent', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByText('SB-2026-07-20-REQ-001')).toBeInTheDocument());
    expect(screen.queryByText('Sent to scheme', { selector: '.MuiChip-label' })).toBeNull();
  });

  it('renders an absent transmissionState as UNKNOWN, not as NOT_TRANSMITTED and not sent', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByText('SB-2026-07-21-REQ-002')).toBeInTheDocument());
    expect(screen.getByLabelText('Transmission state UNKNOWN')).toBeInTheDocument();
    expect(screen.getByText('Unknown — not verifiable')).toBeInTheDocument();
  });

  it('states plainly that nothing has been transmitted, and why, from the channel board', async () => {
    renderPage();
    const banner = await screen.findByTestId('not-transmitted-banner');
    expect(banner).toHaveTextContent('No settlement file has been transmitted to a scheme');
    expect(banner).toHaveTextContent(/RECONCILED does not mean anything was sent/);

    const board = await screen.findByTestId('settlement-transmission-board');
    expect(within(board).getByText('Scheme channel: not configured')).toBeInTheDocument();
    // The reason is the backend's, not the UI's invention.
    expect(board).toHaveTextContent(/a local directory is not a channel/i);
  });

  it('drops the banner the moment a batch genuinely reports TRANSMITTED', async () => {
    mockListSettlementBatches.mockResolvedValue([
      { ...BATCHES[0], transmissionState: 'TRANSMITTED', transmittedAt: '2026-07-20T09:00:00Z' },
    ]);
    mockGetSettlementTransmissionChannel.mockResolvedValue({
      live: true,
      reachableState: 'TRANSMITTED',
      reason: null,
    });
    renderPage();
    await waitFor(() => expect(screen.getByText('SB-2026-07-20-REQ-001')).toBeInTheDocument());
    expect(screen.queryByTestId('not-transmitted-banner')).toBeNull();
    expect(screen.getByLabelText('Transmission state TRANSMITTED')).toBeInTheDocument();
  });

  it('renders an unreported channel board as unknown, never as availability', async () => {
    mockGetSettlementTransmissionChannel.mockRejectedValue(new Error('upstream down'));
    renderPage();
    const banner = await screen.findByTestId('not-transmitted-banner');
    expect(within(banner).getByTestId('settlement-channel-unknown')).toHaveTextContent(
      /nothing\s+may be assumed transmitted/i,
    );
    expect(screen.queryByText('Scheme channel: live')).toBeNull();
  });

  it('names the window in the empty state rather than implying nothing was ever settled', async () => {
    mockListSettlementBatches.mockResolvedValue([]);
    renderPage();
    await waitFor(() =>
      expect(screen.getByText('No settlement batches in this window')).toBeInTheDocument());
    // No blanket "nothing has been transmitted" claim about zero rows.
    expect(screen.queryByTestId('not-transmitted-banner')).toBeNull();
  });
});
