/**
 * Vitest coverage for the RBAC page (/rbac).
 *
 * Covers:
 *  1. Renders the roles summary table with fixture data.
 *  2. Renders the permission matrix with role columns + permission rows.
 *  3. Toggling a checkbox in edit mode and saving dispatches
 *     saveRolePermissions with the correct grant set.
 *  4. Create-role dialog: validates empty name, validates bad name format,
 *     valid submission dispatches createNewRole.
 *  5. API errors surface as snackbar messages.
 */
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { Provider } from 'react-redux';
import { configureStore } from '@reduxjs/toolkit';
import rbacReducer from '@/store/rbacSlice';
import authReducer from '@/store/authSlice';
import { theme } from '@/theme/theme';

// Mock next/navigation (page.jsx imports nothing from it but subcomponents might)
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }),
  usePathname: () => '/rbac',
}));

// Mock rbacApi — isolate from the BFF.
const mockGetRoles = vi.fn();
const mockGetPermissions = vi.fn();
const mockPutRolePermissions = vi.fn();
const mockCreateRole = vi.fn();

vi.mock('@/api/rbacApi', () => ({
  getRoles: () => mockGetRoles(),
  getPermissions: () => mockGetPermissions(),
  putRolePermissions: (role, grants) => mockPutRolePermissions(role, grants),
  createRole: (body) => mockCreateRole(body),
  FIXTURE_ROLES: [],
  FIXTURE_PERMISSIONS: [],
}));

// Silence SnackbarProvider.
const snackSuccess = vi.fn();
const snackError = vi.fn();
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

import RbacPage from '../page';

// ---- Fixtures ----

const ROLES = [
  {
    role: 'ADMIN',
    description: 'Full access',
    userCount: 2,
    permissions: ['partner.activate', 'rbac.manage'],
  },
  {
    role: 'READ_ONLY',
    description: 'Read only',
    userCount: 5,
    permissions: ['partner.view'],
  },
];

const PERMISSIONS = [
  {
    permission: 'partner.activate',
    resource: 'partner',
    action: 'activate',
    description: 'Activate a partner',
  },
  {
    permission: 'partner.view',
    resource: 'partner',
    action: 'view',
    description: 'View partner',
  },
  {
    permission: 'rbac.manage',
    resource: 'rbac',
    action: 'manage',
    description: 'Manage RBAC',
  },
];

function renderPage() {
  const store = configureStore({
    reducer: { rbac: rbacReducer, auth: authReducer },
  });
  return {
    store,
    ...render(
      <Provider store={store}>
        <ThemeProvider theme={theme}>
          <RbacPage />
        </ThemeProvider>
      </Provider>,
    ),
  };
}

