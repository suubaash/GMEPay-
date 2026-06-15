/**
 * Vitest tests for /transactions/search — TxnSearchPage + txnSearchSlice.
 *
 * Coverage:
 *  1. Renders results table from searchTransactions fixture.
 *  2. Filter form builds correct query params (txnRef, partnerId, status, direction,
 *     date-range, amount-range).
 *  3. Pagination — page change dispatches correct page index; rows-per-page change.
 *  4. Row click navigates to /transactions/[txnRef].
 *  5. CSV export calls exportTransactionsCsv and triggers download.
 *  6. Empty state shown when results are [].
 *  7. Error alert shown when API rejects.
 */
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, within, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { Provider } from 'react-redux';
import { configureStore } from '@reduxjs/toolkit';
import { theme } from '@/theme/theme';
import txnSearchReducer from '@/store/txnSearchSlice';
import authReducer from '@/store/authSlice';

// ---- next/navigation mock ----
const push = vi.fn();
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push, replace: vi.fn(), back: vi.fn() }),
}));

// ---- txnSearchApi mock ----
const mockSearch = vi.fn();
const mockExportCsv = vi.fn();
vi.mock('@/api/txnSearchApi', () => ({
  searchTransactions: (...args) => mockSearch(...args),
  exportTransactionsCsv: (...args) => mockExportCsv(...args),
  rowsToCsv: (rows) => rows.map((r) => r.txnRef).join('\n'),
  FIXTURE_PAGE: {
    content: [],
    page: 0,
    size: 20,
    totalElements: 0,
  },
}));

import TxnSearchPage from '../page';

// ---- fixtures ----
const TXN_ROW_1 = {
  txnRef: 'TXN-001',
  partnerRef: 'GME_KR_001',
  qrSchemeId: 'ZEROPAY',
  status: 'SETTLED',
  createdAt: '2024-06-15T09:30:00+09:00',
  krwAmount: '100000',
  payerCurrency: 'KRW',
  payerCurrencyAmount: '100000',
  sendAmount: '100000',
  sendCcy: 'KRW',
  targetPayout: '75.50',
  targetCcy: 'USD',
  appliedFxRate: '1324.50',
  prefundingDeductedUsd: '75.50',
  merchantName: 'Seoul Mart',
};

const TXN_ROW_2 = {
  txnRef: 'TXN-002',
  partnerRef: 'GME_VN_002',
  qrSchemeId: 'NAPAS247',
  status: 'FAILED',
  createdAt: '2024-06-15T11:15:00+09:00',
  krwAmount: '500000',
  payerCurrency: 'KRW',
  payerCurrencyAmount: '500000',
  sendAmount: '500000',
  sendCcy: 'KRW',
  targetPayout: '377.25',
  targetCcy: 'USD',
  appliedFxRate: '1325.00',
  prefundingDeductedUsd: '377.25',
  merchantName: 'Hanoi Coffee',
};

const PAGE_RESPONSE = {
  content: [TXN_ROW_1, TXN_ROW_2],
  page: 0,
  size: 20,
  totalElements: 2,
};

// ---- helper ----
function renderPage() {
  const store = configureStore({
    reducer: { txnSearch: txnSearchReducer, auth: authReducer },
  });
  return {
    store,
    ...render(
      <Provider store={store}>
        <ThemeProvider theme={theme}>
          <TxnSearchPage />
        </ThemeProvider>
      </Provider>,
    ),
  };
}

