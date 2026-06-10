import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { Provider as ReduxProvider } from 'react-redux';
import { configureStore, createSlice } from '@reduxjs/toolkit';

// Mock next/dynamic-loaded lottie before importing the page.
vi.mock('lottie-react', () => ({
  default: ({ animationData }: { animationData: unknown }) => (
    <div data-testid="mock-lottie" data-has-animation={animationData ? 'yes' : 'no'} />
  )
}));

// next/navigation isn't used by this page but BalancePage may indirectly
// import client modules that touch it — stub defensively.
vi.mock('next/navigation', () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => '/balance'
}));

// Stub the API client so the slice never actually fetches.
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

interface BalanceFixture {
  data: {
    partnerId: string;
    balance: { amount: string; currency: string };
    lowBalanceThreshold: { amount: string; currency: string };
    lastUpdatedAt: string;
    lastSettlementAt?: string | null;
  } | null;
  status: 'idle' | 'loading' | 'succeeded' | 'failed';
  error: string | null;
}

function renderWithBalance(state: BalanceFixture) {
  // Build a minimal store with a `balance` slice that returns the fixture.
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

describe('BalancePage', () => {
  it('renders the loading skeleton when status is loading', () => {
    renderWithBalance({ data: null, status: 'loading', error: null });
    expect(screen.getByTestId('loading-skeleton')).toBeInTheDocument();
  });

  it('renders the balance from a mocked slice', () => {
    renderWithBalance({
      data: {
        partnerId: 'GMEREMIT',
        balance: { amount: '10000.00', currency: 'USD' },
        lowBalanceThreshold: { amount: '1000.00', currency: 'USD' },
        lastUpdatedAt: '2026-06-09T12:00:00Z',
        lastSettlementAt: null
      },
      status: 'succeeded',
      error: null
    });

    // Page title + balance + threshold + Healthy chip.
    expect(screen.getByRole('heading', { name: /Balance/i, level: 1 })).toBeInTheDocument();
    // Both balance and threshold render via MoneyDisplay so we check tokens.
    const amounts = screen.getAllByTestId('money-amount');
    expect(amounts[0].textContent).toMatch(/10,000\.00|10000\.00/);
    expect(screen.getByText('Healthy')).toBeInTheDocument();
  });

  it('shows the celebratory Lottie when balance > 3x threshold', () => {
    renderWithBalance({
      data: {
        partnerId: 'GMEREMIT',
        balance: { amount: '10000.00', currency: 'USD' },
        lowBalanceThreshold: { amount: '1000.00', currency: 'USD' },
        lastUpdatedAt: '2026-06-09T12:00:00Z',
        lastSettlementAt: null
      },
      status: 'succeeded',
      error: null
    });
    expect(screen.getByTestId('balance-celebration')).toBeInTheDocument();
  });

  it('shows a warning when balance is below threshold', () => {
    renderWithBalance({
      data: {
        partnerId: 'GMEREMIT',
        balance: { amount: '500.00', currency: 'USD' },
        lowBalanceThreshold: { amount: '1000.00', currency: 'USD' },
        lastUpdatedAt: '2026-06-09T12:00:00Z',
        lastSettlementAt: null
      },
      status: 'succeeded',
      error: null
    });
    expect(screen.getByText('Below threshold')).toBeInTheDocument();
    expect(screen.queryByTestId('balance-celebration')).toBeNull();
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