describe('RbacPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockGetRoles.mockResolvedValue(ROLES);
    mockGetPermissions.mockResolvedValue(PERMISSIONS);
    mockPutRolePermissions.mockResolvedValue({ role: 'ADMIN', permissions: ['partner.activate'] });
    mockCreateRole.mockResolvedValue({
      role: 'AUDIT',
      description: '',
      userCount: 0,
      permissions: ['partner.view'],
    });
  });

  // ---- 1. Roles summary table ----
  it('renders the roles summary table with role names, descriptions, and user counts', async () => {
    renderPage();

    const rolesTable = await screen.findByRole('table', { name: /roles summary/i });
    expect(within(rolesTable).getByText('ADMIN')).toBeInTheDocument();
    expect(within(rolesTable).getByText('Full access')).toBeInTheDocument();
    expect(within(rolesTable).getByText('READ_ONLY')).toBeInTheDocument();
    expect(within(rolesTable).getByText('Read only')).toBeInTheDocument();
    // User counts
    expect(within(rolesTable).getByText('2')).toBeInTheDocument();
    expect(within(rolesTable).getByText('5')).toBeInTheDocument();
  });

  // ---- 2. Permission matrix structure ----
  it('renders the permission matrix with permission rows and role columns', async () => {
    renderPage();

    const matrix = await screen.findByRole('table', { name: /permission matrix/i });

    // Column headers — roles
    expect(within(matrix).getByText('ADMIN')).toBeInTheDocument();
    expect(within(matrix).getByText('READ_ONLY')).toBeInTheDocument();

    // Permission rows
    expect(within(matrix).getByText('partner.activate')).toBeInTheDocument();
    expect(within(matrix).getByText('partner.view')).toBeInTheDocument();
    expect(within(matrix).getByText('rbac.manage')).toBeInTheDocument();
  });

  it('renders checkboxes as checked where the role holds the permission', async () => {
    renderPage();

    // Wait for data to load
    await screen.findByRole('table', { name: /permission matrix/i });

    // ADMIN has partner.activate — checkbox should be checked
    const adminActivate = screen.getByRole('checkbox', {
      name: /ADMIN partner\.activate/i,
    });
    expect(adminActivate).toBeChecked();

    // READ_ONLY does NOT have partner.activate — checkbox should not be checked
    const readOnlyActivate = screen.getByRole('checkbox', {
      name: /READ_ONLY partner\.activate/i,
    });
    expect(readOnlyActivate).not.toBeChecked();
  });

  // ---- 3. Toggle + save dispatches correct grant set ----
  it('enter edit mode, uncheck a permission, save dispatches saveRolePermissions with updated grants', async () => {
    const user = userEvent.setup();
    renderPage();

    await screen.findByRole('table', { name: /permission matrix/i });

    // Enter edit mode
    await user.click(screen.getByRole('button', { name: /edit permissions/i }));

    // 4-eyes warning should appear
    expect(screen.getByText(/4-eyes approval/i)).toBeInTheDocument();

    // ADMIN currently has partner.activate — uncheck it
    const adminActivate = screen.getByRole('checkbox', {
      name: /ADMIN partner\.activate/i,
    });
    expect(adminActivate).toBeChecked();
    await user.click(adminActivate);
    expect(adminActivate).not.toBeChecked();

    // Save
    await user.click(screen.getByRole('button', { name: /save permission changes/i }));

    await waitFor(() => {
      expect(mockPutRolePermissions).toHaveBeenCalledWith(
        'ADMIN',
        // Removed partner.activate; rbac.manage still present
        expect.arrayContaining(['rbac.manage']),
      );
    });
    // partner.activate must NOT be in the grants
    const [, grants] = mockPutRolePermissions.mock.calls[0];
    expect(grants).not.toContain('partner.activate');

    // Snackbar success
    await waitFor(() => {
      expect(snackSuccess).toHaveBeenCalledWith(
        expect.stringMatching(/ADMIN/i),
      );
    });
  });

  it('cancel edit mode resets the matrix to original state', async () => {
    const user = userEvent.setup();
    renderPage();

    await screen.findByRole('table', { name: /permission matrix/i });

    await user.click(screen.getByRole('button', { name: /edit permissions/i }));

    const adminActivate = screen.getByRole('checkbox', {
      name: /ADMIN partner\.activate/i,
    });
    await user.click(adminActivate); // uncheck
    expect(adminActivate).not.toBeChecked();

    await user.click(screen.getByRole('button', { name: /cancel/i }));

    // After cancel, checkbox should revert (edit mode exited, baseline restored)
    const adminActivateAfter = screen.getByRole('checkbox', {
      name: /ADMIN partner\.activate/i,
    });
    expect(adminActivateAfter).toBeChecked();
    // PUT should NOT have been called
    expect(mockPutRolePermissions).not.toHaveBeenCalled();
  });

  // ---- 4. Create-role dialog ----
  it('Create role button opens dialog', async () => {
    const user = userEvent.setup();
    renderPage();

    await screen.findByRole('table', { name: /roles summary/i });

    await user.click(screen.getByRole('button', { name: /create new role/i }));

    expect(screen.getByRole('dialog', { name: /create new role/i })).toBeInTheDocument();
  });

  it('create-role dialog shows validation error when name is empty', async () => {
    const user = userEvent.setup();
    renderPage();

    await screen.findByRole('table', { name: /roles summary/i });
    await user.click(screen.getByRole('button', { name: /create new role/i }));

    // Click Create without filling name
    await user.click(screen.getByRole('button', { name: /^create role$/i }));

    expect(screen.getByText(/role name is required/i)).toBeInTheDocument();
    expect(mockCreateRole).not.toHaveBeenCalled();
  });

  it('create-role dialog shows validation error for invalid name format', async () => {
    const user = userEvent.setup();
    renderPage();

    await screen.findByRole('table', { name: /roles summary/i });
    await user.click(screen.getByRole('button', { name: /create new role/i }));

    const nameInput = screen.getByRole('textbox', { name: /role name/i });
    await user.type(nameInput, 'invalid-name!');

    await user.click(screen.getByRole('button', { name: /^create role$/i }));

    expect(screen.getByText(/uppercase letters, digits and underscores/i)).toBeInTheDocument();
    expect(mockCreateRole).not.toHaveBeenCalled();
  });

  it('valid create-role submission dispatches createNewRole with name + basePermissions', async () => {
    const user = userEvent.setup();
    renderPage();

    await screen.findByRole('table', { name: /roles summary/i });
    await user.click(screen.getByRole('button', { name: /create new role/i }));

    // Fill name
    const nameInput = screen.getByRole('textbox', { name: /role name/i });
    await user.type(nameInput, 'AUDIT');

    // Select one base permission
    const viewCheckbox = screen.getByRole('checkbox', { name: /^partner\.view$/i });
    await user.click(viewCheckbox);

    await user.click(screen.getByRole('button', { name: /^create role$/i }));

    await waitFor(() => {
      expect(mockCreateRole).toHaveBeenCalledWith({
        name: 'AUDIT',
        basePermissions: ['partner.view'],
      });
    });

    await waitFor(() => {
      expect(snackSuccess).toHaveBeenCalledWith(expect.stringMatching(/AUDIT/i));
    });
  });

  // ---- 5. Error handling ----
  it('shows error alert in roles table when getRoles fails (no fixture fallback in test)', async () => {
    // Override fixture fallback behaviour: in test mode rbacApi throws
    // and rbacSlice stores the error.
    mockGetRoles.mockRejectedValueOnce(new Error('BFF unreachable'));
    mockGetPermissions.mockResolvedValue(PERMISSIONS);

    renderPage();

    await waitFor(() => {
      // The ErrorAlert for roles should appear
      expect(screen.getByText(/BFF unreachable/i)).toBeInTheDocument();
    });
  });
});
