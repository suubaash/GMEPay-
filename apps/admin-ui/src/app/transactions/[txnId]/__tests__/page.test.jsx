/**
 * T4-4 — the Admin transaction-detail page shows WHO WAS PAID.
 *
 * The page previously had no merchant fields at all, and the BFF behind it hardcoded
 * merchantName to null, so an operator investigating a payment could see the scheme refs and the
 * money but never the merchant. These tests pin both halves of the fix:
 *   - a persisted name is rendered;
 *   - an absent name renders an em dash and is NOT back-filled from the merchant id.
 */
import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { Provider } from 'react-redux';
import { configureStore, createSlice } from '@reduxjs/toolkit';
import { ThemeProvider } from '@mui/material/styles';
import { theme } from '@/theme/theme';

vi.mock('next/navigation', () => ({
  useParams: () => ({ txnId: 'TXN-T44' }),
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }),
  usePathname: () => '/transactions/TXN-T44',
}));

vi.mock('@/components/Breadcrumbs', () => ({
  default: ({ crumbs }) => (
    <nav aria-label="breadcrumb">
      {(crumbs ?? []).map((c, i) => <span key={i}>{c.label}</span>)}
    </nav>
  ),
}));

import TransactionDetailPage from '../page';

const DETAIL = {
  summary: {
    txnId: 'TXN-T44',
    partnerId: 'partner_test_001',
    state: 'COMMITTED',
    amount: '125.50',
    currency: 'USD',
    committedAt: '2026-06-09T10:15:30Z',
  },
  schemeTxnRef: 'ZP-TXN-T44',
  schemeApprovalCode: 'AUTH-T44',
  prefundDeductedUsd: '0.0935',
  approvedAt: '2026-06-09T10:15:31Z',
  bookedSettlementAmount: null,
  settlementRoundingMode: 'HALF_UP',
  roundingResidual: null,
  merchantId: 'M0000000001',
  merchantName: 'Gangnam Coffee House',
};

/** Renders the page with a pre-seeded transactions slice (no thunk / network). */
function renderWithDetail(detail) {
  const slice = createSlice({
    name: 'transactions',
    initialState: {
      details: detail ? { 'TXN-T44': detail } : {},
      detailLoading: false,
      error: null,
      items: [],
    },
    reducers: {},
  });
  const store = configureStore({ reducer: { transactions: slice.reducer } });
  return render(
    <Provider store={store}>
      <ThemeProvider theme={theme}>
        <TransactionDetailPage />
      </ThemeProvider>
    </Provider>,
  );
}

describe('Admin transaction detail — merchant identity (T4-4)', () => {
  it('renders the persisted merchant name alongside the merchant id', () => {
    renderWithDetail(DETAIL);
    expect(screen.getByText('Gangnam Coffee House')).toBeInTheDocument();
    expect(screen.getByText('M0000000001')).toBeInTheDocument();
  });

  it('renders an em dash — not the merchant id — when no name was captured', () => {
    renderWithDetail({ ...DETAIL, merchantName: null });
    // The id is still shown in its own field…
    expect(screen.getByText('M0000000001')).toBeInTheDocument();
    // …and the name field is blank rather than duplicating it.
    expect(screen.queryAllByText('M0000000001')).toHaveLength(1);
    expect(screen.getAllByText('—').length).toBeGreaterThanOrEqual(1);
  });
});
