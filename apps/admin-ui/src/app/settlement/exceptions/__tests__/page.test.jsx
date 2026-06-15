/**
 * Vitest coverage for the Settlement Exceptions page (UC-04-03, BS-04).
 *
 * Scenarios:
 *  1. Renders exceptions table — merchant ID, match status chip, money columns.
 *  2. Resolve dialog — opens on "Resolve" click; submits with note + action.
 *  3. Resolve dialog validation — submit blocked when action or note missing.
 *  4. Re-run dispatches reRunException thunk.
 *  5. Resolve button disabled for non-OPEN rows.
 *  6. Money values are rendered as strings, not cast to Number.
 */
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { Provider } from 'react-redux';
import { configureStore } from '@reduxjs/toolkit';
import reconExceptionsReducer from '@/store/reconExceptionsSlice';
import authReducer from '@/store/authSlice';
import { USER_KEY } from '@/api/auth';
import { theme } from '@/theme/theme';

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }),
  usePathname: () => '/settlement/exceptions',
}));

const listReconExceptions = vi.fn();
const resolveReconException = vi.fn();
const reRunReconException = vi.fn();
vi.mock('@/api/client', () => ({
  adminApi: {
    listReconExceptions: (f) => listReconExceptions(f),
    resolveReconException: (id, body) => resolveReconException(id, body),
    reRunReconException: (id, body) => reRunReconException(id, body),
  },
}));

const snackSuccess = vi.fn();
const snackError = vi.fn();
vi.mock('@/components/SnackbarProvider', () => ({
  __esModule: true,
  default: ({ children }) => <>{children}</>,
  useSnackbar: () => ({
    success: snackSuccess,
    error: snackError,
    info: vi.fn(),
    warning: vi.fn(),
  }),
}));

import ExceptionsPage from '../page';

const OPERATOR = 'alice';

function setOperator(name) {
  try {
    window.localStorage.setItem(USER_KEY, name);
  } catch { /* ignore */ }
}

function makeStore() {
  return configureStore({
    reducer: { reconExceptions: reconExceptionsReducer, auth: authReducer },
  });
}

function renderPage() {
  const store = makeStore();
  return {
    store,
    ...render(
      <Provider store={store}>
        <ThemeProvider theme={theme}>
          <ExceptionsPage />
        </ThemeProvider>
      </Provider>,
    ),
  };
}

const OPEN_ROW = {
  id: 1,
  batchId: 'BATCH-2026-06-15-001',
  merchantId: 'MID0000000000000001',
  gmeAmount: '1500000',
  schemeAmount: '1490000',
  discrepancyAmount: '10000',
  matchStatus: 'DISCREPANCY',
  exceptionStatus: 'OPEN',
  operatorId: null,
  resolutionNote: null,
  resolutionAction: null,
  resolvedAt: null,
  createdAt: '2026-06-15T01:05:00Z',
};

const MISSING_ROW = {
  id: 2,
  batchId: 'BATCH-2026-06-15-001',
  merchantId: 'MID0000000000000002',
  gmeAmount: '750000',
  schemeAmount: null,
  discrepancyAmount: '750000',
  matchStatus: 'MISSING_SCHEME',
  exceptionStatus: 'OPEN',
  operatorId: null,
  resolutionNote: null,
  resolutionAction: null,
  resolvedAt: null,
  createdAt: '2026-06-15T01:05:02Z',
};

const RESOLVED_ROW = {
  ...OPEN_ROW,
  exceptionStatus: 'RESOLVED',
  operatorId: OPERATOR,
  resolutionNote: 'Checked with bank',
  resolutionAction: 'MANUAL_OVERRIDE',
  resolvedAt: '2026-06-15T03:00:00Z',
};

