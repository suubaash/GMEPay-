import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Provider as ReduxProvider } from 'react-redux';
import { configureStore } from '@reduxjs/toolkit';

import authReducer from '@/store/authSlice';
import uiReducer, { UI_MODE_KEY } from '@/store/uiSlice';
import { SnackbarProvider } from '@/components/SnackbarProvider';
import { TOKEN_KEY, PARTNER_ID_KEY } from '@/api/auth';

const replaceMock = vi.fn();
vi.mock('next/navigation', () => ({
  useRouter: () => ({ replace: replaceMock, push: vi.fn(), prefetch: vi.fn() }),
  usePathname: () => '/'
}));

vi.mock('@/api/client', () => ({
  portalApi: {},
  currentPartnerId: () => 'GMEREMIT'
}));

import AppShell from '../AppShell';

function makeStore(preloaded) {
  return configureStore({
    reducer: { auth: authReducer, ui: uiReducer },
    preloadedState: preloaded
  });
}

function renderShell(preloaded) {
  const store = makeStore(preloaded);
  return {
    store,
    ...render(
      <ReduxProvider store={store}>
        <SnackbarProvider>
          <AppShell>
            <div data-testid="child">child</div>
          </AppShell>
        </SnackbarProvider>
      </ReduxProvider>
    )
  };
}

describe('AppShell', () => {
  beforeEach(() => {
    replaceMock.mockReset();
    window.localStorage.clear();
  });

  it('renders the brand, the logged-in partner id, and the children', () => {
    renderShell({
      auth: { partnerId: 'GMEREMIT', token: 'tkn', role: null, status: 'succeeded', error: null },
      ui: { mode: 'light' }
    });
    expect(screen.getByTestId('appshell-partner-id')).toHaveTextContent('GMEREMIT');
    expect(screen.getByTestId('child')).toBeInTheDocument();
    expect(screen.getByTestId('notifications-button')).toBeInTheDocument();
    expect(screen.getByTestId('user-menu-button')).toBeInTheDocument();
  });

  it('opens the user menu when the avatar is clicked', async () => {
    renderShell({
      auth: { partnerId: 'GMEREMIT', token: 'tkn', role: null, status: 'succeeded', error: null },
      ui: { mode: 'light' }
    });
    const user = userEvent.setup();
    expect(screen.queryByTestId('toggle-mode-menuitem')).toBeNull();
    await user.click(screen.getByTestId('user-menu-button'));
    expect(screen.getByTestId('toggle-mode-menuitem')).toBeInTheDocument();
    expect(screen.getByTestId('signout-menuitem')).toBeInTheDocument();
  });

  it('toggles dark mode through the menu and updates Redux state', async () => {
    const { store } = renderShell({
      auth: { partnerId: 'GMEREMIT', token: 'tkn', role: null, status: 'succeeded', error: null },
      ui: { mode: 'light' }
    });
    const user = userEvent.setup();
    await user.click(screen.getByTestId('user-menu-button'));
    await user.click(screen.getByTestId('toggle-mode-menuitem'));
    await waitFor(() => {
      expect(store.getState().ui.mode).toBe('dark');
    });
    expect(window.localStorage.getItem(UI_MODE_KEY)).toBe('dark');
  });

  it('sign-out clears localStorage auth keys and redirects to /login', async () => {
    window.localStorage.setItem(TOKEN_KEY, 'tkn');
    window.localStorage.setItem(PARTNER_ID_KEY, 'GMEREMIT');
    const { store } = renderShell({
      auth: { partnerId: 'GMEREMIT', token: 'tkn', role: null, status: 'succeeded', error: null },
      ui: { mode: 'light' }
    });
    const user = userEvent.setup();
    await user.click(screen.getByTestId('user-menu-button'));
    await user.click(screen.getByTestId('signout-menuitem'));

    await waitFor(() => {
      expect(window.localStorage.getItem(TOKEN_KEY)).toBeNull();
    });
    expect(window.localStorage.getItem(PARTNER_ID_KEY)).toBeNull();
    expect(store.getState().auth.token).toBeNull();
    expect(replaceMock).toHaveBeenCalledWith('/login');
  });
});
