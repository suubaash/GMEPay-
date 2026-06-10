import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { Provider } from 'react-redux';
import { configureStore } from '@reduxjs/toolkit';
import auditReducer from '@/store/auditSlice';
import uiReducer from '@/store/uiSlice';
import { theme } from '@/theme/theme';

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }),
}));

const getAuditPage = vi.fn();
vi.mock('@/api/client', () => ({
  adminApi: {
    getAuditPage: (page, size) => getAuditPage(page, size),
  },
}));

vi.mock('@/components/SnackbarProvider', () => ({
  __esModule: true,
  default: ({ children }) => <>{children}</>,
  useSnackbar: () => ({
    success: vi.fn(),
    error: vi.fn(),
    info: vi.fn(),
    warning: vi.fn(),
  }),
}));

import AuditLogPage from '../page';

function renderPage() {
  const store = configureStore({
    reducer: { audit: auditReducer, ui: uiReducer },
  });
  return render(
    <Provider store={store}>
      <ThemeProvider theme={theme}>
        <AuditLogPage />
      </ThemeProvider>
    </Provider>,
  );
}

describe('AuditLogPage', () => {
  beforeEach(() => {
    getAuditPage.mockReset();
  });

  it('renders rows from the BFF response and respects pagination metadata', async () => {
    getAuditPage.mockResolvedValueOnce({
      content: [
        {
          id: 'a1',
          actor: 'admin',
          action: 'CREATE',
          target: 'partner:GME_KR_001',
          at: '2026-06-10T08:00:00Z',
          detail: 'Created LOCAL partner',
        },
        {
          id: 'a2',
          actor: 'admin',
          action: 'UPDATE',
          target: 'partner:GME_VN_002',
          at: '2026-06-10T08:05:00Z',
          detail: 'Changed rounding mode to DOWN',
        },
      ],
      page: 0,
      size: 20,
      total: 47,
    });
    renderPage();

    await waitFor(() => {
      expect(getAuditPage).toHaveBeenCalledTimes(1);
    });
    expect(getAuditPage).toHaveBeenCalledWith(0, 20);

    expect(await screen.findByText('partner:GME_KR_001')).toBeInTheDocument();
    expect(screen.getByText('partner:GME_VN_002')).toBeInTheDocument();
    // CREATE / UPDATE chips render the action text.
    expect(screen.getByText('CREATE')).toBeInTheDocument();
    expect(screen.getByText('UPDATE')).toBeInTheDocument();
    // Total count visible.
    expect(screen.getByText(/47 entries/i)).toBeInTheDocument();
  });

  it('refetches with the new size when the page-size selector changes', async () => {
    getAuditPage.mockResolvedValue({
      content: [],
      page: 0,
      size: 20,
      total: 0,
    });
    const user = userEvent.setup();
    renderPage();
    await waitFor(() => expect(getAuditPage).toHaveBeenCalledTimes(1));

    const select = screen.getByRole('combobox', { name: /page size/i });
    await user.click(select);
    const option = await screen.findByRole('option', { name: '50' });
    await user.click(option);

    await waitFor(() => {
      expect(getAuditPage).toHaveBeenCalledTimes(2);
    });
    expect(getAuditPage).toHaveBeenLastCalledWith(0, 50);
  });
});
