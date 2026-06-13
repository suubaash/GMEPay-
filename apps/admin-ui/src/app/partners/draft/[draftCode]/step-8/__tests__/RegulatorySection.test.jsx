/**
 * Vitest coverage for RegulatorySection (Slice 8).
 *
 * Contract:
 *  - Renders regulatory-section aria-label.
 *  - BOK fields: bok-txn-code input, bok-fx-reporting-category select, bok-remitter-type select.
 *  - Hometax fields: hometax-issuer-cert-id input, vat-treatment select.
 *  - KoFIU fields: kofiu-entity-id input, ctr-threshold-krw (string, not Number).
 *  - PIPA: pipa-jurisdiction-allowlist multi-select.
 *  - Travel Rule: travel-rule-protocol select, endpoint URL, threshold (string).
 *  - Save dispatches patchStep8Regulatory with correct body.
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

const patchRegMock = vi.fn();

vi.mock('@/api/client', () => ({
  __esModule: true,
  ApiError: class ApiError extends Error {
    constructor(status, url, msg) { super(msg); this.status = status; }
  },
  adminApi: {
    patchDraftStep8Regulatory: (...a) => patchRegMock(...a),
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

import { RegulatorySection } from '../RegulatorySection';

const PARTNER_CODE = 'GME_KR_TEST_REG';

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
          <RegulatorySection draft={draft} partnerCode={PARTNER_CODE} />
        </ThemeProvider>
      </Provider>,
    ),
  };
}

describe('RegulatorySection', () => {
  beforeEach(() => {
    patchRegMock.mockReset();
    snackSuccess.mockReset();
    snackError.mockReset();
  });

  it('renders regulatory-section aria-label', () => {
    renderSection();
    expect(screen.getByLabelText('regulatory-section')).toBeInTheDocument();
  });

  it('renders BOK txn code input', () => {
    renderSection();
    expect(screen.getByLabelText('bok-txn-code')).toBeInTheDocument();
  });

  it('renders KoFIU entity ID input', () => {
    renderSection();
    expect(screen.getByLabelText('kofiu-entity-id')).toBeInTheDocument();
  });

  it('renders ctr-threshold-krw as a text input (not number)', () => {
    renderSection();
    const input = screen.getByLabelText('ctr-threshold-krw');
    expect(input).toBeInTheDocument();
    // Must NOT be type=number to prevent float coercion
    expect(input).not.toHaveAttribute('type', 'number');
  });

  it('renders travel-rule-threshold-krw as a text input (not number)', () => {
    renderSection();
    const input = screen.getByLabelText('travel-rule-threshold-krw');
    expect(input).toBeInTheDocument();
    expect(input).not.toHaveAttribute('type', 'number');
  });

  it('Save dispatches patchStep8Regulatory with correct body', async () => {
    patchRegMock.mockResolvedValue({ partnerCode: PARTNER_CODE });
    renderSection();

    const user = userEvent.setup();
    await user.type(screen.getByLabelText('bok-txn-code'), 'TXN123');
    await user.type(screen.getByLabelText('kofiu-entity-id'), 'KFU-001');
    await user.type(screen.getByLabelText('ctr-threshold-krw'), '10000000');
    await user.click(screen.getByLabelText('save-regulatory'));

    await waitFor(() => {
      expect(patchRegMock).toHaveBeenCalledWith(
        PARTNER_CODE,
        expect.objectContaining({
          bok: expect.objectContaining({ txnCode: 'TXN123' }),
          kofiu: expect.objectContaining({
            kofiuEntityId: 'KFU-001',
            // Must be a string, not a Number
            ctrThresholdKrw: '10000000',
          }),
        }),
      );
    });
    // Verify ctrThresholdKrw is NOT cast to a Number
    const callBody = patchRegMock.mock.calls[0][1];
    expect(typeof callBody.kofiu.ctrThresholdKrw).toBe('string');
    expect(snackSuccess).toHaveBeenCalled();
  });

  it('shows error snackbar on save failure', async () => {
    patchRegMock.mockRejectedValue(new Error('Save failed'));
    renderSection();
    const user = userEvent.setup();
    await user.click(screen.getByLabelText('save-regulatory'));
    await waitFor(() => expect(snackError).toHaveBeenCalled());
  });
});
