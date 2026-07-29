import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Provider as ReduxProvider } from 'react-redux';
import { configureStore } from '@reduxjs/toolkit';

import authReducer from '@/store/authSlice';
import { SnackbarProvider } from '@/components/SnackbarProvider';

/**
 * T1-2: the login page is Keycloak SSO ONLY.
 *
 * The Phase-1 `partnerId` + `password` form is gone: it POSTed to
 * `POST /v1/auth/login`, which was deleted from ops-partner-bff (T0-1). These
 * tests lock that in — a password field reappearing here is a regression, not a
 * convenience.
 */
const replaceMock = vi.fn();
vi.mock('next/navigation', () => ({
  useRouter: () => ({ replace: replaceMock, push: vi.fn(), prefetch: vi.fn() }),
  usePathname: () => '/login'
}));

const startLoginMock = vi.fn();
vi.mock('@/api/oidc', () => ({
  startLogin: (...args) => startLoginMock(...args),
  isDevLoginAllowed: () => true,
  decodeJwtPayload: () => null
}));

vi.mock('@/api/auth', () => ({
  TOKEN_KEY: 'gmepay.partnerToken',
  PARTNER_ID_KEY: 'gmepay.partnerId',
  EXPIRES_AT_KEY: 'gmepay.partnerTokenExpiresAt',
  getToken: () => null,
  getPartnerId: () => null,
  partnerIdFromTokens: () => null,
  isAuthenticated: () => false,
  isPartnerScopeMissing: () => false,
  storeOidcSession: vi.fn(),
  clearAuth: vi.fn(),
  logout: vi.fn()
}));

import LoginPage from '../page';

function renderLogin() {
  const store = configureStore({ reducer: { auth: authReducer } });
  return render(
    <ReduxProvider store={store}>
      <SnackbarProvider>
        <LoginPage />
      </SnackbarProvider>
    </ReduxProvider>
  );
}

describe('LoginPage', () => {
  beforeEach(() => {
    startLoginMock.mockReset().mockResolvedValue('https://kc.example.com/auth');
    replaceMock.mockReset();
  });

  it('renders a single Keycloak SSO affordance', () => {
    renderLogin();
    expect(screen.getByTestId('login-sso')).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: /sign in with keycloak/i })
    ).toBeInTheDocument();
  });

  it('offers NO password path (the BFF endpoint no longer exists)', () => {
    const { container } = renderLogin();
    expect(container.querySelector('input[type="password"]')).toBeNull();
    expect(container.querySelector('input')).toBeNull();
    expect(screen.queryByTestId('password-input')).not.toBeInTheDocument();
    expect(screen.queryByTestId('partner-id-input')).not.toBeInTheDocument();
    expect(screen.queryByText(/demo credentials/i)).not.toBeInTheDocument();
  });

  it('starts the OIDC redirect on click', async () => {
    renderLogin();
    const user = userEvent.setup();
    await user.click(screen.getByTestId('login-sso'));
    await waitFor(() => expect(startLoginMock).toHaveBeenCalledTimes(1));
    expect(startLoginMock).toHaveBeenCalledWith('/');
  });

  it('surfaces a redirect failure instead of spinning forever', async () => {
    startLoginMock.mockRejectedValueOnce(new Error('sessionStorage disabled'));
    renderLogin();
    const user = userEvent.setup();
    await user.click(screen.getByTestId('login-sso'));
    await waitFor(() =>
      expect(screen.getByTestId('login-error')).toHaveTextContent(
        /sessionStorage disabled/i
      )
    );
    expect(screen.getByTestId('login-sso')).not.toBeDisabled();
  });
});
