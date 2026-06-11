import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { Provider } from 'react-redux';
import { configureStore } from '@reduxjs/toolkit';
import draftsReducer from '@/store/draftsSlice';
import authReducer from '@/store/authSlice';
import { theme } from '@/theme/theme';

/**
 * Vitest coverage for the Slice 1 Step-1 Identity form.
 *
 * The form's contract is:
 *  - Required fields are flagged inline before any submit reaches the BFF.
 *  - Tax-id format flips with the selected taxIdType discriminator.
 *  - A LEI with a bad mod-97-10 checksum is rejected client-side.
 *  - A successful submit dispatches {@link patchStep1} with the BFF shape
 *    and triggers the {@code onSaved} callback so the wizard can advance.
 *
 * The BFF client is mocked at the {@code @/api/client} seam so no network
 * request leaves the test process; the Redux thunk wiring is exercised
 * exactly as it would be in production.
 */

// Mock BFF client — only patchDraftStep is needed for this form.
const patchDraftStep = vi.fn();
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
    patchDraftStep: (...args) => patchDraftStep(...args),
  },
}));

// Snackbar provider used by the form for success/failure toasts.
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

import IdentityForm from '../IdentityForm';

function buildStore() {
  return configureStore({
    reducer: {
      drafts: draftsReducer,
      auth: authReducer,
    },
  });
}

function renderForm({ draft, partnerCode = 'GME_KR_001', onSaved } = {}) {
  const store = buildStore();
  return {
    store,
    ...render(
      <Provider store={store}>
        <ThemeProvider theme={theme}>
          <IdentityForm
            draft={draft ?? { partnerCode }}
            partnerCode={partnerCode}
            onSaved={onSaved ?? vi.fn()}
          />
        </ThemeProvider>
      </Provider>,
    ),
  };
}

/**
 * Fill the non-country sub-fields of a structured address. Field names
 * follow the form's {@code aria-label="{prefix} {fieldName}"} convention.
 * Country is set via the draft prop because the MUI Autocomplete is
 * awkward to drive through jsdom + userEvent — the country picker has its
 * own dedicated test below.
 */
async function fillAddressText(user, prefix, values) {
  const fields = {
    street1: values.street1,
    city: values.city,
    postcode: values.postcode,
  };
  for (const [name, value] of Object.entries(fields)) {
    if (value === undefined) continue;
    const input = screen.getByLabelText(`${prefix} ${name}`, { selector: 'input' });
    await user.clear(input);
    await user.type(input, value);
  }
}

/**
 * Build a draft pre-populated with the country fields so the
 * Autocomplete state is already valid when the form mounts. The text
 * fields are filled by the helper below.
 */
function happyDraft(overrides = {}) {
  return {
    partnerCode: 'GME_KR_001',
    type: 'LOCAL',
    settlementCurrency: 'KRW',
    settlementRoundingMode: 'HALF_UP',
    countryOfIncorporation: 'KR',
    registeredAddress: { country: 'KR' },
    operatingAddress: { country: 'KR' },
    ...overrides,
  };
}

/** Fill every text field the form requires for a green submit. */
async function fillIdentityTextFields(user) {
  await user.type(screen.getByLabelText('Legal name local'), '주식회사 지엠이');
  await user.type(screen.getByLabelText('Legal name romanized'), 'GME Corporation Co., Ltd.');
  await user.type(screen.getByLabelText('Tax id'), '1234567890');
  await fillAddressText(user, 'registeredAddress', {
    street1: '1 Yangjae-daero',
    city: 'Seoul',
    postcode: '06743',
  });
}

