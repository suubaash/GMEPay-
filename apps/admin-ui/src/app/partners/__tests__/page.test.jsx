/**
 * Vitest coverage for the split partner-list landing page (Slice 1, 1D.4).
 *
 *  1. Renders two MUI tables: "Active partners" + "Drafts".
 *  2. Active table lists rows from /v1/admin/partners filtered to status=LIVE
 *     (rows with no status are also shown — the deprecated PartnerSummary
 *     wire shape carries no status field today; see page.jsx for the
 *     rationale).
 *  3. Drafts table lists rows from /v1/admin/partners/drafts, each with a
 *     "Resume" button pointing at /partners/draft/{partnerCode}/step-1.
 *  4. Clicking "New partner" POSTs /v1/admin/partners/draft and then routes
 *     the operator to /partners/draft/{partnerCode}/step-1.
 */
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { Provider } from 'react-redux';
import { configureStore } from '@reduxjs/toolkit';
import partnersReducer from '@/store/partnersSlice';
import authReducer from '@/store/authSlice';
import { theme } from '@/theme/theme';

// Mock next/navigation BEFORE importing the page so it picks up the stubs.
const push = vi.fn();
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push, replace: vi.fn(), back: vi.fn() }),
}));

// Mock the BFF client — page only needs listPartners + listPartnerDrafts +
// createPartnerDraft.
const listPartners = vi.fn();
const listPartnerDrafts = vi.fn();
const createPartnerDraft = vi.fn();
vi.mock('@/api/client', () => ({
  adminApi: {
    listPartners: () => listPartners(),
    listPartnerDrafts: () => listPartnerDrafts(),
    createPartnerDraft: (body) => createPartnerDraft(body),
  },
}));

// Quiet the snackbar so error toasts don't leak between tests.
const snackError = vi.fn();
const snackSuccess = vi.fn();
vi.mock('@/components/SnackbarProvider', () => ({
  __esModule: true,
  default: ({ children }) => <>{children}</>,
  useSnackbar: () => ({
    success: snackSuccess,
    error: snackError,
    info: vi.fn(),
    warning: vi.fn(),
  }),
}));

import PartnersListPage from '../page';

function renderPage() {
  const store = configureStore({
    reducer: { partners: partnersReducer, auth: authReducer },
  });
  return render(
    <Provider store={store}>
      <ThemeProvider theme={theme}>
        <PartnersListPage />
      </ThemeProvider>
    </Provider>,
  );
}

const ACTIVE = [
  {
    partnerId: 'GME_KR_001',
    type: 'LOCAL',
    settlementCurrency: 'KRW',
    settlementRoundingMode: 'HALF_UP',
  },
  {
    partnerId: 'GME_VN_002',
    type: 'OVERSEAS',
    settlementCurrency: 'USD',
    settlementRoundingMode: 'DOWN',
  },
];

const DRAFTS = [
  {
    id: 100,
    partnerCode: 'draft_partner_001',
    type: 'OVERSEAS',
    settlementCurrency: 'EUR',
    settlementRoundingMode: 'DOWN',
    legalNameLocal: null,
    legalNameRomanized: 'Acme Holdings GmbH',
    countryOfIncorporation: 'DE',
    status: 'ONBOARDING',
  },
  {
    id: 101,
    partnerCode: 'draft_partner_002',
    type: 'LOCAL',
    settlementCurrency: 'KRW',
    settlementRoundingMode: 'HALF_UP',
    legalNameLocal: '주식회사 지엠이',
    legalNameRomanized: null,
    countryOfIncorporation: 'KR',
    status: 'ONBOARDING',
  },
];

