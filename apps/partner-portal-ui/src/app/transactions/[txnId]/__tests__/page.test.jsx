import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { Provider as ReduxProvider } from 'react-redux';
import { configureStore, createSlice } from '@reduxjs/toolkit';

vi.mock('next/navigation', () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  useParams: () => ({ txnId: 'TXN-UC10-001' }),
  usePathname: () => '/transactions/TXN-UC10-001'
}));

vi.mock('next/link', () => ({
  default: ({ href, children, ...props }) => (
    <a href={href} {...props}>{children}</a>
  )
}));

vi.mock('@/api/client', () => ({
  portalApi: {
    getTransaction: vi.fn().mockResolvedValue({}),
    listTransactions: vi.fn(),
    getBalance: vi.fn(),
    getOverview: vi.fn(),
    listWebhooks: vi.fn(),
    getProfile: vi.fn(),
    login: vi.fn(),
    logout: vi.fn(),
    refreshToken: vi.fn()
  },
  currentPartnerId: () => 'GMEREMIT'
}));

vi.mock('@/components/Breadcrumbs', () => ({
  default: ({ items }) => (
    <nav aria-label="breadcrumb">
      {items.map((item, i) => (
        <span key={i}>{item.label}</span>
      ))}
    </nav>
  )
}));

vi.mock('@/components/SnackbarProvider', () => ({
  useSnackbar: () => ({ showError: vi.fn(), showSuccess: vi.fn() })
}));

import TransactionDetailPage from '../page';

/**
 * UC-10-03: Transaction detail — enriched merchant info, FX rate + timestamp,
 * prefunding deducted, and status history timeline.
 *
 * Money MUST NOT be Number-cast — rendered as decimal strings via MoneyDisplay.
 * Internal revenue fields (FX margin, GME revenue) are stripped by the BFF and
 * must not appear.
 */

const UC10_DETAIL = {
  summary: {
    txnId: 'TXN-UC10-001',
    partnerId: 'GMEREMIT',
    state: 'APPROVED',
    amount: '125.50',
    currency: 'USD',
    committedAt: '2026-06-01T10:00:00Z',
    qrSchemeId: 'ZEROPAY',
    krwAmount: '165000',
    payerCurrency: 'USD',
    payerCurrencyAmount: '125.50',
    appliedFxRate: '1315.00',
    rateTimestamp: '2026-06-01T09:59:55Z',
    prefundingDeductedUsd: '125.50'
  },
  schemeTxnRef: 'SCH-TXN-001',
  schemeApprovalCode: 'AP-001',
  prefundDeductedUsd: '125.50',
  approvedAt: '2026-06-01T10:00:01Z',
  bookedSettlementAmount: '125.50',
  settlementRoundingMode: 'HALF_UP',
  roundingResidual: '0.00',
  // UC-10-03 additive
  merchantId: 'MERCH-0042',
  merchantName: 'Seoul Coffee Co.',
  statusHistory: [
    { status: 'PENDING', at: '2026-06-01T09:59:50Z' },
    { status: 'APPROVED', at: '2026-06-01T10:00:01Z' }
  ]
};

function renderWithDetail(detailState) {
  const slice = createSlice({
    name: 'transactions',
    initialState: {
      list: { items: [], status: 'idle', error: null },
      detail: detailState
    },
    reducers: {}
  });
  const store = configureStore({ reducer: { transactions: slice.reducer } });
  return render(
    <ReduxProvider store={store}>
      <TransactionDetailPage />
    </ReduxProvider>
  );
}

