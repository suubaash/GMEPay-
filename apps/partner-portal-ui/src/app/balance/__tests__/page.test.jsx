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
 * The slice state matches the BFF BalanceView wire:
 *   { partnerId, currency, balance, lowBalanceThreshold }
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

describe('BalancePage', () => {
  it('renders the loading skeleton when status is loading', () => {
    renderWithBalance({ data: null, status: 'loading', error: null });
    expect(screen.getByTestId('loading-skeleton')).toBeInTheDocument();
  });

  it('renders the balance from a mocked slice', () => {
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

    expect(screen.getByRole('heading', { name: /Balance/i, level: 1 })).toBeInTheDocument();
    const amounts = screen.getAllByTestId('money-amount');
    expect(amounts[0].textContent).toMatch(/10,000\.00|10000\.00/);
    expect(screen.getByText('Healthy')).toBeInTheDocument();
  });

  it('shows the celebratory Lottie when balance > 3x threshold', () => {
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
    expect(screen.getByTestId('balance-celebration')).toBeInTheDocument();
  });

  it('shows a warning when balance is below threshold', () => {
    renderWithBalance({
      data: {
        partnerId: 'GMEREMIT',
        currency: 'USD',
        balance: '500.00',
        lowBalanceThreshold: '1000.00'
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
