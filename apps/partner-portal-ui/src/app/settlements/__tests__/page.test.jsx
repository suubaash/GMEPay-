import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { Provider as ReduxProvider } from 'react-redux';
import { configureStore, createSlice } from '@reduxjs/toolkit';

// EmptyState pulls in Lottie (browser-only) — stub it, as the other page tests do.
vi.mock('lottie-react', () => ({
  default: ({ animationData }) => (
    <div data-testid="mock-lottie" data-has-animation={animationData ? 'yes' : 'no'} />
  )
}));

vi.mock('next/navigation', () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => '/settlements'
}));

vi.mock('@/api/client', () => ({
  portalApi: {
    getSettlements: vi.fn().mockResolvedValue({}),
    getBalance: vi.fn(),
    getOverview: vi.fn(),
    listTransactions: vi.fn(),
    getTransaction: vi.fn(),
    listWebhooks: vi.fn(),
    getProfile: vi.fn(),
    login: vi.fn(),
    logout: vi.fn(),
    refreshToken: vi.fn()
  },
  currentPartnerId: () => 'GMEREMIT'
}));

import SettlementsPage from '../page';

/**
 * Gap T4-5 — the partner settlement statement page.
 *
 * The behaviour under test is not "does the table render". It is: a batch GMEPay+ never transmitted
 * to the scheme must not read, anywhere on this page, as though it had been sent.
 */

const RECONCILED_BUT_NOT_SENT = {
  partnerId: 'GMEREMIT',
  from: '2026-06-01',
  to: '2026-06-30',
  currency: 'KRW',
  entries: [
    {
      batch: {
        batchId: 'ZP0061-20260609-MORNING',
        partnerId: 'GMEREMIT',
        settlementDate: '2026-06-09',
        currency: 'KRW',
        amount: '173500',
        status: 'RECONCILED',
        transmissionState: 'NOT_TRANSMITTED_CHANNEL_UNAVAILABLE',
        transmissionReason:
          'No settlement transmission channel: endpoint is not set; files are never transmitted.',
        transmittedAt: null
      },
      netSettlementAmount: '80000',
      paymentAmount: '84720',
      clawbackAmount: '4720',
      lineCount: 3,
      openLineCount: 1,
      lines: [{ txnRef: 'TXN-1', amount: '84720', currency: 'KRW', matched: true }]
    }
  ],
  netSettlementAmount: '80000',
  paymentAmount: '84720',
  clawbackAmount: '4720',
  lineCount: 3,
  openLineCount: 1,
  transmittedEntryCount: 0,
  transmissionChannel: {
    live: false,
    reachableState: 'NOT_TRANSMITTED_CHANNEL_UNAVAILABLE',
    reason: 'No settlement transmission channel: endpoint is not set.'
  }
};

function renderWith(state) {
  const slice = createSlice({ name: 'settlements', initialState: state, reducers: {} });
  const store = configureStore({ reducer: { settlements: slice.reducer } });
  return render(
    <ReduxProvider store={store}>
      <SettlementsPage />
    </ReduxProvider>
  );
}

describe('Settlement statement page', () => {
  it('renders the settled record with the real batch id and the partner-scoped net', () => {
    renderWith({ data: RECONCILED_BUT_NOT_SENT, status: 'succeeded', error: null });

    expect(screen.getByText('ZP0061-20260609-MORNING')).toBeTruthy();
    expect(screen.getByText('2026-06-09')).toBeTruthy();
    // The partner's own share (80000), not the batch total (173500).
    expect(screen.getByTestId('total-net').textContent).toContain('80,000');
    expect(screen.getByText('3 (1 open)')).toBeTruthy();
  });

  it('shows the reconciliation status separately from whether it was sent', () => {
    renderWith({ data: RECONCILED_BUT_NOT_SENT, status: 'succeeded', error: null });

    expect(screen.getByText('RECONCILED')).toBeTruthy();
    // The "Sent to scheme" cell for that batch says No — never derived from RECONCILED.
    expect(screen.getByTestId('sent-ZP0061-20260609-MORNING').textContent).toBe('No');
    expect(screen.getByTestId('transmitted-count').textContent).toBe('0 of 1');
  });

  it('banners that nothing has been transmitted, with the reason', () => {
    renderWith({ data: RECONCILED_BUT_NOT_SENT, status: 'succeeded', error: null });

    const banner = screen.getByTestId('no-transmission-banner');
    expect(banner.textContent).toContain('Nothing on this page has been sent to the scheme');
    expect(banner.textContent).toContain('No settlement transmission channel');
  });

  it('treats an ABSENT transmission board as not-live, never as available', () => {
    const noBoard = { ...RECONCILED_BUT_NOT_SENT, transmissionChannel: undefined };
    renderWith({ data: noBoard, status: 'succeeded', error: null });

    expect(screen.getByTestId('no-transmission-banner')).toBeTruthy();
  });

  it('treats UNKNOWN and missing transmission states as NOT sent', () => {
    const unknown = {
      ...RECONCILED_BUT_NOT_SENT,
      entries: [
        {
          ...RECONCILED_BUT_NOT_SENT.entries[0],
          batch: {
            ...RECONCILED_BUT_NOT_SENT.entries[0].batch,
            batchId: 'B-UNKNOWN',
            status: 'UNKNOWN',
            transmissionState: 'UNKNOWN',
            // A stray timestamp must not turn an UNKNOWN state into a send.
            transmittedAt: '2026-06-09T10:00:00Z'
          }
        }
      ]
    };
    renderWith({ data: unknown, status: 'succeeded', error: null });

    expect(screen.getByTestId('sent-B-UNKNOWN').textContent).toBe('No');
  });

  it('says Yes only when the state is literally TRANSMITTED', () => {
    const sent = {
      ...RECONCILED_BUT_NOT_SENT,
      transmittedEntryCount: 1,
      transmissionChannel: { live: true, reachableState: 'TRANSMITTED', reason: null },
      entries: [
        {
          ...RECONCILED_BUT_NOT_SENT.entries[0],
          batch: {
            ...RECONCILED_BUT_NOT_SENT.entries[0].batch,
            batchId: 'B-SENT',
            transmissionState: 'TRANSMITTED',
            transmissionReason: null,
            transmittedAt: '2026-06-09T10:00:00Z'
          }
        }
      ]
    };
    renderWith({ data: sent, status: 'succeeded', error: null });

    expect(screen.getByTestId('sent-B-SENT').textContent).toBe('Yes');
    expect(screen.queryByTestId('no-transmission-banner')).toBeNull();
  });

  it('renders an empty period without claiming anything was settled', () => {
    renderWith({
      data: { ...RECONCILED_BUT_NOT_SENT, entries: [], netSettlementAmount: '0', lineCount: 0 },
      status: 'succeeded',
      error: null
    });

    expect(screen.getByText('No settlement batches in this period')).toBeTruthy();
    expect(screen.getByTestId('transmitted-count').textContent).toBe('0 of 0');
  });
});
