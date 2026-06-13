/**
 * Vitest coverage for PreconditionPanel (Slice 8).
 *
 * Contract:
 *  - Renders precondition-panel aria-label.
 *  - Fetches preconditions on mount.
 *  - Green check (aria-label precondition-met-*) for met items.
 *  - Red error (aria-label precondition-unmet-*) for unmet items.
 *  - Refresh button re-dispatches fetchActivationPreconditions.
 */
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { Provider } from 'react-redux';
import { configureStore } from '@reduxjs/toolkit';
import draftsReducer from '@/store/draftsSlice';
import authReducer from '@/store/authSlice';
import lifecycleReducer from '@/store/lifecycleSlice';
import { theme } from '@/theme/theme';

const getActivationPreconditionsMock = vi.fn();

vi.mock('@/api/client', () => ({
  __esModule: true,
  ApiError: class ApiError extends Error {
    constructor(status, url, msg) { super(msg); this.status = status; }
  },
  adminApi: {
    getActivationPreconditions: (...a) => getActivationPreconditionsMock(...a),
  },
}));

vi.mock('@/components/SnackbarProvider', () => ({
  __esModule: true,
  default: ({ children }) => <>{children}</>,
  useSnackbar: () => ({
    success: vi.fn(), error: vi.fn(), info: vi.fn(), warning: vi.fn(),
  }),
}));

import { PreconditionPanel } from '../PreconditionPanel';

const PARTNER_CODE = 'GME_KR_TEST_PRECOND';

function buildStore(preload = {}) {
  return configureStore({
    reducer: {
      drafts: draftsReducer,
      auth: authReducer,
      lifecycle: lifecycleReducer,
    },
    preloadedState: preload,
  });
}

function renderPanel({ preload = {} } = {}) {
  const store = buildStore(preload);
  return {
    store,
    ...render(
      <Provider store={store}>
        <ThemeProvider theme={theme}>
          <PreconditionPanel partnerCode={PARTNER_CODE} />
        </ThemeProvider>
      </Provider>,
    ),
  };
}

describe('PreconditionPanel', () => {
  beforeEach(() => {
    getActivationPreconditionsMock.mockReset();
    getActivationPreconditionsMock.mockResolvedValue([]);
  });

  it('renders precondition-panel aria-label', () => {
    renderPanel();
    expect(screen.getByLabelText('precondition-panel')).toBeInTheDocument();
  });

  it('fetches preconditions on mount', async () => {
    renderPanel();
    await waitFor(() => {
      expect(getActivationPreconditionsMock).toHaveBeenCalledWith(PARTNER_CODE);
    });
  });

  it('renders met precondition with green check aria-label', async () => {
    getActivationPreconditionsMock.mockResolvedValue([
      { key: 'IDENTITY_COMPLETE', description: 'Identity step complete', met: true },
    ]);
    renderPanel();
    await waitFor(() => {
      expect(screen.getByLabelText('precondition-met-IDENTITY_COMPLETE')).toBeInTheDocument();
    });
  });

  it('renders unmet precondition with red error aria-label', async () => {
    getActivationPreconditionsMock.mockResolvedValue([
      { key: 'KYB_CLEARED', description: 'KYB must be cleared', met: false },
    ]);
    renderPanel();
    await waitFor(() => {
      expect(screen.getByLabelText('precondition-unmet-KYB_CLEARED')).toBeInTheDocument();
    });
  });

  it('shows description text for each precondition', async () => {
    getActivationPreconditionsMock.mockResolvedValue([
      { key: 'BANKING_SET', description: 'At least one bank account', met: true },
      { key: 'SCHEMES_SET', description: 'At least one scheme enrolled', met: false },
    ]);
    renderPanel();
    await waitFor(() => {
      expect(screen.getByText('At least one bank account')).toBeInTheDocument();
      expect(screen.getByText('At least one scheme enrolled')).toBeInTheDocument();
    });
  });

  it('refresh button re-fetches preconditions', async () => {
    getActivationPreconditionsMock.mockResolvedValue([]);
    renderPanel();
    await waitFor(() => expect(getActivationPreconditionsMock).toHaveBeenCalledTimes(1));

    const user = userEvent.setup();
    await user.click(screen.getByLabelText('refresh-preconditions'));

    await waitFor(() => {
      expect(getActivationPreconditionsMock).toHaveBeenCalledTimes(2);
    });
  });
});
