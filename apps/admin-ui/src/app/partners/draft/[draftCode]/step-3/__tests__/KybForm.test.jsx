import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { Provider } from 'react-redux';
import { configureStore } from '@reduxjs/toolkit';
import draftsReducer from '@/store/draftsSlice';
import kybReducer from '@/store/kybSlice';
import authReducer from '@/store/authSlice';
import { theme } from '@/theme/theme';

/**
 * Vitest coverage for the Slice 3 Step-3 KYB form.
 *
 * Contract:
 *  - Renders with an empty UBO row on a blank draft.
 *  - "Add UBO" appends a new row.
 *  - "Remove" button removes a UBO row.
 *  - Ownership sum > 100% shows a soft warning but does not block submit.
 *  - Required fields (licenseType, licenseNumber, licenseAuthority,
 *    licenseExpiry, uboList[0].name, ownershipPct, country, riskRating,
 *    riskRationale, nextReviewDate) are flagged before the BFF is called.
 *  - A valid submit dispatches patchStep3 with the correct BFF shape and
 *    triggers onSaved.
 *  - "Run screening" button dispatches runKybScreening and shows result chips.
 *  - CLEAR / NEEDS_REVIEW / HIT chips render with the correct aria-labels.
 */

// ── Mock BFF client ──────────────────────────────────────────────────────────
const patchDraftStepMock = vi.fn();
const getKybMock = vi.fn();
const runKybScreeningMock = vi.fn();

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
    getKyb: (...args) => getKybMock(...args),
    runKybScreening: (...args) => runKybScreeningMock(...args),
  },
}));

// ── Mock DocumentVault ────────────────────────────────────────────────────────
vi.mock('@/components/DocumentVault', () => ({
  __esModule: true,
  default: ({ docType }) => <div data-testid={`vault-${docType}`}>{docType} vault</div>,
}));

// ── Snackbar mock ─────────────────────────────────────────────────────────────
const snackSuccess = vi.fn();
const snackError = vi.fn();
const snackInfo = vi.fn();
vi.mock('@/components/SnackbarProvider', () => ({
  __esModule: true,
  default: ({ children }) => <>{children}</>,
  useSnackbar: () => ({
    success: snackSuccess,
    error: snackError,
    info: snackInfo,
    warning: vi.fn(),
  }),
}));

import KybForm from '../KybForm';

function buildStore(kybPreload) {
  return configureStore({
    reducer: {
      drafts: draftsReducer,
      kyb: kybReducer,
      auth: authReducer,
    },
    preloadedState: kybPreload
      ? { kyb: { kybByCode: kybPreload, kybLoading: false, kybError: null } }
      : undefined,
  });
}

function renderForm({ draft, partnerCode = 'GME_KR_001', onSaved, kybPreload } = {}) {
  const store = buildStore(kybPreload);
  return {
    store,
    ...render(
      <Provider store={store}>
        <ThemeProvider theme={theme}>
          <KybForm
            draft={draft ?? { partnerCode }}
            partnerCode={partnerCode}
            onSaved={onSaved ?? vi.fn()}
          />
        </ThemeProvider>
      </Provider>,
    ),
  };
}

/** A minimal valid KYB payload. */
function validPayload() {
  return {
    licenseType: 'Money Transfer Operator',
    licenseNumber: 'MTO-2024-001',
    licenseAuthority: 'FSC Korea',
    licenseExpiry: '2026-12-31',
    uboList: [{ name: 'Alice Owner', ownershipPct: 60, isPep: false, country: 'KR' }],
    riskRating: 'LOW',
    riskRationale: 'Regulated in Korea, clean compliance history.',
    nextReviewDate: '2027-01-01',
    cbddqDocId: null,
  };
}

/** Fill the first UBO row fields. */
async function fillUboRow(user, index, { name, pct, country } = {}) {
  if (name) {
    const nameInput = screen.getByLabelText(`uboList[${index}].name`, { selector: 'input' });
    await user.clear(nameInput);
    await user.type(nameInput, name);
  }
  if (pct !== undefined) {
    const pctInput = screen.getByLabelText(`uboList[${index}].ownershipPct`, { selector: 'input' });
    await user.clear(pctInput);
    await user.type(pctInput, String(pct));
  }
  if (country) {
    const countrySelect = screen.getByLabelText(`uboList[${index}].country`);
    await user.click(countrySelect);
    const option = await screen.findByRole('option', { name: new RegExp(country, 'i') });
    await user.click(option);
  }
}

