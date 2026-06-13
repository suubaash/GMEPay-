/**
 * Vitest coverage for StatusActionHistory (Slice 8).
 *
 * Covers:
 *  1. Renders lifecycle transition rows from audit trail.
 *  2. Filters out non-lifecycle events.
 *  3. Pagination appears for large sets.
 *  4. Empty state when no lifecycle events.
 *  5. Reason extracted from afterJson.
 */
import { describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { Provider } from 'react-redux';
import { configureStore } from '@reduxjs/toolkit';
import { ThemeProvider } from '@mui/material/styles';
import { theme } from '@/theme/theme';
import auditTrailReducer from '@/store/auditTrailSlice';
import authReducer from '@/store/authSlice';

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn() }),
  useParams: () => ({}),
}));

const fetchTrailMock = vi.fn();
vi.mock('@/api/client', () => ({
  adminApi: {
    getAuditTrail: (...a) => fetchTrailMock(...a),
  },
}));

import StatusActionHistory from '../StatusActionHistory';

const LIFECYCLE_ENTRIES = [
  {
    recordedAt: '2026-06-01T10:00:00Z',
    actorId: 'alice',
    eventType: 'PARTNER_LIFECYCLE_SUSPENDED',
    beforeJson: null,
    afterJson: '{"reason":"FRAUD_SUSPECTED"}',
  },
  {
    recordedAt: '2026-06-05T12:00:00Z',
    actorId: 'bob',
    eventType: 'PARTNER_LIFECYCLE_REACTIVATED',
    beforeJson: null,
    afterJson: '{"reason":"COMPLIANCE_REVIEW"}',
  },
];

const MIXED_ENTRIES = [
  ...LIFECYCLE_ENTRIES,
  {
    recordedAt: '2026-06-03T08:00:00Z',
    actorId: 'carol',
    eventType: 'PARTNER_UPDATED',
    beforeJson: '{}',
    afterJson: '{"type":"OVERSEAS"}',
  },
];

function makeStore(entries = LIFECYCLE_ENTRIES, total = 2) {
  return configureStore({
    reducer: { auditTrail: auditTrailReducer, auth: authReducer },
    preloadedState: {
      auditTrail: {
        byKey: {
          'partner:GME_KR_001': {
            entries,
            chainValid: null,
            page: 0,
            size: 20,
            total,
            loading: false,
            error: null,
          },
        },
      },
    },
  });
}

function renderHistory(props, entries, total) {
  const store = makeStore(entries, total);
  return render(
    <Provider store={store}>
      <ThemeProvider theme={theme}>
        <StatusActionHistory {...props} />
      </ThemeProvider>
    </Provider>,
  );
}

describe('StatusActionHistory', () => {
  it('renders lifecycle transition rows', async () => {
    fetchTrailMock.mockResolvedValue({ entries: LIFECYCLE_ENTRIES, chainValid: null, page: 0, size: 20, total: 2 });
    renderHistory({ partnerCode: 'GME_KR_001' }, LIFECYCLE_ENTRIES, 2);
    await waitFor(() => {
      const rows = screen.getAllByTestId('lifecycle-history-row');
      expect(rows).toHaveLength(2);
    });
    expect(screen.getByText('PARTNER_LIFECYCLE_SUSPENDED')).toBeInTheDocument();
    expect(screen.getByText('PARTNER_LIFECYCLE_REACTIVATED')).toBeInTheDocument();
  });

  it('filters out non-lifecycle events', async () => {
    fetchTrailMock.mockResolvedValue({ entries: MIXED_ENTRIES, chainValid: null, page: 0, size: 20, total: 3 });
    renderHistory({ partnerCode: 'GME_KR_001' }, MIXED_ENTRIES, 3);
    // Only 2 lifecycle rows visible
    await waitFor(() => {
      const rows = screen.getAllByTestId('lifecycle-history-row');
      expect(rows).toHaveLength(2);
    });
    expect(screen.queryByText('PARTNER_UPDATED')).not.toBeInTheDocument();
  });

  it('shows reason from afterJson', async () => {
    fetchTrailMock.mockResolvedValue({ entries: LIFECYCLE_ENTRIES, chainValid: null, page: 0, size: 20, total: 2 });
    renderHistory({ partnerCode: 'GME_KR_001' }, LIFECYCLE_ENTRIES, 2);
    await waitFor(() => {
      expect(screen.getByText('FRAUD_SUSPECTED')).toBeInTheDocument();
    });
  });

  it('shows empty state when no lifecycle events', async () => {
    fetchTrailMock.mockResolvedValue({ entries: [], chainValid: null, page: 0, size: 20, total: 0 });
    renderHistory({ partnerCode: 'GME_KR_001' }, [], 0);
    await waitFor(() => {
      expect(screen.getByText(/No lifecycle transitions recorded yet/i)).toBeInTheDocument();
    });
  });

  it('shows pagination for large result sets', () => {
    const manyEntries = Array.from({ length: 5 }, (_, i) => ({
      recordedAt: `2026-06-0${i + 1}T00:00:00Z`,
      actorId: 'alice',
      eventType: 'PARTNER_LIFECYCLE_SUSPENDED',
      beforeJson: null,
      afterJson: '{"reason":"OTHER"}',
    }));
    fetchTrailMock.mockResolvedValue({ entries: manyEntries, chainValid: null, page: 0, size: 2, total: 25 });

    const storeWithMany = configureStore({
      reducer: { auditTrail: auditTrailReducer, auth: authReducer },
      preloadedState: {
        auditTrail: {
          byKey: {
            'partner:GME_KR_001': {
              entries: manyEntries,
              chainValid: null,
              page: 0,
              size: 2,
              total: 25,
              loading: false,
              error: null,
            },
          },
        },
      },
    });

    render(
      <Provider store={storeWithMany}>
        <ThemeProvider theme={theme}>
          <StatusActionHistory partnerCode="GME_KR_001" pageSize={2} />
        </ThemeProvider>
      </Provider>,
    );
    expect(screen.getByRole('navigation', { name: /pagination/i })).toBeInTheDocument();
  });
});
