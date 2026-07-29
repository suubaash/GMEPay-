/**
 * Vitest coverage for WebhookSecretPanel (gap T5-8).
 *
 * The situation under test: webhook signing moved to per-endpoint derived secrets, so an
 * endpoint registered before that change can never be signed for — the partner silently
 * receives nothing. Rotation is the only fix and it had no UI at all.
 *
 * Covers:
 *  1. An un-signable endpoint is reported as such, with copy explaining WHY the operator must act.
 *  2. A healthy partner is NOT told to go rotate things.
 *  3. Rotate is confirm-gated, sends the chosen overlap + reason, and reveals the secret once.
 *  4. Closing the reveal drops the plaintext from the store and re-reads the rows.
 *  5. ROOT_KEY_MISSING is surfaced as a deployment fix and rotation is disabled.
 */
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Provider } from 'react-redux';
import { configureStore } from '@reduxjs/toolkit';
import { ThemeProvider } from '@mui/material/styles';
import { theme } from '@/theme/theme';
import partnerLifecycleReducer from '@/store/partnerLifecycleSlice';

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

const healthMock = vi.fn();
const rotateMock = vi.fn();
vi.mock('@/api/client', () => ({
  adminApi: {
    getWebhookEndpointHealth: (...a) => healthMock(...a),
    rotateWebhookEndpointSecret: (...a) => rotateMock(...a),
  },
}));

import WebhookSecretPanel from '../WebhookSecretPanel';

/** A pre-change endpoint: active, registered months ago, and completely unable to sign. */
const UNSIGNABLE = {
  endpointId: '17',
  partnerId: 42,
  environment: 'LIVE',
  webhookUrl: 'https://partner.example.com/hooks/gmepay',
  secretGeneration: 1,
  status: 'SECRET_NOT_DERIVABLE',
  deliverable: false,
  fixableByRotation: true,
  detail: 'the secret stored for this endpoint cannot be re-derived',
  rotationOverlapExpiresAt: null,
  createdAt: '2026-01-05T00:00:00Z',
  updatedAt: '2026-01-05T00:00:00Z',
};

const SIGNABLE = {
  ...UNSIGNABLE,
  endpointId: '18',
  environment: 'SANDBOX',
  status: 'SIGNABLE',
  deliverable: true,
  fixableByRotation: false,
  detail: 'signable',
  createdAt: '2026-07-20T00:00:00Z',
};

function renderPanel() {
  const store = configureStore({ reducer: { partnerLifecycle: partnerLifecycleReducer } });
  const utils = render(
    <Provider store={store}>
      <ThemeProvider theme={theme}>
        <WebhookSecretPanel partnerCode="GMEREMIT" />
      </ThemeProvider>
    </Provider>,
  );
  return { ...utils, store };
}

