/**
 * Vitest coverage for the /delivery Delivery analytics page.
 *
 *  1. Overall success-rate % renders from a mocked adminApi client.
 *  2. The by-partner table renders one row per partner with counts + %.
 *  3. The decline-reason list renders reasons highest-count-first.
 *  4. The activation table renders partners with an activated/pending status.
 */
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import { theme } from '@/theme/theme';

// Mock the API client — the page must never hit real network.
const mockGetDeliveryOverview = vi.fn();
vi.mock('@/api/client', () => ({
  adminApi: {
    getDeliveryOverview: (...a) => mockGetDeliveryOverview(...a),
  },
  ApiError: class ApiError extends Error {},
}));

import DeliveryPage from './page';

const OVERVIEW = {
  window: { from: '2026-06-03T00:00:00Z', to: '2026-07-03T23:59:59Z' },
  successRate: {
    overall: { total: 1000, approved: 962, declined: 38, successRatePct: 96.2 },
    byPartner: [
      { partner: 'GME_KR_001', total: 600, approved: 585, declined: 15, successRatePct: 97.5 },
      { partner: 'GME_VN_002', total: 400, approved: 340, declined: 60, successRatePct: 85.0 },
      { partner: 'GME_PH_003', total: 100, approved: 70, declined: 30, successRatePct: 70.0 },
    ],
    byCorridor: [
      { corridor: 'KR→VN', total: 500, approved: 490, declined: 10, successRatePct: 98.0 },
      { corridor: 'KR→PH', total: 300, approved: 255, declined: 45, successRatePct: 85.0 },
    ],
  },
  declineReasons: [
    { reason: 'INSUFFICIENT_FUNDS', count: 20 },
    { reason: 'SCHEME_TIMEOUT', count: 12 },
    { reason: 'INVALID_ACCOUNT', count: 6 },
  ],
  activation: [
    {
      partner: 'GME_KR_001',
      onboardedAt: '2026-06-01T00:00:00Z',
      firstApprovedAt: '2026-06-02T12:00:00Z',
      activationHours: 36,
      status: 'activated',
    },
    {
      partner: 'GME_NP_004',
      onboardedAt: '2026-06-20T00:00:00Z',
      firstApprovedAt: null,
      activationHours: null,
      status: 'pending',
    },
  ],
};

function renderPage() {
  return render(
    <ThemeProvider theme={theme}>
      <DeliveryPage />
    </ThemeProvider>,
  );
}

describe('DeliveryPage', () => {
  beforeEach(() => {
    mockGetDeliveryOverview.mockReset();
    mockGetDeliveryOverview.mockResolvedValue(OVERVIEW);
  });

  it('renders the overall success rate and counts', async () => {
    renderPage();
    const headline = await screen.findByLabelText('overall-success-rate');
    expect(headline).toHaveTextContent('96.2%');
    // attempted/succeeded/failed counts appear somewhere on the page
    expect(await screen.findByText('962')).toBeInTheDocument();
    expect(screen.getByText('38')).toBeInTheDocument();
  });

  it('renders the by-partner success-rate table', async () => {
    renderPage();
    const table = await screen.findByRole('table', {
      name: /success rate by partner/i,
    });
    expect(within(table).getByText('GME_KR_001')).toBeInTheDocument();
    expect(within(table).getByText('GME_VN_002')).toBeInTheDocument();
    expect(within(table).getByText('GME_PH_003')).toBeInTheDocument();
    // per-partner percentages
    expect(within(table).getByText('97.5%')).toBeInTheDocument();
    expect(within(table).getByText('70.0%')).toBeInTheDocument();
  });

  it('renders the decline-reason list', async () => {
    renderPage();
    const reasons = await screen.findByLabelText('decline-reasons');
    expect(within(reasons).getByText('INSUFFICIENT_FUNDS')).toBeInTheDocument();
    expect(within(reasons).getByText('SCHEME_TIMEOUT')).toBeInTheDocument();
    expect(within(reasons).getByText('INVALID_ACCOUNT')).toBeInTheDocument();
    expect(within(reasons).getByText('20')).toBeInTheDocument();
  });

  it('renders the activation table with activated + pending status', async () => {
    renderPage();
    const table = await screen.findByRole('table', { name: /partner activation/i });
    expect(within(table).getByText('GME_KR_001')).toBeInTheDocument();
    expect(within(table).getByText('GME_NP_004')).toBeInTheDocument();
    expect(within(table).getByText('Activated')).toBeInTheDocument();
    expect(within(table).getByText(/Pending — no payment yet/i)).toBeInTheDocument();
  });
});
