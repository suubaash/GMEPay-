/**
 * Vitest coverage for CorridorsBuilder (Slice 7).
 *
 * Contract:
 *  - Renders corridors-builder-section wrapper.
 *  - "Add corridor" button is present.
 *  - Clicking "Add corridor" opens the dialog.
 *  - Filling dialog and clicking Save adds a row to the table.
 *  - Remove button deletes the row.
 *  - No-corridors info alert shown when list is empty.
 */
import { describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { useForm } from 'react-hook-form';
import { theme } from '@/theme/theme';
import CorridorsBuilder from '../CorridorsBuilder';

function Wrapper({ initialCorridors } = {}) {
  const { control, register, formState: { errors } } = useForm({
    defaultValues: { corridors: initialCorridors ?? [] },
  });
  return (
    <ThemeProvider theme={theme}>
      <CorridorsBuilder control={control} register={register} errors={errors.corridors} />
    </ThemeProvider>
  );
}

describe('CorridorsBuilder', () => {
  it('renders corridors-builder-section wrapper', () => {
    render(<Wrapper />);
    expect(screen.getByLabelText('corridors-builder-section')).toBeInTheDocument();
  });

  it('renders "Add corridor" button', () => {
    render(<Wrapper />);
    expect(screen.getByLabelText('add-corridor')).toBeInTheDocument();
  });

  it('shows no-corridors info alert when list is empty', () => {
    render(<Wrapper />);
    expect(screen.getByLabelText('no-corridors-info')).toBeInTheDocument();
  });

  it('does not show no-corridors alert when corridors exist', () => {
    const corridors = [{
      srcCountry: 'KR', srcCcy: 'KRW',
      dstCountry: 'VN', dstCcy: 'VND',
      goLiveDate: '2026-01-01', active: true,
    }];
    render(<Wrapper initialCorridors={corridors} />);
    expect(screen.queryByLabelText('no-corridors-info')).not.toBeInTheDocument();
  });

  it('opens dialog when "Add corridor" is clicked', async () => {
    const user = userEvent.setup();
    render(<Wrapper />);
    await user.click(screen.getByLabelText('add-corridor'));
    await waitFor(() => {
      expect(screen.getByLabelText('corridor-dialog')).toBeInTheDocument();
    });
  });

  it('dialog has go-live date input', async () => {
    const user = userEvent.setup();
    render(<Wrapper />);
    await user.click(screen.getByLabelText('add-corridor'));
    await waitFor(() => {
      expect(screen.getByLabelText('corridor-go-live-date')).toBeInTheDocument();
    });
  });

  it('dialog has active toggle', async () => {
    const user = userEvent.setup();
    render(<Wrapper />);
    await user.click(screen.getByLabelText('add-corridor'));
    await waitFor(() => {
      expect(screen.getByLabelText('corridor-active')).toBeInTheDocument();
    });
  });

  it('cancels dialog without adding a row', async () => {
    const user = userEvent.setup();
    render(<Wrapper />);
    await user.click(screen.getByLabelText('add-corridor'));
    await waitFor(() => screen.getByLabelText('corridor-dialog-cancel'));
    await user.click(screen.getByLabelText('corridor-dialog-cancel'));
    await waitFor(() => {
      expect(screen.queryByLabelText('corridor-dialog')).not.toBeInTheDocument();
    });
    expect(screen.getByLabelText('no-corridors-info')).toBeInTheDocument();
  });

  it('adds a corridor row after filling dialog and saving', async () => {
    const user = userEvent.setup();
    render(<Wrapper />);
    await user.click(screen.getByLabelText('add-corridor'));
    await waitFor(() => screen.getByLabelText('corridor-go-live-date'));

    // Fill go-live date (src/dst already have defaults)
    await user.clear(screen.getByLabelText('corridor-go-live-date'));
    await user.type(screen.getByLabelText('corridor-go-live-date'), '2026-07-01');

    await user.click(screen.getByLabelText('corridor-dialog-save'));
    await waitFor(() => {
      expect(screen.queryByLabelText('corridor-dialog')).not.toBeInTheDocument();
    });
    // Table should now show something — no-corridors alert gone
    expect(screen.queryByLabelText('no-corridors-info')).not.toBeInTheDocument();
    expect(screen.getByLabelText('corridors-table')).toBeInTheDocument();
  });

  it('renders remove button for existing corridor row', () => {
    const corridors = [{
      srcCountry: 'KR', srcCcy: 'KRW',
      dstCountry: 'VN', dstCcy: 'VND',
      goLiveDate: '2026-01-01', active: true,
    }];
    render(<Wrapper initialCorridors={corridors} />);
    expect(screen.getByLabelText('remove-corridor-0')).toBeInTheDocument();
  });

  it('removes a corridor when remove button is clicked', async () => {
    const user = userEvent.setup();
    const corridors = [{
      srcCountry: 'KR', srcCcy: 'KRW',
      dstCountry: 'VN', dstCcy: 'VND',
      goLiveDate: '2026-01-01', active: true,
    }];
    render(<Wrapper initialCorridors={corridors} />);
    expect(screen.queryByLabelText('no-corridors-info')).not.toBeInTheDocument();
    await user.click(screen.getByLabelText('remove-corridor-0'));
    await waitFor(() => {
      expect(screen.getByLabelText('no-corridors-info')).toBeInTheDocument();
    });
  });

  it('renders active-toggle switch for existing corridor', () => {
    const corridors = [{
      srcCountry: 'KR', srcCcy: 'KRW',
      dstCountry: 'VN', dstCcy: 'VND',
      goLiveDate: '2026-01-01', active: true,
    }];
    render(<Wrapper initialCorridors={corridors} />);
    expect(screen.getByLabelText('corridor-active-toggle-0')).toBeInTheDocument();
  });
});
