import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { Provider } from 'react-redux';
import { configureStore } from '@reduxjs/toolkit';
import partnersReducer from '@/store/partnersSlice';
import authReducer from '@/store/authSlice';
import { theme } from '@/theme/theme';

// Mock next/navigation BEFORE importing the page so it picks up our stubs.
const push = vi.fn();
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push, replace: vi.fn(), back: vi.fn() }),
}));

// Mock the BFF client — we only need createPartner; everything else is unused
// here. The thunk resolves with the same shape the BFF would return.
const createPartner = vi.fn();
vi.mock('@/api/client', () => ({
  adminApi: {
    createPartner: (req: unknown) => createPartner(req),
  },
}));

// Mock the snackbar provider so the form's success/error toasts no-op.
const snackSuccess = vi.fn();
const snackError = vi.fn();
vi.mock('@/components/SnackbarProvider', () => ({
  __esModule: true,
  default: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  useSnackbar: () => ({
    success: snackSuccess,
    error: snackError,
    info: vi.fn(),
    warning: vi.fn(),
  }),
}));

import NewPartnerPage from '../page';

function renderPage() {
  const store = configureStore({
    reducer: { partners: partnersReducer, auth: authReducer },
  });
  return render(
    <Provider store={store}>
      <ThemeProvider theme={theme}>
        <NewPartnerPage />
      </ThemeProvider>
    </Provider>,
  );
}

describe('NewPartnerPage', () => {
  beforeEach(() => {
    push.mockReset();
    createPartner.mockReset();
    snackSuccess.mockReset();
    snackError.mockReset();
  });

  it('shows required-field errors when submitting an invalid form', async () => {
    const user = userEvent.setup();
    renderPage();
    // The partnerId default is empty -> should fail the regex/required check.
    await user.click(screen.getByRole('button', { name: /create partner/i }));
    expect(await screen.findByText(/Partner ID is required/i)).toBeInTheDocument();
    expect(createPartner).not.toHaveBeenCalled();
  });

  it('shows a regex error for a lowercase partner ID', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.type(screen.getByLabelText('Partner ID'), 'lower_case');
    await user.click(screen.getByRole('button', { name: /create partner/i }));
    expect(
      await screen.findByText(/uppercase letters, digits, hyphen or underscore/i),
    ).toBeInTheDocument();
    expect(createPartner).not.toHaveBeenCalled();
  });

  it('calls createPartner when a valid form is submitted', async () => {
    createPartner.mockResolvedValueOnce({
      partnerId: 'GME_KR_001',
      type: 'LOCAL',
      settlementCurrency: 'KRW',
      settlementRoundingMode: 'HALF_UP',
    });
    const user = userEvent.setup();
    renderPage();

    await user.type(screen.getByLabelText('Partner ID'), 'GME_KR_001');
    // Defaults: type=LOCAL, currency=KRW, mode=HALF_UP -> all valid already.
    await user.click(screen.getByRole('button', { name: /create partner/i }));

    await waitFor(() => {
      expect(createPartner).toHaveBeenCalledTimes(1);
    });
    expect(createPartner).toHaveBeenCalledWith({
      partnerId: 'GME_KR_001',
      type: 'LOCAL',
      settlementCurrency: 'KRW',
      settlementRoundingMode: 'HALF_UP',
    });
    await waitFor(() => {
      expect(snackSuccess).toHaveBeenCalled();
      expect(push).toHaveBeenCalledWith('/partners');
    });
  });
});
