/**
 * Vitest coverage for Step8Form (Slice 8).
 *
 * Contract:
 *  - Renders step-8-form aria-label.
 *  - Tabs (Review, Regulatory, Webhook, IP allowlist, mTLS, Activate) present.
 *  - Tab navigation switches content.
 *  - Activate tab shows PreconditionPanel and ActivateButton.
 *  - After successful executeActivate, ActivationCredentialModal appears with bundle.
 *  - After dismissBundle, modal disappears and bundle is null in store.
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
import partnerSchemesReducer from '@/store/partnerSchemesSlice';
import kybReducer from '@/store/kybSlice';
import bankAccountsReducer from '@/store/bankAccountsSlice';
import prefundingConfigReducer from '@/store/prefundingConfigSlice';
import commercialTermsReducer from '@/store/commercialTermsSlice';
import { theme } from '@/theme/theme';

const getActivationPreconditionsMock = vi.fn();
const proposeActivationMock = vi.fn();
const executeActivationMock = vi.fn();
const patchRegMock = vi.fn();
const patchWebhookMock = vi.fn();
const patchIpMock = vi.fn();
const patchMtlsMock = vi.fn();

vi.mock('@/api/client', () => ({
  __esModule: true,
  ApiError: class ApiError extends Error {
    constructor(status, url, msg) { super(msg); this.status = status; }
  },
  adminApi: {
    getActivationPreconditions: (...a) => getActivationPreconditionsMock(...a),
    proposePartnerActivation: (...a) => proposeActivationMock(...a),
    executePartnerActivation: (...a) => executeActivationMock(...a),
    patchDraftStep8Regulatory: (...a) => patchRegMock(...a),
    patchDraftStep8WebhookSubscription: (...a) => patchWebhookMock(...a),
    patchDraftStep8IpAllowlist: (...a) => patchIpMock(...a),
    patchDraftStep8MtlsCert: (...a) => patchMtlsMock(...a),
  },
}));

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn() }),
  useParams: () => ({ draftCode: 'GME_KR_TEST_S8' }),
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

import { Step8Form } from '../Step8Form';

const PARTNER_CODE = 'GME_KR_TEST_S8';

const SAMPLE_BUNDLE = {
  keyId: 'key-test-001',
  keyPrefix: 'gme_',
  keyLast4: 'ef56',
  plaintextApiKey: 'api-key-plaintext',
  plaintextHmac: 'hmac-plaintext',
  plaintextWebhookSecret: 'webhook-secret-plaintext',
};

function buildStore(preload = {}) {
  return configureStore({
    reducer: {
      drafts: draftsReducer,
      auth: authReducer,
      lifecycle: lifecycleReducer,
      partnerSchemes: partnerSchemesReducer,
      kyb: kybReducer,
      bankAccounts: bankAccountsReducer,
      prefundingConfig: prefundingConfigReducer,
      commercialTerms: commercialTermsReducer,
    },
    preloadedState: preload,
  });
}

function renderForm({ draft = { partnerCode: PARTNER_CODE }, preload = {} } = {}) {
  const store = buildStore(preload);
  return {
    store,
    ...render(
      <Provider store={store}>
        <ThemeProvider theme={theme}>
          <Step8Form
            draft={draft}
            partnerCode={PARTNER_CODE}
            onSaved={vi.fn()}
          />
        </ThemeProvider>
      </Provider>,
    ),
  };
}

describe('Step8Form', () => {
  beforeEach(() => {
    getActivationPreconditionsMock.mockReset();
    proposeActivationMock.mockReset();
    executeActivationMock.mockReset();
    snackSuccess.mockReset();
    snackError.mockReset();
    snackInfo.mockReset();

    getActivationPreconditionsMock.mockResolvedValue([]);
  });

  it('renders step-8-form aria-label', () => {
    renderForm();
    expect(screen.getByLabelText('step-8-form')).toBeInTheDocument();
  });

  it('renders all 6 tabs', () => {
    renderForm();
    const tabLabels = ['Review', 'Regulatory', 'Webhook', 'IP allowlist', 'mTLS', 'Activate'];
    for (const label of tabLabels) {
      expect(screen.getByText(label)).toBeInTheDocument();
    }
  });

  it('shows ReviewSection by default on the Review tab', () => {
    renderForm();
    expect(screen.getByLabelText('review-section')).toBeInTheDocument();
  });

  it('switches to Regulatory tab', async () => {
    renderForm();
    const user = userEvent.setup();
    await user.click(screen.getByLabelText('tab-regulatory'));
    await waitFor(() => {
      expect(screen.getByLabelText('regulatory-section')).toBeInTheDocument();
    });
  });

  it('switches to Webhook tab', async () => {
    renderForm();
    const user = userEvent.setup();
    await user.click(screen.getByLabelText('tab-webhook'));
    await waitFor(() => {
      expect(screen.getByLabelText('webhook-subscription-section')).toBeInTheDocument();
    });
  });

  it('switches to IP allowlist tab', async () => {
    renderForm();
    const user = userEvent.setup();
    await user.click(screen.getByLabelText('tab-ip-allowlist'));
    await waitFor(() => {
      expect(screen.getByLabelText('ip-allowlist-section')).toBeInTheDocument();
    });
  });

  it('switches to mTLS tab', async () => {
    renderForm();
    const user = userEvent.setup();
    await user.click(screen.getByLabelText('tab-mtls'));
    await waitFor(() => {
      expect(screen.getByLabelText('mtls-cert-section')).toBeInTheDocument();
    });
  });

  it('switches to Activate tab — shows PreconditionPanel and ActivateButton', async () => {
    renderForm();
    const user = userEvent.setup();
    await user.click(screen.getByLabelText('tab-activate'));
    await waitFor(() => {
      expect(screen.getByLabelText('precondition-panel')).toBeInTheDocument();
    });
    // ActivateButton is disabled because preconditions empty → allMet=false
    expect(screen.getByLabelText('propose-activation')).toBeDisabled();
  });

  it('Activate button enabled when all preconditions met', async () => {
    const MET_PRECONDITIONS = [
      { key: 'IDENTITY', description: 'Identity complete', met: true },
    ];
    // Override the mock so the fetch on mount returns met preconditions
    getActivationPreconditionsMock.mockResolvedValue(MET_PRECONDITIONS);
    const preload = {
      lifecycle: {
        preconditionsByCode: {
          [PARTNER_CODE]: MET_PRECONDITIONS,
        },
        activationByCode: {},
        issuedBundle: null,
        loadingByCode: {},
        saving: false,
        error: null,
      },
    };
    renderForm({ preload });
    const user = userEvent.setup();
    await user.click(screen.getByLabelText('tab-activate'));
    await waitFor(() => {
      expect(screen.getByLabelText('propose-activation')).not.toBeDisabled();
    });
  });

  it('ActivationCredentialModal appears after bundle is in store', async () => {
    const preload = {
      lifecycle: {
        preconditionsByCode: {
          [PARTNER_CODE]: [{ key: 'IDENTITY', description: 'Identity complete', met: true }],
        },
        activationByCode: {},
        issuedBundle: SAMPLE_BUNDLE,
        loadingByCode: {},
        saving: false,
        error: null,
      },
    };
    renderForm({ preload });
    // Modal should be visible because issuedBundle is set
    await waitFor(() => {
      expect(screen.getByLabelText('one-time-credential-modal')).toBeInTheDocument();
    });
  });

  it('ActivationCredentialModal disappears after dismissBundle', async () => {
    const preload = {
      lifecycle: {
        preconditionsByCode: {},
        activationByCode: {},
        issuedBundle: SAMPLE_BUNDLE,
        loadingByCode: {},
        saving: false,
        error: null,
      },
    };
    const { store } = renderForm({ preload });

    await waitFor(() => {
      expect(screen.getByLabelText('one-time-credential-modal')).toBeInTheDocument();
    });

    const user = userEvent.setup();
    // Check the confirmation and dismiss
    await user.click(screen.getByLabelText('confirm-stored-checkbox'));
    await user.click(screen.getByLabelText('done-dismiss-credentials'));

    await waitFor(() => {
      expect(store.getState().lifecycle.issuedBundle).toBeNull();
    });
  });
});
