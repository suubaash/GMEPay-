/**
 * The E2E Test tab is now an honest DISABLED notice, not a runner console (GAP T0-5).
 *
 * The old suite drove a run form and asserted on POST /e2e/run + the run history
 * table. Those paths no longer exist: the payment-executor sandbox runner is
 * @ConditionalOnProperty (404 by default) and internal-token gated when on, and the
 * admin portal's same-origin `/e2e/*` rewrite was deleted. Coverage is therefore
 * inverted rather than dropped — the meaningful assertions now are:
 *
 *  1. the tab says the runner is not enabled (instead of looking broken),
 *  2. it explains it is a dev-only tool and how to run the journey without the portal,
 *  3. it makes NO network call — in particular nothing to /e2e/**,
 *  4. it offers no run affordance that could imply money can be moved from here.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import { theme } from '@/theme/theme';

import E2eTestConsole from './E2eTestConsole';

function renderConsole() {
  return render(
    <ThemeProvider theme={theme}>
      <E2eTestConsole />
    </ThemeProvider>,
  );
}

describe('E2eTestConsole (disabled sandbox runner notice)', () => {
  let fetchMock;

  beforeEach(() => {
    fetchMock = vi.fn(() =>
      Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({}) }),
    );
    global.fetch = fetchMock;
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('states that the sandbox runner is not enabled', () => {
    renderConsole();
    expect(screen.getByTestId('e2e-runner-disabled')).toBeInTheDocument();
    expect(screen.getByText(/sandbox runner not enabled/i)).toBeInTheDocument();
    expect(screen.getByText(/not reachable from the/i)).toBeInTheDocument();
  });

  it('is presented as a developer tool and names the gate + the removed proxy', () => {
    renderConsole();
    expect(screen.getByText(/developer tool/i)).toBeInTheDocument();
    expect(screen.getByText('gmepay.sandbox.e2e.enabled=true')).toBeInTheDocument();
    expect(screen.getAllByText('X-Gme-Internal').length).toBeGreaterThan(0);
    expect(screen.getByText(/proxy to it was removed/i)).toBeInTheDocument();
  });

  it('explains how to run the journey without the portal', () => {
    renderConsole();
    expect(screen.getByText(/running the journey from a dev machine/i)).toBeInTheDocument();
    expect(screen.getByText('POST /v1/sandbox/e2e/run')).toBeInTheDocument();
  });

  it('makes no network call at all (nothing to /e2e/**)', () => {
    renderConsole();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('offers no run affordance', () => {
    renderConsole();
    expect(screen.queryByRole('button', { name: /run test/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('spinbutton')).not.toBeInTheDocument();
    expect(screen.queryByRole('radio')).not.toBeInTheDocument();
  });
});
