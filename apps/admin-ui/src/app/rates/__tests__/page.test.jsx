import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { Provider } from 'react-redux';
import { configureStore } from '@reduxjs/toolkit';
import ratesReducer from '@/store/ratesSlice';
import partnersReducer from '@/store/partnersSlice';
import authReducer from '@/store/authSlice';
import uiReducer from '@/store/uiSlice';
import { theme } from '@/theme/theme';

// next/navigation stub.
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }),
}));

// Mock the BFF client. listPartners returns a seeded list so the partner
// dropdown is selectable; previewRate is asserted on.
const previewRate = vi.fn();
const listPartners = vi.fn();
vi.mock('@/api/client', () => ({
  adminApi: {
    previewRate: (req) => previewRate(req),
    listPartners: () => listPartners(),
  },
}));

// Snackbar provider no-op.
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

import RatesPreviewPage from '../page';

function renderPage() {
  const store = configureStore({
    reducer: {
      rates: ratesReducer,
      partners: partnersReducer,
      auth: authReducer,
      ui: uiReducer,
    },
  });
  return render(
    <Provider store={store}>
      <ThemeProvider theme={theme}>
        <RatesPreviewPage />
      </ThemeProvider>
    </Provider>,
  );
}

describe('RatesPreviewPage', () => {
  beforeEach(() => {
    previewRate.mockReset();
    listPartners.mockReset();
    snackSuccess.mockReset();
    snackError.mockReset();
    listPartners.mockResolvedValue([
      {
        partnerId: 'GME_KR_001',
        type: 'LOCAL',
        settlementCurrency: 'KRW',
        settlementRoundingMode: 'HALF_UP',
      },
    ]);
  });

  it('shows a required-partner validation error when submitting without one', async () => {
    const user = userEvent.setup();
    renderPage();
    // Wait for partners list to load (otherwise the Select is empty).
    await waitFor(() => expect(listPartners).toHaveBeenCalled());
    await user.click(screen.getByRole('button', { name: /preview quote/i }));
    expect(await screen.findByText(/Partner is required/i)).toBeInTheDocument();
    expect(previewRate).not.toHaveBeenCalled();
  });

  it('calls previewRate with the shaped body when the form is valid', async () => {
    previewRate.mockResolvedValueOnce({
      collectionAmount: '100000',
      collectionCurrency: 'KRW',
      payoutAmount: '72.50',
      payoutCurrency: 'USD',
      collectionUsd: '73.00',
      payoutUsdCost: '72.50',
      collectionMarginUsd: '0.50',
      payoutMarginUsd: '0.00',
      offerRateColl: '0.000730',
      crossRate: '1369.86',
      shortCircuit: false,
      quotedAt: '2026-06-10T08:00:00Z',
    });
    const user = userEvent.setup();
    renderPage();

    await waitFor(() => expect(listPartners).toHaveBeenCalled());

    // Pick the partner from the dropdown. MUI Select exposes a combobox; we
    // locate it by accessible name (the "Partner" InputLabel).
    const partnerCombobox = screen.getByRole('combobox', { name: /partner$/i });
    await user.click(partnerCombobox);
    const option = await screen.findByRole('option', { name: /GME_KR_001/i });
    await user.click(option);

    await user.click(screen.getByRole('button', { name: /preview quote/i }));

    await waitFor(() => {
      expect(previewRate).toHaveBeenCalledTimes(1);
    });
    expect(previewRate).toHaveBeenCalledWith({
      fromCcy: 'KRW',
      toCcy: 'USD',
      amount: '100000',
      direction: 'INBOUND',
      partnerId: 'GME_KR_001',
    });
    await waitFor(() => {
      expect(snackSuccess).toHaveBeenCalled();
    });
  });
});
