/**
 * Vitest coverage for IpAllowlistSection (Slice 8).
 *
 * Contract:
 *  - Renders ip-allowlist-section aria-label.
 *  - Operator can add a CIDR — appears in cidr-list.
 *  - Operator can remove a CIDR — disappears from cidr-list.
 *  - 10-cap enforced: ip-cap-warning shown when 10 entries exist; Add disabled.
 *  - Save button dispatches patchStep8IpAllowlist with { env, cidrs }.
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

const patchIpAllowlistMock = vi.fn();

vi.mock('@/api/client', () => ({
  __esModule: true,
  ApiError: class ApiError extends Error {
    constructor(status, url, msg) { super(msg); this.status = status; }
  },
  adminApi: {
    patchDraftStep8IpAllowlist: (...a) => patchIpAllowlistMock(...a),
  },
}));

const snackSuccess = vi.fn();
const snackError = vi.fn();
const snackWarning = vi.fn();
vi.mock('@/components/SnackbarProvider', () => ({
  __esModule: true,
  default: ({ children }) => <>{children}</>,
  useSnackbar: () => ({
    success: snackSuccess, error: snackError, info: vi.fn(), warning: snackWarning,
  }),
}));

import { IpAllowlistSection } from '../IpAllowlistSection';

const PARTNER_CODE = 'GME_KR_TEST_IP';

function buildStore(preload = {}) {
  return configureStore({
    reducer: { drafts: draftsReducer, auth: authReducer, lifecycle: lifecycleReducer },
    preloadedState: preload,
  });
}

function renderSection({ preload = {} } = {}) {
  const store = buildStore(preload);
  return {
    store,
    ...render(
      <Provider store={store}>
        <ThemeProvider theme={theme}>
          <IpAllowlistSection partnerCode={PARTNER_CODE} />
        </ThemeProvider>
      </Provider>,
    ),
  };
}

describe('IpAllowlistSection', () => {
  beforeEach(() => {
    patchIpAllowlistMock.mockReset();
    snackSuccess.mockReset();
    snackError.mockReset();
    snackWarning.mockReset();
  });

  it('renders ip-allowlist-section aria-label', () => {
    renderSection();
    expect(screen.getByLabelText('ip-allowlist-section')).toBeInTheDocument();
  });

  it('adds a CIDR entry and shows it in the list', async () => {
    renderSection();
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('cidr-input'), '192.168.1.0/24');
    await user.click(screen.getByLabelText('add-cidr'));
    await waitFor(() => {
      expect(screen.getByText('192.168.1.0/24')).toBeInTheDocument();
    });
  });

  it('removes a CIDR entry when remove button is clicked', async () => {
    renderSection();
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('cidr-input'), '10.0.0.0/8');
    await user.click(screen.getByLabelText('add-cidr'));
    await waitFor(() => expect(screen.getByText('10.0.0.0/8')).toBeInTheDocument());

    await user.click(screen.getByLabelText('remove-cidr-10.0.0.0/8'));
    await waitFor(() => {
      expect(screen.queryByText('10.0.0.0/8')).not.toBeInTheDocument();
    });
  });

  it('shows ip-cap-warning and disables Add at 10 entries', async () => {
    renderSection();
    const user = userEvent.setup();
    for (let i = 1; i <= 10; i++) {
      await user.clear(screen.getByLabelText('cidr-input'));
      await user.type(screen.getByLabelText('cidr-input'), `10.0.${i}.0/24`);
      await user.click(screen.getByLabelText('add-cidr'));
    }
    await waitFor(() => {
      expect(screen.getByLabelText('ip-cap-warning')).toBeInTheDocument();
    });
    expect(screen.getByLabelText('add-cidr')).toBeDisabled();
  });

  it('Save button dispatches patchStep8IpAllowlist', async () => {
    patchIpAllowlistMock.mockResolvedValue({ partnerCode: PARTNER_CODE });
    renderSection();
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('cidr-input'), '203.0.113.0/24');
    await user.click(screen.getByLabelText('add-cidr'));
    await user.click(screen.getByLabelText('save-ip-allowlist'));

    await waitFor(() => {
      expect(patchIpAllowlistMock).toHaveBeenCalledWith(
        PARTNER_CODE,
        expect.objectContaining({ cidrs: ['203.0.113.0/24'] }),
      );
    });
    expect(snackSuccess).toHaveBeenCalled();
  });
});
