import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { Provider } from 'react-redux';
import { configureStore } from '@reduxjs/toolkit';
import draftsReducer from '@/store/draftsSlice';
import bankAccountsReducer from '@/store/bankAccountsSlice';
import authReducer from '@/store/authSlice';
import { theme } from '@/theme/theme';

/**
 * Vitest coverage for the Slice 4A.2 BankAccountsSection component.
 *
 * Contract:
 *  - Renders with a single empty account row on a blank draft.
 *  - "Add account" appends a new row.
 *  - "Remove" button removes a row; disabled when only one row remains.
 *  - BIC field: lowercase input is uppercased on change.
 *  - BIC validation: 7-char string is flagged before submit.
 *  - Required fields are flagged before the BFF is called.
 *  - A valid submit dispatches patchStep4 with the correct shape.
 *  - Verify button dispatches verifyBankAccount with correct accountId.
 *  - Verification chip renders with aria-label per row when status is present.
 *  - Primary-per-currency: two primaries for the same currency trigger an error.
 */

// ── Mock BFF client ──────────────────────────────────────────────────────────
const patchDraftStepMock = vi.fn();
const getBankAccountsMock = vi.fn();
const verifyBankAccountMock = vi.fn();

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
    patchDraftStep: (...args) => patchDraftStepMock(...args),
    getBankAccounts: (...args) => getBankAccountsMock(...args),
    verifyBankAccount: (...args) => verifyBankAccountMock(...args),
  },
}));

// ── Snackbar mock ─────────────────────────────────────────────────────────────
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

import BankAccountsSection from '../BankAccountsSection';

// ── Store builder ─────────────────────────────────────────────────────────────

function buildStore(bankAccountsPreload) {
  return configureStore({
    reducer: {
      drafts: draftsReducer,
      bankAccounts: bankAccountsReducer,
      auth: authReducer,
    },
    preloadedState: bankAccountsPreload
      ? { bankAccounts: bankAccountsPreload }
      : undefined,
  });
}

function renderSection({ partnerCode = 'GME_KR_001', onSaved, bankAccountsPreload } = {}) {
  const store = buildStore(bankAccountsPreload);
  return {
    store,
    ...render(
      <Provider store={store}>
        <ThemeProvider theme={theme}>
          <BankAccountsSection
            draft={{ partnerCode }}
            partnerCode={partnerCode}
            onSaved={onSaved ?? vi.fn()}
          />
        </ThemeProvider>
      </Provider>,
    ),
  };
}

// ── Fixtures ──────────────────────────────────────────────────────────────────

function savedAccount(overrides = {}) {
  return {
    id: 'ba-uuid-1',
    currency: 'USD',
    bankName: 'First National Bank',
    bicSwift: 'FNBKUSD1',
    ibanOrAccountNumber: '123456789',
    accountHolderName: 'GME Corp',
    bankCountry: 'US',
    intermediaryBic: null,
    swiftChargeBearer: 'SHA',
    purpose: 'PAYOUT',
    isPrimary: true,
    verificationStatus: 'UNVERIFIED',
    verificationDate: null,
    ...overrides,
  };
}

/**
 * Helper to render with a set of pre-saved accounts.
 * getBankAccountsMock is configured to return the same list so the mount
 * fetch does not clobber the preloaded Redux state.
 */
function renderWithAccounts(accounts, extra = {}) {
  getBankAccountsMock.mockResolvedValue(accounts);
  return renderSection({
    ...extra,
    bankAccountsPreload: {
      byCode: { GME_KR_001: accounts },
      loading: {},
      verifying: {},
      error: null,
    },
  });
}

// ── Tests ─────────────────────────────────────────────────────────────────────

