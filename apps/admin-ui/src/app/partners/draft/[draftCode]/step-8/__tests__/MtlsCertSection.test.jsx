/**
 * Vitest coverage for MtlsCertSection (Slice 8).
 *
 * Contract:
 *  - Renders mtls-cert-section aria-label.
 *  - PEM textarea is present (aria-label pem-textarea).
 *  - Upload button disabled when PEM is empty.
 *  - After pasting PEM + clicking Upload, dispatches patchStep8MtlsCert.
 *  - Parsed cert fields (subject DN, issuer DN, etc.) appear after successful upload.
 *  - Error alert shown on upload failure.
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

const patchMtlsCertMock = vi.fn();

vi.mock('@/api/client', () => ({
  __esModule: true,
  ApiError: class ApiError extends Error {
    constructor(status, url, msg) { super(msg); this.status = status; }
  },
  adminApi: {
    patchDraftStep8MtlsCert: (...a) => patchMtlsCertMock(...a),
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

import { MtlsCertSection } from '../MtlsCertSection';

const PARTNER_CODE = 'GME_KR_TEST_MTLS';
const FAKE_PEM = '-----BEGIN CERTIFICATE-----\nMIIBxxx\n-----END CERTIFICATE-----';
const FAKE_PARSED = {
  subjectDn: 'CN=partner.example.com',
  issuerDn: 'CN=GMEPay CA',
  notBefore: '2026-01-01',
  notAfter: '2027-01-01',
  fingerprint: 'AA:BB:CC:DD',
};

function buildStore(preload = {}) {
  return configureStore({
    reducer: { drafts: draftsReducer, auth: authReducer, lifecycle: lifecycleReducer },
    preloadedState: preload,
  });
}

function renderSection({ preload = {} } = {}) {
  const store = buildStore(preload);
  return {
    store,
    ...render(
      <Provider store={store}>
        <ThemeProvider theme={theme}>
          <MtlsCertSection partnerCode={PARTNER_CODE} />
        </ThemeProvider>
      </Provider>,
    ),
  };
}

describe('MtlsCertSection', () => {
  beforeEach(() => {
    patchMtlsCertMock.mockReset();
    snackSuccess.mockReset();
    snackError.mockReset();
  });

  it('renders mtls-cert-section aria-label', () => {
    renderSection();
    expect(screen.getByLabelText('mtls-cert-section')).toBeInTheDocument();
  });

  it('renders pem-textarea', () => {
    renderSection();
    expect(screen.getByLabelText('pem-textarea')).toBeInTheDocument();
  });

  it('upload button is disabled when PEM is empty', () => {
    renderSection();
    expect(screen.getByLabelText('upload-mtls-cert')).toBeDisabled();
  });

  it('upload button is enabled after pasting PEM', async () => {
    renderSection();
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('pem-textarea'), FAKE_PEM);
    expect(screen.getByLabelText('upload-mtls-cert')).not.toBeDisabled();
  });

  it('dispatches patchStep8MtlsCert on upload', async () => {
    patchMtlsCertMock.mockResolvedValue(FAKE_PARSED);
    renderSection();
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('pem-textarea'), FAKE_PEM);
    await user.click(screen.getByLabelText('upload-mtls-cert'));

    await waitFor(() => {
      expect(patchMtlsCertMock).toHaveBeenCalledWith(
        PARTNER_CODE,
        expect.objectContaining({ pemCertificate: expect.any(String) }),
      );
    });
    expect(snackSuccess).toHaveBeenCalled();
  });

  it('shows parsed certificate fields after successful upload', async () => {
    patchMtlsCertMock.mockResolvedValue(FAKE_PARSED);
    renderSection();
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('pem-textarea'), FAKE_PEM);
    await user.click(screen.getByLabelText('upload-mtls-cert'));

    await waitFor(() => {
      expect(screen.getByLabelText('parsed-subject-dn')).toHaveTextContent('CN=partner.example.com');
      expect(screen.getByLabelText('parsed-issuer-dn')).toHaveTextContent('CN=GMEPay CA');
      expect(screen.getByLabelText('parsed-fingerprint')).toHaveTextContent('AA:BB:CC:DD');
    });
  });

  it('shows upload error alert on failure', async () => {
    patchMtlsCertMock.mockRejectedValue(new Error('Invalid PEM'));
    renderSection();
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('pem-textarea'), FAKE_PEM);
    await user.click(screen.getByLabelText('upload-mtls-cert'));

    await waitFor(() => {
      expect(screen.getByLabelText('mtls-upload-error')).toBeInTheDocument();
    });
    expect(snackError).toHaveBeenCalled();
  });
});
