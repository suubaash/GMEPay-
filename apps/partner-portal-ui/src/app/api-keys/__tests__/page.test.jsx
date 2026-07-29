import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Provider as ReduxProvider } from 'react-redux';
import { configureStore, createSlice } from '@reduxjs/toolkit';
import { SnackbarProvider } from '@/components/SnackbarProvider';

vi.mock('lottie-react', () => ({
  default: ({ animationData }) => (
    <div data-testid="mock-lottie" data-has-animation={animationData ? 'yes' : 'no'} />
  )
}));

vi.mock('next/navigation', () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn(), prefetch: vi.fn() }),
  usePathname: () => '/api-keys'
}));

vi.mock('@/api/client', () => ({
  portalApi: {
    listApiKeys: vi.fn().mockResolvedValue([]),
    getBalance: vi.fn(),
    getOverview: vi.fn(),
    listTransactions: vi.fn(),
    getTransaction: vi.fn(),
    listWebhooks: vi.fn(),
    getProfile: vi.fn(),
    downloadStatement: vi.fn(),
    login: vi.fn(),
    logout: vi.fn(),
    refreshToken: vi.fn()
  },
  currentPartnerId: () => 'GMEREMIT',
  listApiKeys: vi.fn(),
  downloadStatement: vi.fn()
}));

import ApiKeysPage from '../page';

function renderWithApiKeys(state) {
  const slice = createSlice({
    name: 'apiKeys',
    initialState: state,
    reducers: {}
  });
  const store = configureStore({ reducer: { apiKeys: slice.reducer } });
  return render(
    <ReduxProvider store={store}>
      <SnackbarProvider>
        <ApiKeysPage />
      </SnackbarProvider>
    </ReduxProvider>
  );
}

/**
 * The REAL wire shape auth-identity's api_keys registry produces (gap T1-3):
 * name is null, scopes is empty and lastUsedAt is null because none of the three
 * has a column/owner; status is the api_keys.status roster and environment is the
 * field that actually distinguishes a live key from a test one.
 */
const TWO_KEYS = [
  {
    keyId: 'k_01HXYZACTIVE',
    name: null,
    prefix: 'gmepk_live_abcd1234',
    scopes: [],
    createdAt: '2026-01-15T08:00:00Z',
    lastUsedAt: null,
    status: 'ACTIVE',
    environment: 'PRODUCTION',
    expiresAt: null
  },
  {
    keyId: 'k_01HXYZSBX',
    name: null,
    prefix: 'gmepk_live_efgh5678',
    scopes: [],
    createdAt: '2026-05-30T08:00:00Z',
    lastUsedAt: null,
    status: 'PENDING_EXPIRY',
    environment: 'SANDBOX',
    expiresAt: '2027-05-30T08:00:00Z'
  }
];

