/**
 * Vitest coverage for ReviewSection (Slice 8).
 *
 * Contract:
 *  - Renders 8 step groups (aria-label review-group-step-{1..8}).
 *  - Each group has an "Edit" button with aria-label edit-step-{n}.
 *  - Clicking "Edit" on step N navigates to the correct route.
 *  - Draft fields (partnerCode, legalNameLocal, etc.) are displayed.
 *  - "(not set)" placeholder appears when a field is empty.
 */
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import { Provider } from 'react-redux';
import { configureStore } from '@reduxjs/toolkit';
import draftsReducer from '@/store/draftsSlice';
import authReducer from '@/store/authSlice';
import partnerSchemesReducer from '@/store/partnerSchemesSlice';
import kybReducer from '@/store/kybSlice';
import bankAccountsReducer from '@/store/bankAccountsSlice';
import prefundingConfigReducer from '@/store/prefundingConfigSlice';
import commercialTermsReducer from '@/store/commercialTermsSlice';
import { theme } from '@/theme/theme';

const mockPush = vi.fn();
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: mockPush }),
  useParams: () => ({ draftCode: 'GME_KR_TEST_8' }),
}));

vi.mock('@/components/SnackbarProvider', () => ({
  __esModule: true,
  default: ({ children }) => <>{children}</>,
  useSnackbar: () => ({
    success: vi.fn(), error: vi.fn(), info: vi.fn(), warning: vi.fn(),
  }),
}));

import { ReviewSection } from '../ReviewSection';

const PARTNER_CODE = 'GME_KR_TEST_8';

function buildStore(preload = {}) {
  return configureStore({
    reducer: {
      drafts: draftsReducer,
      auth: authReducer,
      partnerSchemes: partnerSchemesReducer,
      kyb: kybReducer,
      bankAccounts: bankAccountsReducer,
      prefundingConfig: prefundingConfigReducer,
      commercialTerms: commercialTermsReducer,
    },
    preloadedState: preload,
  });
}

function renderSection({ draft = {}, preload = {} } = {}) {
  const store = buildStore(preload);
  return {
    store,
    ...render(
      <Provider store={store}>
        <ThemeProvider theme={theme}>
          <ReviewSection draft={draft} partnerCode={PARTNER_CODE} />
        </ThemeProvider>
      </Provider>,
    ),
  };
}

describe('ReviewSection', () => {
  beforeEach(() => {
    mockPush.mockReset();
  });

  it('renders review-section aria-label', () => {
    renderSection();
    expect(screen.getByLabelText('review-section')).toBeInTheDocument();
  });

  it('renders 8 step groups', () => {
    renderSection();
    for (let i = 1; i <= 8; i++) {
      expect(screen.getByLabelText(`review-group-step-${i}`)).toBeInTheDocument();
    }
  });

  it('renders edit buttons for each step (1..8)', () => {
    renderSection();
    for (let i = 1; i <= 8; i++) {
      expect(screen.getByLabelText(`edit-step-${i}`)).toBeInTheDocument();
    }
  });

  it('navigates to step-1 when Edit is clicked on step 1', () => {
    renderSection();
    fireEvent.click(screen.getByLabelText('edit-step-1'));
    expect(mockPush).toHaveBeenCalledWith(
      expect.stringContaining('step-1'),
    );
  });

  it('navigates to step-7 when Edit is clicked on step 7', () => {
    renderSection();
    fireEvent.click(screen.getByLabelText('edit-step-7'));
    expect(mockPush).toHaveBeenCalledWith(
      expect.stringContaining('step-7'),
    );
  });

  it('navigates to step-8 when Edit is clicked on step 8', () => {
    renderSection();
    fireEvent.click(screen.getByLabelText('edit-step-8'));
    expect(mockPush).toHaveBeenCalledWith(
      expect.stringContaining('step-8'),
    );
  });

  it('displays draft partnerCode', () => {
    renderSection({ draft: { partnerCode: 'GME_KR_TEST_8' } });
    expect(screen.getByText('GME_KR_TEST_8')).toBeInTheDocument();
  });

  it('shows (not set) for empty legalNameLocal', () => {
    renderSection({ draft: { legalNameLocal: '' } });
    // There will be at least one "(not set)" for the empty field
    const notSetEls = screen.getAllByText('(not set)');
    expect(notSetEls.length).toBeGreaterThan(0);
  });

  it('displays legalNameLocal when set', () => {
    renderSection({ draft: { legalNameLocal: 'Test Corp KR' } });
    expect(screen.getByText('Test Corp KR')).toBeInTheDocument();
  });

  it('shows enabled schemes from partnerSchemes store', () => {
    const preload = {
      partnerSchemes: {
        schemesByCode: {
          [PARTNER_CODE]: [
            { schemeId: 'ZEROPAY', enabled: true },
            { schemeId: 'KFTC', enabled: false },
          ],
        },
        corridorsByCode: {},
        loadingByCode: {},
        saving: false,
        error: null,
      },
    };
    renderSection({ preload });
    expect(screen.getByText('ZEROPAY')).toBeInTheDocument();
    expect(screen.queryByText('KFTC')).not.toBeInTheDocument();
  });
});
