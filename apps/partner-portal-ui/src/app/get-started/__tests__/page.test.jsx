import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { SnackbarProvider } from '@/components/SnackbarProvider';

vi.mock('next/navigation', () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn(), prefetch: vi.fn() }),
  usePathname: () => '/get-started'
}));

const issueSandboxKey = vi.fn();
const listSandboxKeys = vi.fn();

vi.mock('@/api/client', () => ({
  portalApi: {
    issueSandboxKey: (...a) => issueSandboxKey(...a),
    listSandboxKeys: (...a) => listSandboxKeys(...a)
  },
  currentPartnerId: () => 'GMEREMIT'
}));

import GetStartedPage from '../page';

function renderPage() {
  return render(
    <SnackbarProvider>
      <GetStartedPage />
    </SnackbarProvider>
  );
}

const GENERATED = {
  keyId: 'pk_test_ABC123keyid',
  apiKey: 'sk_test_EXAMPLE_not_a_real_secret',
  prefix: 'pk_test_ABC1',
  scope: 'SANDBOX',
  createdAt: '2026-07-03T10:00:00Z'
};

describe('GetStartedPage', () => {
  const clipboardWriteText = vi.fn().mockResolvedValue(undefined);

  beforeEach(() => {
    vi.clearAllMocks();
    listSandboxKeys.mockResolvedValue([]);
    issueSandboxKey.mockResolvedValue(GENERATED);
    clipboardWriteText.mockReset();
    clipboardWriteText.mockResolvedValue(undefined);
    if (!navigator.clipboard) {
      Object.defineProperty(navigator, 'clipboard', { configurable: true, value: {} });
    }
    Object.defineProperty(navigator.clipboard, 'writeText', {
      configurable: true,
      writable: true,
      value: clipboardWriteText
    });
  });

  it('renders the three onboarding steps and the endpoint table', async () => {
    renderPage();
    expect(screen.getByTestId('step-sandbox-key')).toBeInTheDocument();
    expect(screen.getByTestId('step-quickstart')).toBeInTheDocument();
    expect(screen.getByTestId('step-endpoints')).toBeInTheDocument();
    expect(screen.getByTestId('endpoint-table')).toBeInTheDocument();
    await waitFor(() => expect(listSandboxKeys).toHaveBeenCalledWith('GMEREMIT'));
  });

  it('shows a placeholder key in the quickstart before generation', () => {
    renderPage();
    expect(screen.getByTestId('quickstart-classify')).toHaveTextContent('sk_test_YOUR_SANDBOX_KEY');
  });

  it('generate button POSTs to issue endpoint and renders the returned key once', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByTestId('generate-sandbox-key'));

    await waitFor(() => expect(issueSandboxKey).toHaveBeenCalledWith('GMEREMIT', undefined));

    // The one-time plaintext key is rendered in the copyable result box...
    await waitFor(() => {
      expect(screen.getByTestId('sandbox-key-value')).toHaveTextContent(GENERATED.apiKey);
    });
    // ...with the "won't be shown again" warning and the SANDBOX scope chip.
    expect(screen.getByTestId('sandbox-key-result')).toHaveTextContent(/won't be shown again/i);
    expect(screen.getByTestId('sandbox-key-scope')).toHaveTextContent('SANDBOX');
  });

  it('substitutes the generated key into the quickstart snippets', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByTestId('generate-sandbox-key'));

    await waitFor(() => {
      expect(screen.getByTestId('quickstart-classify')).toHaveTextContent(GENERATED.apiKey);
    });
    expect(screen.getByTestId('quickstart-pay')).toHaveTextContent(GENERATED.apiKey);
    // partner id is threaded into the pay snippet
    expect(screen.getByTestId('quickstart-pay')).toHaveTextContent('"partner":"GMEREMIT"');
  });

  it('passes the entered name to the issue call', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.type(screen.getByTestId('sandbox-key-name'), 'local dev');
    await user.click(screen.getByTestId('generate-sandbox-key'));

    await waitFor(() => expect(issueSandboxKey).toHaveBeenCalledWith('GMEREMIT', 'local dev'));
  });

  it('lists existing sandbox keys returned by the backend', async () => {
    listSandboxKeys.mockResolvedValue([
      { keyId: 'pk_test_oldkey1', prefix: 'pk_test_old1', scope: 'SANDBOX', createdAt: '2026-06-01T00:00:00Z' }
    ]);
    renderPage();
    await waitFor(() => {
      expect(screen.getByTestId('existing-sandbox-keys')).toHaveTextContent('pk_test_old1');
    });
  });

  it('shows an error when generation fails', async () => {
    issueSandboxKey.mockRejectedValue(new Error('BFF POST failed: 500'));
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByTestId('generate-sandbox-key'));

    await waitFor(() => {
      expect(screen.getByTestId('sandbox-key-error')).toHaveTextContent(/failed: 500/i);
    });
  });
});
