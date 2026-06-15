/**
 * Vitest coverage for the Operator User Management page (/users).
 *
 * Tests:
 *  1. renders the user list with name, email, role chips, status chip, last login
 *  2. role filter hides rows that do not have the selected role
 *  3. status filter hides rows that do not match the selected status
 *  4. text search filters by name and by email
 *  5. invite dialog validates (empty email → error, no roles → error)
 *  6. valid invite form dispatches inviteUser thunk
 *  7. deactivate button opens confirm dialog; confirm dispatches deactivateUser thunk
 *  8. edit roles dialog dispatches updateUserRoles thunk with new selection
 *  9. fixture banner shown when fromFixture=true
 * 10. reactivate button shown for DISABLED users and dispatches reactivateUser
 */
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { Provider } from 'react-redux';
import { configureStore } from '@reduxjs/toolkit';
import usersReducer from '@/store/usersSlice';
import authReducer from '@/store/authSlice';
import { theme } from '@/theme/theme';

// Mock next/navigation (page.jsx does not use it directly, but
// child components may import it via transitive deps)
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }),
}));

// Mock the api module — the slice imports this; we control resolved values here.
const mockListUsers = vi.fn();
const mockInviteUser = vi.fn();
const mockUpdateUser = vi.fn();
const mockDeactivateUser = vi.fn();
const mockReactivateUser = vi.fn();

vi.mock('@/api/usersApi', () => ({
  listUsers: () => mockListUsers(),
  inviteUser: (body) => mockInviteUser(body),
  updateUser: (id, body) => mockUpdateUser(id, body),
  deactivateUser: (id) => mockDeactivateUser(id),
  reactivateUser: (id) => mockReactivateUser(id),
}));

// Silence snackbar
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

import UsersPage from '../page';

// ---- Fixtures ----

const USERS = [
  {
    id: 'u-001',
    name: 'Subash Sharma',
    email: 'subash@gmeremit.com',
    roles: ['ADMIN', 'OPS'],
    status: 'ACTIVE',
    lastLoginAt: '2026-06-15T09:12:00+09:00',
  },
  {
    id: 'u-002',
    name: 'Ji-yeon Park',
    email: 'jiyeon@gmeremit.com',
    roles: ['COMPLIANCE'],
    status: 'ACTIVE',
    lastLoginAt: '2026-06-14T17:45:00+09:00',
  },
  {
    id: 'u-003',
    name: 'Carlos Reyes',
    email: 'carlos@gmeremit.com',
    roles: ['OPS', 'FINANCE'],
    status: 'DISABLED',
    lastLoginAt: null,
  },
  {
    id: 'u-004',
    name: 'Mei Lin',
    email: 'mei@gmeremit.com',
    roles: ['READ_ONLY'],
    status: 'INVITED',
    lastLoginAt: null,
  },
];

function renderPage(preloadedState = {}) {
  const store = configureStore({
    reducer: { users: usersReducer, auth: authReducer },
    preloadedState,
  });
  return {
    store,
    ...render(
      <Provider store={store}>
        <ThemeProvider theme={theme}>
          <UsersPage />
        </ThemeProvider>
      </Provider>,
    ),
  };
}

