/**
 * Vitest coverage for AuditLogPanel (Slice 8).
 *
 * Covers:
 *  1. Renders audit trail scoped to partner aggregateType=partner.
 *  2. Pagination appears when total > pageSize.
 *  3. Empty state when no entries.
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

const getAuditTrailMock = vi.fn();
vi.mock('@/api/client', () => ({
  adminApi: {
    getAuditTrail: (...a) => getAuditTrailMock(...a),
  },
}));

import AuditLogPanel from '../AuditLogPanel';

const ENTRIES = [
  { recordedAt: '2026-06-01T10:00:00Z', actorId: 'alice', eventType: 'PARTNER_UPDATED', beforeJson: null, afterJson: '{}' },
  { recordedAt: '2026-06-02T11:00:00Z', actorId: 'bob', eventType: 'PARTNER_LIFECYCLE_SUSPENDED', beforeJson: null, afterJson: '{"reason":"FRAUD_SUSPECTED"}' },
];

function makeStore(trailOverride = {}) {
  return configureStore({
    reducer: { auditTrail: auditTrailReducer, auth: authReducer },
    preloadedState: {
      auditTrail: {
        byKey: {
          'partner:GME_KR_001': {
            entries: ENTRIES,
            chainValid: true,
            page: 0,
            size: 20,
            total: 2,
            loading: false,
            error: null,
            ...trailOverride,
          },
        },
      },
    },
  });
}

function renderPanel(props, trailOverride) {
  const store = makeStore(trailOverride);
  return render(
    <Provider store={store}>
      <ThemeProvider theme={theme}>
        <AuditLogPanel {...props} />
      </ThemeProvider>
    </Provider>,
  );
}

describe('AuditLogPanel', () => {
  it('renders audit entries from auditTrail slice', async () => {
    getAuditTrailMock.mockResolvedValue({ entries: ENTRIES, chainValid: true, page: 0, size: 20, total: 2 });
    renderPanel({ partnerCode: 'GME_KR_001' });
    // Chain valid chip
    await waitFor(() => {
      expect(screen.getByTestId('chain-valid-chip')).toBeInTheDocument();
    });
    // Both event types visible
    expect(screen.getByText('PARTNER_UPDATED')).toBeInTheDocument();
    expect(screen.getByText('PARTNER_LIFECYCLE_SUSPENDED')).toBeInTheDocument();
  });

  it('shows pagination when total > pageSize', () => {
    getAuditTrailMock.mockResolvedValue({ entries: ENTRIES, chainValid: true, page: 0, size: 5, total: 50 });
    const storeWithMore = configureStore({
      reducer: { auditTrail: auditTrailReducer, auth: authReducer },
      preloadedState: {
        auditTrail: {
          byKey: {
            'partner:GME_KR_001': {
              entries: ENTRIES,
              chainValid: true,
              page: 0,
              size: 5,
              total: 50,
              loading: false,
              error: null,
            },
          },
        },
      },
    });
    render(
      <Provider store={storeWithMore}>
        <ThemeProvider theme={theme}>
          <AuditLogPanel partnerCode="GME_KR_001" pageSize={5} />
        </ThemeProvider>
      </Provider>,
    );
    expect(screen.getByRole('navigation', { name: /pagination/i })).toBeInTheDocument();
  });

  it('shows empty state when no entries', async () => {
    getAuditTrailMock.mockResolvedValue({ entries: [], chainValid: null, page: 0, size: 20, total: 0 });
    const emptyStore = configureStore({
      reducer: { auditTrail: auditTrailReducer, auth: authReducer },
      preloadedState: {
        auditTrail: {
          byKey: {
            'partner:GME_KR_001': {
              entries: [],
              chainValid: null,
              page: 0,
              size: 20,
              total: 0,
              loading: false,
              error: null,
            },
          },
        },
      },
    });
    render(
      <Provider store={emptyStore}>
        <ThemeProvider theme={theme}>
          <AuditLogPanel partnerCode="GME_KR_001" />
        </ThemeProvider>
      </Provider>,
    );
    await waitFor(() => {
      expect(screen.getByText(/No audit entries found/i)).toBeInTheDocument();
    });
  });
});
