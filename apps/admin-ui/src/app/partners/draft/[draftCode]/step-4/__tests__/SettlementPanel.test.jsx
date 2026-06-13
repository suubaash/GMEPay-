/**
 * Vitest coverage for the Slice 4B.2 SettlementPanel component.
 *
 * Contract:
 *  - Renders cycle select, cutoff time, timezone select, method select.
 *  - Fetches settlement config on mount; pre-populates form when data arrives.
 *  - Preview box shows "calculating" while previewLoading is true.
 *  - Preview box renders payoutDate and explanation trail on fulfilled.
 *  - Each explanation item renders with aria-label explanation-item-{n}.
 *  - Field change triggers debouncedRefresh -> fetchSettlementPreview dispatch.
 *  - Save dispatches patchDraftStep4Settlement with correct shape.
 *  - Server error surfaces via snackbar without calling onSaved.
 *  - onSaved is called with the result on success.
 */
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, act } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { Provider } from 'react-redux';
import { configureStore } from '@reduxjs/toolkit';
import draftsReducer from '@/store/draftsSlice';
import bankAccountsReducer from '@/store/bankAccountsSlice';
import settlementConfigReducer from '@/store/settlementConfigSlice';
import authReducer from '@/store/authSlice';
import { theme } from '@/theme/theme';

// ── Mock BFF client ───────────────────────────────────────────────────────────
const getSettlementConfigMock = vi.fn();
const getSettlementPreviewMock = vi.fn();
const patchDraftStep4SettlementMock = vi.fn();

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
    getSettlementConfig: (...args) => getSettlementConfigMock(...args),
    getSettlementPreview: (...args) => getSettlementPreviewMock(...args),
    patchDraftStep4Settlement: (...args) => patchDraftStep4SettlementMock(...args),
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

import SettlementPanel from '../SettlementPanel';

// ── Store builder ─────────────────────────────────────────────────────────────
function buildStore(settlementConfigPreload) {
  return configureStore({
    reducer: {
      drafts: draftsReducer,
      bankAccounts: bankAccountsReducer,
      settlementConfig: settlementConfigReducer,
      auth: authReducer,
    },
    preloadedState: settlementConfigPreload
      ? { settlementConfig: settlementConfigPreload }
      : undefined,
  });
}

const PARTNER_CODE = 'GME_KR_001';

function renderPanel({
  partnerCode = PARTNER_CODE,
  onSaved,
  settlementConfigPreload,
} = {}) {
  const store = buildStore(settlementConfigPreload);
  return {
    store,
    ...render(
      <Provider store={store}>
        <ThemeProvider theme={theme}>
          <SettlementPanel
            draft={{ partnerCode }}
            partnerCode={partnerCode}
            onSaved={onSaved ?? vi.fn()}
          />
        </ThemeProvider>
      </Provider>,
    ),
  };
}

const SAVED_CONFIG = {
  cycleTPlusN: 2,
  cutoffTime: '17:00',
  cutoffTimezone: 'Asia/Seoul',
  settlementMethod: 'SWIFT',
};

const PREVIEW = {
  payoutDate: '2026-06-15',
  explanation: [
    'Fri 2026-06-12: cutoff passed',
    'Sat 2026-06-13: skip — weekend',
    'Sun 2026-06-14: skip — weekend',
    'Mon 2026-06-15: payout date (T+2)',
  ],
};

// ── Tests ─────────────────────────────────────────────────────────────────────

