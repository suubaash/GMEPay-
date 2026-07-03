/**
 * Vitest coverage for the /settings Platform Settings editor page.
 *
 *  1. The settings table renders one row per tunable, showing each key + its
 *     current value (incl. a NUMBER-typed setting).
 *  2. Editing a value and clicking Save PUTs the new value to
 *     adminApi.updateSetting(key, value).
 *  3. A non-numeric value on a NUMBER setting keeps Save disabled (blocked).
 */
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import { theme } from '@/theme/theme';

// Mock the API client — the page must never hit real network.
const mockListSettings = vi.fn();
const mockUpdateSetting = vi.fn();
vi.mock('@/api/client', () => ({
  adminApi: {
    listSettings: (...a) => mockListSettings(...a),
    updateSetting: (...a) => mockUpdateSetting(...a),
  },
  ApiError: class ApiError extends Error {},
}));

// Quiet snackbar — assert nothing, just satisfy the hook.
const snackSuccess = vi.fn();
const snackError = vi.fn();
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

import SettingsPage from './page';

const SETTINGS = [
  {
    key: 'settlement.cutoff.hour',
    value: '15',
    valueType: 'NUMBER',
    description: 'Hour of day (KST) the settlement window closes.',
    updatedAt: '2026-07-03T09:30:00Z',
    updatedBy: 'alice',
  },
  {
    key: 'partner.default.rounding',
    value: 'HALF_UP',
    valueType: 'STRING',
    description: 'Default rounding mode for new partners.',
    updatedAt: '2026-07-02T14:15:00Z',
    updatedBy: 'bob',
  },
  {
    key: 'webhook.retry.enabled',
    value: 'true',
    valueType: 'BOOLEAN',
    description: 'Whether failed webhooks are retried.',
    updatedAt: null,
    updatedBy: null,
  },
];

function renderPage() {
  return render(
    <ThemeProvider theme={theme}>
      <SettingsPage />
    </ThemeProvider>,
  );
}

describe('SettingsPage', () => {
  beforeEach(() => {
    mockListSettings.mockReset();
    mockUpdateSetting.mockReset();
    snackSuccess.mockReset();
    snackError.mockReset();
    mockListSettings.mockResolvedValue(SETTINGS);
  });

  it('renders a row per setting with its key and current value', async () => {
    renderPage();
    const table = await screen.findByRole('table', { name: /platform settings/i });
    expect(within(table).getByText('settlement.cutoff.hour')).toBeInTheDocument();
    expect(within(table).getByText('partner.default.rounding')).toBeInTheDocument();
    // the NUMBER setting's current value shows in its value input
    const numberInput = within(table).getByLabelText(
      'value for settlement.cutoff.hour',
    );
    expect(numberInput).toHaveValue(15);
    const stringInput = within(table).getByLabelText(
      'value for partner.default.rounding',
    );
    expect(stringInput).toHaveValue('HALF_UP');
  });

  it('PUTs the new value when a changed row is saved', async () => {
    mockUpdateSetting.mockResolvedValue({
      ...SETTINGS[1],
      value: 'HALF_EVEN',
      updatedAt: '2026-07-03T10:00:00Z',
      updatedBy: 'carol',
    });
    renderPage();
    await screen.findByRole('table', { name: /platform settings/i });

    const input = screen.getByLabelText('value for partner.default.rounding');
    fireEvent.change(input, { target: { value: 'HALF_EVEN' } });

    const saveBtn = screen.getByLabelText('save partner.default.rounding');
    expect(saveBtn).toBeEnabled();
    fireEvent.click(saveBtn);

    await waitFor(() =>
      expect(mockUpdateSetting).toHaveBeenCalledWith(
        'partner.default.rounding',
        'HALF_EVEN',
      ),
    );
  });

  it('blocks Save when a NUMBER setting is given a non-numeric value', async () => {
    renderPage();
    await screen.findByRole('table', { name: /platform settings/i });

    const numberInput = screen.getByLabelText('value for settlement.cutoff.hour');
    // A number <input> rejects letters, so force a non-numeric string via change.
    fireEvent.change(numberInput, { target: { value: 'abc' } });

    const saveBtn = screen.getByLabelText('save settlement.cutoff.hour');
    expect(saveBtn).toBeDisabled();
    expect(mockUpdateSetting).not.toHaveBeenCalled();
  });
});
