/**
 * Vitest coverage for OperatingHoursPreview (Slice 7).
 *
 * Contract:
 *  - Renders operating-hours-preview-section wrapper.
 *  - Shows placeholder text when schemeId is null.
 *  - Calls adminApi.listSchemeOperatingHours when schemeId is set.
 *  - Renders a table row for each day of the week.
 *  - Shows "—" for days with no hours data.
 *  - Shows open/close times when data is returned.
 */
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import { theme } from '@/theme/theme';

// ── Mock BFF client ────────────────────────────────────────────────────────────
const listSchemeOperatingHoursMock = vi.fn();

vi.mock('@/api/client', () => ({
  __esModule: true,
  ApiError: class ApiError extends Error {
    constructor(status, url, message) {
      super(message);
      this.status = status;
    }
  },
  adminApi: {
    listSchemeOperatingHours: (...args) => listSchemeOperatingHoursMock(...args),
  },
}));

import OperatingHoursPreview from '../OperatingHoursPreview';

const MOCK_HOURS = [
  { dayOfWeek: 'MON', openTime: '09:00', closeTime: '17:00', closed: false },
  { dayOfWeek: 'TUE', openTime: '09:00', closeTime: '17:00', closed: false },
  { dayOfWeek: 'WED', openTime: '09:00', closeTime: '17:00', closed: false },
  { dayOfWeek: 'THU', openTime: '09:00', closeTime: '17:00', closed: false },
  { dayOfWeek: 'FRI', openTime: '09:00', closeTime: '17:00', closed: false },
  { dayOfWeek: 'SAT', openTime: null, closeTime: null, closed: true },
  { dayOfWeek: 'SUN', openTime: null, closeTime: null, closed: true },
];

function renderPreview(schemeId = null) {
  return render(
    <ThemeProvider theme={theme}>
      <OperatingHoursPreview schemeId={schemeId} />
    </ThemeProvider>,
  );
}

describe('OperatingHoursPreview', () => {
  beforeEach(() => {
    listSchemeOperatingHoursMock.mockReset();
  });

  it('renders operating-hours-preview-section wrapper', () => {
    renderPreview();
    expect(screen.getByLabelText('operating-hours-preview-section')).toBeInTheDocument();
  });

  it('shows placeholder text when schemeId is null', () => {
    renderPreview();
    expect(
      screen.getByText(/Enable a scheme in the matrix above/),
    ).toBeInTheDocument();
  });

  it('calls listSchemeOperatingHours when schemeId is provided', async () => {
    listSchemeOperatingHoursMock.mockResolvedValue(MOCK_HOURS);
    renderPreview('ZEROPAY');
    await waitFor(() => {
      expect(listSchemeOperatingHoursMock).toHaveBeenCalledWith('ZEROPAY');
    });
  });

  it('renders operating-hours-table after data loads', async () => {
    listSchemeOperatingHoursMock.mockResolvedValue(MOCK_HOURS);
    renderPreview('ZEROPAY');
    await waitFor(() => {
      expect(screen.getByLabelText('operating-hours-table')).toBeInTheDocument();
    });
  });

  it('shows open time for MON', async () => {
    listSchemeOperatingHoursMock.mockResolvedValue(MOCK_HOURS);
    renderPreview('ZEROPAY');
    await waitFor(() => {
      expect(screen.getByLabelText('op-hours-open-MON')).toHaveTextContent('09:00');
    });
  });

  it('shows "—" for SAT open time (closed day)', async () => {
    listSchemeOperatingHoursMock.mockResolvedValue(MOCK_HOURS);
    renderPreview('ZEROPAY');
    await waitFor(() => {
      expect(screen.getByLabelText('op-hours-open-SAT')).toHaveTextContent('—');
    });
  });

  it('shows Closed status for SAT', async () => {
    listSchemeOperatingHoursMock.mockResolvedValue(MOCK_HOURS);
    renderPreview('ZEROPAY');
    await waitFor(() => {
      expect(screen.getByLabelText('op-hours-status-SAT')).toHaveTextContent('Closed');
    });
  });

  it('shows Open status for MON', async () => {
    listSchemeOperatingHoursMock.mockResolvedValue(MOCK_HOURS);
    renderPreview('ZEROPAY');
    await waitFor(() => {
      expect(screen.getByLabelText('op-hours-status-MON')).toHaveTextContent('Open');
    });
  });

  it('shows "—" for all days when API returns empty array', async () => {
    listSchemeOperatingHoursMock.mockResolvedValue([]);
    renderPreview('BAKONG');
    await waitFor(() => {
      expect(screen.getByLabelText('operating-hours-table')).toBeInTheDocument();
    });
    expect(screen.getByLabelText('op-hours-open-MON')).toHaveTextContent('—');
  });

  it('shows table even when API call fails', async () => {
    listSchemeOperatingHoursMock.mockRejectedValue(new Error('BFF error'));
    renderPreview('PROMPT_PAY');
    await waitFor(() => {
      expect(screen.getByLabelText('operating-hours-table')).toBeInTheDocument();
    });
  });
});