describe('BankAccountsSection', () => {
  beforeEach(() => {
    patchDraftStepMock.mockReset();
    getBankAccountsMock.mockReset();
    verifyBankAccountMock.mockReset();
    snackSuccess.mockReset();
    snackError.mockReset();
    // Default: getBankAccounts returns empty (new draft, no accounts yet).
    getBankAccountsMock.mockResolvedValue([]);
  });

  it('renders with a single empty account row on a blank draft', () => {
    renderSection();
    expect(
      screen.getByLabelText('bankAccounts[0].currency', { selector: 'input' }),
    ).toBeInTheDocument();
    expect(
      screen.getByLabelText('bankAccounts[0].bankName', { selector: 'input' }),
    ).toBeInTheDocument();
  });

  it('adds a new row when "Add account" is clicked', async () => {
    const user = userEvent.setup();
    renderSection();

    await user.click(screen.getByRole('button', { name: /Add account/i }));

    expect(
      screen.getByLabelText('bankAccounts[1].currency', { selector: 'input' }),
    ).toBeInTheDocument();
  });

  it('removes a row when the remove button is clicked', async () => {
    const user = userEvent.setup();
    const accounts = [
      savedAccount({ id: 'ba-1' }),
      savedAccount({ id: 'ba-2', currency: 'KRW' }),
    ];
    renderWithAccounts(accounts);

    // Wait for the rows to appear after the fetch resolves.
    await screen.findByLabelText('bankAccounts[1].currency', { selector: 'input' });

    await user.click(screen.getByLabelText('remove-account-1'));

    await waitFor(() => {
      expect(
        screen.queryByLabelText('bankAccounts[1].currency', { selector: 'input' }),
      ).not.toBeInTheDocument();
    });
  });

  it('disables remove when only one row remains', () => {
    renderSection();
    expect(screen.getByLabelText('remove-account-0')).toBeDisabled();
  });

  it('uppercases BIC input on change', async () => {
    const user = userEvent.setup();
    renderSection();

    const bicInput = screen.getByLabelText('bankAccounts[0].bicSwift', { selector: 'input' });
    await user.clear(bicInput);
    await user.type(bicInput, 'fnbkusd1');

    // The controller uppercases on change — value should be FNBKUSD1.
    expect(bicInput.value).toBe('FNBKUSD1');
  });

  it('flags BIC validation error on submit with short BIC', async () => {
    const user = userEvent.setup();
    renderSection();

    // Fill minimum required fields but give an invalid (7-char) BIC.
    const currencyInput = screen.getByLabelText('bankAccounts[0].currency', { selector: 'input' });
    await user.clear(currencyInput);
    await user.type(currencyInput, 'USD');

    const bicInput = screen.getByLabelText('bankAccounts[0].bicSwift', { selector: 'input' });
    await user.clear(bicInput);
    await user.type(bicInput, 'FNBKUSD');

    await user.click(screen.getByRole('button', { name: /save & next/i }));

    expect(await screen.findByText(/BIC must be 8 or 11/i)).toBeInTheDocument();
    expect(patchDraftStepMock).not.toHaveBeenCalled();
  });

  it('flags required fields on empty submit', async () => {
    const user = userEvent.setup();
    renderSection();

    await user.click(screen.getByRole('button', { name: /save & next/i }));

    expect(await screen.findByText(/Currency is required/i)).toBeInTheDocument();
    expect(patchDraftStepMock).not.toHaveBeenCalled();
  });

  it('dispatches patchStep4 with correct BFF shape on valid submit', async () => {
    patchDraftStepMock.mockResolvedValueOnce({
      partnerCode: 'GME_KR_001',
      status: 'ONBOARDING',
    });

    const onSaved = vi.fn();
    const user = userEvent.setup();
    const account = savedAccount();
    renderWithAccounts([account], { onSaved });

    // Wait for the form to be populated from the fetched/preloaded account.
    await waitFor(() => {
      expect(
        screen.getByLabelText('bankAccounts[0].currency', { selector: 'input' }).value,
      ).toBe('USD');
    });

    await user.click(screen.getByRole('button', { name: /save & next/i }));

    await waitFor(() => {
      expect(patchDraftStepMock).toHaveBeenCalledTimes(1);
    });

    const [step, code, body] = patchDraftStepMock.mock.calls[0];
    expect(step).toBe(4);
    expect(code).toBe('GME_KR_001');
    expect(body).toMatchObject({
      bankAccounts: [
        expect.objectContaining({
          currency: 'USD',
          bankName: 'First National Bank',
          bicSwift: 'FNBKUSD1',
          ibanOrAccountNumber: '123456789',
          accountHolderName: 'GME Corp',
          bankCountry: 'US',
          swiftChargeBearer: 'SHA',
          purpose: 'PAYOUT',
          isPrimary: true,
        }),
      ],
    });

    await waitFor(() => {
      expect(snackSuccess).toHaveBeenCalled();
      expect(onSaved).toHaveBeenCalledTimes(1);
    });
  });

  it('surfaces a server error via snackbar without advancing', async () => {
    patchDraftStepMock.mockRejectedValueOnce(new Error('BFF error'));
    const onSaved = vi.fn();
    const account = savedAccount();
    renderWithAccounts([account], { onSaved });

    // Wait for the form to be populated.
    await waitFor(() => {
      expect(
        screen.getByLabelText('bankAccounts[0].currency', { selector: 'input' }).value,
      ).toBe('USD');
    });

    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: /save & next/i }));

    await waitFor(() => {
      expect(snackError).toHaveBeenCalled();
    });
    expect(onSaved).not.toHaveBeenCalled();
  });

  it('renders "Verify" button for a saved account row', async () => {
    renderWithAccounts([savedAccount()]);
    // The Verify button only appears after the fetch populates savedAccounts.
    expect(await screen.findByLabelText('verify-account-0')).toBeInTheDocument();
  });

  it('dispatches verifyBankAccount when "Verify" is clicked', async () => {
    const account = savedAccount();
    getBankAccountsMock.mockResolvedValue([account]);
    verifyBankAccountMock.mockResolvedValueOnce({
      ...account,
      verificationStatus: 'MICRO_DEPOSIT',
      verificationDate: '2026-06-12',
    });

    const user = userEvent.setup();
    renderWithAccounts([account]);

    // Wait for Verify button to appear.
    const verifyBtn = await screen.findByLabelText('verify-account-0');
    await user.click(verifyBtn);

    await waitFor(() => {
      expect(verifyBankAccountMock).toHaveBeenCalledWith('GME_KR_001', 'ba-uuid-1');
    });
  });

  it('renders KFTC_VERIFIED chip when verificationStatus is KFTC_VERIFIED', async () => {
    const account = savedAccount({
      verificationStatus: 'KFTC_VERIFIED',
      verificationDate: '2026-06-10',
    });
    renderWithAccounts([account]);

    expect(await screen.findByLabelText('verification-status-0')).toBeInTheDocument();
    expect(screen.getByText('KFTC Verified')).toBeInTheDocument();
  });

  it('renders BANK_LETTER chip for BANK_LETTER status', async () => {
    renderWithAccounts([savedAccount({ verificationStatus: 'BANK_LETTER' })]);
    expect(await screen.findByText('Bank Letter')).toBeInTheDocument();
  });

  it('renders MICRO_DEPOSIT chip for MICRO_DEPOSIT status', async () => {
    renderWithAccounts([savedAccount({ verificationStatus: 'MICRO_DEPOSIT' })]);
    expect(await screen.findByText('Micro-deposit')).toBeInTheDocument();
  });

  it('does not render the verify button for an unsaved (no id) row', () => {
    renderSection();
    // A fresh empty row has no accountId — no Verify button should appear.
    expect(screen.queryByLabelText('verify-account-0')).not.toBeInTheDocument();
  });

  it('primary-per-currency: two primaries for same currency triggers error', async () => {
    const user = userEvent.setup();
    const accounts = [
      savedAccount({ id: 'ba-1', currency: 'USD', isPrimary: true }),
      savedAccount({ id: 'ba-2', currency: 'USD', isPrimary: true }),
    ];
    renderWithAccounts(accounts);

    // Wait for both rows to load from the fetch.
    await screen.findByLabelText('bankAccounts[1].currency', { selector: 'input' });

    await user.click(screen.getByRole('button', { name: /save & next/i }));

    expect(
      await screen.findByText(/at most one primary/i),
    ).toBeInTheDocument();
    expect(patchDraftStepMock).not.toHaveBeenCalled();
  });
});
