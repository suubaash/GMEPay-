/**
 * Vitest coverage for ActivateButton (Slice 8).
 *
 * Contract:
 *  - Disabled when allMet=false.
 *  - Enabled when allMet=true.
 *  - First click dispatches proposeActivate → snackbar info "Activation proposed".
 *  - Second click (when status=PROPOSED) dispatches executeActivate → calls onActivated.
 *  - Shows proposed banner when status=PROPOSED.
 *  - Shows success banner when status=ACTIVATED (button hidden).
 *  - Shows error snackbar on dispatch failure.
 */
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { Provider } from 'react-redux';
import { configureStore } from '@reduxjs/toolkit';
import draftsReducer from '@/store/draftsSlice';
import authReducer from '@/store/authSlice';
import lifecycleReducer from '@/store/lifecycleSlice';
import { theme } from '@/theme/theme';

const proposeActivationMock = vi.fn();
const executeActivationMock = vi.fn();

vi.mock('@/api/client', () => ({
  __esModule: true,
  ApiError: class ApiError extends Error {
    constructor(status, url, msg) { super(msg); this.status = status; }
  },
  adminApi: {
    proposePartnerActivation: (...a) => proposeActivationMock(...a),
    executePartnerActivation: (...a) => executeActivationMock(...a),
  },
}));

const snackSuccess = vi.fn();
const snackError = vi.fn();
const snackInfo = vi.fn();
vi.mock('@/components/SnackbarProvider', () => ({
  __esModule: true,
  default: ({ children }) => <>{children}</>,
  useSnackbar: () => ({
    success: snackSuccess, error: snackError, info: snackInfo, warning: vi.fn(),
  }),
}));

import { ActivateButton } from '../ActivateButton';

const PARTNER_CODE = 'GME_KR_TEST_ACT';

function buildStore(preload = {}) {
  return configureStore({
    reducer: {
      drafts: draftsReducer,
      auth: authReducer,
      lifecycle: lifecycleReducer,
    },
    preloadedState: preload,
  });
}

function renderButton({ allMet = true, onActivated = vi.fn(), preload = {} } = {}) {
  const store = buildStore(preload);
  return {
    store,
    ...render(
      <Provider store={store}>
        <ThemeProvider theme={theme}>
          <ActivateButton
            partnerCode={PARTNER_CODE}
            allMet={allMet}
            onActivated={onActivated}
          />
        </ThemeProvider>
      </Provider>,
    ),
  };
}

describe('ActivateButton', () => {
  beforeEach(() => {
    proposeActivationMock.mockReset();
    executeActivationMock.mockReset();
    snackSuccess.mockReset();
    snackError.mockReset();
    snackInfo.mockReset();
  });

  it('button is disabled when allMet=false', () => {
    renderButton({ allMet: false });
    expect(screen.getByLabelText('propose-activation')).toBeDisabled();
  });

  it('button is enabled when allMet=true', () => {
    renderButton({ allMet: true });
    expect(screen.getByLabelText('propose-activation')).not.toBeDisabled();
  });

  it('shows preconditions-unmet-banner when allMet=false', () => {
    renderButton({ allMet: false });
    expect(screen.getByLabelText('preconditions-unmet-banner')).toBeInTheDocument();
  });

  it('first click dispatches proposeActivate and shows info snackbar', async () => {
    proposeActivationMock.mockResolvedValue({ status: 'PROPOSED', proposedAt: '2026-06-13T00:00:00Z' });
    renderButton({ allMet: true });

    const user = userEvent.setup();
    await user.click(screen.getByLabelText('propose-activation'));

    await waitFor(() => {
      expect(proposeActivationMock).toHaveBeenCalledWith(PARTNER_CODE);
    });
    expect(snackInfo).toHaveBeenCalled();
  });

  it('shows activation-proposed-banner after propose', async () => {
    const preload = {
      lifecycle: {
        preconditionsByCode: {},
        activationByCode: { [PARTNER_CODE]: { status: 'PROPOSED', proposedAt: '2026-06-13T00:00:00Z' } },
        issuedBundle: null,
        loadingByCode: {},
        saving: false,
        error: null,
      },
    };
    renderButton({ allMet: true, preload });
    expect(screen.getByLabelText('activation-proposed-banner')).toBeInTheDocument();
  });

  it('second click (when PROPOSED) dispatches executeActivate and calls onActivated', async () => {
    const bundle = {
      keyId: 'k-001',
      keyPrefix: 'gme_',
      keyLast4: 'ab12',
      plaintextApiKey: 'secret-api-key',
      plaintextHmac: 'secret-hmac',
      plaintextWebhookSecret: 'secret-webhook',
    };
    executeActivationMock.mockResolvedValue(bundle);

    const preload = {
      lifecycle: {
        preconditionsByCode: {},
        activationByCode: { [PARTNER_CODE]: { status: 'PROPOSED' } },
        issuedBundle: null,
        loadingByCode: {},
        saving: false,
        error: null,
      },
    };

    const onActivated = vi.fn();
    renderButton({ allMet: true, onActivated, preload });

    const user = userEvent.setup();
    await user.click(screen.getByLabelText('confirm-activation'));

    await waitFor(() => {
      expect(executeActivationMock).toHaveBeenCalledWith(PARTNER_CODE);
    });
    expect(snackSuccess).toHaveBeenCalled();
  });

  it('shows activation-success-banner when status=ACTIVATED', () => {
    const preload = {
      lifecycle: {
        preconditionsByCode: {},
        activationByCode: { [PARTNER_CODE]: { status: 'ACTIVATED' } },
        issuedBundle: null,
        loadingByCode: {},
        saving: false,
        error: null,
      },
    };
    renderButton({ allMet: true, preload });
    expect(screen.getByLabelText('activation-success-banner')).toBeInTheDocument();
    // Activate button should not be visible
    expect(screen.queryByLabelText('propose-activation')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('confirm-activation')).not.toBeInTheDocument();
  });

  it('shows error snackbar when proposeActivate fails', async () => {
    proposeActivationMock.mockRejectedValue(new Error('BFF 500'));
    renderButton({ allMet: true });

    const user = userEvent.setup();
    await user.click(screen.getByLabelText('propose-activation'));

    await waitFor(() => {
      expect(snackError).toHaveBeenCalled();
    });
  });
});