describe('PartnersListPage', () => {
  beforeEach(() => {
    push.mockReset();
    listPartners.mockReset();
    listPartnerDrafts.mockReset();
    createPartnerDraft.mockReset();
    snackError.mockReset();
    snackSuccess.mockReset();
  });

  it('renders both tables with rows from listPartners + listPartnerDrafts', async () => {
    listPartners.mockResolvedValue(ACTIVE);
    listPartnerDrafts.mockResolvedValue(DRAFTS);

    renderPage();

    // Active partners table — find it by its aria-label.
    const activeTable = await screen.findByRole('table', {
      name: /active partners/i,
    });
    expect(within(activeTable).getByText('GME_KR_001')).toBeInTheDocument();
    expect(within(activeTable).getByText('GME_VN_002')).toBeInTheDocument();

    // Drafts table — find it by aria-label too.
    const draftsTable = await screen.findByRole('table', {
      name: /partner drafts/i,
    });
    expect(within(draftsTable).getByText('draft_partner_001')).toBeInTheDocument();
    expect(within(draftsTable).getByText('draft_partner_002')).toBeInTheDocument();
    // Romanised name preferred for row 1; local-script name fallback on row 2.
    expect(within(draftsTable).getByText('Acme Holdings GmbH')).toBeInTheDocument();
    expect(within(draftsTable).getByText('주식회사 지엠이')).toBeInTheDocument();
  });

  it('filters Active rows to status=LIVE when status is present, but keeps statusless rows', async () => {
    listPartners.mockResolvedValue([
      { partnerId: 'LIVE_ONE', type: 'LOCAL', settlementCurrency: 'KRW' },
      { partnerId: 'ALSO_LIVE', type: 'LOCAL', settlementCurrency: 'KRW', status: 'LIVE' },
      { partnerId: 'SUSPENDED', type: 'LOCAL', settlementCurrency: 'KRW', status: 'SUSPENDED' },
      { partnerId: 'ONBOARDING', type: 'LOCAL', settlementCurrency: 'KRW', status: 'ONBOARDING' },
    ]);
    listPartnerDrafts.mockResolvedValue([]);

    renderPage();

    const activeTable = await screen.findByRole('table', { name: /active partners/i });
    // statusless and status=LIVE rows survive; the other statuses get hidden.
    expect(within(activeTable).getByText('LIVE_ONE')).toBeInTheDocument();
    expect(within(activeTable).getByText('ALSO_LIVE')).toBeInTheDocument();
    expect(within(activeTable).queryByText('SUSPENDED')).not.toBeInTheDocument();
    expect(within(activeTable).queryByText('ONBOARDING')).not.toBeInTheDocument();
  });

  it('Resume button on a draft row links to /partners/draft/{partnerCode}/step-1', async () => {
    listPartners.mockResolvedValue([]);
    listPartnerDrafts.mockResolvedValue(DRAFTS);

    renderPage();

    const draftsTable = await screen.findByRole('table', { name: /partner drafts/i });
    const resumeLinks = within(draftsTable).getAllByRole('link', { name: /resume/i });
    expect(resumeLinks).toHaveLength(2);
    expect(resumeLinks[0]).toHaveAttribute(
      'href',
      '/partners/draft/draft_partner_001/step-1',
    );
    expect(resumeLinks[1]).toHaveAttribute(
      'href',
      '/partners/draft/draft_partner_002/step-1',
    );
  });

  it('New partner button POSTs a draft and routes to step-1 with the returned partnerCode', async () => {
    listPartners.mockResolvedValue([]);
    listPartnerDrafts.mockResolvedValue([]);
    createPartnerDraft.mockResolvedValueOnce({
      id: 200,
      partnerCode: 'draft_partner_003',
      status: 'ONBOARDING',
    });

    const user = userEvent.setup();
    renderPage();

    // Wait until initial loads have resolved before clicking — otherwise the
    // button still reads as creatingDraft=false (we never start that thunk).
    await waitFor(() => expect(listPartners).toHaveBeenCalled());
    await user.click(
      screen.getByRole('button', { name: /new partner \(start draft\)/i }),
    );

    await waitFor(() => {
      expect(createPartnerDraft).toHaveBeenCalledTimes(1);
    });
    // Called with an empty body — the BFF generates the code.
    expect(createPartnerDraft).toHaveBeenCalledWith({});
    await waitFor(() => {
      expect(push).toHaveBeenCalledWith(
        '/partners/draft/draft_partner_003/step-1',
      );
    });
  });

  it('surfaces a snackbar error when createPartnerDraft fails', async () => {
    listPartners.mockResolvedValue([]);
    listPartnerDrafts.mockResolvedValue([]);
    createPartnerDraft.mockRejectedValueOnce(new Error('config-registry down'));

    const user = userEvent.setup();
    renderPage();
    await waitFor(() => expect(listPartners).toHaveBeenCalled());
    await user.click(
      screen.getByRole('button', { name: /new partner \(start draft\)/i }),
    );

    await waitFor(() => {
      expect(snackError).toHaveBeenCalled();
    });
    expect(push).not.toHaveBeenCalled();
  });

  it('shows the drafts empty state when /drafts returns []', async () => {
    listPartners.mockResolvedValue(ACTIVE);
    listPartnerDrafts.mockResolvedValue([]);

    renderPage();

    expect(
      await screen.findByText(/No drafts in flight/i),
    ).toBeInTheDocument();
    // And the active table is still rendered.
    expect(await screen.findByRole('table', { name: /active partners/i })).toBeInTheDocument();
  });

  it('shows the active empty state when /partners returns []', async () => {
    listPartners.mockResolvedValue([]);
    listPartnerDrafts.mockResolvedValue(DRAFTS);

    renderPage();

    expect(
      await screen.findByText(/No active partners yet/i),
    ).toBeInTheDocument();
    expect(await screen.findByRole('table', { name: /partner drafts/i })).toBeInTheDocument();
  });
});
