/**
 * Vitest coverage for WebhookSubscriptionSection (Slice 8).
 *
 * Contract:
 *  - Renders webhook-subscription-section aria-label.
 *  - webhook-url input present.
 *  - webhook-event-types multi-select present.
 *  - Save dispatches patchStep8WebhookSubscription with { url, eventTypes }.
 *  - Error snackbar on save failure.
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

const patchWebhookMock = vi.fn();

vi.mock('@/api/client', () => ({
  __esModule: true,
  ApiError: class ApiError extends Error {
    constructor(status, url, msg) { super(msg); this.status = status; }
  },
  adminApi: {
    patchDraftStep8WebhookSubscription: (...a) => patchWebhookMock(...a),
  },
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

import { WebhookSubscriptionSection } from '../WebhookSubscriptionSection';

const PARTNER_CODE = 'GME_KR_TEST_WEBHOOK';

function buildStore(preload = {}) {
  return configureStore({
    reducer: { drafts: draftsReducer, auth: authReducer, lifecycle: lifecycleReducer },
    preloadedState: preload,
  });
}

function renderSection({ draft = {}, preload = {} } = {}) {
  const store = buildStore(preload);
  return {
    store,
    ...render(
      <Provider store={store}>
        <ThemeProvider theme={theme}>
          <WebhookSubscriptionSection draft={draft} partnerCode={PARTNER_CODE} />
        </ThemeProvider>
      </Provider>,
    ),
  };
}

describe('WebhookSubscriptionSection', () => {
  beforeEach(() => {
    patchWebhookMock.mockReset();
    snackSuccess.mockReset();
    snackError.mockReset();
  });

  it('renders webhook-subscription-section aria-label', () => {
    renderSection();
    expect(screen.getByLabelText('webhook-subscription-section')).toBeInTheDocument();
  });

  it('renders webhook-url input', () => {
    renderSection();
    expect(screen.getByLabelText('webhook-url')).toBeInTheDocument();
  });

  it('renders webhook-event-types multi-select', () => {
    renderSection();
    expect(screen.getByLabelText('webhook-event-types')).toBeInTheDocument();
  });

  it('save dispatches patchStep8WebhookSubscription with url', async () => {
    patchWebhookMock.mockResolvedValue({ partnerCode: PARTNER_CODE });
    renderSection();

    const user = userEvent.setup();
    await user.type(
      screen.getByLabelText('webhook-url'),
      'https://partner.example.com/webhooks',
    );
    await user.click(screen.getByLabelText('save-webhook-subscription'));

    await waitFor(() => {
      expect(patchWebhookMock).toHaveBeenCalledWith(
        PARTNER_CODE,
        expect.objectContaining({
          url: 'https://partner.example.com/webhooks',
          eventTypes: expect.any(Array),
        }),
      );
    });
    expect(snackSuccess).toHaveBeenCalled();
  });

  it('pre-populates url from draft.webhookUrl', () => {
    renderSection({
      draft: { webhookUrl: 'https://existing.example.com/hooks' },
    });
    expect(screen.getByLabelText('webhook-url')).toHaveValue(
      'https://existing.example.com/hooks',
    );
  });

  it('shows error snackbar on save failure', async () => {
    patchWebhookMock.mockRejectedValue(new Error('BFF error'));
    renderSection();
    const user = userEvent.setup();
    // Type a URL so the save is not blocked
    await user.type(screen.getByLabelText('webhook-url'), 'https://example.com');
    await user.click(screen.getByLabelText('save-webhook-subscription'));
    await waitFor(() => expect(snackError).toHaveBeenCalled());
  });
});
