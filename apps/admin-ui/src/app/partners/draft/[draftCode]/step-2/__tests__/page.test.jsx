import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { Provider } from 'react-redux';
import { configureStore } from '@reduxjs/toolkit';
import draftsReducer, { fetchDraft, fetchContacts } from '@/store/draftsSlice';
import authReducer from '@/store/authSlice';
import { theme } from '@/theme/theme';

/**
 * Vitest coverage for the step-2 deep-link page route.
 *
 * Spec contract:
 *  1. Rendering Step2Page mounts the ContactsForm (cursor pinned to step 2).
 *  2. The Back button dispatches the correct thunk/state change — cursor
 *     moves to step 1 so the IdentityForm appears instead.
 */

// ── next/navigation stubs ────────────────────────────────────────────────────
const mockPush = vi.fn();
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: mockPush, replace: vi.fn(), back: vi.fn() }),
  useParams: () => ({ draftCode: 'GME_KR_001' }),
}));

// ── BFF client ────────────────────────────────────────────────────────────────
const getDraftMock = vi.fn();
const getPartnerContactsMock = vi.fn();
const patchDraftStepMock = vi.fn();

vi.mock('@/api/client', () => ({
  __esModule: true,
  ApiError: class ApiError extends Error {
    constructor(status, url, message) {
      super(message);
      this.status = status;
      this.url = url;
    }
  },
  adminApi: {
    getDraft: (...args) => getDraftMock(...args),
    getPartnerContacts: (...args) => getPartnerContactsMock(...args),
    patchDraftStep: (...args) => patchDraftStepMock(...args),
  },
}));

// ── Snackbar ──────────────────────────────────────────────────────────────────
vi.mock('@/components/SnackbarProvider', () => ({
  __esModule: true,
  default: ({ children }) => <>{children}</>,
  useSnackbar: () => ({
    success: vi.fn(),
    error: vi.fn(),
    info: vi.fn(),
    warning: vi.fn(),
  }),
}));

// ── Mock ContactsForm so the test does not depend on its internals ────────────
vi.mock('../ContactsForm', () => ({
  __esModule: true,
  default: ({ partnerCode }) => (
    <div data-testid="contacts-form" data-partner-code={partnerCode}>
      ContactsForm stub
    </div>
  ),
}));

// ── Mock IdentityForm to detect when cursor moves back to step 1 ──────────────
vi.mock('../../step-1/IdentityForm', () => ({
  __esModule: true,
  default: () => <div data-testid="identity-form">IdentityForm stub</div>,
}));

// ── Silence deep-linked form mocks that render on other steps ────────────────
vi.mock('../../step-3/KybForm', () => ({
  __esModule: true,
  default: () => <div data-testid="kyb-form">KybForm stub</div>,
}));
vi.mock('../../step-4/BankAccountsSection', () => ({
  __esModule: true,
  default: () => <div data-testid="bank-accounts">BankAccountsSection stub</div>,
}));
vi.mock('../../step-4/SettlementPanel', () => ({
  __esModule: true,
  default: () => <div data-testid="settlement-panel">SettlementPanel stub</div>,
}));
vi.mock('../../step-5/PrefundingForm', () => ({
  __esModule: true,
  default: () => <div data-testid="prefunding-form">PrefundingForm stub</div>,
}));
vi.mock('../../step-6/page', () => ({
  __esModule: true,
  Step6CommercialForm: () => (
    <div data-testid="step6-commercial-form">Step6CommercialForm stub</div>
  ),
}));

import Step2Page from '../page';

/** A minimal PartnerView returned by getDraft. */
const DRAFT = {
  id: 1,
  partnerCode: 'GME_KR_001',
  status: 'ONBOARDING',
  type: 'OVERSEAS',
  settlementCurrency: 'USD',
  settlementRoundingMode: 'DOWN',
  legalNameRomanized: 'Acme Corp',
};

function buildStore() {
  return configureStore({
    reducer: {
      drafts: draftsReducer,
      auth: authReducer,
    },
  });
}

function renderPage() {
  const store = buildStore();
  return {
    store,
    ...render(
      <Provider store={store}>
        <ThemeProvider theme={theme}>
          <Step2Page />
        </ThemeProvider>
      </Provider>,
    ),
  };
}

describe('Step2Page', () => {
  beforeEach(() => {
    mockPush.mockReset();
    getDraftMock.mockReset();
    getPartnerContactsMock.mockReset();
    patchDraftStepMock.mockReset();
    // Default: getDraft and getPartnerContacts succeed with minimal data.
    getDraftMock.mockResolvedValue(DRAFT);
    getPartnerContactsMock.mockResolvedValue([]);
  });

  it('mounts ContactsForm with the correct partnerCode when activeStep=2', async () => {
    renderPage();

    // ContactsForm (mocked above) should appear once the wizard loads.
    const form = await screen.findByTestId('contacts-form');
    expect(form).toBeInTheDocument();
    expect(form).toHaveAttribute('data-partner-code', 'GME_KR_001');

    // The wizard also fetches the draft and the contacts list on mount.
    await waitFor(() => {
      expect(getDraftMock).toHaveBeenCalledWith('GME_KR_001');
      expect(getPartnerContactsMock).toHaveBeenCalledWith('GME_KR_001');
    });
  });

  it('Back button decrements cursor to step 1, showing IdentityForm', async () => {
    const user = userEvent.setup();
    renderPage();

    // Wait for step 2 to be fully mounted.
    await screen.findByTestId('contacts-form');

    // Click Back — cursor should drop to step 1.
    await user.click(screen.getByRole('button', { name: /back/i }));

    // IdentityForm stub should now be visible instead of ContactsForm.
    await waitFor(() => {
      expect(screen.getByTestId('identity-form')).toBeInTheDocument();
    });
    expect(screen.queryByTestId('contacts-form')).not.toBeInTheDocument();
  });
});