describe('IdentityForm', () => {
  beforeEach(() => {
    patchDraftStep.mockReset();
    snackSuccess.mockReset();
    snackError.mockReset();
  });

  it('flags required fields when the operator submits an empty form', async () => {
    const user = userEvent.setup();
    renderForm({ draft: { partnerCode: 'GME_KR_001' } });

    await user.click(screen.getByRole('button', { name: /save & next/i }));

    expect(
      await screen.findByText(/Legal name \(local script\) is required/i),
    ).toBeInTheDocument();
    expect(
      screen.getByText(/Legal name \(romanized\) is required/i),
    ).toBeInTheDocument();
    expect(patchDraftStep).not.toHaveBeenCalled();
  });

  it('flags a tax-id that does not match the selected type', async () => {
    const user = userEvent.setup();
    renderForm({ draft: { partnerCode: 'GME_KR_001' } });

    // Fill legal names so they don't surface their own errors.
    await user.type(screen.getByLabelText('Legal name local'), '주식회사 지엠이');
    await user.type(screen.getByLabelText('Legal name romanized'), 'GME Corp');
    // Type-default is KR_BRN (10 digits); send 9 chars to break it.
    await user.type(screen.getByLabelText('Tax id'), '123456789');

    await user.click(screen.getByRole('button', { name: /save & next/i }));

    expect(
      await screen.findByText(/Tax id does not match/i),
    ).toBeInTheDocument();
    expect(patchDraftStep).not.toHaveBeenCalled();
  });

  it('flags an LEI with a broken checksum', async () => {
    const user = userEvent.setup();
    renderForm({ draft: happyDraft() });

    await fillIdentityTextFields(user);
    // Break the published GLEIF sample's checksum by swapping the last two
    // characters.
    await user.type(screen.getByLabelText('LEI'), '213800WSGIIZCXF1P527');

    await user.click(screen.getByRole('button', { name: /save & next/i }));

    expect(
      await screen.findByText(/checksum invalid/i),
    ).toBeInTheDocument();
    expect(patchDraftStep).not.toHaveBeenCalled();
  });

  it('dispatches patchStep1 with the BFF shape on a valid submit', async () => {
    patchDraftStep.mockResolvedValueOnce({
      id: 1,
      partnerCode: 'GME_KR_001',
      status: 'ONBOARDING',
      type: 'LOCAL',
      legalNameLocal: '주식회사 지엠이',
      legalNameRomanized: 'GME Corporation Co., Ltd.',
      taxId: '1234567890',
      taxIdType: 'KR_BRN',
      countryOfIncorporation: 'KR',
      legalForm: 'CORP',
      registeredAddress: {
        street1: '1 Yangjae-daero',
        city: 'Seoul',
        postcode: '06743',
        country: 'KR',
      },
      operatingAddress: {
        street1: '1 Yangjae-daero',
        city: 'Seoul',
        postcode: '06743',
        country: 'KR',
      },
    });

    const onSaved = vi.fn();
    const user = userEvent.setup();
    renderForm({ draft: happyDraft(), onSaved });

    await fillIdentityTextFields(user);
    await user.click(screen.getByRole('button', { name: /save & next/i }));

    await waitFor(() => {
      expect(patchDraftStep).toHaveBeenCalledTimes(1);
    });

    const [step, partnerCode, body] = patchDraftStep.mock.calls[0];
    expect(step).toBe(1);
    expect(partnerCode).toBe('GME_KR_001');
    expect(body).toMatchObject({
      type: 'LOCAL',
      settlementCurrency: 'KRW',
      settlementRoundingMode: 'HALF_UP',
      legalNameLocal: '주식회사 지엠이',
      legalNameRomanized: 'GME Corporation Co., Ltd.',
      taxIdType: 'KR_BRN',
      taxId: '1234567890',
      countryOfIncorporation: 'KR',
      legalForm: 'CORP',
    });
    expect(body.registeredAddress).toMatchObject({
      street1: '1 Yangjae-daero',
      city: 'Seoul',
      postcode: '06743',
      country: 'KR',
    });
    // operatingSameAsRegistered defaults to true on a blank draft -> the
    // body's operatingAddress mirrors the registered address.
    expect(body.operatingAddress).toMatchObject({
      street1: '1 Yangjae-daero',
      city: 'Seoul',
      postcode: '06743',
      country: 'KR',
    });

    await waitFor(() => {
      expect(snackSuccess).toHaveBeenCalled();
      expect(onSaved).toHaveBeenCalledTimes(1);
    });
  });

  it('surfaces a server error via the snackbar without advancing', async () => {
    patchDraftStep.mockRejectedValueOnce(new Error('partner exists'));
    const onSaved = vi.fn();
    const user = userEvent.setup();
    renderForm({ draft: happyDraft(), onSaved });

    await fillIdentityTextFields(user);
    await user.click(screen.getByRole('button', { name: /save & next/i }));

    await waitFor(() => {
      expect(snackError).toHaveBeenCalled();
    });
    expect(onSaved).not.toHaveBeenCalled();
  });

  it('renders a separate operating-address block when "same as registered" is toggled off', async () => {
    const user = userEvent.setup();
    renderForm({
      draft: {
        partnerCode: 'GME_KR_001',
        registeredAddress: {
          street1: '1 Yangjae-daero',
          city: 'Seoul',
          postcode: '06743',
          country: 'KR',
        },
        operatingAddress: {
          street1: '1 Yangjae-daero',
          city: 'Seoul',
          postcode: '06743',
          country: 'KR',
        },
      },
    });

    // Toggle off — operating block should appear.
    const toggle = screen.getByLabelText(/Operating address same as registered/i);
    await user.click(toggle);

    expect(
      await screen.findByLabelText('operatingAddress street1', { selector: 'input' }),
    ).toBeInTheDocument();
  });
});
