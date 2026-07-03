/**
 * Vitest coverage for the /journal double-entry ledger page.
 *
 *  1. The journal list renders one row per journal, newest-first, with the
 *     money-movement reference shown.
 *  2. The balanced indicator renders (green Balanced / red Out of balance).
 *  3. Expanding a row reveals that journal's Debit and Credit ledger lines.
 */
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import { theme } from '@/theme/theme';

// Mock the API client — the page must never hit real network.
const mockGetJournals = vi.fn();
vi.mock('@/api/client', () => ({
  adminApi: {
    getJournals: (...a) => mockGetJournals(...a),
  },
  ApiError: class ApiError extends Error {},
}));

import JournalPage from './page';

const PAYLOAD = {
  page: 0,
  size: 50,
  total: 2,
  items: [
    {
      journalId: 'JRN-1001',
      reference: 'TXN-ABC-001',
      createdAt: '2026-07-03T09:30:00Z',
      lines: [
        { account: 'PARTNER_PREFUND', side: 'DR', amount: '100.00', currency: 'KRW' },
        { account: 'MERCHANT_PAYABLE', side: 'CR', amount: '100.00', currency: 'KRW' },
      ],
    },
    {
      journalId: 'JRN-1002',
      reference: 'TXN-XYZ-002',
      createdAt: '2026-07-02T14:15:00Z',
      lines: [
        { account: 'CASH_USD', side: 'DR', amount: '50.00', currency: 'USD' },
        { account: 'FX_SUSPENSE', side: 'CR', amount: '40.00', currency: 'USD' },
      ],
    },
  ],
};

function renderPage() {
  return render(
    <ThemeProvider theme={theme}>
      <JournalPage />
    </ThemeProvider>,
  );
}

describe('JournalPage', () => {
  beforeEach(() => {
    mockGetJournals.mockReset();
    mockGetJournals.mockResolvedValue(PAYLOAD);
  });

  it('renders the journal list with references and balanced indicators', async () => {
    renderPage();
    const table = await screen.findByRole('table', { name: /journal entries/i });
    // both money-movement references show
    expect(within(table).getByText('TXN-ABC-001')).toBeInTheDocument();
    expect(within(table).getByText('TXN-XYZ-002')).toBeInTheDocument();
    // balanced check: first journal balances, second does not (50 DR vs 40 CR)
    expect(within(table).getByLabelText('balanced')).toBeInTheDocument();
    expect(within(table).getByLabelText('out-of-balance')).toBeInTheDocument();
  });

  it('expands a row to show its Debit and Credit ledger lines', async () => {
    renderPage();
    await screen.findByText('TXN-ABC-001');
    // expand the first journal
    const expandButtons = screen.getAllByLabelText('expand journal');
    fireEvent.click(expandButtons[0]);

    const ledger = await screen.findByLabelText('ledger-JRN-1001');
    const debit = within(ledger).getByRole('table', { name: /debit lines/i });
    const credit = within(ledger).getByRole('table', { name: /credit lines/i });
    expect(within(debit).getByText('PARTNER_PREFUND')).toBeInTheDocument();
    expect(within(credit).getByText('MERCHANT_PAYABLE')).toBeInTheDocument();
    // per-currency totals + balance verdict render inside the ledger view
    expect(within(ledger).getByText(/KRW:/)).toBeInTheDocument();
  });
});