describe('ApiKeysPage', () => {
  // jsdom 25 pre-installs a non-replaceable `navigator.clipboard`, and
  // user-event 14.5 also tries to shim it in setup(). Install our own
  // writeText mock ONCE per test via Object.defineProperty on the existing
  // clipboard object (which IS configurable in jsdom 25). Per-test we just
  // call .mockReset() / .mockResolvedValueOnce() / .mockRejectedValueOnce().
  const clipboardWriteText = vi.fn().mockResolvedValue(undefined);

  beforeEach(() => {
    vi.clearAllMocks();
    clipboardWriteText.mockReset();
    clipboardWriteText.mockResolvedValue(undefined);
    if (!navigator.clipboard) {
      Object.defineProperty(navigator, 'clipboard', {
        configurable: true,
        value: {}
      });
    }
    Object.defineProperty(navigator.clipboard, 'writeText', {
      configurable: true,
      writable: true,
      value: clipboardWriteText
    });
  });

  it('renders the Phase 2 rotation banner', () => {
    renderWithApiKeys({ data: TWO_KEYS, status: 'succeeded', error: null });
    expect(screen.getByTestId('api-keys-phase2-banner')).toBeInTheDocument();
  });

  it('renders a row per key with the prefix masked to first 8 chars', () => {
    renderWithApiKeys({ data: TWO_KEYS, status: 'succeeded', error: null });
    expect(screen.getByTestId('api-keys-table')).toBeInTheDocument();
    expect(screen.getByTestId('api-key-row-k_01HXYZACTIVE')).toBeInTheDocument();
    expect(screen.getByTestId('api-key-row-k_01HXYZSBX')).toBeInTheDocument();

    // first 8 chars of "gmepk_live_abcd1234" -> "gmepk_li" then "****"
    expect(screen.getByTestId('api-key-prefix-k_01HXYZACTIVE')).toHaveTextContent('gmepk_li****');
    expect(screen.getByTestId('api-key-prefix-k_01HXYZSBX')).toHaveTextContent('gmepk_li****');
  });

  it('shows the real environment per key so a test key is distinguishable', () => {
    renderWithApiKeys({ data: TWO_KEYS, status: 'succeeded', error: null });
    expect(screen.getByTestId('api-key-env-k_01HXYZACTIVE')).toHaveTextContent('PRODUCTION');
    expect(screen.getByTestId('api-key-env-k_01HXYZSBX')).toHaveTextContent('SANDBOX');
  });

  it('explains why name/scopes/last-used are blank instead of leaving bare dashes', () => {
    // Gap T1-3: those three fields have no source, so the page must say so rather
    // than let an em dash read as "we lost your data" (or invent a value).
    renderWithApiKeys({ data: TWO_KEYS, status: 'succeeded', error: null });
    expect(screen.getByTestId('api-keys-absent-fields-note')).toBeInTheDocument();
  });

  it('renders the real api_keys status roster, not fabricated PRIMARY/ROTATING labels', () => {
    renderWithApiKeys({ data: TWO_KEYS, status: 'succeeded', error: null });
    expect(screen.getByText('ACTIVE')).toBeInTheDocument();
    expect(screen.getByText('PENDING_EXPIRY')).toBeInTheDocument();
    expect(screen.queryByText('PRIMARY')).not.toBeInTheDocument();
    expect(screen.queryByText('ROTATING')).not.toBeInTheDocument();
  });

  it('shows a loading skeleton when status is loading', () => {
    renderWithApiKeys({ data: null, status: 'loading', error: null });
    expect(screen.getByTestId('loading-skeleton')).toBeInTheDocument();
  });

  it('shows an empty state when the list is empty', () => {
    renderWithApiKeys({ data: [], status: 'succeeded', error: null });
    expect(screen.getByText(/No API keys provisioned/i)).toBeInTheDocument();
  });

  it('renders an error alert on failed fetch', () => {
    renderWithApiKeys({
      data: null,
      status: 'failed',
      error: 'BFF GET /v1/portal/GMEREMIT/api-keys failed: 500'
    });
    // The page renders two role=alert elements: the Phase-2 info banner and
    // the ErrorAlert. Pick the one carrying the failure message.
    const errorAlert = screen
      .getAllByRole('alert')
      .find((el) => /failed: 500/.test(el.textContent ?? ''));
    expect(errorAlert).toBeTruthy();
  });

  it('copy-prefix click writes the prefix to the clipboard and shows a snackbar', async () => {
    clipboardWriteText.mockResolvedValueOnce(undefined);

    renderWithApiKeys({ data: TWO_KEYS, status: 'succeeded', error: null });
    // user-event 14 installs its own clipboard shim in setup() that replaces
    // navigator.clipboard wholesale. Set up the user FIRST, then re-install
    // our writeText mock so the page's call lands on it.
    const user = userEvent.setup();
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText: clipboardWriteText }
    });
    await user.click(screen.getByTestId('copy-prefix-k_01HXYZACTIVE'));

    await waitFor(() => {
      expect(clipboardWriteText).toHaveBeenCalledWith('gmepk_live_abcd1234');
    });
    await waitFor(() => {
      expect(screen.getByTestId('snackbar-toast')).toHaveTextContent(
        /Prefix copied to clipboard/i
      );
    });
  });

  it('copy-prefix click surfaces an error snackbar when clipboard fails', async () => {
    clipboardWriteText.mockRejectedValueOnce(new Error('denied'));

    renderWithApiKeys({ data: TWO_KEYS, status: 'succeeded', error: null });
    const user = userEvent.setup();
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText: clipboardWriteText }
    });
    await user.click(screen.getByTestId('copy-prefix-k_01HXYZSBX'));

    await waitFor(() => {
      expect(screen.getByTestId('snackbar-toast')).toHaveTextContent(
        /Could not copy to clipboard/i
      );
    });
  });
});
