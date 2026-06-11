/**
 * AuditTrail component tests.
 *
 * We mount against a real Redux store pre-seeded with audit trail data so
 * the component renders the entries, chain-valid chip states, and pagination
 * without hitting the network.
 *
 * The fetchAuditTrail thunk is mocked as a no-op so it does not overwrite the
 * pre-seeded store state on mount.
 */
import { describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { Provider } from 'react-redux';
import { configureStore } from '@reduxjs/toolkit';
import auditTrailReducer, { trailKey } from '@/store/auditTrailSlice';
import { theme } from '@/theme/theme';
import AuditTrail from '@/components/AuditTrail';

// The thunk dispatched on mount must not overwrite the pre-seeded store state.
// We mock the whole slice module and replace fetchAuditTrail with a thunk that
// resolves to undefined without mutating state.
vi.mock('@/store/auditTrailSlice', async (importOriginal) => {
  const original = await importOriginal();
  const noopThunk = () => () => Promise.resolve();
  noopThunk.pending = { type: '__noop__/pending' };
  noopThunk.fulfilled = { type: '__noop__/fulfilled' };
  noopThunk.rejected = { type: '__noop__/rejected' };
  return {
    ...original,
    fetchAuditTrail: noopThunk,
  };
});

const AGG_TYPE = 'partner';
const AGG_ID = 'GME_KR_001';

const ENTRIES = [
  {
    recordedAt: '2026-06-10T08:00:00.000Z',
    actorId: 'admin@gme.com',
    eventType: 'PARTNER_CREATED',
    beforeJson: null,
    afterJson: '{"partnerCode":"GME_KR_001","status":"ONBOARDING"}',
  },
  {
    recordedAt: '2026-06-10T09:30:00.000Z',
    actorId: 'ops@gme.com',
    eventType: 'PARTNER_UPDATED',
    beforeJson: '{"status":"ONBOARDING"}',
    afterJson: '{"status":"ACTIVE"}',
  },
];

/** Build a store pre-seeded with audit trail data for the test aggregate. */
function makeStore({ entries = ENTRIES, chainValid = true, total = 2, page = 0 } = {}) {
  const key = trailKey(AGG_TYPE, AGG_ID);
  return configureStore({
    reducer: { auditTrail: auditTrailReducer },
    preloadedState: {
      auditTrail: {
        byKey: {
          [key]: {
            entries,
            chainValid,
            page,
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

function renderComponent(storeOverrides = {}) {
  const store = makeStore(storeOverrides);
  render(
    <Provider store={store}>
      <ThemeProvider theme={theme}>
        <AuditTrail aggregateType={AGG_TYPE} aggregateId={AGG_ID} />
      </ThemeProvider>
    </Provider>,
  );
  return store;
}

describe('AuditTrail', () => {
  it('renders all entries with recordedAt, actorId, eventType', () => {
    renderComponent();
    // Both actor IDs visible
    expect(screen.getAllByText('admin@gme.com')).toHaveLength(1);
    expect(screen.getAllByText('ops@gme.com')).toHaveLength(1);
    // Event type chips
    expect(screen.getByText('PARTNER_CREATED')).toBeInTheDocument();
    expect(screen.getByText('PARTNER_UPDATED')).toBeInTheDocument();
  });

  it('shows "Chain verified" green chip when chainValid is true', () => {
    renderComponent({ chainValid: true });
    const chip = screen.getByTestId('chain-valid-chip');
    expect(chip).toBeInTheDocument();
    expect(chip.textContent).toMatch(/chain verified/i);
    expect(screen.queryByTestId('chain-broken-chip')).toBeNull();
  });

  it('shows "CHAIN BROKEN" red chip when chainValid is false', () => {
    renderComponent({ chainValid: false });
    const chip = screen.getByTestId('chain-broken-chip');
    expect(chip).toBeInTheDocument();
    expect(chip.textContent).toMatch(/chain broken/i);
    expect(screen.queryByTestId('chain-valid-chip')).toBeNull();
  });

  it('shows neither chain chip when chainValid is null (not loaded)', () => {
    renderComponent({ chainValid: null });
    expect(screen.queryByTestId('chain-valid-chip')).toBeNull();
    expect(screen.queryByTestId('chain-broken-chip')).toBeNull();
  });

  it('shows empty state message when entries array is empty', () => {
    renderComponent({ entries: [], total: 0 });
    expect(screen.getByText(/no audit entries found/i)).toBeInTheDocument();
  });

  it('expands the before/after diff pane when the expand button is clicked', async () => {
    const user = userEvent.setup();
    renderComponent();

    // "After" / "Before" labels are only visible when an entry is expanded.
    expect(screen.queryByText('After')).toBeNull();

    // The second entry has both beforeJson and afterJson so its expand button is present.
    const expandButtons = screen.getAllByRole('button', { name: /expand diff/i });
    expect(expandButtons.length).toBeGreaterThan(0);

    // Click the first available expand button
    await user.click(expandButtons[0]);

    // After / Before pane labels should now be visible
    expect(await screen.findByText('After')).toBeInTheDocument();
  });

  it('does not render pagination when total <= pageSize', () => {
    renderComponent({ total: 2 }); // default pageSize 20 > 2
    expect(screen.queryByRole('navigation')).toBeNull();
  });

  it('renders pagination when total > pageSize', () => {
    renderComponent({ total: 45 }); // 45 > 20
    // MUI Pagination renders a <nav> element
    expect(screen.getByRole('navigation')).toBeInTheDocument();
  });

  it('pagination shows correct number of pages', () => {
    renderComponent({ total: 45 }); // ceil(45/20) = 3 pages
    const nav = screen.getByRole('navigation');
    // Page buttons 1..3 present (MUI also renders prev/next arrows)
    expect(within(nav).getByRole('button', { name: /page 1/i })).toBeInTheDocument();
    expect(within(nav).getByRole('button', { name: /page 3/i })).toBeInTheDocument();
    expect(within(nav).queryByRole('button', { name: /page 4/i })).toBeNull();
  });
});