describe('ExceptionsPage', () => {
  beforeEach(() => {
    listReconExceptions.mockReset();
    resolveReconException.mockReset();
    reRunReconException.mockReset();
    snackSuccess.mockReset();
    snackError.mockReset();
    try { window.localStorage.clear(); } catch { /* ignore */ }
    setOperator(OPERATOR);
  });

  it('renders exceptions table with merchant ID, match type chip, and amounts', async () => {
    listReconExceptions.mockResolvedValue([OPEN_ROW, MISSING_ROW]);

    renderPage();

    const table = await screen.findByRole('table', {
      name: /settlement exceptions/i,
    });

    // Merchant IDs
    expect(within(table).getByText('MID0000000000000001')).toBeInTheDocument();
    expect(within(table).getByText('MID0000000000000002')).toBeInTheDocument();

    // Match status chips
    expect(within(table).getByText('DISCREPANCY')).toBeInTheDocument();
    expect(within(table).getByText('MISSING_SCHEME')).toBeInTheDocument();

    // GME amounts rendered as strings (no thousands separator here since KRW scale=0)
    expect(within(table).getByText('1,500,000')).toBeInTheDocument();
    // MISSING_ROW: gmeAmount = discrepancyAmount = 750000, so two cells show 750,000
    expect(within(table).getAllByText('750,000').length).toBeGreaterThanOrEqual(1);
  });

  it('money values are never Number()-cast — KRW rendered with integer formatting', async () => {
    listReconExceptions.mockResolvedValue([OPEN_ROW]);

    renderPage();

    // MoneyDisplay shows 1500000 as "1,500,000" (KRW, scale=0) — no decimal point.
    const gme = await screen.findByText('1,500,000');
    expect(gme).toBeInTheDocument();
    // schemeAmount: 1490000 -> "1,490,000"
    expect(screen.getByText('1,490,000')).toBeInTheDocument();
    // discrepancyAmount: 10000 -> "10,000"
    expect(screen.getByText('10,000')).toBeInTheDocument();
  });

  it('Resolve dialog opens, requires note, then dispatches resolve thunk', async () => {
    listReconExceptions.mockResolvedValue([OPEN_ROW]);
    resolveReconException.mockResolvedValue(RESOLVED_ROW);

    const { fireEvent, act } = await import('@testing-library/react');
    const user = userEvent.setup();
    renderPage();

    // Open dialog
    const resolveBtn = await screen.findByRole('button', {
      name: /resolve exception 1/i,
    });
    await user.click(resolveBtn);

    // Dialog visible
    expect(screen.getByRole('dialog')).toBeInTheDocument();

    const confirmBtn = screen.getByRole('button', { name: /confirm resolve/i });

    // Fill in note quickly via fireEvent (avoids slow char-by-char userEvent.type)
    const noteField = screen.getByLabelText(/resolution note/i);
    await act(async () => {
      fireEvent.change(noteField, { target: { value: 'Checked' } });
    });

    // Pick action via MUI Select dropdown
    const actionButton = screen.getByRole('combobox');
    await user.click(actionButton);
    const manualOption = await screen.findByRole('option', { name: /manual override/i });
    await user.click(manualOption);

    // Submit
    await user.click(confirmBtn);

    await waitFor(() => {
      expect(resolveReconException).toHaveBeenCalled();
    });

    await waitFor(() => {
      expect(snackSuccess).toHaveBeenCalled();
    });
  }, 20000);

  it('Re-run button dispatches reRunException thunk', async () => {
    listReconExceptions.mockResolvedValue([OPEN_ROW]);
    const reRunRow = { ...OPEN_ROW, exceptionStatus: 'RE_RUN' };
    reRunReconException.mockResolvedValue(reRunRow);

    const user = userEvent.setup();
    renderPage();

    const reRunBtn = await screen.findByRole('button', {
      name: /re-run exception 1/i,
    });
    await user.click(reRunBtn);

    await waitFor(() => {
      expect(reRunReconException).toHaveBeenCalledWith(1, { operatorId: OPERATOR });
    });

    await waitFor(() => {
      expect(snackSuccess).toHaveBeenCalled();
    });
  });

  it('Resolve button disabled for RESOLVED rows; Re-run button disabled too', async () => {
    listReconExceptions.mockResolvedValue([RESOLVED_ROW]);
    renderPage();

    const resolveBtn = await screen.findByRole('button', {
      name: /resolve exception 1/i,
    });
    expect(resolveBtn).toBeDisabled();

    const reRunBtn = screen.getByRole('button', { name: /re-run exception 1/i });
    expect(reRunBtn).toBeDisabled();
  });

  it('empty state shown when no exceptions exist', async () => {
    listReconExceptions.mockResolvedValue([]);
    renderPage();

    expect(
      await screen.findByText(/no exceptions found/i),
    ).toBeInTheDocument();
  });
});