describe('TransactionDetailPage — UC-10-03', () => {
  it('renders loading skeleton while fetching', () => {
    renderWithDetail({ data: null, status: 'loading', error: null, failureStatus: null });
    expect(screen.getByTestId('loading-skeleton')).toBeInTheDocument();
  });

  it('renders merchant ID and name', () => {
    renderWithDetail({ data: UC10_DETAIL, status: 'succeeded', error: null, failureStatus: null });
    expect(screen.getByText('MERCH-0042')).toBeInTheDocument();
    expect(screen.getByText('Seoul Coffee Co.')).toBeInTheDocument();
  });

  it('renders QR scheme chip', () => {
    renderWithDetail({ data: UC10_DETAIL, status: 'succeeded', error: null, failureStatus: null });
    expect(screen.getByText('ZEROPAY')).toBeInTheDocument();
  });

  it('renders KRW amount as MoneyDisplay (no Number-cast)', () => {
    renderWithDetail({ data: UC10_DETAIL, status: 'succeeded', error: null, failureStatus: null });
    const amounts = screen.getAllByTestId('money-amount');
    const texts = amounts.map((el) => el.textContent);
    // KRW 165000 is zero-decimal; rendered without decimals
    expect(texts.some((t) => t === '165,000' || t === '165000')).toBe(true);
  });

  it('renders payer currency amount', () => {
    renderWithDetail({ data: UC10_DETAIL, status: 'succeeded', error: null, failureStatus: null });
    // USD 125.50 appears as payer amount
    const amounts = screen.getAllByTestId('money-amount');
    expect(amounts.some((el) => el.textContent === '125.50')).toBe(true);
  });

  it('renders applied FX rate as plain decimal string', () => {
    renderWithDetail({ data: UC10_DETAIL, status: 'succeeded', error: null, failureStatus: null });
    expect(screen.getByText('1315.00')).toBeInTheDocument();
  });

  it('renders rate timestamp label', () => {
    renderWithDetail({ data: UC10_DETAIL, status: 'succeeded', error: null, failureStatus: null });
    expect(screen.getByText(/Rate locked:/i)).toBeInTheDocument();
  });

  it('renders prefunding deducted USD', () => {
    renderWithDetail({ data: UC10_DETAIL, status: 'succeeded', error: null, failureStatus: null });
    // 125.50 USD
    const amounts = screen.getAllByTestId('money-amount');
    expect(amounts.some((el) => el.textContent === '125.50')).toBe(true);
    const currencies = screen.getAllByTestId('money-currency');
    expect(currencies.some((el) => el.textContent === 'USD')).toBe(true);
  });

  it('renders the status history stepper with all entries', () => {
    renderWithDetail({ data: UC10_DETAIL, status: 'succeeded', error: null, failureStatus: null });
    expect(screen.getByTestId('status-history-stepper')).toBeInTheDocument();
    expect(screen.getByText('Status history')).toBeInTheDocument();
    // Both PENDING and APPROVED chips rendered
    const chips = screen.getAllByRole('generic').filter(
      (el) => el.className && typeof el.className === 'string' && el.className.includes('MuiChip')
    );
    // At least the two status chips are present
    expect(screen.getAllByText('PENDING').length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText('APPROVED').length).toBeGreaterThanOrEqual(1);
  });

  it('does NOT render status history section when statusHistory is null', () => {
    const detailNoHistory = { ...UC10_DETAIL, statusHistory: null };
    renderWithDetail({ data: detailNoHistory, status: 'succeeded', error: null, failureStatus: null });
    expect(screen.queryByTestId('status-history-stepper')).toBeNull();
  });

  it('does NOT render status history section when statusHistory is empty', () => {
    const detailEmptyHistory = { ...UC10_DETAIL, statusHistory: [] };
    renderWithDetail({ data: detailEmptyHistory, status: 'succeeded', error: null, failureStatus: null });
    expect(screen.queryByTestId('status-history-stepper')).toBeNull();
  });

  it('renders — for absent merchant fields (backward compat)', () => {
    const detailNoMerchant = {
      ...UC10_DETAIL,
      merchantId: null,
      merchantName: null,
      summary: { ...UC10_DETAIL.summary, qrSchemeId: null }
    };
    renderWithDetail({ data: detailNoMerchant, status: 'succeeded', error: null, failureStatus: null });
    // At least one em-dash rendered for missing fields
    const dashes = screen.getAllByText('—');
    expect(dashes.length).toBeGreaterThanOrEqual(2);
  });

  it('renders an error alert for non-404 failures', () => {
    renderWithDetail({
      data: null,
      status: 'failed',
      error: 'Service unavailable',
      failureStatus: 503
    });
    expect(screen.getByRole('alert')).toHaveTextContent(/Service unavailable/i);
  });
});