describe('KybForm', () => {
  beforeEach(() => {
    patchDraftStepMock.mockReset();
    getKybMock.mockReset();
    runKybScreeningMock.mockReset();
    snackSuccess.mockReset();
    snackError.mockReset();
    snackInfo.mockReset();
    // Default: getKyb returns 404-like rejection (new draft, no KYB data yet).
    getKybMock.mockRejectedValue(new Error('Not found'));
  });

  it('renders with a single empty UBO row on a blank draft', () => {
    renderForm();
    expect(screen.getByLabelText('uboList[0].name', { selector: 'input' })).toBeInTheDocument();
    expect(screen.getByLabelText('uboList[0].ownershipPct', { selector: 'input' })).toBeInTheDocument();
  });

  it('adds a new UBO row when "Add UBO" is clicked', async () => {
    const user = userEvent.setup();
    renderForm();

    await user.click(screen.getByRole('button', { name: /Add UBO/i }));

    expect(screen.getByLabelText('uboList[1].name', { selector: 'input' })).toBeInTheDocument();
  });

  it('removes a UBO row when the remove button is clicked', async () => {
    const user = userEvent.setup();
    renderForm({
      kybPreload: {
        GME_KR_001: {
          partnerCode: 'GME_KR_001',
          uboList: [
            { name: 'Alice', ownershipPct: 50, isPep: false, country: 'KR' },
            { name: 'Bob', ownershipPct: 30, isPep: false, country: 'KR' },
          ],
        },
      },
    });

    expect(screen.getByLabelText('uboList[1].name', { selector: 'input' })).toBeInTheDocument();

    await user.click(screen.getByLabelText('remove-ubo-1'));

    await waitFor(() => {
      expect(screen.queryByLabelText('uboList[1].name', { selector: 'input' })).not.toBeInTheDocument();
    });
  });

  it('disables remove when only one UBO row remains', () => {
    renderForm();
    expect(screen.getByLabelText('remove-ubo-0')).toBeDisabled();
  });

  it('shows ownership-sum warning when sum exceeds 100%', async () => {
    const user = userEvent.setup();
    renderForm();

    // Add a second UBO row.
    await user.click(screen.getByRole('button', { name: /Add UBO/i }));

    // Fill row 0 with 60%.
    const pct0 = screen.getByLabelText('uboList[0].ownershipPct', { selector: 'input' });
    await user.clear(pct0);
    await user.type(pct0, '60');
    await user.tab();

    // Fill row 1 with 60% (total = 120% > 100).
    const pct1 = screen.getByLabelText('uboList[1].ownershipPct', { selector: 'input' });
    await user.clear(pct1);
    await user.type(pct1, '60');
    await user.tab();

    expect(await screen.findByLabelText('ownership-sum-warning')).toBeInTheDocument();
  });

  it('flags required fields on empty submit', async () => {
    const user = userEvent.setup();
    renderForm();

    await user.click(screen.getByRole('button', { name: /save & next/i }));

    expect(await screen.findByText(/License type is required/i)).toBeInTheDocument();
    expect(patchDraftStepMock).not.toHaveBeenCalled();
  });

  it('flags missing risk rating', async () => {
    const user = userEvent.setup();
    renderForm();

    // Fill license fields but leave riskRating empty.
    await user.type(screen.getByLabelText('licenseType', { selector: 'input' }), 'MTO');
    await user.type(screen.getByLabelText('licenseNumber', { selector: 'input' }), 'MTO-001');
    await user.type(screen.getByLabelText('licenseAuthority', { selector: 'input' }), 'FSC');
    await user.type(screen.getByLabelText('licenseExpiry', { selector: 'input' }), '2026-12-31');

    await user.click(screen.getByRole('button', { name: /save & next/i }));

    expect(await screen.findByText(/Select a risk rating/i)).toBeInTheDocument();
    expect(patchDraftStepMock).not.toHaveBeenCalled();
  });

  it('dispatches patchStep3 with correct BFF shape on valid submit', async () => {
    patchDraftStepMock.mockResolvedValueOnce({
      partnerCode: 'GME_KR_001',
      status: 'ONBOARDING',
    });

    const onSaved = vi.fn();
    const user = userEvent.setup();
    const payload = validPayload();

    // Pre-populate store so form initialises from KYB data.
    renderForm({
      onSaved,
      kybPreload: { GME_KR_001: { partnerCode: 'GME_KR_001', ...payload } },
    });

    await user.click(screen.getByRole('button', { name: /save & next/i }));

    await waitFor(() => {
      expect(patchDraftStepMock).toHaveBeenCalledTimes(1);
    });

    const [step, code, body] = patchDraftStepMock.mock.calls[0];
    expect(step).toBe(3);
    expect(code).toBe('GME_KR_001');
    expect(body).toMatchObject({
      riskRating: 'LOW',
      licenseType: 'Money Transfer Operator',
      licenseNumber: 'MTO-2024-001',
      uboList: [
        expect.objectContaining({
          name: 'Alice Owner',
          ownershipPct: 60,
          isPep: false,
          country: 'KR',
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
    const payload = validPayload();

    renderForm({
      onSaved,
      kybPreload: { GME_KR_001: { partnerCode: 'GME_KR_001', ...payload } },
    });

    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: /save & next/i }));

    await waitFor(() => {
      expect(snackError).toHaveBeenCalled();
    });
    expect(onSaved).not.toHaveBeenCalled();
  });

  it('dispatches runScreening when "Run screening" is clicked', async () => {
    runKybScreeningMock.mockResolvedValueOnce({
      partnerCode: 'GME_KR_001',
      screeningStatus: 'CLEAR',
      screenedAt: '2026-06-12T10:00:00Z',
      screeningHits: [],
    });

    const user = userEvent.setup();
    renderForm();

    await user.click(screen.getByLabelText('run-screening'));

    await waitFor(() => {
      expect(runKybScreeningMock).toHaveBeenCalledWith('GME_KR_001');
    });
  });

  it('renders CLEAR chip in green when screening status is CLEAR', () => {
    renderForm({
      kybPreload: {
        GME_KR_001: {
          partnerCode: 'GME_KR_001',
          screeningStatus: 'CLEAR',
          screenedAt: '2026-06-12T10:00:00Z',
          screeningHits: [],
        },
      },
    });

    expect(screen.getByLabelText('screening-status-CLEAR')).toBeInTheDocument();
  });

  it('renders NEEDS_REVIEW chip in amber', () => {
    renderForm({
      kybPreload: {
        GME_KR_001: {
          partnerCode: 'GME_KR_001',
          screeningStatus: 'NEEDS_REVIEW',
          screenedAt: '2026-06-12T10:00:00Z',
          screeningHits: [],
        },
      },
    });

    expect(screen.getByLabelText('screening-status-NEEDS_REVIEW')).toBeInTheDocument();
  });

  it('renders HIT chip in red with hits list', () => {
    renderForm({
      kybPreload: {
        GME_KR_001: {
          partnerCode: 'GME_KR_001',
          screeningStatus: 'HIT',
          screenedAt: '2026-06-12T10:00:00Z',
          screeningHits: [
            { name: 'Alice Suspect', matchScore: 95, matchType: 'EXACT', source: 'OFAC' },
          ],
        },
      },
    });

    expect(screen.getByLabelText('screening-status-HIT')).toBeInTheDocument();
    expect(screen.getByLabelText('screening-hits-list')).toBeInTheDocument();
    expect(screen.getByText('Alice Suspect')).toBeInTheDocument();
  });

  it('renders both DocumentVault sections', () => {
    renderForm();
    expect(screen.getByTestId('vault-License scan')).toBeInTheDocument();
    expect(screen.getByTestId('vault-CBDDQ')).toBeInTheDocument();
  });
});
