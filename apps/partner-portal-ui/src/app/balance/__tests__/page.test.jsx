import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { Provider as ReduxProvider } from 'react-redux';
import { configureStore, createSlice } from '@reduxjs/toolkit';

vi.mock('lottie-react', () => ({
  default: ({ animationData }) => (
    <div data-testid="mock-lottie" data-has-animation={animationData ? 'yes' : 'no'} />
  )
}));

vi.mock('next/navigation', () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => '/balance'
}));

vi.mock('@/api/client', () => ({
  portalApi: {
    getBalance: vi.fn().mockResolvedValue({}),
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

import BalancePage from '../page';

/**
 * UC-10-01: Balance page.
 *
 * Wire shape (BalanceView):
 *   {
 *     partnerCode: string,
 *     currency: string,
 *     balance: string,          // BigDecimal-as-string
 *     threshold: string,        // BigDecimal-as-string (UC-10-01 field name)
 *     pctOfThreshold: string,   // BigDecimal-as-string, scale-2
 *     recentDeductions: Array<{ amountUsd: string, at: string, txnRef: string }>|null
 *   }
 *
 * Legacy BFF shape also carries { partnerId, lowBalanceThreshold } — accepted defensively.
 */
function renderWithBalance(state) {
  const slice = createSlice({
    name: 'balance',
    initialState: state,
    reducers: {}
  });
  const store = configureStore({ reducer: { balance: slice.reducer } });
  return render(
    <ReduxProvider store={store}>
      <BalancePage />
    </ReduxProvider>
  );
}

describe('BalancePage — UC-10-01', () => {
  it('renders the loading skeleton when status is loading', () => {
    renderWithBalance({ data: null, status: 'loading', error: null });
    expect(screen.getByTestId('loading-skeleton')).toBeInTheDocument();
  });

  it('renders the balance from the UC-10-01 BalanceView shape', () => {
    renderWithBalance({
      data: {
        partnerCode: 'GMEREMIT',
        currency: 'USD',
        balance: '10000.00',
        threshold: '1000.00',
        pctOfThreshold: '1000.00',
        recentDeductions: null
      },
      status: 'succeeded',
      error: null
    });

    expect(screen.getByRole('heading', { name: /Balance/i, level: 1 })).toBeInTheDocument();
    const amounts = screen.getAllByTestId('money-amount');
    expect(amounts[0].textContent).toMatch(/10,000\.00|10000\.00/);
    expect(screen.getByText('Healthy')).toBeInTheDocument();
  });

  it('also renders from the legacy BFF shape (lowBalanceThreshold + partnerId)', () => {
    renderWithBalance({
      data: {
        partnerId: 'GMEREMIT',
        currency: 'USD',
        balance: '10000.00',
        lowBalanceThreshold: '1000.00'
      },
      status: 'succeeded',
      error: null
    });
    const amounts = screen.getAllByTestId('money-amount');
    expect(amounts[0].textContent).toMatch(/10,000\.00|10000\.00/);
    expect(screen.getByText('Healthy')).toBeInTheDocument();
  });

  it('shows the celebratory Lottie when balance > 3x threshold', () => {
    renderWithBalance({
      data: {
        partnerCode: 'GMEREMIT',
        currency: 'USD',
        balance: '10000.00',
        threshold: '1000.00',
        pctOfThreshold: '1000.00',
        recentDeductions: null
      },
      status: 'succeeded',
      error: null
    });
    expect(screen.getByTestId('balance-celebration')).toBeInTheDocument();
  });

  it('shows a warning chip when balance is below threshold', () => {
    renderWithBalance({
      data: {
        partnerCode: 'GMEREMIT',
        currency: 'USD',
        balance: '500.00',
        threshold: '1000.00',
        pctOfThreshold: '50.00',
        recentDeductions: null
      },
      status: 'succeeded',
      error: null
    });
    expect(screen.getByText('Below threshold')).toBeInTheDocument();
    expect(screen.queryByTestId('balance-celebration')).toBeNull();
  });

  it('renders pctOfThreshold when present', () => {
    renderWithBalance({
      data: {
        partnerCode: 'GMEREMIT',
        currency: 'USD',
        balance: '1500.00',
        threshold: '1000.00',
        pctOfThreshold: '150.00',
        recentDeductions: null
      },
      status: 'succeeded',
      error: null
    });
    // pct label visible; value rendered as-is (no Number cast)
    expect(screen.getByText('% of threshold')).toBeInTheDocument();
    expect(screen.getByText('150.00%')).toBeInTheDocument();
  });

  it('renders the recent deduction history list when recentDeductions is present', () => {
    renderWithBalance({
      data: {
        partnerCode: 'GMEREMIT',
        currency: 'USD',
        balance: '9875.50',
        threshold: '1000.00',
        pctOfThreshold: '987.55',
        recentDeductions: [
          { amountUsd: '100.00', at: '2026-06-15T01:00:00Z', txnRef: 'TXN-D001' },
          { amountUsd: '24.50', at: '2026-06-14T10:00:00Z', txnRef: 'TXN-D002' }
        ]
      },
      status: 'succeeded',
      error: null
    });

    expect(screen.getByTestId('deduction-history-list')).toBeInTheDocument();
    expect(screen.getByText('Recent deductions')).toBeInTheDocument();

    // Deduction amounts rendered by MoneyDisplay (no Number cast)
    const amounts = screen.getAllByTestId('money-amount');
    const texts = amounts.map((el) => el.textContent);
    expect(texts.some((t) => t === '100.00')).toBe(true);
    expect(texts.some((t) => t === '24.50')).toBe(true);

    // txnRef values visible
    expect(screen.getByText('TXN-D001')).toBeInTheDocument();
    expect(screen.getByText('TXN-D002')).toBeInTheDocument();
  });

  it('renders deduction timestamps in KST timezone label', () => {
    renderWithBalance({
      data: {
        partnerCode: 'GMEREMIT',
        currency: 'USD',
        balance: '9875.50',
        threshold: '1000.00',
        pctOfThreshold: '987.55',
        recentDeductions: [
          { amountUsd: '100.00', at: '2026-06-15T01:00:00Z', txnRef: 'TXN-D001' }
        ]
      },
      status: 'succeeded',
      error: null
    });
    // KST label present in the rendered timestamp (may appear in caption text too)
    const kstElements = screen.getAllByText(/KST/);
    expect(kstElements.length).toBeGreaterThanOrEqual(1);
    // At least one element shows a formatted timestamp containing KST
    expect(kstElements.some((el) => /\d{2}\/\d{2}\/\d{4}.*KST/.test(el.textContent))).toBe(true);
  });

  it('shows "No deductions recorded yet" when recentDeductions is empty array', () => {
    renderWithBalance({
      data: {
        partnerCode: 'GMEREMIT',
        currency: 'USD',
        balance: '5000.00',
        threshold: '1000.00',
        pctOfThreshold: '500.00',
        recentDeductions: []
      },
      status: 'succeeded',
      error: null
    });
    expect(screen.getByText(/No deductions recorded yet/i)).toBeInTheDocument();
  });

  it('does NOT render deduction section when recentDeductions is null', () => {
    renderWithBalance({
      data: {
        partnerCode: 'GMEREMIT',
        currency: 'USD',
        balance: '5000.00',
        threshold: '1000.00',
        pctOfThreshold: '500.00',
        recentDeductions: null
      },
      status: 'succeeded',
      error: null
    });
    expect(screen.queryByTestId('deduction-history-list')).toBeNull();
  });

  it('renders an error alert with retry when fetch fails', () => {
    renderWithBalance({
      data: null,
      status: 'failed',
      error: 'BFF GET /v1/portal/GMEREMIT/balance failed: 500'
    });
    expect(screen.getByRole('alert')).toHaveTextContent(/failed: 500/);
    expect(screen.getByTestId('error-retry')).toBeInTheDocument();
  });
});
