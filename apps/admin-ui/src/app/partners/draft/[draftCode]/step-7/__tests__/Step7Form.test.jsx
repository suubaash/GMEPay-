/**
 * Vitest coverage for Step7Form (Slice 7).
 *
 * Contract:
 *  - Renders step-7-schemes-form aria-label.
 *  - Save & next button has aria-label "save-step-7-schemes".
 *  - On mount dispatches fetchPartnerSchemes + fetchPartnerCorridors.
 *  - On submit dispatches updateStep7Schemes then updateStep7Corridors.
 *  - Calls onSaved on success.
 *  - Surfaces snackbar error without calling onSaved on schemes save failure.
 *  - Pre-populates from saved state when available in store.
 */
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { Provider } from 'react-redux';
import { configureStore } from '@reduxjs/toolkit';
import draftsReducer from '@/store/draftsSlice';
import authReducer from '@/store/authSlice';
import partnerSchemesReducer from '@/store/partnerSchemesSlice';
import { theme } from '@/theme/theme';

// ── Mock BFF client ────────────────────────────────────────────────────────────
const listPartnerSchemesMock = vi.fn();
const listPartnerCorridorsMock = vi.fn();
const patchDraftStep7SchemesMock = vi.fn();
const patchDraftStep7CorridorsMock = vi.fn();
const listSchemeOperatingHoursMock = vi.fn();

vi.mock('@/api/client', () => ({
  __esModule: true,
  ApiError: class ApiError extends Error {
    constructor(status, url, message) {
      super(message);
      this.status = status;
    }
  },
  adminApi: {
    listPartnerSchemes: (...a) => listPartnerSchemesMock(...a),
    listPartnerCorridors: (...a) => listPartnerCorridorsMock(...a),
    patchDraftStep7Schemes: (...a) => patchDraftStep7SchemesMock(...a),
    patchDraftStep7Corridors: (...a) => patchDraftStep7CorridorsMock(...a),
    listSchemeOperatingHours: (...a) => listSchemeOperatingHoursMock(...a),
  },
}));

// ── Snackbar mock ──────────────────────────────────────────────────────────────
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

import { Step7Form } from '../Step7Form';

const PARTNER_CODE = 'GME_KR_TEST_7';

function buildStore(preload) {
  return configureStore({
    reducer: {
      drafts: draftsReducer,
      auth: authReducer,
      partnerSchemes: partnerSchemesReducer,
    },
    preloadedState: preload,
  });
}

function renderForm({ onSaved, storePreload } = {}) {
  const store = buildStore(storePreload);
  return {
    store,
    ...render(
      <Provider store={store}>
        <ThemeProvider theme={theme}>
          <Step7Form
            draft={{ partnerCode: PARTNER_CODE }}
            partnerCode={PARTNER_CODE}
            onSaved={onSaved ?? vi.fn()}
          />
        </ThemeProvider>
      </Provider>,
    ),
  };
}

describe('Step7Form', () => {
  beforeEach(() => {
    listPartnerSchemesMock.mockReset();
    listPartnerCorridorsMock.mockReset();
    patchDraftStep7SchemesMock.mockReset();
    patchDraftStep7CorridorsMock.mockReset();
    listSchemeOperatingHoursMock.mockReset();
    snackSuccess.mockReset();
    snackError.mockReset();

    listPartnerSchemesMock.mockRejectedValue(new Error('not found'));
    listPartnerCorridorsMock.mockRejectedValue(new Error('not found'));
    listSchemeOperatingHoursMock.mockResolvedValue([]);
  });

  it('renders form with aria-label step-7-schemes-form', () => {
    renderForm();
    expect(screen.getByLabelText('step-7-schemes-form')).toBeInTheDocument();
  });

  it('renders save button with aria-label save-step-7-schemes', () => {
    renderForm();
    expect(screen.getByLabelText('save-step-7-schemes')).toBeInTheDocument();
  });

  it('fetches partner schemes on mount', async () => {
    renderForm();
    await waitFor(() => {
      expect(listPartnerSchemesMock).toHaveBeenCalledWith(PARTNER_CODE);
    });
  });

  it('fetches partner corridors on mount', async () => {
    renderForm();
    await waitFor(() => {
      expect(listPartnerCorridorsMock).toHaveBeenCalledWith(PARTNER_CODE);
    });
  });

  it('dispatches updateStep7Schemes on valid submit', async () => {
    patchDraftStep7SchemesMock.mockResolvedValue({ partnerCode: PARTNER_CODE });
    patchDraftStep7CorridorsMock.mockResolvedValue({ partnerCode: PARTNER_CODE });

    const onSaved = vi.fn();
    renderForm({ onSaved });

    const user = userEvent.setup();
    await user.click(screen.getByLabelText('save-step-7-schemes'));

    await waitFor(() => {
      expect(patchDraftStep7SchemesMock).toHaveBeenCalledTimes(1);
    });
    const [code, body] = patchDraftStep7SchemesMock.mock.calls[0];
    expect(code).toBe(PARTNER_CODE);
    expect(body).toHaveProperty('schemes');
    expect(Array.isArray(body.schemes)).toBe(true);
    expect(body.schemes.length).toBe(7); // one row per SCHEME_IDS
  });

  it('dispatches updateStep7Corridors on valid submit', async () => {
    patchDraftStep7SchemesMock.mockResolvedValue({ partnerCode: PARTNER_CODE });
    patchDraftStep7CorridorsMock.mockResolvedValue({ partnerCode: PARTNER_CODE });

    renderForm();
    const user = userEvent.setup();
    await user.click(screen.getByLabelText('save-step-7-schemes'));

    await waitFor(() => {
      expect(patchDraftStep7CorridorsMock).toHaveBeenCalledTimes(1);
    });
  });

  it('calls onSaved after successful submit', async () => {
    patchDraftStep7SchemesMock.mockResolvedValue({ partnerCode: PARTNER_CODE });
    patchDraftStep7CorridorsMock.mockResolvedValue({ partnerCode: PARTNER_CODE });

    const onSaved = vi.fn();
    renderForm({ onSaved });

    const user = userEvent.setup();
    await user.click(screen.getByLabelText('save-step-7-schemes'));

    await waitFor(() => {
      expect(onSaved).toHaveBeenCalledTimes(1);
    });
    expect(snackSuccess).toHaveBeenCalled();
  });

  it('shows error snackbar without calling onSaved when schemes patch fails', async () => {
    patchDraftStep7SchemesMock.mockRejectedValue(new Error('BFF 500'));

    const onSaved = vi.fn();
    renderForm({ onSaved });

    const user = userEvent.setup();
    await user.click(screen.getByLabelText('save-step-7-schemes'));

    await waitFor(() => {
      expect(snackError).toHaveBeenCalled();
    });
    expect(onSaved).not.toHaveBeenCalled();
    expect(patchDraftStep7CorridorsMock).not.toHaveBeenCalled();
  });

  it('pre-populates schemes from store when available', () => {
    const preload = {
      partnerSchemes: {
        schemesByCode: {
          [PARTNER_CODE]: [
            {
              schemeId: 'ZEROPAY',
              enabled: true,
              direction: 'BOTH',
              role: 'ISSUER',
              zeropayMerchantId: 'M123',
              kftcInstitutionCode: 'K001',
            },
          ],
        },
        corridorsByCode: {},
        loadingByCode: {},
        saving: false,
        error: null,
      },
    };
    renderForm({ storePreload: preload });
    // ZEROPAY switch (index 0) should be checked
    const enableSwitch = screen.getByLabelText('schemes.0.enabled');
    expect(enableSwitch).toBeChecked();
  });
});
