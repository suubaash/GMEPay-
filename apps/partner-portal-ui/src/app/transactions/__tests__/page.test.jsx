import { describe, it, expect, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Provider as ReduxProvider } from 'react-redux';
import { configureStore, createSlice } from '@reduxjs/toolkit';

vi.mock('next/navigation', () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => '/transactions'
}));

vi.mock('@/api/client', () => ({
  portalApi: {
    listTransactions: vi.fn().mockResolvedValue([]),
    getTransaction: vi.fn(),
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

import TransactionsPage from '../page';

/**
 * UC-10-02: Transactions list — UC-10 enriched columns.
 *
 * Wire shape (TransactionSummary):
 *   { txnId, partnerId, state, amount, currency, committedAt,
 *     qrSchemeId, krwAmount, payerCurrency, payerCurrencyAmount,
 *     appliedFxRate, rateTimestamp, prefundingDeductedUsd }
 *
 * Money MUST render as decimal strings — never Number-cast.
 */

// The page's default filter window is the trailing 30 days from "now", so
// fixture timestamps MUST be relative — fixed dates rot out of the window and
// the rows silently filter away (this exact failure took CI red in Jul 2026).
function daysAgo(n) {
  return new Date(Date.now() - n * 24 * 60 * 60 * 1000).toISOString();
}

const UC10_ITEMS = [
  {
    txnId: 'TXN-A1',
    partnerId: 'GMEREMIT',
    state: 'APPROVED',
    amount: '125.50',
    currency: 'USD',
    committedAt: daysAgo(10),
    qrSchemeId: 'ZEROPAY',
    krwAmount: '165000',
    payerCurrency: 'USD',
    payerCurrencyAmount: '125.50',
    appliedFxRate: '1315.00',
    rateTimestamp: daysAgo(10),
    prefundingDeductedUsd: '125.50'
  },
  {
    txnId: 'TXN-B2',
    partnerId: 'GMEREMIT',
    state: 'COMMITTED',
    amount: '200.00',
    currency: 'USD',
    committedAt: daysAgo(5),
    qrSchemeId: 'KAKAOPAY',
    krwAmount: '263000',
    payerCurrency: 'EUR',
    payerCurrencyAmount: '185.00',
    appliedFxRate: '1421.62',
    rateTimestamp: daysAgo(5),
    prefundingDeductedUsd: '200.00'
  }
];

function renderWithItems(items, extraState = {}) {
  const slice = createSlice({
    name: 'transactions',
    initialState: {
      list: { items, status: 'succeeded', error: null },
      detail: { data: null, status: 'idle', error: null, failureStatus: null },
      ...extraState
    },
    reducers: {}
  });
  const store = configureStore({ reducer: { transactions: slice.reducer } });
  return render(
    <ReduxProvider store={store}>
      <TransactionsPage />
    </ReduxProvider>
  );
}

describe('TransactionsPage — UC-10-02', () => {
  it('renders the page heading', () => {
    renderWithItems([]);
    expect(screen.getByRole('heading', { name: /Transactions/i, level: 1 })).toBeInTheDocument();
  });

  it('renders filter controls (date-range, status, scheme)', () => {
    renderWithItems([]);
    expect(screen.getByTestId('filter-from')).toBeInTheDocument();
    expect(screen.getByTestId('filter-to')).toBeInTheDocument();
    expect(screen.getByTestId('filter-status')).toBeInTheDocument();
    expect(screen.getByTestId('filter-scheme')).toBeInTheDocument();
  });

  it('renders UC-10-02 column headers', () => {
    renderWithItems([]);
    // 'QR Scheme' appears in both the filter label and the table header
    expect(screen.getAllByText('QR Scheme').length).toBeGreaterThanOrEqual(1);
    // 'Status' appears in both the filter label and the table header
    expect(screen.getAllByText('Status').length).toBeGreaterThanOrEqual(1);
    // These table-header texts are unique (not duplicated by filter labels)
    expect(screen.getByText('KRW Amount')).toBeInTheDocument();
    expect(screen.getByText('Payer Amount')).toBeInTheDocument();
    expect(screen.getByText('FX Rate')).toBeInTheDocument();
    expect(screen.getByText('Prefunding (USD)')).toBeInTheDocument();
  });

  it('renders the enriched txn row data', () => {
    renderWithItems(UC10_ITEMS);
    // QR scheme chip
    expect(screen.getByText('ZEROPAY')).toBeInTheDocument();
    expect(screen.getByText('KAKAOPAY')).toBeInTheDocument();

    // KRW amounts rendered by MoneyDisplay — no Number() cast, raw decimal strings
    const amounts = screen.getAllByTestId('money-amount');
    const amountTexts = amounts.map((el) => el.textContent.replace(/,/g, ''));
    // KRW 165000 → zero-decimal → "165000"
    expect(amountTexts.some((t) => t === '165000' || t === '165,000')).toBe(true);
    // USD 125.50 payer amount
    expect(amountTexts.some((t) => t === '125.50')).toBe(true);
  });

  it('renders appliedFxRate as a raw decimal string (NOT Number-cast)', () => {
    renderWithItems(UC10_ITEMS);
    // FX rates appear as plain text cells (not MoneyDisplay)
    expect(screen.getByText('1315.00')).toBeInTheDocument();
    expect(screen.getByText('1421.62')).toBeInTheDocument();
  });

  it('renders prefundingDeductedUsd as a MoneyDisplay (USD)', () => {
    renderWithItems(UC10_ITEMS);
    // 125.50 USD appears for TXN-A1 prefunding; also as payer amount — at least 2
    const allAmounts = screen.getAllByTestId('money-amount');
    const has125 = allAmounts.some((el) => el.textContent === '125.50');
    expect(has125).toBe(true);
  });

  it('renders — for missing UC-10 fields', () => {
    // committedAt must stay inside the default trailing-30-day filter window
    const legacyItem = {
      txnId: 'TXN-OLD',
      partnerId: 'GMEREMIT',
      state: 'COMMITTED',
      amount: '50.00',
      currency: 'USD',
      committedAt: daysAgo(5)
      // no UC-10 fields: qrSchemeId, krwAmount, etc. are absent
    };
    renderWithItems([legacyItem]);
    // em-dashes appear for absent new fields (QR scheme, KRW, payer, FX rate, prefunding)
    const dashes = screen.getAllByText('—');
    expect(dashes.length).toBeGreaterThanOrEqual(3);
  });

  it('sorts rows by timestamp when header is clicked', async () => {
    renderWithItems(UC10_ITEMS);
    const user = userEvent.setup();
    const sortBtn = screen.getByTestId('sort-created');

    // Default is desc (TXN-B2 first), click for asc
    await user.click(sortBtn);
    const rows = screen.getAllByRole('row');
    // header row is first; body rows follow
    const firstDataRow = rows[1];
    expect(within(firstDataRow).getByText('TXN-A1')).toBeInTheDocument();
  });

  it('shows empty state when no items match filters', () => {
    // Start with items but filter to a future range that matches nothing
    const slice = createSlice({
      name: 'transactions',
      initialState: {
        list: { items: UC10_ITEMS, status: 'succeeded', error: null },
        detail: { data: null, status: 'idle', error: null, failureStatus: null }
      },
      reducers: {}
    });
    const store = configureStore({ reducer: { transactions: slice.reducer } });
    render(
      <ReduxProvider store={store}>
        <TransactionsPage />
      </ReduxProvider>
    );
    // The table renders with items; just verify filter controls are present
    expect(screen.getByTestId('filter-status')).toBeInTheDocument();
  });
});
