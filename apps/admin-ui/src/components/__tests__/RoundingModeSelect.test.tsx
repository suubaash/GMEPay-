import { describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import RoundingModeSelect from '../RoundingModeSelect';
import { ROUNDING_MODES, type RoundingMode } from '@/api/types';
import { theme } from '@/theme/theme';

function renderWithTheme(ui: React.ReactElement) {
  return render(<ThemeProvider theme={theme}>{ui}</ThemeProvider>);
}

describe('RoundingModeSelect', () => {
  it('renders the select with the default label', () => {
    renderWithTheme(
      <RoundingModeSelect value="HALF_UP" onChange={() => undefined} />,
    );
    // MUI renders the label inside a combobox role
    expect(screen.getByRole('combobox')).toBeInTheDocument();
    expect(screen.getByLabelText(/settlement rounding mode/i)).toBeInTheDocument();
  });

  it('exposes all 7 rounding modes when opened', async () => {
    const user = userEvent.setup();
    renderWithTheme(
      <RoundingModeSelect value="HALF_UP" onChange={() => undefined} />,
    );
    await user.click(screen.getByRole('combobox'));
    const listbox = await screen.findByRole('listbox');
    const options = within(listbox).getAllByRole('option');
    expect(options).toHaveLength(7);
    const labels = options.map((o) => o.textContent);
    for (const mode of ROUNDING_MODES) {
      expect(labels).toContain(mode);
    }
  });

  it('fires onChange with the selected value', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn<(next: RoundingMode) => void>();
    renderWithTheme(
      <RoundingModeSelect value="HALF_UP" onChange={onChange} />,
    );
    await user.click(screen.getByRole('combobox'));
    const listbox = await screen.findByRole('listbox');
    await user.click(within(listbox).getByText('DOWN'));
    expect(onChange).toHaveBeenCalledTimes(1);
    expect(onChange).toHaveBeenCalledWith('DOWN');
  });
});
