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

const TWO_KEYS = [
  {
    keyId: 'k_01HXYZACTIVE',
    name: 'Production',
    prefix: 'gmepk_live_abcd1234',
    scopes: ['payments:create', 'payments:read'],
    createdAt: '2026-01-15T08:00:00Z',
    lastUsedAt: '2026-06-09T11:24:00Z',
    status: 'ACTIVE'
  },
  {
    keyId: 'k_01HXYZROT',
    name: 'Production (rotating)',
    prefix: 'gmepk_live_efgh5678',
    scopes: ['payments:create'],
    createdAt: '2026-05-30T08:00:00Z',
    lastUsedAt: null,
    status: 'ROTATING'
  }
];

describe('ApiKeysPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders the Phase 2 rotation banner', () => {
    renderWithApiKeys({ data: TWO_KEYS, status: 'succeeded', error: null });
    expect(screen.getByTestId('api-keys-phase2-banner')).toBeInTheDocument();
  });

  it('renders a row per key with the prefix masked to first 8 chars', () => {
    renderWithApiKeys({ data: TWO_KEYS, status: 'succeeded', error: null });
    expect(screen.getByTestId('api-keys-table')).toBeInTheDocument();
    expect(screen.getByTestId('api-key-row-k_01HXYZACTIVE')).toBeInTheDocument();
    expect(screen.getByTestId('api-key-row-k_01HXYZROT')).toBeInTheDocument();

    // first 8 chars of "gmepk_live_abcd1234" -> "gmepk_li" then "****"
    expect(screen.getByTestId('api-key-prefix-k_01HXYZACTIVE')).toHaveTextContent('gmepk_li****');
    expect(screen.getByTestId('api-key-prefix-k_01HXYZROT')).toHaveTextContent('gmepk_li****');
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
    expect(screen.getByRole('alert')).toHaveTextContent(/failed: 500/);
  });

  it('copy-prefix click writes the prefix to the clipboard and shows a snackbar', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText }
    });

    renderWithApiKeys({ data: TWO_KEYS, status: 'succeeded', error: null });
    const user = userEvent.setup();
    await user.click(screen.getByTestId('copy-prefix-k_01HXYZACTIVE'));

    expect(writeText).toHaveBeenCalledWith('gmepk_live_abcd1234');
    await waitFor(() => {
      expect(screen.getByTestId('snackbar-toast')).toHaveTextContent(
        /Prefix copied to clipboard/i
      );
    });
  });

  it('copy-prefix click surfaces an error snackbar when clipboard fails', async () => {
    const writeText = vi.fn().mockRejectedValue(new Error('denied'));
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText }
    });

    renderWithApiKeys({ data: TWO_KEYS, status: 'succeeded', error: null });
    const user = userEvent.setup();
    await user.click(screen.getByTestId('copy-prefix-k_01HXYZROT'));

    await waitFor(() => {
      expect(screen.getByTestId('snackbar-toast')).toHaveTextContent(
        /Could not copy to clipboard/i
      );
    });
  });
});
