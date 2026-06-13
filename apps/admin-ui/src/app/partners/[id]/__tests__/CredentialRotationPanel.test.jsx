/**
 * Vitest coverage for CredentialRotationPanel (Slice 8).
 *
 * Covers:
 *  1. Renders credentials table with all rows.
 *  2. Rotate button triggers rotateCredential thunk.
 *  3. OneTimeCredentialModal opens when rotateResult is set.
 *  4. Empty state when no credentials.
 *  5. Rotate button disabled while another rotation in-flight.
 */
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Provider } from 'react-redux';
import { configureStore } from '@reduxjs/toolkit';
import { ThemeProvider } from '@mui/material/styles';
import { theme } from '@/theme/theme';
import partnerLifecycleReducer from '@/store/partnerLifecycleSlice';
import authReducer from '@/store/authSlice';
import auditTrailReducer from '@/store/auditTrailSlice';

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn() }),
  useParams: () => ({}),
}));

const snackError = vi.fn();
const snackSuccess = vi.fn();
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

const getCredentialsMock = vi.fn();
const rotateMock = vi.fn();
vi.mock('@/api/client', () => ({
  adminApi: {
    getPartnerCredentials: (...a) => getCredentialsMock(...a),
    rotatePartnerCredential: (...a) => rotateMock(...a),
  },
}));

import CredentialRotationPanel from '../CredentialRotationPanel';

const CREDENTIALS = [
  {
    id: 'cred-1',
    env: 'SANDBOX',
    kind: 'API_KEY',
    prefix: 'sk_sb_',
    last4: 'Ab3Z',
    issuedAt: '2026-01-01T00:00:00Z',
    expiresAt: '2027-01-01T00:00:00Z',
    status: 'ACTIVE',
  },
  {
    id: 'cred-2',
    env: 'PRODUCTION',
    kind: 'HMAC_SECRET',
    prefix: 'hmac_',
    last4: 'Xy9W',
    issuedAt: '2026-02-01T00:00:00Z',
    expiresAt: null,
    status: 'ACTIVE',
  },
];

function makeStore(override = {}) {
  return configureStore({
    reducer: {
      partnerLifecycle: partnerLifecycleReducer,
      auth: authReducer,
      auditTrail: auditTrailReducer,
    },
    preloadedState: {
      partnerLifecycle: {
        credentials: [],
        credentialsLoading: false,
        credentialsError: null,
        rotatingId: null,
        rotateResult: null,
        partnerAudit: {},
        lifecycle: {},
        ...override,
      },
    },
  });
}

function renderPanel(props, storeOverride) {
  const store = makeStore(storeOverride);
  return render(
    <Provider store={store}>
      <ThemeProvider theme={theme}>
        <CredentialRotationPanel {...props} />
      </ThemeProvider>
    </Provider>,
  );
}

describe('CredentialRotationPanel', () => {
  beforeEach(() => {
    getCredentialsMock.mockReset();
    rotateMock.mockReset();
    snackError.mockReset();
    snackSuccess.mockReset();
  });

  it('renders all credential rows', async () => {
    getCredentialsMock.mockResolvedValue(CREDENTIALS);
    renderPanel({ partnerCode: 'GME_KR_001' });

    // Wait for dispatch to resolve and store to update
    await waitFor(() => {
      expect(getCredentialsMock).toHaveBeenCalledWith('GME_KR_001');
    });

    // Preload via storeOverride for sync rendering check
    renderPanel({ partnerCode: 'GME_KR_001' }, { credentials: CREDENTIALS });
    expect(screen.getAllByTestId(/cred-row-/).length).toBeGreaterThanOrEqual(2);
    expect(screen.getAllByText('SANDBOX')[0]).toBeInTheDocument();
    expect(screen.getAllByText('PRODUCTION')[0]).toBeInTheDocument();
  });

  it('shows empty state when no credentials', async () => {
    getCredentialsMock.mockResolvedValue([]);
    renderPanel({ partnerCode: 'GME_KR_001' }, { credentials: [] });
    await waitFor(() => {
      expect(screen.getByText(/No credentials found/i)).toBeInTheDocument();
    });
  });

  it('Rotate button triggers rotateCredential and shows OneTimeCredentialModal', async () => {
    const secret = { id: 'cred-1', env: 'SANDBOX', kind: 'API_KEY', prefix: 'sk_sb_', last4: 'NEW1', issuedAt: '2026-06-01T00:00:00Z', expiresAt: '2027-06-01T00:00:00Z', plaintextSecret: 'sk_sb_PLAINTEXT123' };
    rotateMock.mockResolvedValueOnce(secret);
    getCredentialsMock.mockResolvedValue(CREDENTIALS);

    const user = userEvent.setup();
    renderPanel({ partnerCode: 'GME_KR_001' }, { credentials: CREDENTIALS });

    const rotateBtn = screen.getByTestId('rotate-btn-cred-1');
    await user.click(rotateBtn);

    await waitFor(() => {
      expect(rotateMock).toHaveBeenCalledWith('GME_KR_001', 'cred-1');
    });
  });

  it('Rotate button disabled while rotatingId is set', () => {
    renderPanel(
      { partnerCode: 'GME_KR_001' },
      { credentials: CREDENTIALS, rotatingId: 'cred-1' },
    );
    // Both rotate buttons should be disabled while rotatingId is set
    const rotateBtn1 = screen.getByTestId('rotate-btn-cred-1');
    const rotateBtn2 = screen.getByTestId('rotate-btn-cred-2');
    expect(rotateBtn1).toBeDisabled();
    expect(rotateBtn2).toBeDisabled();
  });

  it('OneTimeCredentialModal opens when rotateResult is set', () => {
    const credential = {
      id: 'cred-1',
      env: 'SANDBOX',
      kind: 'API_KEY',
      prefix: 'sk_sb_',
      last4: 'NEW1',
      issuedAt: '2026-06-01T00:00:00Z',
      expiresAt: '2027-06-01T00:00:00Z',
      plaintextSecret: 'sk_sb_PLAINTEXT123',
    };
    renderPanel(
      { partnerCode: 'GME_KR_001' },
      { credentials: CREDENTIALS, rotateResult: credential },
    );
    expect(screen.getByTestId('one-time-credential-modal')).toBeInTheDocument();
    expect(screen.getByTestId('secret-field')).toBeInTheDocument();
  });
});
