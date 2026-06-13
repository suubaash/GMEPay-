import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { Provider } from 'react-redux';
import { configureStore } from '@reduxjs/toolkit';
import draftsReducer from '@/store/draftsSlice';
import authReducer from '@/store/authSlice';
import { theme } from '@/theme/theme';

// next/navigation must be mocked BEFORE importing the page.
const push = vi.fn();
const replace = vi.fn();
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push, replace, back: vi.fn() }),
}));

// The deprecated form posted directly to createPartner; the wizard-entry page
// only needs createPartnerDraft + getPartnerDraft (the draftsSlice thunks).
const createPartnerDraft = vi.fn();
vi.mock('@/api/client', () => ({
  adminApi: {
    createPartnerDraft: (body) => createPartnerDraft(body),
    createDraft: (body) => createPartnerDraft(body),
  },
}));

const snackError = vi.fn();
vi.mock('@/components/SnackbarProvider', () => ({
  __esModule: true,
  default: ({ children }) => <>{children}</>,
  useSnackbar: () => ({
    success: vi.fn(),
    error: snackError,
    info: vi.fn(),
    warning: vi.fn(),
  }),
}));

import NewPartnerPage from '../page';

function renderPage() {
  const store = configureStore({
    reducer: { drafts: draftsReducer, auth: authReducer },
  });
  return render(
    <Provider store={store}>
      <ThemeProvider theme={theme}>
        <NewPartnerPage />
      </ThemeProvider>
    </Provider>,
  );
}

describe('NewPartnerPage (wizard entry)', () => {
  beforeEach(() => {
    push.mockReset();
    replace.mockReset();
    createPartnerDraft.mockReset();
    snackError.mockReset();
  });

  it('asks for a partner code and disables Start until format is valid', async () => {
    renderPage();
    const start = screen.getByRole('button', { name: /start wizard/i });
    expect(start).toBeDisabled();

    const user = userEvent.setup();
    const input = screen.getByLabelText(/partner code/i);
    await user.type(input, 'AB'); // too short
    expect(start).toBeDisabled();
    await user.type(input, 'C'); // now 3 chars
    expect(start).toBeEnabled();
  });

  it('creates a draft and redirects to step-1 on Start', async () => {
    createPartnerDraft.mockResolvedValueOnce({ partnerCode: 'GME_VN_001', status: 'ONBOARDING' });
    renderPage();
    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/partner code/i), 'gme_vn_001'); // lowercased input
    await user.click(screen.getByRole('button', { name: /start wizard/i }));

    await waitFor(() => expect(createPartnerDraft).toHaveBeenCalledWith({ partnerCode: 'GME_VN_001' }));
    await waitFor(() =>
      expect(replace).toHaveBeenCalledWith('/partners/draft/GME_VN_001/step-1'),
    );
  });

  it('surfaces the upstream error message via snackbar when create fails', async () => {
    createPartnerDraft.mockRejectedValueOnce({ message: "partner 'GME_VN_001' already exists" });
    renderPage();
    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/partner code/i), 'GME_VN_001');
    await user.click(screen.getByRole('button', { name: /start wizard/i }));

    await waitFor(() =>
      expect(snackError).toHaveBeenCalledWith(
        expect.stringContaining("partner 'GME_VN_001' already exists"),
      ),
    );
    expect(replace).not.toHaveBeenCalled();
  });

  it('Cancel returns to /partners', async () => {
    renderPage();
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: /cancel/i }));
    expect(push).toHaveBeenCalledWith('/partners');
  });
});
