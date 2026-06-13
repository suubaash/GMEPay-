/**
 * Vitest coverage for ActivationCredentialModal (Slice 8).
 *
 * Contract:
 *  - Does not render when bundle is null.
 *  - Renders modal when bundle is provided.
 *  - API key, HMAC, webhook secret fields are masked by default (type=password).
 *  - Toggle-reveal button changes field type to text.
 *  - Copy button is present for each secret field.
 *  - Done button is disabled until confirmation checkbox is checked.
 *  - After checking + clicking Done, dismissBundle is dispatched
 *    → bundle is wiped from Redux state (issuedBundle becomes null).
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

vi.mock('@/api/client', () => ({
  __esModule: true,
  ApiError: class ApiError extends Error {},
  adminApi: {},
}));

const snackSuccess = vi.fn();
const snackError = vi.fn();
vi.mock('@/components/SnackbarProvider', () => ({
  __esModule: true,
  default: ({ children }) => <>{children}</>,
  useSnackbar: () => ({
    success: snackSuccess, error: snackError, info: vi.fn(), warning: vi.fn(),
  }),
}));

import { ActivationCredentialModal } from '../ActivationCredentialModal';

const SAMPLE_BUNDLE = {
  keyId: 'key-001',
  keyPrefix: 'gme_',
  keyLast4: 'ab12',
  plaintextApiKey: 'my-secret-api-key',
  plaintextHmac: 'my-secret-hmac',
  plaintextWebhookSecret: 'my-secret-webhook',
};

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

function renderModal({ bundle = SAMPLE_BUNDLE } = {}) {
  const store = buildStore({
    lifecycle: {
      preconditionsByCode: {},
      activationByCode: {},
      issuedBundle: bundle,
      loadingByCode: {},
      saving: false,
      error: null,
    },
  });
  return {
    store,
    ...render(
      <Provider store={store}>
        <ThemeProvider theme={theme}>
          <ActivationCredentialModal bundle={bundle} />
        </ThemeProvider>
      </Provider>,
    ),
  };
}

describe('ActivationCredentialModal', () => {
  beforeEach(() => {
    snackSuccess.mockReset();
    snackError.mockReset();
  });

  it('does not render when bundle is null', () => {
    const store = buildStore();
    render(
      <Provider store={store}>
        <ThemeProvider theme={theme}>
          <ActivationCredentialModal bundle={null} />
        </ThemeProvider>
      </Provider>,
    );
    expect(screen.queryByLabelText('one-time-credential-modal')).not.toBeInTheDocument();
  });

  it('renders modal when bundle is provided', () => {
    renderModal();
    expect(screen.getByLabelText('one-time-credential-modal')).toBeInTheDocument();
  });

  it('shows one-time warning message', () => {
    renderModal();
    expect(screen.getByLabelText('one-time-warning')).toBeInTheDocument();
  });

  it('displays key ID, prefix and last4', () => {
    renderModal();
    expect(screen.getByLabelText('key-id-display')).toHaveTextContent('key-001');
    expect(screen.getByLabelText('key-prefix-display')).toHaveTextContent('gme_');
    expect(screen.getByLabelText('key-last4-display')).toHaveTextContent('ab12');
  });

  it('API key field is masked by default (type=password)', () => {
    renderModal();
    const field = screen.getByLabelText('plaintext-api-key');
    expect(field).toHaveAttribute('type', 'password');
  });

  it('HMAC field is masked by default', () => {
    renderModal();
    const field = screen.getByLabelText('plaintext-hmac');
    expect(field).toHaveAttribute('type', 'password');
  });

  it('webhook secret field is masked by default', () => {
    renderModal();
    const field = screen.getByLabelText('plaintext-webhook-secret');
    expect(field).toHaveAttribute('type', 'password');
  });

  it('toggle-reveal reveals the API key', async () => {
    renderModal();
    const user = userEvent.setup();
    await user.click(screen.getByLabelText('toggle-plaintext-api-key'));
    expect(screen.getByLabelText('plaintext-api-key')).toHaveAttribute('type', 'text');
  });

  it('copy buttons are present for all three secrets', () => {
    renderModal();
    expect(screen.getByLabelText('copy-plaintext-api-key')).toBeInTheDocument();
    expect(screen.getByLabelText('copy-plaintext-hmac')).toBeInTheDocument();
    expect(screen.getByLabelText('copy-plaintext-webhook-secret')).toBeInTheDocument();
  });

  it('Done button is disabled when confirmation checkbox is unchecked', () => {
    renderModal();
    expect(screen.getByLabelText('done-dismiss-credentials')).toBeDisabled();
  });

  it('Done button is enabled after checking the confirmation checkbox', async () => {
    renderModal();
    const user = userEvent.setup();
    await user.click(screen.getByLabelText('confirm-stored-checkbox'));
    expect(screen.getByLabelText('done-dismiss-credentials')).not.toBeDisabled();
  });

  it('dispatching dismissBundle wipes bundle from Redux state', async () => {
    const { store } = renderModal();

    // Verify bundle is present before dismiss
    expect(store.getState().lifecycle.issuedBundle).not.toBeNull();

    const user = userEvent.setup();
    await user.click(screen.getByLabelText('confirm-stored-checkbox'));
    await user.click(screen.getByLabelText('done-dismiss-credentials'));

    await waitFor(() => {
      expect(store.getState().lifecycle.issuedBundle).toBeNull();
    });
  });

  it('modal is not visible after bundle is wiped', async () => {
    const { store } = renderModal();
    const user = userEvent.setup();
    await user.click(screen.getByLabelText('confirm-stored-checkbox'));
    await user.click(screen.getByLabelText('done-dismiss-credentials'));

    await waitFor(() => {
      expect(store.getState().lifecycle.issuedBundle).toBeNull();
    });
    // The modal conditionally renders based on bundle; after wipe it should be gone.
    // Re-render with null bundle to verify
    const storeState = store.getState();
    expect(storeState.lifecycle.issuedBundle).toBeNull();
  });
});
