/**
 * Vitest tests for the Settlement batch DETAIL page (/settlement/[batchId]).
 *
 * GAP T4-5: the detail card used to show a single "Status" field holding `batch.status`,
 * with no transmission fact anywhere on the page. Pinned here: the two axes are separate
 * fields, the banner names what has not happened, a `transmittedAt` on a batch that is not
 * TRANSMITTED is never displayed as a send time, and success styling only follows a genuine
 * TRANSMITTED.
 */
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import { Provider } from 'react-redux';
import { configureStore } from '@reduxjs/toolkit';
import { theme } from '@/theme/theme';

const mockGetSettlement = vi.fn();
const mockGetChannel = vi.fn();

vi.mock('@/api/client', () => ({
  adminApi: {
    listSettlements: vi.fn(),
    listSettlementBatches: vi.fn(),
    getSettlement: (...a) => mockGetSettlement(...a),
    getSettlementTransmissionChannel: (...a) => mockGetChannel(...a),
  },
}));

vi.mock('next/navigation', () => ({
  useParams: () => ({ batchId: 'SB-2026-07-20-REQ-001' }),
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }),
}));

import settlementReducer from '@/store/settlementSlice';
import SettlementBatchDetailPage from '../[batchId]/page';

const DARK_CHANNEL = {
  live: false,
  reachableState: 'NOT_TRANSMITTED_CHANNEL_UNAVAILABLE',
  reason: 'no SFTP endpoint or credential is configured for ZEROPAY.',
};

const DETAIL = {
  batch: {
    batchId: 'SB-2026-07-20-REQ-001',
    partnerId: 'ZEROPAY',
    settlementDate: '2026-07-20',
    currency: 'KRW',
    amount: '61000',
    status: 'RECONCILED',
    transmissionState: 'NOT_TRANSMITTED_CHANNEL_UNAVAILABLE',
    transmissionReason: 'reclassified by V013: no transmission channel is configured',
    transmittedAt: null,
  },
  lines: [
    { txnRef: 'TXN-9001', amount: '50000', currency: 'KRW', matched: true },
    { txnRef: 'TXN-9002', amount: '11000', currency: 'KRW', matched: false },
  ],
  matchedCount: 1,
  openCount: 1,
};

function renderPage() {
  const store = configureStore({ reducer: { settlement: settlementReducer } });
  return render(
    <Provider store={store}>
      <ThemeProvider theme={theme}>
        <SettlementBatchDetailPage />
      </ThemeProvider>
    </Provider>,
  );
}

describe('SettlementBatchDetailPage — transmission honesty (T4-5)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockGetSettlement.mockResolvedValue(DETAIL);
    mockGetChannel.mockResolvedValue(DARK_CHANNEL);
  });

  it('shows the lifecycle status and the transmission state as two separate fields', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByText('Lifecycle status')).toBeInTheDocument());
    expect(screen.getByText('Sent to scheme')).toBeInTheDocument();
    expect(screen.getByLabelText('Lifecycle status RECONCILED')).toBeInTheDocument();
    expect(
      screen.getByLabelText('Transmission state NOT_TRANSMITTED_CHANNEL_UNAVAILABLE'),
    ).toBeInTheDocument();
    // The old bare "Status" field is gone.
    expect(screen.queryByText('Status')).toBeNull();
  });

  it('banners that this file was not transmitted and says the lifecycle status is not a send', async () => {
    renderPage();
    const banner = await screen.findByTestId('not-transmitted-banner');
    expect(banner).toHaveTextContent(
      'This settlement file has not been transmitted to the scheme',
    );
    expect(banner).toHaveTextContent(/lifecycle status is RECONCILED/);
    expect(banner).toHaveTextContent(/not whether GMEPay\+ sent anything/);
    // Reason comes from the backend row.
    expect(banner).toHaveTextContent(/reclassified by V013/);
    expect(
      within(banner).getByTestId('settlement-transmission-board'),
    ).toBeInTheDocument();
  });

  it('never shows a send time for a batch that was not transmitted', async () => {
    // A stray timestamp on a not-transmitted row is not evidence of anything.
    mockGetSettlement.mockResolvedValue({
      ...DETAIL,
      batch: { ...DETAIL.batch, transmittedAt: '2026-07-20T09:00:00Z' },
    });
    renderPage();
    await waitFor(() => expect(screen.getByTestId('transmitted-at')).toBeInTheDocument());
    expect(screen.getByTestId('transmitted-at')).toHaveTextContent('never transmitted');
    expect(screen.queryByText('2026-07-20T09:00:00Z')).toBeNull();
  });

  it('renders an absent transmissionState as UNKNOWN and still banners', async () => {
    mockGetSettlement.mockResolvedValue({
      ...DETAIL,
      batch: { ...DETAIL.batch, transmissionState: undefined, transmissionReason: undefined },
    });
    renderPage();
    await waitFor(() =>
      expect(screen.getByLabelText('Transmission state UNKNOWN')).toBeInTheDocument());
    expect(await screen.findByTestId('not-transmitted-banner')).toBeInTheDocument();
  });

  it('drops the banner and shows the send time for a genuinely transmitted batch', async () => {
    mockGetSettlement.mockResolvedValue({
      ...DETAIL,
      batch: {
        ...DETAIL.batch,
        transmissionState: 'TRANSMITTED',
        transmissionReason: null,
        transmittedAt: '2026-07-20T09:00:00Z',
      },
    });
    mockGetChannel.mockResolvedValue({ live: true, reachableState: 'TRANSMITTED', reason: null });
    renderPage();
    await waitFor(() =>
      expect(screen.getByLabelText('Transmission state TRANSMITTED')).toBeInTheDocument());
    expect(screen.queryByTestId('not-transmitted-banner')).toBeNull();
    expect(screen.getByTestId('transmitted-at')).toHaveTextContent('2026-07-20T09:00:00Z');
  });

  it('surfaces the matched / open line counts the BFF already sends', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByText('Matched lines')).toBeInTheDocument());
    expect(screen.getByText('Open lines')).toBeInTheDocument();
    expect(screen.getByText('MATCHED')).toBeInTheDocument();
    expect(screen.getByText('UNMATCHED')).toBeInTheDocument();
  });
});