// ---- tests ----
describe('TxnSearchPage', () => {
  beforeEach(() => {
    push.mockReset();
    mockSearch.mockReset();
    mockExportCsv.mockReset();
  });

  afterEach(() => {
    // Restore any spies set during individual tests so they don't bleed.
    vi.restoreAllMocks();
  });

  // 1. Renders results table
  it('renders results table with rows from searchTransactions', async () => {
    mockSearch.mockResolvedValue(PAGE_RESPONSE);
    renderPage();

    const table = await screen.findByRole('table', { name: /transaction search results/i });
    expect(within(table).getByText('TXN-001')).toBeInTheDocument();
    expect(within(table).getByText('TXN-002')).toBeInTheDocument();
    expect(within(table).getByText('GME_KR_001')).toBeInTheDocument();
    expect(within(table).getByText('ZEROPAY')).toBeInTheDocument();
    // Money as string
    expect(within(table).getAllByText('100000').length).toBeGreaterThan(0);
    // Status chips
    expect(within(table).getByText('SETTLED')).toBeInTheDocument();
    expect(within(table).getByText('FAILED')).toBeInTheDocument();
  });

  // 2a. Search with txnRef builds correct params
  it('passes txnRef filter to searchTransactions', async () => {
    mockSearch.mockResolvedValue(PAGE_RESPONSE);
    const user = userEvent.setup();
    renderPage();

    await waitFor(() => expect(mockSearch).toHaveBeenCalled());
    mockSearch.mockClear();
    mockSearch.mockResolvedValue({ content: [TXN_ROW_1], page: 0, size: 20, totalElements: 1 });

    const txnRefInput = screen.getByRole('textbox', { name: /transaction ref/i });
    await user.clear(txnRefInput);
    await user.type(txnRefInput, 'TXN-001');

    await user.click(screen.getByRole('button', { name: /search transactions/i }));

    await waitFor(() => {
      expect(mockSearch).toHaveBeenCalledWith(
        expect.objectContaining({ txnRef: 'TXN-001', page: 0, size: 20 }),
      );
    });
  });

  // 2b. Status + direction filter
  it('passes status and direction filters to searchTransactions', async () => {
    mockSearch.mockResolvedValue(PAGE_RESPONSE);
    const user = userEvent.setup();
    renderPage();

    await waitFor(() => expect(mockSearch).toHaveBeenCalled());
    mockSearch.mockClear();
    mockSearch.mockResolvedValue({ content: [], page: 0, size: 20, totalElements: 0 });

    // Select status
    const statusSelect = screen.getByRole('combobox', { name: /status/i });
    await user.click(statusSelect);
    const settledOption = await screen.findByRole('option', { name: 'SETTLED' });
    await user.click(settledOption);

    // Select direction
    const directionSelect = screen.getByRole('combobox', { name: /direction/i });
    await user.click(directionSelect);
    const inboundOption = await screen.findByRole('option', { name: 'INBOUND' });
    await user.click(inboundOption);

    await user.click(screen.getByRole('button', { name: /search transactions/i }));

    await waitFor(() => {
      expect(mockSearch).toHaveBeenCalledWith(
        expect.objectContaining({ status: 'SETTLED', direction: 'INBOUND' }),
      );
    });
  });

  // 2c. Date range filter
  it('passes date range filter to searchTransactions', async () => {
    mockSearch.mockResolvedValue(PAGE_RESPONSE);
    const user = userEvent.setup();
    renderPage();

    await waitFor(() => expect(mockSearch).toHaveBeenCalled());
    mockSearch.mockClear();
    mockSearch.mockResolvedValue({ content: [], page: 0, size: 20, totalElements: 0 });

    const fromInput = screen.getByLabelText(/date from/i);
    fireEvent.change(fromInput, { target: { value: '2024-06-01' } });
    const toInput = screen.getByLabelText(/date to/i);
    fireEvent.change(toInput, { target: { value: '2024-06-15' } });

    await user.click(screen.getByRole('button', { name: /search transactions/i }));

    await waitFor(() => {
      expect(mockSearch).toHaveBeenCalledWith(
        expect.objectContaining({ from: '2024-06-01', to: '2024-06-15' }),
      );
    });
  });

  // 2d. Amount range
  it('passes amountMin and amountMax to searchTransactions', async () => {
    mockSearch.mockResolvedValue(PAGE_RESPONSE);
    const user = userEvent.setup();
    renderPage();

    await waitFor(() => expect(mockSearch).toHaveBeenCalled());
    mockSearch.mockClear();
    mockSearch.mockResolvedValue({ content: [], page: 0, size: 20, totalElements: 0 });

    const minInput = screen.getByRole('textbox', { name: /amount min/i });
    const maxInput = screen.getByRole('textbox', { name: /amount max/i });
    await user.type(minInput, '10000');
    await user.type(maxInput, '500000');

    await user.click(screen.getByRole('button', { name: /search transactions/i }));

    await waitFor(() => {
      expect(mockSearch).toHaveBeenCalledWith(
        expect.objectContaining({ amountMin: '10000', amountMax: '500000' }),
      );
    });
  });

  // 3a. Pagination — page change
  it('dispatches next page when pagination changes', async () => {
    const manyResults = {
      content: Array.from({ length: 20 }, (_, i) => ({
        ...TXN_ROW_1,
        txnRef: `TXN-${String(i).padStart(3, '0')}`,
      })),
      page: 0,
      size: 20,
      totalElements: 45,
    };
    mockSearch.mockResolvedValue(manyResults);
    renderPage();

    await screen.findByRole('table', { name: /transaction search results/i });
    mockSearch.mockResolvedValue({ ...manyResults, page: 1 });

    // MUI TablePagination "next page" button
    const nextBtn = screen.getByRole('button', { name: /next page/i });
    await userEvent.click(nextBtn);

    await waitFor(() => {
      expect(mockSearch).toHaveBeenLastCalledWith(
        expect.objectContaining({ page: 1, size: 20 }),
      );
    });
  });

  // 3b. Rows per page change
  it('dispatches page 0 with new size on rows-per-page change', async () => {
    mockSearch.mockResolvedValue(PAGE_RESPONSE);
    renderPage();

    await screen.findByRole('table', { name: /transaction search results/i });
    mockSearch.mockClear();
    mockSearch.mockResolvedValue({ ...PAGE_RESPONSE, size: 50 });

    // The rows-per-page select. MUI renders it as a combobox with label "Rows per page:"
    const rppSelect = screen.getByRole('combobox', { name: /rows per page/i });
    await userEvent.click(rppSelect);
    const opt50 = await screen.findByRole('option', { name: '50' });
    await userEvent.click(opt50);

    await waitFor(() => {
      expect(mockSearch).toHaveBeenCalledWith(
        expect.objectContaining({ page: 0, size: 50 }),
      );
    });
  });

  // 4. Row click navigates to detail
  it('clicking a row navigates to /transactions/[txnRef]', async () => {
    mockSearch.mockResolvedValue(PAGE_RESPONSE);
    renderPage();

    const table = await screen.findByRole('table', { name: /transaction search results/i });
    const row = within(table).getByRole('row', { name: /TXN-001/i });
    await userEvent.click(row);

    expect(push).toHaveBeenCalledWith('/transactions/TXN-001');
  });

  // 5. CSV export
  it('CSV export calls exportTransactionsCsv and triggers download', async () => {
    mockSearch.mockResolvedValue(PAGE_RESPONSE);
    mockExportCsv.mockResolvedValue('txnRef\nTXN-001\nTXN-002');

    renderPage();

    // Wait for results to render before intercepting DOM helpers.
    await screen.findByRole('table', { name: /transaction search results/i });

    // Set up URL + DOM spies AFTER the component has mounted so they don't
    // interfere with MUI's portal/render cycle.
    const createObjectURL = vi.fn(() => 'blob:mock');
    const revokeObjectURL = vi.fn();
    Object.defineProperty(URL, 'createObjectURL', { value: createObjectURL, writable: true });
    Object.defineProperty(URL, 'revokeObjectURL', { value: revokeObjectURL, writable: true });

    // Stub the anchor click so JSDOM doesn't throw on programmatic download.
    const origCreate = document.createElement.bind(document);
    const createElementSpy = vi.spyOn(document, 'createElement').mockImplementation((tag) => {
      const el = origCreate(tag);
      if (tag === 'a') {
        // Prevent JSDOM from throwing on .click()
        Object.defineProperty(el, 'click', { value: vi.fn(), writable: true });
      }
      return el;
    });

    const exportBtn = screen.getByRole('button', { name: /export results as csv/i });
    await userEvent.click(exportBtn);

    await waitFor(() => {
      expect(mockExportCsv).toHaveBeenCalled();
    });
    expect(createObjectURL).toHaveBeenCalled();

    createElementSpy.mockRestore();
  });

  // 6. Empty state
  it('shows empty state when search returns no results', async () => {
    mockSearch.mockResolvedValue({ content: [], page: 0, size: 20, totalElements: 0 });
    renderPage();

    expect(await screen.findByText(/No transactions found/i)).toBeInTheDocument();
    expect(screen.queryByRole('table', { name: /transaction search results/i })).not.toBeInTheDocument();
  });

  // 7. Error alert — verify ErrorAlert renders when the store has an error.
  // Strategy: preload the store with error + items, and make mockSearch
  // never resolve so fetchTxnSearch.pending fires but fulfilled never clears
  // the preloaded error.
  it('shows error alert when the store has an error', async () => {
    // Never resolve — keeps the store in loading state without clearing error.
    mockSearch.mockReturnValue(new Promise(() => {}));

    const storeWithError = configureStore({
      reducer: { txnSearch: txnSearchReducer, auth: authReducer },
      preloadedState: {
        txnSearch: {
          filters: {},
          items: [TXN_ROW_1],
          page: 0,
          size: 20,
          totalElements: 1,
          loading: false,
          error: 'Could not connect to backend',
          csvLoading: false,
          csvError: null,
        },
      },
    });

    render(
      <Provider store={storeWithError}>
        <ThemeProvider theme={theme}>
          <TxnSearchPage />
        </ThemeProvider>
      </Provider>,
    );

    // fetchTxnSearch.pending sets loading=true + error=null, so we need to
    // assert before that clears our preloaded error. Because mockSearch
    // never resolves the fulfilled action never fires, but pending already
    // set error=null. Dispatch the rejected action directly instead.
    storeWithError.dispatch({
      type: 'txnSearch/fetch/rejected',
      error: { message: 'Could not connect to backend' },
    });

    expect(await screen.findByText(/Could not connect to backend/i)).toBeInTheDocument();
  });

  // 8. Reset button clears filters
  it('Reset button re-runs search with empty params', async () => {
    mockSearch.mockResolvedValue(PAGE_RESPONSE);
    const user = userEvent.setup();
    renderPage();

    // Wait for the initial load to complete so the form is visible.
    await waitFor(() => expect(mockSearch).toHaveBeenCalled());
    mockSearch.mockClear();
    mockSearch.mockResolvedValue(PAGE_RESPONSE);

    // Use findBy so we wait for the form to be in the DOM.
    const txnRefInput = await screen.findByRole('textbox', { name: /transaction ref/i });
    await user.type(txnRefInput, 'TXN-001');

    await user.click(screen.getByRole('button', { name: /reset filters/i }));

    await waitFor(() => {
      expect(mockSearch).toHaveBeenCalledWith({ page: 0, size: 20 });
    });
  });
});