describe('SettlementPanel', () => {
  beforeEach(() => {
    getSettlementConfigMock.mockReset();
    getSettlementPreviewMock.mockReset();
    patchDraftStep4SettlementMock.mockReset();
    snackSuccess.mockReset();
    snackError.mockReset();
    // Default: new draft with no saved config or preview.
    getSettlementConfigMock.mockRejectedValue(new Error('Not found'));
    getSettlementPreviewMock.mockResolvedValue(PREVIEW);
  });

  it('renders cycle select, cutoff time, timezone select, method select', () => {
    renderPanel();
    expect(screen.getByLabelText('cycleTPlusN')).toBeInTheDocument();
    expect(screen.getByLabelText('cutoffTime')).toBeInTheDocument();
    expect(screen.getByLabelText('cutoffTimezone')).toBeInTheDocument();
    expect(screen.getByLabelText('settlementMethod')).toBeInTheDocument();
  });

  it('renders the settlement panel aria-label wrapper', () => {
    renderPanel();
    expect(screen.getByRole('form', { name: 'settlement-panel' })).toBeInTheDocument();
  });

  it('renders the save button', () => {
    renderPanel();
    expect(screen.getByLabelText('save-settlement-config')).toBeInTheDocument();
  });

  it('pre-populates form fields when saved config is preloaded', async () => {
    getSettlementConfigMock.mockResolvedValue(SAVED_CONFIG);
    renderPanel({
      settlementConfigPreload: {
        configByCode: { [PARTNER_CODE]: SAVED_CONFIG },
        previewByCode: {},
        configLoading: {},
        previewLoading: {},
        patchSaving: false,
        error: null,
      },
    });

    // Cutoff time input should have the saved value.
    await waitFor(() => {
      expect(screen.getByLabelText('cutoffTime').value).toBe('17:00');
    });
  });

  it('renders the preview box', () => {
    renderPanel();
    expect(screen.getByLabelText('settlement-preview')).toBeInTheDocument();
  });

  it('renders preview payoutDate when preview is preloaded', () => {
    renderPanel({
      settlementConfigPreload: {
        configByCode: {},
        previewByCode: { [PARTNER_CODE]: PREVIEW },
        configLoading: {},
        previewLoading: {},
        patchSaving: false,
        error: null,
      },
    });
    const previewDateEl = screen.getByLabelText('preview-payout-date');
    expect(previewDateEl).toBeInTheDocument();
    // The payoutDate should appear inside the preview-payout-date element.
    expect(previewDateEl.textContent).toContain('2026-06-15');
  });

  it('renders explanation trail items from preview', () => {
    renderPanel({
      settlementConfigPreload: {
        configByCode: {},
        previewByCode: { [PARTNER_CODE]: PREVIEW },
        configLoading: {},
        previewLoading: {},
        patchSaving: false,
        error: null,
      },
    });
    expect(screen.getByLabelText('explanation-trail')).toBeInTheDocument();
    expect(screen.getByLabelText('explanation-item-0')).toBeInTheDocument();
    expect(screen.getByLabelText('explanation-item-3')).toBeInTheDocument();
    // The payout item text should be present inside explanation-item-3.
    const lastItem = screen.getByLabelText('explanation-item-3');
    expect(lastItem.textContent).toMatch(/payout date/i);
  });

  it('shows preview-loading indicator when previewLoading is true', () => {
    renderPanel({
      settlementConfigPreload: {
        configByCode: {},
        previewByCode: {},
        configLoading: {},
        previewLoading: { [PARTNER_CODE]: true },
        patchSaving: false,
        error: null,
      },
    });
    expect(screen.getByLabelText('preview-loading')).toBeInTheDocument();
    expect(screen.getByText(/Calculating/i)).toBeInTheDocument();
  });

  it('dispatches patchDraftStep4Settlement with correct shape on save', async () => {
    patchDraftStep4SettlementMock.mockResolvedValue({
      partnerCode: PARTNER_CODE,
      status: 'ONBOARDING',
    });
    const onSaved = vi.fn();

    renderPanel({
      onSaved,
      settlementConfigPreload: {
        configByCode: { [PARTNER_CODE]: SAVED_CONFIG },
        previewByCode: {},
        configLoading: {},
        previewLoading: {},
        patchSaving: false,
        error: null,
      },
    });

    // Wait for form to populate.
    await waitFor(() => {
      expect(screen.getByLabelText('cutoffTime').value).toBe('17:00');
    });

    const user = userEvent.setup();
    await user.click(screen.getByLabelText('save-settlement-config'));

    await waitFor(() => {
      expect(patchDraftStep4SettlementMock).toHaveBeenCalledTimes(1);
    });

    const [code, body] = patchDraftStep4SettlementMock.mock.calls[0];
    expect(code).toBe(PARTNER_CODE);
    expect(body).toMatchObject({
      cycleTPlusN: 2,
      cutoffTime: '17:00',
      cutoffTimezone: 'Asia/Seoul',
      settlementMethod: 'SWIFT',
    });

    await waitFor(() => {
      expect(snackSuccess).toHaveBeenCalled();
      expect(onSaved).toHaveBeenCalledTimes(1);
    });
  });

  it('surfaces server error via snackbar without calling onSaved', async () => {
    patchDraftStep4SettlementMock.mockRejectedValue(new Error('BFF error'));
    const onSaved = vi.fn();

    renderPanel({
      onSaved,
      settlementConfigPreload: {
        configByCode: { [PARTNER_CODE]: SAVED_CONFIG },
        previewByCode: {},
        configLoading: {},
        previewLoading: {},
        patchSaving: false,
        error: null,
      },
    });

    await waitFor(() => {
      expect(screen.getByLabelText('cutoffTime').value).toBe('17:00');
    });

    const user = userEvent.setup();
    await user.click(screen.getByLabelText('save-settlement-config'));

    await waitFor(() => {
      expect(snackError).toHaveBeenCalled();
    });
    expect(onSaved).not.toHaveBeenCalled();
  });

  it('fetches settlement config on mount', async () => {
    getSettlementConfigMock.mockResolvedValue(SAVED_CONFIG);
    renderPanel();

    await waitFor(() => {
      expect(getSettlementConfigMock).toHaveBeenCalledWith(PARTNER_CODE);
    });
  });
});
