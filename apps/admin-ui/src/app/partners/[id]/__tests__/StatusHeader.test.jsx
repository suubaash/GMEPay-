/**
 * Vitest coverage for StatusHeader (Slice 8 partner-detail surface).
 *
 * Covers:
 *  1. LIVE status: shows Suspend + Terminate buttons.
 *  2. SUSPENDED status: shows Reactivate + Terminate buttons.
 *  3. TERMINATED status: shows no action buttons; shows terminated_at + reason.
 *  4. ONBOARDING status: shows "Resume wizard" link.
 *  5. Suspend dialog: requires reason (enum picker); disabled submit when empty.
 *  6. Terminate dialog: requires free-text; disabled when < 5 chars.
 *  7. 4-eyes notice shown in both dialogs.
 *  8. Propose calls proposeLifecycleTransition thunk.
 *  9. Pending change request shows "Awaiting approval" chip.
 * 10. Execute button calls executeLifecycleTransition.
 */
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Provider } from 'react-redux';
import { configureStore } from '@reduxjs/toolkit';
import { ThemeProvider } from '@mui/material/styles';
import { theme } from '@/theme/theme';
import partnerLifecycleReducer from '@/store/partnerLifecycleSlice';
import authReducer from '@/store/authSlice';

// Mock navigation (STEPS import in StatusHeader needs next/navigation-free env)
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn() }),
  useParams: () => ({}),
}));

// Mock SnackbarProvider
const snackSuccess = vi.fn();
const snackError = vi.fn();
const snackInfo = vi.fn();
vi.mock('@/components/SnackbarProvider', () => ({
  __esModule: true,
  default: ({ children }) => <>{children}</>,
  useSnackbar: () => ({ success: snackSuccess, error: snackError, info: snackInfo, warning: vi.fn() }),
}));

// Mock adminApi
const proposeLifecycleMock = vi.fn();
const executeLifecycleMock = vi.fn();
vi.mock('@/api/client', () => ({
  adminApi: {
    proposeLifecycleTransition: (...a) => proposeLifecycleMock(...a),
    executeLifecycleTransition: (...a) => executeLifecycleMock(...a),
  },
}));

import StatusHeader from '../StatusHeader';

function makeStore(lifecycleOverride = {}) {
  return configureStore({
    reducer: {
      partnerLifecycle: partnerLifecycleReducer,
      auth: authReducer,
    },
    preloadedState: {
      partnerLifecycle: {
        credentials: [],
        credentialsLoading: false,
        credentialsError: null,
        rotatingId: null,
        rotateResult: null,
        partnerAudit: {},
        lifecycle: lifecycleOverride,
      },
    },
  });
}

function renderHeader(props, lifecycleOverride) {
  const store = makeStore(lifecycleOverride);
  return render(
    <Provider store={store}>
      <ThemeProvider theme={theme}>
        <StatusHeader {...props} />
      </ThemeProvider>
    </Provider>,
  );
}

const CODE = 'GME_KR_001';

