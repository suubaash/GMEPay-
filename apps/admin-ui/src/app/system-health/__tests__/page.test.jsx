import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import { Provider } from 'react-redux';
import { configureStore } from '@reduxjs/toolkit';
import systemHealthReducer from '@/store/systemHealthSlice';
import uiReducer from '@/store/uiSlice';
import { theme } from '@/theme/theme';

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }),
}));

const getSystemHealth = vi.fn();
vi.mock('@/api/client', () => ({
  adminApi: {
    getSystemHealth: () => getSystemHealth(),
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

import SystemHealthPage from '../page';

function renderPage() {
  const store = configureStore({
    reducer: { systemHealth: systemHealthReducer, ui: uiReducer },
  });
  return render(
    <Provider store={store}>
      <ThemeProvider theme={theme}>
        <SystemHealthPage />
      </ThemeProvider>
    </Provider>,
  );
}

describe('SystemHealthPage', () => {
  beforeEach(() => {
    getSystemHealth.mockReset();
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('renders one status chip per service with the correct label per status', async () => {
    getSystemHealth.mockResolvedValueOnce({
      checkedAt: '2026-06-10T08:30:00Z',
      services: [
        { name: 'rate-fx', status: 'UP', lastSeenAt: '2026-06-10T08:29:55Z', uptimeSec: 90000 },
        { name: 'config-registry', status: 'DEGRADED', lastSeenAt: '2026-06-10T08:29:55Z', uptimeSec: 3600 },
        { name: 'prefunding', status: 'DOWN', lastSeenAt: '2026-06-10T08:00:00Z', uptimeSec: 0 },
      ],
    });
    renderPage();
    await waitFor(() => expect(getSystemHealth).toHaveBeenCalled());

    expect(await screen.findByText('rate-fx')).toBeInTheDocument();
    expect(screen.getByText('config-registry')).toBeInTheDocument();
    expect(screen.getByText('prefunding')).toBeInTheDocument();
    expect(screen.getByText('UP')).toBeInTheDocument();
    expect(screen.getByText('DEGRADED')).toBeInTheDocument();
    expect(screen.getByText('DOWN')).toBeInTheDocument();
  });
});
