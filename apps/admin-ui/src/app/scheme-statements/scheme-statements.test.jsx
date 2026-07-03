/**
 * Vitest coverage for the /scheme-statements reconciliation page.
 *
 *  1. Scheme + date-range controls render.
 *  2. After Fetch, the per-currency totals cards render (the reconciliation
 *     headline).
 *  3. After Fetch, the statement table renders one row per transaction.
 *  4. Selecting a scheme + fetching calls getSchemeStatement with the right
 *     schemeId and paging.
 */
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import { theme } from '@/theme/theme';

// Mock the API client — the page must never hit real network.
const mockGetSchemeStatement = vi.fn();
const mockListSchemes = vi.fn();
vi.mock('@/api/client', () => ({
  adminApi: {
    getSchemeStatement: (...a) => mockGetSchemeStatement(...a),
    listSchemes: (...a) => mockListSchemes(...a),
  },
  ApiError: class ApiError extends Error {},
}));

import SchemeStatementsPage from './page';

const PAYLOAD = {
  schemeId: 'ZEROPAY',
  window: { from: '2026-06-03T00:00:00Z', to: '2026-07-03T23:59:59Z' },
  page: 0,
  size: 50,
  total: 2,
  totals: [
    { currency: 'KRW', count: 2, gross: '30000.00' },
    { currency: 'USD', count: 1, gross: '12.50' },
  ],
  items: [
    {
      txnRef: 'TXN-ABC-001',
      occurredAt: '2026-07-03T09:30:00Z',
      merchantId: 'MERCH-1',
      partnerId: 'PTNR-1',
      amount: '20000.00',
      currency: 'KRW',
      status: 'APPROVED',
    },
    {
      txnRef: 'TXN-XYZ-002',
      occurredAt: '2026-07-02T14:15:00Z',
      merchantId: 'MERCH-2',
      partnerId: 'PTNR-2',
      amount: '10000.00',
      currency: 'KRW',
      status: 'SETTLED',
    },
  ],
};

function renderPage() {
  return render(
    <ThemeProvider theme={theme}>
      <SchemeStatementsPage />
    </ThemeProvider>,
  );
}

describe('SchemeStatementsPage', () => {
  beforeEach(() => {
    mockGetSchemeStatement.mockReset();
    mockListSchemes.mockReset();
    mockGetSchemeStatement.mockResolvedValue(PAYLOAD);
    // Default: scheme catalog reachable, returns ZEROPAY + NEPAL.
    mockListSchemes.mockResolvedValue([
      { schemeId: 'ZEROPAY' },
      { schemeId: 'NEPAL' },
    ]);
  });

  it('renders the scheme + date-range controls', async () => {
    renderPage();
    expect(await screen.findByLabelText('scheme select')).toBeInTheDocument();
    expect(screen.getByLabelText('From')).toBeInTheDocument();
    expect(screen.getByLabelText('To')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /fetch/i })).toBeInTheDocument();
  });

  it('renders per-currency totals and the statement table after Fetch', async () => {
    renderPage();
    fireEvent.click(screen.getByRole('button', { name: /fetch/i }));

    // Totals headline: both currencies show as raw decimal strings.
    const totals = await screen.findByLabelText('scheme-totals');
    expect(within(totals).getByText(/30000\.00/)).toBeInTheDocument();
    expect(within(totals).getByText(/12\.50/)).toBeInTheDocument();

    // Statement table: one row per transaction.
    const table = screen.getByRole('table', { name: /scheme statement/i });
    expect(within(table).getByText('TXN-ABC-001')).toBeInTheDocument();
    expect(within(table).getByText('TXN-XYZ-002')).toBeInTheDocument();
    expect(within(table).getByText('MERCH-1')).toBeInTheDocument();
    // Money shown as raw decimal string.
    expect(within(table).getByText('20000.00')).toBeInTheDocument();
  });

  it('fetches the selected scheme statement with the right params', async () => {
    renderPage();
    // Wait for the catalog to load so the picker is populated.
    await screen.findByLabelText('scheme select');
    fireEvent.click(screen.getByRole('button', { name: /fetch/i }));

    await screen.findByRole('table', { name: /scheme statement/i });
    expect(mockGetSchemeStatement).toHaveBeenCalledTimes(1);
    const arg = mockGetSchemeStatement.mock.calls[0][0];
    expect(arg.schemeId).toBe('ZEROPAY');
    expect(arg.page).toBe(0);
    expect(arg.size).toBe(50);
    expect(arg.from).toMatch(/T00:00:00Z$/);
    expect(arg.to).toMatch(/T23:59:59Z$/);
  });
});
