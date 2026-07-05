/**
 * ActivationTile tests — mocks the BFF client and asserts the three activation
 * states: activated (row with firstApprovedAt), pending (row without), and
 * no-data (partner absent from the delivery overview).
 */
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import ActivationTile, { formatHours } from '@/components/ActivationTile';
import { adminApi } from '@/api/client';

vi.mock('@/api/client', () => ({
  adminApi: {
    getDeliveryOverview: vi.fn(),
  },
}));

const OVERVIEW = {
  activation: [
    {
      partner: 'GME_KR_001',
      onboardedAt: '2026-06-01T00:00:00Z',
      firstApprovedAt: '2026-06-03T12:00:00Z',
      activationHours: 60,
      status: 'activated',
    },
    {
      partner: 'GME_NP_002',
      onboardedAt: '2026-06-10T00:00:00Z',
      firstApprovedAt: null,
      activationHours: null,
      status: 'pending',
    },
  ],
};

describe('ActivationTile', () => {
  beforeEach(() => {
    vi.mocked(adminApi.getDeliveryOverview).mockResolvedValue(OVERVIEW);
  });

  it('renders activation hours and status for an activated partner', async () => {
    render(<ActivationTile partnerCode="GME_KR_001" />);
    await waitFor(() =>
      expect(screen.getByTestId('activation-hours')).toHaveTextContent('2d 12h')
    );
    expect(screen.getByTestId('activation-status')).toHaveTextContent('Activated');
    expect(screen.getByTestId('activation-first-approved')).not.toHaveTextContent('—');
  });

  it('renders pending state when the partner has no approved txn yet', async () => {
    render(<ActivationTile partnerCode="GME_NP_002" />);
    await waitFor(() =>
      expect(screen.getByTestId('activation-status')).toHaveTextContent('Pending first txn')
    );
    expect(screen.getByTestId('activation-first-approved')).toHaveTextContent('—');
    expect(screen.getByTestId('activation-hours')).toHaveTextContent('—');
  });

  it('renders a no-data message when the partner is absent from the overview', async () => {
    render(<ActivationTile partnerCode="UNKNOWN" />);
    await waitFor(() =>
      expect(
        screen.getByText(/No activation data yet for this partner/i)
      ).toBeInTheDocument()
    );
  });

  it('formatHours renders hours under 48h and d/h above', () => {
    expect(formatHours(0)).toBe('0h');
    expect(formatHours(47)).toBe('47h');
    expect(formatHours(60)).toBe('2d 12h');
    expect(formatHours(null)).toBe('—');
  });
});
