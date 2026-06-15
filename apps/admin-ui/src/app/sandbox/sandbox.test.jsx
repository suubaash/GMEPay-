import { describe, it, expect, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { theme } from '@/theme/theme';

// next/navigation is used indirectly by layout imports; stub it so the module
// resolves cleanly in the jsdom environment.
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }),
  usePathname: () => '/sandbox',
}));

import SandboxPage from './page';

function renderPage() {
  return render(
    <ThemeProvider theme={theme}>
      <SandboxPage />
    </ThemeProvider>,
  );
}

describe('SandboxPage', () => {
  it('renders all three tab labels', () => {
    renderPage();
    expect(screen.getByRole('tab', { name: /merchant terminal/i })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: /gmeremit wallet/i })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: /fx rate board/i })).toBeInTheDocument();
  });

  it('shows the Merchant Terminal iframe by default and other panels are not visible', () => {
    renderPage();
    // tab 0 panel is visible
    const panel0 = screen.getByRole('tabpanel', { hidden: false });
    expect(within(panel0).getByTestId('iframe-0')).toBeInTheDocument();

    // The other panels have display:none so their children are not rendered in jsdom
    expect(screen.queryByTestId('iframe-1')).not.toBeInTheDocument();
    expect(screen.queryByTestId('iframe-2')).not.toBeInTheDocument();
  });

  it('switching to GMERemit Wallet tab shows iframe-1 and hides iframe-0', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByRole('tab', { name: /gmeremit wallet/i }));

    const visiblePanel = screen.getByRole('tabpanel', { hidden: false });
    expect(within(visiblePanel).getByTestId('iframe-1')).toBeInTheDocument();

    // iframe-0 is no longer rendered (its panel has display:none)
    expect(screen.queryByTestId('iframe-0')).not.toBeInTheDocument();
  });

  it('switching to FX Rate Board tab shows iframe-2', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByRole('tab', { name: /fx rate board/i }));

    const visiblePanel = screen.getByRole('tabpanel', { hidden: false });
    expect(within(visiblePanel).getByTestId('iframe-2')).toBeInTheDocument();
  });

  it('each simulator caption and URL are visible when their tab is active', async () => {
    const user = userEvent.setup();
    renderPage();

    // Merchant tab (default)
    expect(screen.getByText(/localhost:9104/)).toBeInTheDocument();

    // Wallet tab
    await user.click(screen.getByRole('tab', { name: /gmeremit wallet/i }));
    expect(screen.getByText(/localhost:9105/)).toBeInTheDocument();

    // Rate tab
    await user.click(screen.getByRole('tab', { name: /fx rate board/i }));
    expect(screen.getByText(/localhost:9101/)).toBeInTheDocument();
  });
});
