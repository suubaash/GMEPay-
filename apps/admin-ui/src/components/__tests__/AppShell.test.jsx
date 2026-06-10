import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { Provider } from 'react-redux';
import { configureStore } from '@reduxjs/toolkit';
import authReducer from '@/store/authSlice';
import uiReducer, { UI_MODE_KEY } from '@/store/uiSlice';
import { TOKEN_KEY, USER_KEY } from '@/api/auth';
import { theme } from '@/theme/theme';

const replace = vi.fn();
vi.mock('next/navigation', () => ({
  useRouter: () => ({ replace, push: vi.fn(), back: vi.fn() }),
  usePathname: () => '/',
}));

import AppShell from '@/components/AppShell';

function renderShell() {
  const store = configureStore({
    reducer: { auth: authReducer, ui: uiReducer },
  });
  return {
    store,
    ...render(
      <Provider store={store}>
        <ThemeProvider theme={theme}>
          <AppShell>
            <div data-testid="content">child</div>
          </AppShell>
        </ThemeProvider>
      </Provider>,
    ),
  };
}

describe('AppShell', () => {
  beforeEach(() => {
    replace.mockReset();
    try {
      window.localStorage.clear();
    } catch {
      /* ignore */
    }
    window.localStorage.setItem(USER_KEY, 'subash');
    window.localStorage.setItem(TOKEN_KEY, 'tok123');
  });

  it('opens the user menu when the avatar is clicked', async () => {
    const user = userEvent.setup();
    renderShell();
    const trigger = screen.getByRole('button', { name: /user menu/i });
    await user.click(trigger);
    // Menu items appear.
    expect(await screen.findByRole('menuitem', { name: /toggle dark mode/i })).toBeInTheDocument();
    expect(screen.getByRole('menuitem', { name: /sign out/i })).toBeInTheDocument();
    // Username surfaces in the disabled "logged in as" row.
    expect(screen.getByText('subash')).toBeInTheDocument();
  });

  it('toggling dark mode dispatches the uiSlice action and persists', async () => {
    const user = userEvent.setup();
    const { store } = renderShell();
    expect(store.getState().ui.mode).toBe('light');

    await user.click(screen.getByRole('button', { name: /user menu/i }));
    await user.click(await screen.findByRole('menuitem', { name: /toggle dark mode/i }));

    await waitFor(() => {
      expect(store.getState().ui.mode).toBe('dark');
    });
    expect(window.localStorage.getItem(UI_MODE_KEY)).toBe('dark');
  });

  it('sign-out clears localStorage tokens and redirects to /login', async () => {
    const user = userEvent.setup();
    renderShell();
    await user.click(screen.getByRole('button', { name: /user menu/i }));
    await user.click(await screen.findByRole('menuitem', { name: /sign out/i }));

    await waitFor(() => {
      expect(replace).toHaveBeenCalledWith('/login');
    });
    expect(window.localStorage.getItem(TOKEN_KEY)).toBeNull();
    expect(window.localStorage.getItem(USER_KEY)).toBeNull();
  });
});
