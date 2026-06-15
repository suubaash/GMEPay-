import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import React from 'react';

// ── Mocks ─────────────────────────────────────────────────────────────────

// next/navigation — AuthGate uses usePathname + useRouter.
const mockReplace = vi.fn();
let mockPathname = '/dashboard';

vi.mock('next/navigation', () => ({
  useRouter: () => ({ replace: mockReplace }),
  usePathname: () => mockPathname,
}));

// OIDC module — prevent real Keycloak navigations.
// Note: the factory is called once; isDevLoginAllowed and startLogin are
// re-exported as plain functions, so we track them via a shared object.
const oidcMocks = {
  isDevLoginAllowed: vi.fn(() => true),
  startLogin: vi.fn().mockResolvedValue('https://kc.example.com/auth'),
};

vi.mock('@/api/oidc', () => ({
  isDevLoginAllowed: () => oidcMocks.isDevLoginAllowed(),
  startLogin: (path) => oidcMocks.startLogin(path),
}));

// Auth helpers — control isAuthenticated per test.
const mockIsAuthenticated = vi.fn(() => false);
vi.mock('@/api/auth', () => ({
  isAuthenticated: () => mockIsAuthenticated(),
}));

import AuthGate from '../AuthGate';

// ── Helpers ────────────────────────────────────────────────────────────────

function renderGate(pathname = '/dashboard') {
  mockPathname = pathname;
  return render(
    <AuthGate>
      <div data-testid="protected-content">Protected</div>
    </AuthGate>
  );
}

// ── Tests ──────────────────────────────────────────────────────────────────

describe('AuthGate (admin-ui)', () => {
  beforeEach(() => {
    mockReplace.mockReset();
    oidcMocks.startLogin.mockReset().mockResolvedValue('https://kc.example.com/auth');
    oidcMocks.isDevLoginAllowed.mockReturnValue(true);
    mockIsAuthenticated.mockReturnValue(false);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  // The admin-ui AuthGate sets checked=true synchronously in the first
  // useEffect tick (same microtask after render), so the spinner is only
  // visible for SSR / the very first paint. The meaningful assertions are
  // on the post-hydration state: children rendered or redirect triggered.

  it('renders children when the user is authenticated', async () => {
    mockIsAuthenticated.mockReturnValue(true);
    renderGate('/dashboard');
    await waitFor(() =>
      expect(screen.getByTestId('protected-content')).toBeInTheDocument()
    );
  });

  it('renders nothing (null) on a protected route when not authenticated', async () => {
    mockIsAuthenticated.mockReturnValue(false);
    oidcMocks.isDevLoginAllowed.mockReturnValue(true);
    const { container } = renderGate('/dashboard');
    await waitFor(() => expect(mockReplace).toHaveBeenCalled());
    // After the gate decides user is not allowed it returns null.
    expect(container.firstChild).toBeNull();
  });

  it('redirects to /login (dev mode) when not authenticated on a protected route', async () => {
    mockIsAuthenticated.mockReturnValue(false);
    oidcMocks.isDevLoginAllowed.mockReturnValue(true);
    renderGate('/dashboard');
    await waitFor(() => expect(mockReplace).toHaveBeenCalledWith('/login'));
    expect(oidcMocks.startLogin).not.toHaveBeenCalled();
  });

  it('calls startLogin (OIDC) when not authenticated and dev mode is off', async () => {
    mockIsAuthenticated.mockReturnValue(false);
    oidcMocks.isDevLoginAllowed.mockReturnValue(false);
    renderGate('/reports');
    await waitFor(() =>
      expect(oidcMocks.startLogin).toHaveBeenCalledWith('/reports')
    );
    expect(mockReplace).not.toHaveBeenCalled();
  });

  it('renders children on the /login route without redirecting', async () => {
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