describe('StatusHeader', () => {
  beforeEach(() => {
    proposeLifecycleMock.mockReset();
    executeLifecycleMock.mockReset();
    snackSuccess.mockReset();
    snackError.mockReset();
    snackInfo.mockReset();
  });

  it('LIVE status shows Suspend and Terminate buttons', () => {
    renderHeader({ partnerCode: CODE, status: 'LIVE' });
    expect(screen.getByTestId('suspend-btn')).toBeInTheDocument();
    expect(screen.getByTestId('terminate-btn')).toBeInTheDocument();
    expect(screen.queryByTestId('reactivate-btn')).not.toBeInTheDocument();
  });

  it('SUSPENDED status shows Reactivate and Terminate buttons', () => {
    renderHeader({ partnerCode: CODE, status: 'SUSPENDED' });
    expect(screen.getByTestId('reactivate-btn')).toBeInTheDocument();
    expect(screen.getByTestId('terminate-btn')).toBeInTheDocument();
    expect(screen.queryByTestId('suspend-btn')).not.toBeInTheDocument();
  });

  it('TERMINATED status shows no action buttons', () => {
    renderHeader({
      partnerCode: CODE,
      status: 'TERMINATED',
      terminatedAt: '2026-06-01T10:00:00Z',
      terminationReason: 'Contract breach',
    });
    expect(screen.queryByTestId('suspend-btn')).not.toBeInTheDocument();
    expect(screen.queryByTestId('reactivate-btn')).not.toBeInTheDocument();
    expect(screen.queryByTestId('terminate-btn')).not.toBeInTheDocument();
    expect(screen.getByText(/Contract breach/i)).toBeInTheDocument();
  });

  it('ONBOARDING status shows Resume wizard link', () => {
    renderHeader({ partnerCode: CODE, status: 'ONBOARDING' });
    expect(screen.getByTestId('resume-wizard-btn')).toBeInTheDocument();
  });

  it('Suspend dialog opens and shows reason picker + 4-eyes notice', async () => {
    const user = userEvent.setup();
    renderHeader({ partnerCode: CODE, status: 'LIVE' });

    await user.click(screen.getByTestId('suspend-btn'));

    expect(screen.getByTestId('transition-dialog')).toBeInTheDocument();
    expect(screen.getByTestId('four-eyes-notice')).toBeInTheDocument();
    expect(screen.getByTestId('suspend-reason-select')).toBeInTheDocument();
  });

  it('Suspend dialog Propose button disabled when no reason selected', async () => {
    const user = userEvent.setup();
    renderHeader({ partnerCode: CODE, status: 'LIVE' });

    await user.click(screen.getByTestId('suspend-btn'));
    const proposeBtn = screen.getByTestId('confirm-transition-btn');
    expect(proposeBtn).toBeDisabled();
  });

  it('Terminate dialog requires free-text reason of at least 5 chars', async () => {
    const user = userEvent.setup();
    renderHeader({ partnerCode: CODE, status: 'LIVE' });

    await user.click(screen.getByTestId('terminate-btn'));
    const proposeBtn = screen.getByTestId('confirm-transition-btn');

    // Initially disabled
    expect(proposeBtn).toBeDisabled();

    // Short reason — still disabled
    await user.type(screen.getByTestId('terminate-reason-input'), 'abc');
    expect(proposeBtn).toBeDisabled();

    // Long enough — enabled
    await user.type(screen.getByTestId('terminate-reason-input'), 'de');
    expect(proposeBtn).not.toBeDisabled();
  });

  it('Terminate dialog 4-eyes notice is visible', async () => {
    const user = userEvent.setup();
    renderHeader({ partnerCode: CODE, status: 'SUSPENDED' });

    await user.click(screen.getByTestId('terminate-btn'));
    expect(screen.getByTestId('four-eyes-notice')).toBeInTheDocument();
  });

  it('Proposing calls proposeLifecycleTransition with action+reason', async () => {
    proposeLifecycleMock.mockResolvedValueOnce({ changeRequestId: 'CR-99', status: 'PROPOSED' });
    const user = userEvent.setup();
    renderHeader({ partnerCode: CODE, status: 'LIVE' });

    await user.click(screen.getByTestId('suspend-btn'));

    // Open select and pick FRAUD_SUSPECTED
    const select = screen.getByTestId('suspend-reason-select');
    await user.click(select);
    await user.click(await screen.findByRole('option', { name: /FRAUD SUSPECTED/i }));

    await user.click(screen.getByTestId('confirm-transition-btn'));

    await waitFor(() => {
      expect(proposeLifecycleMock).toHaveBeenCalledWith(
        CODE,
        expect.objectContaining({ action: 'SUSPEND', reason: 'FRAUD_SUSPECTED' }),
      );
    });
    expect(snackInfo).toHaveBeenCalled();
  });

  it('Pending changeRequestId shows "Awaiting approval" chip', () => {
    renderHeader(
      { partnerCode: CODE, status: 'LIVE' },
      { [CODE]: { pendingChangeRequestId: 'CR-42', proposing: false, executing: false, error: null } },
    );
    expect(screen.getByTestId('pending-change-request-chip')).toBeInTheDocument();
    expect(screen.getByText(/CR-42/i)).toBeInTheDocument();
  });

  it('Execute button calls executeLifecycleTransition', async () => {
    executeLifecycleMock.mockResolvedValueOnce({ partnerCode: CODE, status: 'SUSPENDED' });
    const onTransitionDone = vi.fn();
    const user = userEvent.setup();
    renderHeader(
      { partnerCode: CODE, status: 'LIVE', onTransitionDone },
      { [CODE]: { pendingChangeRequestId: 'CR-42', proposing: false, executing: false, error: null } },
    );

    await user.click(screen.getByTestId('execute-transition-btn'));

    await waitFor(() => {
      expect(executeLifecycleMock).toHaveBeenCalledWith(CODE, 'CR-42');
    });
    expect(snackSuccess).toHaveBeenCalled();
    expect(onTransitionDone).toHaveBeenCalled();
  });
});
