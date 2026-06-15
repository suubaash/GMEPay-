/**
 * Vitest unit tests for usersSlice.
 *
 * Tests:
 *  1. initialState is correct
 *  2. fetchUsers.fulfilled populates items
 *  3. fetchUsers.fulfilled with fromFixture=true sets fromFixture + error
 *  4. fetchUsers.rejected sets error
 *  5. inviteUser.fulfilled upserts a new user
 *  6. updateUserRoles.fulfilled replaces the matching user
 *  7. deactivateUser.fulfilled replaces the matching user
 *  8. reactivateUser.fulfilled replaces the matching user
 *  9. clearError resets error to null
 */
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { configureStore } from '@reduxjs/toolkit';
import usersReducer, {
  clearError,
  deactivateUser,
  fetchUsers,
  inviteUser,
  reactivateUser,
  updateUserRoles,
} from '@/store/usersSlice';

// Mock the api module so no real HTTP calls happen
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

const ALICE = {
  id: 'u-001',
  name: 'Alice',
  email: 'alice@gme.com',
  roles: ['ADMIN'],
  status: 'ACTIVE',
  lastLoginAt: null,
};

const BOB = {
  id: 'u-002',
  name: 'Bob',
  email: 'bob@gme.com',
  roles: ['OPS'],
  status: 'ACTIVE',
  lastLoginAt: null,
};

function makeStore(preloaded) {
  return configureStore({
    reducer: { users: usersReducer },
    ...(preloaded !== undefined ? { preloadedState: { users: preloaded } } : {}),
  });
}

describe('usersSlice', () => {
  beforeEach(() => {
    mockListUsers.mockReset();
    mockInviteUser.mockReset();
    mockUpdateUser.mockReset();
    mockDeactivateUser.mockReset();
    mockReactivateUser.mockReset();
  });

  // 1 — initial state
  it('has correct initial state', () => {
    const store = makeStore();
    const state = store.getState().users;
    expect(state.items).toEqual([]);
    expect(state.loading).toBe(false);
    expect(state.saving).toBe(false);
    expect(state.error).toBeNull();
    expect(state.fromFixture).toBe(false);
  });

  // 2 — fetchUsers.fulfilled
  it('fetchUsers.fulfilled populates items', async () => {
    mockListUsers.mockResolvedValue({ data: [ALICE, BOB], fromFixture: false });
    const store = makeStore();
    await store.dispatch(fetchUsers());
    const state = store.getState().users;
    expect(state.items).toHaveLength(2);
    expect(state.items[0].id).toBe('u-001');
    expect(state.loading).toBe(false);
    expect(state.fromFixture).toBe(false);
    expect(state.error).toBeNull();
  });

  // 3 — fromFixture mode
  it('fetchUsers.fulfilled with fromFixture=true sets fromFixture and a warning error', async () => {
    mockListUsers.mockResolvedValue({
      data: [ALICE],
      fromFixture: true,
      error: 'Connection refused',
    });
    const store = makeStore();
    await store.dispatch(fetchUsers());
    const state = store.getState().users;
    expect(state.fromFixture).toBe(true);
    expect(state.error).toMatch(/backend unavailable/i);
  });

  // 4 — fetchUsers.rejected
  it('fetchUsers.rejected sets error', async () => {
    mockListUsers.mockRejectedValue(new Error('Network error'));
    const store = makeStore();
    await store.dispatch(fetchUsers());
    const state = store.getState().users;
    expect(state.error).toMatch(/network error/i);
    expect(state.loading).toBe(false);
  });

  // 5 — inviteUser.fulfilled upserts
  it('inviteUser.fulfilled appends a new user', async () => {
    const newUser = {
      id: 'u-003',
      name: 'Carol',
      email: 'carol@gme.com',
      roles: ['READ_ONLY'],
      status: 'INVITED',
      lastLoginAt: null,
    };
    mockInviteUser.mockResolvedValue(newUser);
    const store = makeStore({ items: [ALICE, BOB], loading: false, saving: false, error: null, fromFixture: false });
    await store.dispatch(inviteUser({ email: 'carol@gme.com', roles: ['READ_ONLY'] }));
    const state = store.getState().users;
    expect(state.items).toHaveLength(3);
    expect(state.items[2].id).toBe('u-003');
    expect(state.saving).toBe(false);
  });

  // 6 — updateUserRoles.fulfilled replaces
  it('updateUserRoles.fulfilled replaces the matching user', async () => {
    const updated = { ...ALICE, roles: ['ADMIN', 'FINANCE'] };
    mockUpdateUser.mockResolvedValue(updated);
    const store = makeStore({ items: [ALICE, BOB], loading: false, saving: false, error: null, fromFixture: false });
    await store.dispatch(updateUserRoles({ id: 'u-001', roles: ['ADMIN', 'FINANCE'] }));
    const state = store.getState().users;
    expect(state.items[0].roles).toEqual(['ADMIN', 'FINANCE']);
    expect(state.saving).toBe(false);
  });

  // 7 — deactivateUser.fulfilled
  it('deactivateUser.fulfilled sets user status to DISABLED', async () => {
    const updated = { ...ALICE, status: 'DISABLED' };
    mockDeactivateUser.mockResolvedValue(updated);
    const store = makeStore({ items: [ALICE, BOB], loading: false, saving: false, error: null, fromFixture: false });
    await store.dispatch(deactivateUser('u-001'));
    const state = store.getState().users;
    expect(state.items[0].status).toBe('DISABLED');
    expect(state.saving).toBe(false);
  });

  // 8 — reactivateUser.fulfilled
  it('reactivateUser.fulfilled sets user status to ACTIVE', async () => {
    const disabled = { ...BOB, status: 'DISABLED' };
    const reactivated = { ...BOB, status: 'ACTIVE' };
    mockReactivateUser.mockResolvedValue(reactivated);
    const store = makeStore({ items: [ALICE, disabled], loading: false, saving: false, error: null, fromFixture: false });
    await store.dispatch(reactivateUser('u-002'));
    const state = store.getState().users;
    expect(state.items[1].status).toBe('ACTIVE');
    expect(state.saving).toBe(false);
  });

  // 9 — clearError
  it('clearError resets error to null', async () => {
    mockListUsers.mockRejectedValue(new Error('fail'));
    const store = makeStore();
    await store.dispatch(fetchUsers());
    expect(store.getState().users.error).not.toBeNull();
    store.dispatch(clearError());
    expect(store.getState().users.error).toBeNull();
  });
});