describe('WebhookSecretPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    healthMock.mockResolvedValue([]);
    rotateMock.mockResolvedValue({});
  });

  it('reads the partner\'s endpoints by partner code', async () => {
    healthMock.mockResolvedValue([SIGNABLE]);
    renderPanel();
    await waitFor(() => expect(healthMock).toHaveBeenCalledWith('GMEREMIT'));
  });

  it('reports an un-signable endpoint and explains why the operator must rotate it', async () => {
    healthMock.mockResolvedValue([UNSIGNABLE]);
    renderPanel();

    await waitFor(() =>
      expect(screen.getByTestId('webhook-endpoint-row-17')).toBeInTheDocument(),
    );

    // The status is not a soft warning — the partner is getting nothing.
    expect(screen.getByTestId('webhook-status-17')).toHaveTextContent('Cannot sign');

    const warning = screen.getByTestId('undeliverable-warning');
    expect(warning).toHaveTextContent(/receiving no webhooks/i);
    // The causal explanation the task requires: pre-change endpoints cannot sign until rotated.
    expect(warning).toHaveTextContent(/before that change cannot be signed for/i);
    expect(warning).toHaveTextContent(/Rotate to fix it/i);
  });

  it('does NOT tell the operator to rotate when every endpoint is signing', async () => {
    healthMock.mockResolvedValue([SIGNABLE]);
    renderPanel();

    await waitFor(() =>
      expect(screen.getByTestId('webhook-endpoint-row-18')).toBeInTheDocument(),
    );
    expect(screen.queryByTestId('undeliverable-warning')).not.toBeInTheDocument();
    expect(screen.getByTestId('webhook-status-18')).toHaveTextContent('Signing');
  });

  it('rotates behind a confirm step, forwards the overlap, and reveals the secret once', async () => {
    const user = userEvent.setup();
    healthMock.mockResolvedValue([UNSIGNABLE]);
    rotateMock.mockResolvedValue({
      endpointId: '17',
      signingSecretPlaintext: 'whsec_new_derivable_value',
      secretGeneration: 2,
      previousSecretExpiresAt: '2026-07-30T05:00:00Z',
    });

    const { store } = renderPanel();
    await waitFor(() =>
      expect(screen.getByTestId('rotate-webhook-btn-17')).toBeInTheDocument(),
    );

    // A single click must NOT rotate — it opens the confirmation instead.
    await user.click(screen.getByTestId('rotate-webhook-btn-17'));
    expect(rotateMock).not.toHaveBeenCalled();

    await user.type(screen.getByTestId('rotate-reason-input'), 'legacy endpoint cannot sign');
    await user.click(screen.getByTestId('confirm-rotate-webhook-btn'));

    await waitFor(() => expect(rotateMock).toHaveBeenCalledTimes(1));
    expect(rotateMock).toHaveBeenCalledWith('17', {
      reason: 'legacy endpoint cannot sign',
      overlapMinutes: 1440,
    });

    // The plaintext is revealed exactly once, in the modal, with the overlap deadline.
    await waitFor(() =>
      expect(screen.getByTestId('webhook-secret-reveal-modal')).toBeInTheDocument(),
    );
    expect(screen.getByTestId('webhook-secret-field')).toHaveValue('whsec_new_derivable_value');
    expect(screen.getByTestId('overlap-window-notice')).toBeInTheDocument();
    expect(screen.getByTestId('webhook-secret-reveal-modal')).toHaveTextContent(
      /must receive it out of band/i,
    );

    // Acknowledging drops the plaintext from the store and re-reads the rows.
    await user.click(screen.getByTestId('confirm-webhook-secret-copied-btn'));
    await waitFor(() =>
      expect(store.getState().partnerLifecycle.webhookRotateResult).toBeNull(),
    );
    expect(healthMock).toHaveBeenCalledTimes(2);
  });

  it('surfaces a missing platform signing key as a deployment fix and disables rotation', async () => {
    healthMock.mockResolvedValue([
      {
        ...UNSIGNABLE,
        status: 'ROOT_KEY_MISSING',
        deliverable: false,
        fixableByRotation: false,
        detail: 'the derivation root key is not configured',
      },
    ]);
    renderPanel();

    await waitFor(() =>
      expect(screen.getByTestId('root-key-warning')).toBeInTheDocument(),
    );
    // Rotation would issue a secret the dispatcher could not reproduce — refuse it here too.
    expect(screen.getByTestId('rotate-webhook-btn-17')).toBeDisabled();
    // And it must not be presented as a per-endpoint problem.
    expect(screen.queryByTestId('undeliverable-warning')).not.toBeInTheDocument();
  });

  it('shows an immediate-cutover warning when the operator chose no overlap', async () => {
    const user = userEvent.setup();
    healthMock.mockResolvedValue([UNSIGNABLE]);
    rotateMock.mockResolvedValue({
      endpointId: '17',
      signingSecretPlaintext: 'whsec_immediate',
      secretGeneration: 2,
      previousSecretExpiresAt: null,
    });

    renderPanel();
    await waitFor(() =>
      expect(screen.getByTestId('rotate-webhook-btn-17')).toBeInTheDocument(),
    );
    await user.click(screen.getByTestId('rotate-webhook-btn-17'));
    await user.click(screen.getByTestId('confirm-rotate-webhook-btn'));

    await waitFor(() =>
      expect(screen.getByTestId('immediate-cutover-notice')).toBeInTheDocument(),
    );
  });

  it('reports a rotation failure instead of pretending it worked', async () => {
    const user = userEvent.setup();
    healthMock.mockResolvedValue([UNSIGNABLE]);
    rotateMock.mockRejectedValue(new Error('403 Forbidden'));

    renderPanel();
    await waitFor(() =>
      expect(screen.getByTestId('rotate-webhook-btn-17')).toBeInTheDocument(),
    );
    await user.click(screen.getByTestId('rotate-webhook-btn-17'));
    await user.click(screen.getByTestId('confirm-rotate-webhook-btn'));

    await waitFor(() =>
      expect(snackError).toHaveBeenCalledWith(expect.stringContaining('Rotation failed')),
    );
    expect(screen.queryByTestId('webhook-secret-reveal-modal')).not.toBeInTheDocument();
  });
});