describe('UsersPage', () => {
  beforeEach(() => {
    mockListUsers.mockReset();
    mockInviteUser.mockReset();
    mockUpdateUser.mockReset();
    mockDeactivateUser.mockReset();
    mockReactivateUser.mockReset();
    snackError.mockReset();
    snackSuccess.mockReset();
  });

  // 1 — renders user list
  it('renders user list with name, email, role chips, status chip', async () => {
    mockListUsers.mockResolvedValue({ data: USERS, fromFixture: false });
    renderPage();

    const table = await screen.findByRole('table', { name: /operator users/i });
    expect(within(table).getByText('Subash Sharma')).toBeInTheDocument();
    expect(within(table).getByText('subash@gmeremit.com')).toBeInTheDocument();
    // Role chips
    const allAdmin = within(table).getAllByText('ADMIN');
    expect(allAdmin.length).toBeGreaterThan(0);
    // Status chip
    const activeChips = within(table).getAllByText('ACTIVE');
    expect(activeChips.length).toBeGreaterThan(0);
  });

  // 2 — role filter
  it('role filter hides rows that do not have the selected role', async () => {
    mockListUsers.mockResolvedValue({ data: USERS, fromFixture: false });
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole('table', { name: /operator users/i });

    const filterSelect = screen.getByRole('combobox', { name: /filter by role/i });
    await user.click(filterSelect);
    const complianceOption = await screen.findByRole('option', { name: 'COMPLIANCE' });
    await user.click(complianceOption);

    // Ji-yeon Park has COMPLIANCE, Subash does not
    expect(await screen.findByText('Ji-yeon Park')).toBeInTheDocument();
    expect(screen.queryByText('Subash Sharma')).not.toBeInTheDocument();
  });

  // 3 — status filter
  it('status filter hides rows that do not match the selected status', async () => {
    mockListUsers.mockResolvedValue({ data: USERS, fromFixture: false });
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole('table', { name: /operator users/i });

    const statusSelect = screen.getByRole('combobox', { name: /filter by status/i });
    await user.click(statusSelect);
    const disabledOption = await screen.findByRole('option', { name: 'DISABLED' });
    await user.click(disabledOption);

    expect(await screen.findByText('Carlos Reyes')).toBeInTheDocument();
    expect(screen.queryByText('Subash Sharma')).not.toBeInTheDocument();
  });

  // 4 — search
  it('search filters by name', async () => {
    mockListUsers.mockResolvedValue({ data: USERS, fromFixture: false });
    renderPage();
    await screen.findByRole('table', { name: /operator users/i });

    const searchBox = screen.getByRole('textbox', { name: /search users/i });
    fireEvent.change(searchBox, { target: { value: 'mei' } });

    expect(await screen.findByText('Mei Lin')).toBeInTheDocument();
    expect(screen.queryByText('Subash Sharma')).not.toBeInTheDocument();
  });

  it('search filters by email', async () => {
    mockListUsers.mockResolvedValue({ data: USERS, fromFixture: false });
    renderPage();
    await screen.findByRole('table', { name: /operator users/i });

    const searchBox = screen.getByRole('textbox', { name: /search users/i });
    fireEvent.change(searchBox, { target: { value: 'carlos@' } });

    expect(await screen.findByText('Carlos Reyes')).toBeInTheDocument();
    expect(screen.queryByText('Ji-yeon Park')).not.toBeInTheDocument();
  });

  // 5 — invite dialog validation
  it('invite dialog shows error for empty email and no roles', async () => {
    mockListUsers.mockResolvedValue({ data: [], fromFixture: false });
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByRole('button', { name: /invite user/i }));
    const dialog = await screen.findByRole('dialog', { name: /invite operator user/i });

    // Click send without filling anything
    await user.click(within(dialog).getByRole('button', { name: /send invitation/i }));

    expect(await screen.findByText('Email is required')).toBeInTheDocument();
    expect(await screen.findByText('Select at least one role')).toBeInTheDocument();
  });

  it('invite dialog shows email format error for invalid email', async () => {
    mockListUsers.mockResolvedValue({ data: [], fromFixture: false });
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByRole('button', { name: /invite user/i }));
    const dialog = await screen.findByRole('dialog', { name: /invite operator user/i });

    const emailInput = within(dialog).getByRole('textbox', { name: /email address/i });
    await user.type(emailInput, 'not-an-email');
    await user.click(within(dialog).getByRole('button', { name: /send invitation/i }));

    expect(await screen.findByText('Enter a valid email address')).toBeInTheDocument();
  });

  // 6 — valid invite dispatches thunk
  it('valid invite form dispatches inviteUser and closes dialog', async () => {
    mockListUsers.mockResolvedValue({ data: USERS, fromFixture: false });
    mockInviteUser.mockResolvedValue({
      id: 'u-new',
      name: 'New User',
      email: 'new@gmeremit.com',
      roles: ['READ_ONLY'],
      status: 'INVITED',
      lastLoginAt: null,
    });

    const user = userEvent.setup();
    renderPage();
    await screen.findByRole('table', { name: /operator users/i });

    await user.click(screen.getByRole('button', { name: /invite user/i }));
    const dialog = await screen.findByRole('dialog', { name: /invite operator user/i });

    const emailInput = within(dialog).getByRole('textbox', { name: /email address/i });
    await user.type(emailInput, 'new@gmeremit.com');

    // Check READ_ONLY checkbox
    const readOnlyCheck = within(dialog).getByRole('checkbox', { name: /read_only/i });
    await user.click(readOnlyCheck);

    await user.click(within(dialog).getByRole('button', { name: /send invitation/i }));

    await waitFor(() => {
      expect(mockInviteUser).toHaveBeenCalledWith({
        email: 'new@gmeremit.com',
        roles: ['READ_ONLY'],
      });
    });
    await waitFor(() => {
      expect(snackSuccess).toHaveBeenCalledWith(
        expect.stringContaining('new@gmeremit.com'),
      );
    });
    // Dialog closed
    await waitFor(() => {
      expect(screen.queryByRole('dialog', { name: /invite operator user/i })).not.toBeInTheDocument();
    });
  });

  // 7 — deactivate confirm flow
  it('deactivate button opens confirm dialog; confirm dispatches deactivateUser', async () => {
    mockListUsers.mockResolvedValue({ data: USERS, fromFixture: false });
    mockDeactivateUser.mockResolvedValue({
      ...USERS[0],
      status: 'DISABLED',
    });

    const user = userEvent.setup();
    renderPage();
    await screen.findByRole('table', { name: /operator users/i });

    // Click deactivate for Subash Sharma (first ACTIVE user with deactivate button)
    const deactivateBtn = screen.getByRole('button', {
      name: /deactivate subash sharma/i,
    });
    await user.click(deactivateBtn);

    // Confirm dialog should appear
    const confirmDialog = await screen.findByRole('dialog', {
      name: /deactivate subash sharma/i,
    });
    expect(confirmDialog).toBeInTheDocument();

    // Confirm
    await user.click(
      within(confirmDialog).getByRole('button', { name: /confirm deactivation/i }),
    );

    await waitFor(() => {
      expect(mockDeactivateUser).toHaveBeenCalledWith('u-001');
    });
    await waitFor(() => {
      expect(snackSuccess).toHaveBeenCalledWith(
        expect.stringContaining('Subash Sharma'),
      );
    });
  });

  it('deactivate confirm dialog cancel does NOT dispatch', async () => {
    mockListUsers.mockResolvedValue({ data: USERS, fromFixture: false });
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole('table', { name: /operator users/i });

    await user.click(
      screen.getByRole('button', { name: /deactivate subash sharma/i }),
    );
    const confirmDialog = await screen.findByRole('dialog', {
      name: /deactivate subash sharma/i,
    });
    await user.click(within(confirmDialog).getByRole('button', { name: /^cancel$/i }));

    await waitFor(() => {
      expect(mockDeactivateUser).not.toHaveBeenCalled();
    });
  });

  // 8 — edit roles dialog
  it('edit roles dialog dispatches updateUserRoles with new selection', async () => {
    mockListUsers.mockResolvedValue({ data: USERS, fromFixture: false });
    mockUpdateUser.mockResolvedValue({
      ...USERS[1],
      roles: ['COMPLIANCE', 'FINANCE'],
    });

    const user = userEvent.setup();
    renderPage();
    await screen.findByRole('table', { name: /operator users/i });

    await user.click(
      screen.getByRole('button', { name: /edit roles for ji-yeon park/i }),
    );
    const dialog = await screen.findByRole('dialog', { name: /edit roles/i });

    // COMPLIANCE is pre-checked; add FINANCE
    const financeCheck = within(dialog).getByRole('checkbox', { name: /finance/i });
    await user.click(financeCheck);

    await user.click(within(dialog).getByRole('button', { name: /save roles/i }));

    await waitFor(() => {
      expect(mockUpdateUser).toHaveBeenCalledWith('u-002', {
        roles: expect.arrayContaining(['COMPLIANCE', 'FINANCE']),
      });
    });
    await waitFor(() => {
      expect(snackSuccess).toHaveBeenCalledWith(
        expect.stringContaining('Ji-yeon Park'),
      );
    });
  });

  // 9 — fixture banner
  it('shows the demo banner when fromFixture is true', async () => {
    mockListUsers.mockResolvedValue({
      data: USERS,
      fromFixture: true,
      error: 'Connection refused',
    });
    renderPage();

    expect(
      await screen.findByText(/showing demo data/i),
    ).toBeInTheDocument();
  });

  // 10 — reactivate
  it('reactivate button shown for DISABLED user and dispatches reactivateUser', async () => {
    mockListUsers.mockResolvedValue({ data: USERS, fromFixture: false });
    mockReactivateUser.mockResolvedValue({
      ...USERS[2],
      status: 'ACTIVE',
    });

    const user = userEvent.setup();
    renderPage();
    await screen.findByRole('table', { name: /operator users/i });

    const reactivateBtn = screen.getByRole('button', {
      name: /reactivate carlos reyes/i,
    });
    await user.click(reactivateBtn);

    await waitFor(() => {
      expect(mockReactivateUser).toHaveBeenCalledWith('u-003');
    });
    await waitFor(() => {
      expect(snackSuccess).toHaveBeenCalledWith(
        expect.stringContaining('Carlos Reyes'),
      );
    });
  });
});
