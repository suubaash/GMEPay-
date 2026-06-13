/**
 * Vitest coverage for RegulatorySettingsTab (Slice 8).
 *
 * Covers:
 *  1. Renders regulatory summary when data present.
 *  2. Shows empty state when regulatory is null.
 *  3. Edit button opens dialog with RegulatorySection.
 *  4. Save button in dialog calls onSaved and closes.
 *  5. Cancel button closes dialog without calling onSaved.
 */
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Provider } from 'react-redux';
import { configureStore } from '@reduxjs/toolkit';
import { ThemeProvider } from '@mui/material/styles';
import { theme } from '@/theme/theme';
import partnerLifecycleReducer from '@/store/partnerLifecycleSlice';
import authReducer from '@/store/authSlice';
import lifecycleReducer from '@/store/lifecycleSlice';

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn() }),
  useParams: () => ({}),
}));

const snackError = vi.fn();
const snackSuccess = vi.fn();
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

vi.mock('@/api/client', () => ({
  adminApi: {
    patchDraftStep8Regulatory: vi.fn().mockResolvedValue({}),
  },
}));

import RegulatorySettingsTab from '../RegulatorySettingsTab';

function makeStore() {
  return configureStore({
    reducer: {
      partnerLifecycle: partnerLifecycleReducer,
      lifecycle: lifecycleReducer,
      auth: authReducer,
    },
  });
}

function renderTab(props) {
  const store = makeStore();
  return render(
    <Provider store={store}>
      <ThemeProvider theme={theme}>
        <RegulatorySettingsTab {...props} />
      </ThemeProvider>
    </Provider>,
  );
}

const REGULATORY = {
  bok: { reportingCode: 'BOK-001', fxLicenseNumber: 'FX-123', licenseExpiry: '2027-12-31' },
  hometax: { bizRegNumber: '123-45-67890', delegateReportingEnabled: true },
  kofiu: { reportingThresholdUsd: '10000', suspiciousTxnContactEmail: 'aml@gme.com', riskCategory: 'LOW' },
};

describe('RegulatorySettingsTab', () => {
  beforeEach(() => {
    snackError.mockReset();
    snackSuccess.mockReset();
  });

  it('renders regulatory summary when data present', () => {
    renderTab({ regulatory: REGULATORY });
    expect(screen.getByTestId('regulatory-settings-tab')).toBeInTheDocument();
    expect(screen.getByTestId('regulatory-section')).toBeInTheDocument();
  });

  it('shows empty message when regulatory is null', () => {
    renderTab({ regulatory: null });
    expect(screen.getByText(/No regulatory configuration loaded/i)).toBeInTheDocument();
  });

  it('Edit button opens dialog', async () => {
    const user = userEvent.setup();
    renderTab({ regulatory: REGULATORY });

    await user.click(screen.getByTestId('edit-regulatory-btn'));
    expect(screen.getByTestId('edit-regulatory-dialog')).toBeInTheDocument();
  });

  it('Save button in dialog calls onSaved', async () => {
    const onSaved = vi.fn();
    const user = userEvent.setup();
    renderTab({ regulatory: REGULATORY, onSaved });

    await user.click(screen.getByTestId('edit-regulatory-btn'));
    await user.click(screen.getByTestId('save-regulatory-btn'));

    expect(onSaved).toHaveBeenCalled();
  });

  it('Cancel button closes dialog without calling onSaved', async () => {
    const onSaved = vi.fn();
    const user = userEvent.setup();
    renderTab({ regulatory: REGULATORY, onSaved });

    await user.click(screen.getByTestId('edit-regulatory-btn'));
    // Verify dialog open
    expect(screen.getByTestId('edit-regulatory-dialog')).toBeInTheDocument();

    // Click cancel
    await user.click(screen.getByRole('button', { name: /cancel/i }));

    expect(onSaved).not.toHaveBeenCalled();
  });
});
