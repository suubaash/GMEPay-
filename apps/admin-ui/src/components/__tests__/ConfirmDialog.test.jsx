import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import ConfirmDialog from '../ConfirmDialog';
import { theme } from '@/theme/theme';

function renderWithTheme(ui) {
  return render(<ThemeProvider theme={theme}>{ui}</ThemeProvider>);
}

describe('ConfirmDialog', () => {
  it('renders the title and message when open', () => {
    renderWithTheme(
      <ConfirmDialog
        open
        title="Are you sure?"
        message="This will deactivate partner GME_001."
        onConfirm={() => undefined}
        onCancel={() => undefined}
      />,
    );
    expect(screen.getByText('Are you sure?')).toBeInTheDocument();
    expect(
      screen.getByText('This will deactivate partner GME_001.'),
    ).toBeInTheDocument();
  });

  it('does not render when open=false', () => {
    renderWithTheme(
      <ConfirmDialog
        open={false}
        title="Hidden"
        message="Hidden message"
        onConfirm={() => undefined}
        onCancel={() => undefined}
      />,
    );
    expect(screen.queryByText('Hidden')).toBeNull();
  });

  it('calls onConfirm when the confirm button is clicked', async () => {
    const onConfirm = vi.fn();
    const onCancel = vi.fn();
    const user = userEvent.setup();
    renderWithTheme(
      <ConfirmDialog
        open
        title="t"
        message="m"
        confirmLabel="Yes, do it"
        onConfirm={onConfirm}
        onCancel={onCancel}
      />,
    );
    await user.click(screen.getByRole('button', { name: 'Yes, do it' }));
    expect(onConfirm).toHaveBeenCalledTimes(1);
    expect(onCancel).not.toHaveBeenCalled();
  });

  it('calls onCancel when the cancel button is clicked (and NOT onConfirm)', async () => {
    const onConfirm = vi.fn();
    const onCancel = vi.fn();
    const user = userEvent.setup();
    renderWithTheme(
      <ConfirmDialog
        open
        title="t"
        message="m"
        onConfirm={onConfirm}
        onCancel={onCancel}
      />,
    );
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(onCancel).toHaveBeenCalledTimes(1);
    expect(onConfirm).not.toHaveBeenCalled();
  });
});
