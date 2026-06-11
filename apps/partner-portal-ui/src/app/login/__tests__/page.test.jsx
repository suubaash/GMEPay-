import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Provider as ReduxProvider } from 'react-redux';
import { configureStore } from '@reduxjs/toolkit';

import authReducer from '@/store/authSlice';
import { SnackbarProvider } from '@/components/SnackbarProvider';

const replaceMock = vi.fn();
vi.mock('next/navigation', () => ({
  useRouter: () => ({ replace: replaceMock, push: vi.fn(), prefetch: vi.fn() }),
  usePathname: () => '/login'
}));

const loginMock = vi.fn();
vi.mock('@/api/auth', () => ({
  TOKEN_KEY: 'gmepay.partnerToken',
  PARTNER_ID_KEY: 'gmepay.partnerId',
  getToken: () => null,
  getPartnerId: () => null,
  isAuthenticated: () => false,
  login: (...args) => loginMock(...args),
  logout: vi.fn()
}));

vi.mock('@/api/client', () => ({
  portalApi: {
    login: (...args) => loginMock(...args)
  },
  currentPartnerId: () => ''
}));

import LoginPage from '../page';

function makeStore() {
  return configureStore({ reducer: { auth: authReducer } });
}

function renderLogin() {
  const store = makeStore();
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
    loginMock.mockReset();
    replaceMock.mockReset();
  });

  it('renders the partner-id and password fields', () => {
    renderLogin();
    expect(screen.getByTestId('partner-id-input')).toBeInTheDocument();
    expect(screen.getByTestId('password-input')).toBeInTheDocument();
    expect(screen.getByTestId('login-submit')).toBeInTheDocument();
  });

  it('shows validation errors when submitting an empty form', async () => {
    renderLogin();
    const user = userEvent.setup();
    await user.click(screen.getByTestId('login-submit'));

    await waitFor(() => {
      expect(screen.getByText(/Partner ID is required/i)).toBeInTheDocument();
      expect(screen.getByText(/Password is required/i)).toBeInTheDocument();
    });
    expect(loginMock).not.toHaveBeenCalled();
  });

  it('rejects partner ids that contain invalid characters', async () => {
    renderLogin();
    const user = userEvent.setup();
    await user.type(screen.getByTestId('partner-id-input'), 'invalid id!');
    await user.type(screen.getByTestId('password-input'), 'demopass');
    await user.click(screen.getByTestId('login-submit'));

    await waitFor(() => {
      expect(screen.getByText(/letters, digits, _ and -/i)).toBeInTheDocument();
    });
    expect(loginMock).not.toHaveBeenCalled();
  });

  it('calls login() with the form values on a valid submit', async () => {
    loginMock.mockResolvedValueOnce({
      token: 'tkn',
      partnerId: 'GMEREMIT',
      expiresAt: '2026-06-09T13:15:30Z',
      role: 'ADMIN'
    });
    renderLogin();
    const user = userEvent.setup();

    await user.type(screen.getByTestId('partner-id-input'), 'GMEREMIT');
    await user.type(screen.getByTestId('password-input'), 'demo');
    await user.click(screen.getByTestId('login-submit'));

    await waitFor(() => {
      expect(loginMock).toHaveBeenCalledTimes(1);
    });
    expect(loginMock).toHaveBeenCalledWith({
      partnerId: 'GMEREMIT',
      password: 'demo'
    });

    await waitFor(() => {
      expect(replaceMock).toHaveBeenCalledWith('/');
    });
  });

  it('surfaces the rejection error to the user', async () => {
    loginMock.mockRejectedValueOnce(new Error('Invalid partner id or password'));
    renderLogin();
    const user = userEvent.setup();

    await user.type(screen.getByTestId('partner-id-input'), 'GMEREMIT');
    await user.type(screen.getByTestId('password-input'), 'wrong');
    await user.click(screen.getByTestId('login-submit'));

    await waitFor(() => {
      expect(screen.getByTestId('login-error')).toHaveTextContent(
        /Invalid partner id or password/i
      );
    });
    expect(replaceMock).not.toHaveBeenCalledWith('/');
  });
});
