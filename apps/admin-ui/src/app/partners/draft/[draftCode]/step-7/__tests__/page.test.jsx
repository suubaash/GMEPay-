/**
 * Vitest coverage for step-7/page.jsx (Slice 7).
 *
 * Contract:
 *  - Renders the PartnerDraftWizard with activeStep=7.
 *  - The stepper highlights step 7 (Schemes) as active.
 */
import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import { Provider } from 'react-redux';
import { configureStore } from '@reduxjs/toolkit';
import { theme } from '@/theme/theme';
import draftsReducer from '@/store/draftsSlice';
import authReducer from '@/store/authSlice';
import partnerSchemesReducer from '@/store/partnerSchemesSlice';

// ── Mock next/navigation ───────────────────────────────────────────────────────
vi.mock('next/navigation', () => ({
  useParams: () => ({ draftCode: 'GME_KR_STEP7' }),
  useRouter: () => ({ push: vi.fn() }),
}));

// ── Mock BFF client ────────────────────────────────────────────────────────────
vi.mock('@/api/client', () => ({
  __esModule: true,
  ApiError: class ApiError extends Error {
    constructor(status, url, msg) { super(msg); this.status = status; }
  },
  adminApi: {
    getDraft: vi.fn().mockRejectedValue(new Error('not found')),
    listPartnerDrafts: vi.fn().mockResolvedValue([]),
    listPartnerSchemes: vi.fn().mockRejectedValue(new Error('not found')),
    listPartnerCorridors: vi.fn().mockRejectedValue(new Error('not found')),
    listSchemeOperatingHours: vi.fn().mockResolvedValue([]),
  },
}));

// ── Mock SnackbarProvider ──────────────────────────────────────────────────────
vi.mock('@/components/SnackbarProvider', () => ({
  __esModule: true,
  default: ({ children }) => <>{children}</>,
  useSnackbar: () => ({ success: vi.fn(), error: vi.fn(), info: vi.fn(), warning: vi.fn() }),
}));

// ── Other component mocks ──────────────────────────────────────────────────────
vi.mock('@/components/Breadcrumbs', () => ({ default: () => <nav /> }));
vi.mock('@/components/ErrorAlert', () => ({ default: () => null }));
vi.mock('@/components/LoadingSkeleton', () => ({ default: () => <div>Loading…</div> }));

import Step7Page from '../page';

function buildStore() {
  return configureStore({
    reducer: {
      drafts: draftsReducer,
      auth: authReducer,
      partnerSchemes: partnerSchemesReducer,
    },
    preloadedState: {
      drafts: {
        current: { partnerCode: 'GME_KR_STEP7', status: 'ONBOARDING' },
        currentCode: 'GME_KR_STEP7',
        contacts: [],
        contactsLoading: false,
        list: [],
        loading: false,
        listLoading: false,
        saving: false,
        error: null,
      },
    },
  });
}

describe('step-7/page', () => {
  it('renders the step 7 page without crashing', () => {
    const store = buildStore();
    render(
      <Provider store={store}>
        <ThemeProvider theme={theme}>
          <Step7Page />
        </ThemeProvider>
      </Provider>,
    );
    // "Schemes" should appear in the stepper
    expect(screen.getAllByText('Schemes').length).toBeGreaterThan(0);
  });

  it('shows the step-7-schemes-form for step 7', () => {
    const store = buildStore();
    render(
      <Provider store={store}>
        <ThemeProvider theme={theme}>
          <Step7Page />
        </ThemeProvider>
      </Provider>,
    );
    expect(screen.getByLabelText('step-7-schemes-form')).toBeInTheDocument();
  });
});
