import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import React from 'react';

// ── Mocks ─────────────────────────────────────────────────────────────────

// next/navigation
const mockReplace = vi.fn();
let mockPathname = '/balance';

vi.mock('next/navigation', () => ({
  useRouter: () => ({ replace: mockReplace }),
  usePathname: () => mockPathname,
}));

// OIDC module — prevent real Keycloak navigations.
// Factory is called once; track fns via a shared object.
const oidcMocks = {
  isDevLoginAllowed: vi.fn(() => true),
  startLogin: vi.fn().mockResolvedValue('https://kc.example.com/auth'),
};

vi.mock('@/api/oidc', () => ({
  isDevLoginAllowed: () => oidcMocks.isDevLoginAllowed(),
  startLogin: (path) => oidcMocks.startLogin(path),
}));

// auth helpers — control isAuthenticated per test
const mockIsAuthenticated = vi.fn(() => false);

vi.mock('@/api/auth', () => ({
  isAuthenticated: () => mockIsAuthenticated(),
  getToken: () => null,
  getPartnerId: () => null,
}));

// Redux — AuthGate uses useDispatch + useSelector; provide a minimal store.
vi.mock('react-redux', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    useDispatch: () => vi.fn(),
    useSelector: (selector) =>
      selector({ auth: { token: null, partnerId: null } }),
  };
});

// hydrateFromStorage action creator just needs to be callable
vi.mock('@/store/authSlice', () => ({
  hydrateFromStorage: () => ({ type: 'auth/hydrateFromStorage' }),
}));

import AuthGate from '../AuthGate';

// ── Helpers ────────────────────────────────────────────────────────────────

function renderGate(pathname = '/balance') {
  mockPathname = pathname;
  return render(
    <AuthGate>
      <div data-testid="protected-content">Protected</div>
    </AuthGate>
  );
}

// ── Tests ──────────────────────────────────────────────────────────────────

describe('AuthGate (partner-portal-ui)', () => {
  beforeEach(() => {
    mockReplace.mockReset();
    oidcMocks.startLogin.mockReset().mockResolvedValue('https://kc.example.com/auth');
    oidcMocks.isDevLoginAllowed.mockReturnValue(true);
    mockIsAuthenticated.mockReturnValue(false);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  // The partner-portal-ui AuthGate sets hydrated=true synchronously inside
  // its first useEffect, so the transient spinner is gone before any async
  // assertion can run. The meaningful assertions are on the post-hydration
  // state: children rendered or redirect triggered.

  it('renders children when the user is authenticated', async () => {
    mockIsAuthenticated.mockReturnValue(true);
    renderGate('/balance');
    await waitFor(() =>
      expect(screen.getByTestId('protected-content')).toBeInTheDocument()
    );
  });

  it('renders nothing (null) on a protected route when not authenticated', async () => {
    mockIsAuthenticated.mockReturnValue(false);
    oidcMocks.isDevLoginAllowed.mockReturnValue(true);
    const { container } = renderGate('/balance');
    await waitFor(() => expect(mockReplace).toHaveBeenCalled());
    expect(container.firstChild).toBeNull();
  });

  it('redirects to /login (dev mode) when not authenticated on a protected route', async () => {
    mockIsAuthenticated.mockReturnValue(false);
    oidcMocks.isDevLoginAllowed.mockReturnValue(true);
    renderGate('/balance');
    await waitFor(() => expect(mockReplace).toHaveBeenCalledWith('/login'));
    expect(oidcMocks.startLogin).not.toHaveBeenCalled();
  });

  it('calls startLogin (OIDC) when not authenticated and dev mode is off', async () => {
    mockIsAuthenticated.mockReturnValue(false);
    oidcMocks.isDevLoginAllowed.mockReturnValue(false);
    renderGate('/transactions');
    await waitFor(() =>
      expect(oidcMocks.startLogin).toHaveBeenCalledWith('/transactions')
    );
    expect(mockReplace).not.toHaveBeenCalled();
  });

  it('renders children on /login without redirecting', async () => {
    mockIsAuthenticated.mockReturnValue(false);
    renderGate('/login');
    await waitFor(() =>
      expect(screen.getByTestId('protected-content')).toBeInTheDocument()
    );
    expect(mockReplace).not.toHaveBeenCalled();
    expect(oidcMocks.startLogin).not.toHaveBeenCalled();
  });

  it('renders children on /auth/callback without redirecting', async () => {
    mockIsAuthenticated.mockReturnValue(false);
    renderGate('/auth/callback');
    await waitFor(() =>
      expect(screen.getByTestId('protected-content')).toBeInTheDocument()
    );
    expect(mockReplace).not.toHaveBeenCalled();
    expect(oidcMocks.startLogin).not.toHaveBeenCalled();
  });
});
