import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Provider as ReduxProvider } from 'react-redux';
import { configureStore } from '@reduxjs/toolkit';

import statementReducer from '@/store/statementSlice';
import { SnackbarProvider } from '@/components/SnackbarProvider';

vi.mock('lottie-react', () => ({
  default: ({ animationData }) => (
    <div data-testid="mock-lottie" data-has-animation={animationData ? 'yes' : 'no'} />
  )
}));

vi.mock('next/navigation', () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn(), prefetch: vi.fn() }),
  usePathname: () => '/statement'
}));

const downloadStatementMock = vi.fn();
const downloadStatementTopLevelMock = vi.fn();
vi.mock('@/api/client', () => ({
  portalApi: {
    downloadStatement: (...args) => downloadStatementMock(...args),
    listApiKeys: vi.fn(),
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
  currentPartnerId: () => 'GMEREMIT',
  listApiKeys: vi.fn(),
  downloadStatement: (...args) => downloadStatementTopLevelMock(...args)
}));

import StatementPage from '../page';

function renderStatement() {
  const store = configureStore({ reducer: { statement: statementReducer } });
  return render(
    <ReduxProvider store={store}>
      <SnackbarProvider>
        <StatementPage />
      </SnackbarProvider>
    </ReduxProvider>
  );
}

describe('StatementPage', () => {
  beforeEach(() => {
    downloadStatementMock.mockReset();
    downloadStatementTopLevelMock.mockReset();
    // jsdom doesn't implement createObjectURL/revokeObjectURL; stub them so
    // the download flow doesn't blow up.
    if (typeof URL.createObjectURL === 'undefined') {
      URL.createObjectURL = vi.fn(() => 'blob:fake');
    } else {
      vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:fake');
    }
    if (typeof URL.revokeObjectURL === 'undefined') {
      URL.revokeObjectURL = vi.fn();
    } else {
      vi.spyOn(URL, 'revokeObjectURL').mockReturnValue(undefined);
    }
  });

  it('renders the date range picker and the download button', () => {
    renderStatement();
    expect(screen.getByTestId('date-from')).toBeInTheDocument();
    expect(screen.getByTestId('date-to')).toBeInTheDocument();
    expect(screen.getByTestId('download-button')).toBeInTheDocument();
    expect(screen.getByTestId('preview-button')).toBeInTheDocument();
  });

  it('disables the download button when the From date is cleared', async () => {
    renderStatement();
    const user = userEvent.setup();
    const from = screen.getByTestId('date-from');
    await user.clear(from);
    // The "valid range required" caption appears.
    expect(screen.getByTestId('range-required')).toBeInTheDocument();
    expect(screen.getByTestId('download-button')).toBeDisabled();
  });

  it('calls portalApi.downloadStatement with the selected From/To when clicked', async () => {
    const csv = 'txnId,amount,currency\nT-1,10.50,USD\nT-2,20.00,USD\n';
    downloadStatementMock.mockResolvedValue(new Blob([csv], { type: 'text/csv' }));
    renderStatement();
    const user = userEvent.setup();

    // Date inputs are seeded with thirtyDaysAgo..today; just click Download.
    await user.click(screen.getByTestId('download-button'));

    await waitFor(() => {
      expect(downloadStatementMock).toHaveBeenCalledTimes(1);
    });
    const [partnerId, from, to] = downloadStatementMock.mock.calls[0];
    expect(partnerId).toBe('GMEREMIT');
    // The page seeds the form with valid YYYY-MM-DD dates.
    expect(from).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect(to).toMatch(/^\d{4}-\d{2}-\d{2}$/);

    // Success snackbar fires.
    await waitFor(() => {
      expect(screen.getByTestId('snackbar-toast')).toHaveTextContent(/Statement downloaded/i);
    });
  });

  it('preview button parses the CSV and renders the first rows', async () => {
    const csv = 'txnId,amount,currency\nT-1,10.50,USD\nT-2,20.00,USD\n';
    downloadStatementTopLevelMock.mockResolvedValue(new Blob([csv], { type: 'text/csv' }));
    renderStatement();
    const user = userEvent.setup();
    await user.click(screen.getByTestId('preview-button'));

    await waitFor(() => {
      expect(screen.getByTestId('preview-table')).toBeInTheDocument();
    });
    expect(screen.getByText('txnId')).toBeInTheDocument();
    expect(screen.getByText('T-1')).toBeInTheDocument();
    expect(screen.getByText('T-2')).toBeInTheDocument();
  });

  it('surfaces a snackbar error if download throws', async () => {
    downloadStatementMock.mockRejectedValue(new Error('502 Bad Gateway'));
    renderStatement();
    const user = userEvent.setup();

    await user.click(screen.getByTestId('download-button'));

    await waitFor(() => {
      expect(screen.getByTestId('snackbar-toast')).toHaveTextContent(/502 Bad Gateway/i);
    });
  });
});
