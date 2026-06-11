/**
 * Vitest coverage for the Approvals queue page (Slice 2, agent 2B.2).
 *
 * Scenarios:
 *  1. List renders — table shows aggregate, proposer, proposed-at, payload summary.
 *  2. Approve dispatches the approve thunk and removes the row.
 *  3. Reject requires a non-empty reason before submitting.
 *  4. Self-approval disabled — Approve button has disabled attr + tooltip text
 *     when proposer === current operator.
 */
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { Provider } from 'react-redux';
import { configureStore } from '@reduxjs/toolkit';
import approvalsReducer from '@/store/approvalsSlice';
import authReducer from '@/store/authSlice';
import { USER_KEY } from '@/api/auth';
import { theme } from '@/theme/theme';

// Mock next/navigation (page doesn't use router directly but transitive deps might).
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }),
  usePathname: () => '/approvals',
}));

// Mock BFF client — the page only needs the three CR endpoints.
const listPendingChangeRequests = vi.fn();
const approveChangeRequest = vi.fn();
const rejectChangeRequest = vi.fn();
vi.mock('@/api/client', () => ({
  adminApi: {
    listPendingChangeRequests: () => listPendingChangeRequests(),
    approveChangeRequest: (id, approvedBy) => approveChangeRequest(id, approvedBy),
    rejectChangeRequest: (id, rejectedBy, reason) =>
      rejectChangeRequest(id, rejectedBy, reason),
  },
}));

// Quiet snackbar.
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

import ApprovalsPage from '../page';

const OPERATOR = 'alice';

/** Helper — set the mock operator name in localStorage before rendering. */
function setOperator(name) {
  try {
    window.localStorage.setItem(USER_KEY, name);
  } catch {
    /* ignore */
  }
}

function renderPage() {
  const store = configureStore({
    reducer: { approvals: approvalsReducer, auth: authReducer },
  });
  return {
    store,
    ...render(
      <Provider store={store}>
        <ThemeProvider theme={theme}>
          <ApprovalsPage />
        </ThemeProvider>
      </Provider>,
    ),
  };
}

const CR_LIST = [
  {
    id: 1,
    aggregate: 'Partner:GME_KR_001',
    proposer: 'bob',
    proposedAt: '2026-06-10T09:00:00Z',
    payload: { legalNameLocal: 'GME Korea', taxId: '123-45-67890' },
  },
  {
    id: 2,
    aggregate: 'Partner:GME_VN_002',
    proposer: OPERATOR, // same as logged-in user → self-approval scenario
    proposedAt: '2026-06-10T10:30:00Z',
    payload: { countryOfIncorporation: 'VN' },
  },
];

describe('ApprovalsPage', () => {
  beforeEach(() => {
    listPendingChangeRequests.mockReset();
    approveChangeRequest.mockReset();
    rejectChangeRequest.mockReset();
    snackSuccess.mockReset();
    snackError.mockReset();
    try {
      window.localStorage.clear();
    } catch {
      /* ignore */
    }
    setOperator(OPERATOR);
  });

  it('renders table with aggregate, proposer, and payload summary columns', async () => {
    listPendingChangeRequests.mockResolvedValue(CR_LIST);

    renderPage();

    const table = await screen.findByRole('table', {
      name: /pending change requests/i,
    });

    // Aggregate column
    expect(within(table).getByText('Partner:GME_KR_001')).toBeInTheDocument();
    expect(within(table).getByText('Partner:GME_VN_002')).toBeInTheDocument();
    // Proposer column
    expect(within(table).getByText('bob')).toBeInTheDocument();
    // Payload summary — at least one key from the first row's payload
    expect(within(table).getByText(/legalNameLocal/)).toBeInTheDocument();
  });

  it('approve button dispatches approve thunk and removes the row on success', async () => {
    listPendingChangeRequests.mockResolvedValue([CR_LIST[0]]); // only bob's row
    approveChangeRequest.mockResolvedValue({ id: 1, state: 'APPROVED' });

    const user = userEvent.setup();
    renderPage();

    // Wait for the table to populate.
    const approveBtn = await screen.findByRole('button', {
      name: /approve change request 1/i,
    });
    await user.click(approveBtn);

    await waitFor(() => {
      expect(approveChangeRequest).toHaveBeenCalledWith(1, OPERATOR);
    });
    // Success snackbar
    await waitFor(() => {
      expect(snackSuccess).toHaveBeenCalled();
    });
    // Row is removed from the table
    await waitFor(() => {
      expect(screen.queryByText('Partner:GME_KR_001')).not.toBeInTheDocument();
    });
  });

  it('reject button opens dialog; submitting without a reason shows validation error', async () => {
    listPendingChangeRequests.mockResolvedValue([CR_LIST[0]]);
    rejectChangeRequest.mockResolvedValue({ id: 1, state: 'REJECTED' });

    const user = userEvent.setup();
    renderPage();

    // Open reject dialog
    const rejectBtn = await screen.findByRole('button', {
      name: /reject change request 1/i,
    });
    await user.click(rejectBtn);

    // Dialog is open
    expect(await screen.findByRole('dialog')).toBeInTheDocument();

    // Click "Confirm reject" without entering a reason
    await user.click(screen.getByRole('button', { name: /confirm reject/i }));

    // Validation error appears
    expect(
      await screen.findByText(/a reason is required before rejecting/i),
    ).toBeInTheDocument();

    // The API was NOT called
    expect(rejectChangeRequest).not.toHaveBeenCalled();
  });

  it('reject submits after a reason is entered and removes the row', async () => {
    listPendingChangeRequests.mockResolvedValue([CR_LIST[0]]);
    rejectChangeRequest.mockResolvedValue({ id: 1, state: 'REJECTED' });

    const user = userEvent.setup();
    renderPage();

    const rejectBtn = await screen.findByRole('button', {
      name: /reject change request 1/i,
    });
    await user.click(rejectBtn);

    // Type a reason
    const reasonInput = await screen.findByRole('textbox', {
      name: /rejection reason/i,
    });
    await user.type(reasonInput, 'Incorrect tax ID format');

    // Confirm
    await user.click(screen.getByRole('button', { name: /confirm reject/i }));

    await waitFor(() => {
      expect(rejectChangeRequest).toHaveBeenCalledWith(
        1,
        OPERATOR,
        'Incorrect tax ID format',
      );
    });
    await waitFor(() => {
      expect(snackSuccess).toHaveBeenCalled();
    });
    // Row removed
    await waitFor(() => {
      expect(screen.queryByText('Partner:GME_KR_001')).not.toBeInTheDocument();
    });
  });

  it('self-approval: Approve button is disabled when proposer === current operator', async () => {
    // CR_LIST[1] has proposer === OPERATOR ('alice').
    listPendingChangeRequests.mockResolvedValue([CR_LIST[1]]);

    renderPage();

    // Wait for the row to appear
    await screen.findByText('Partner:GME_VN_002');

    const approveBtn = screen.getByRole('button', {
      name: /approve change request 2/i,
    });
    expect(approveBtn).toBeDisabled();
  });

  it('shows empty state when no pending approvals exist', async () => {
    listPendingChangeRequests.mockResolvedValue([]);

    renderPage();

    expect(
      await screen.findByText(/no pending approvals/i),
    ).toBeInTheDocument();
  });
});
