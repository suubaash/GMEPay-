/**
 * Vitest coverage for SchemesMatrix (Slice 7).
 *
 * Contract:
 *  - Renders a row for each of the 7 scheme IDs.
 *  - Each row has enabled switch, direction select, role select.
 *  - "Configure…" button is present only for ZEROPAY row.
 *  - Clicking Configure… opens the ZeroPay dialog.
 *  - Inline warning shown when ZEROPAY enabled but merchantId is empty.
 *  - No warning shown when ZEROPAY is disabled.
 */
import { describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { useForm } from 'react-hook-form';
import { theme } from '@/theme/theme';
import SchemesMatrix, { SCHEME_IDS, defaultSchemesValue } from '../SchemesMatrix';

function Wrapper({ initialSchemes } = {}) {
  const { control, register, formState: { errors } } = useForm({
    defaultValues: { schemes: initialSchemes ?? defaultSchemesValue() },
  });
  return (
    <ThemeProvider theme={theme}>
      <SchemesMatrix control={control} register={register} errors={errors.schemes} />
    </ThemeProvider>
  );
}

describe('SchemesMatrix', () => {
  it('renders the schemes-matrix-section aria wrapper', () => {
    render(<Wrapper />);
    expect(screen.getByLabelText('schemes-matrix-section')).toBeInTheDocument();
  });

  it('renders a table row for each scheme ID', () => {
    render(<Wrapper />);
    for (const id of SCHEME_IDS) {
      expect(screen.getByText(id)).toBeInTheDocument();
    }
  });

  it('renders enabled switch for the first scheme', () => {
    render(<Wrapper />);
    expect(screen.getByLabelText('schemes.0.enabled')).toBeInTheDocument();
  });

  it('renders direction select for the first scheme', () => {
    render(<Wrapper />);
    expect(screen.getByLabelText('schemes.0.direction')).toBeInTheDocument();
  });

  it('renders role select for the first scheme', () => {
    render(<Wrapper />);
    expect(screen.getByLabelText('schemes.0.role')).toBeInTheDocument();
  });

  it('renders "Configure…" button only for ZEROPAY row (index 0)', () => {
    render(<Wrapper />);
    // ZEROPAY is index 0
    expect(screen.getByLabelText('configure-scheme-0')).toBeInTheDocument();
    // No configure button for BAKONG (index 1)
    expect(screen.queryByLabelText('configure-scheme-1')).not.toBeInTheDocument();
  });

  it('opens ZeroPay dialog when "Configure…" is clicked', async () => {
    const user = userEvent.setup();
    render(<Wrapper />);
    await user.click(screen.getByLabelText('configure-scheme-0'));
    await waitFor(() => {
      expect(screen.getByLabelText('zeropay-config-dialog')).toBeInTheDocument();
    });
  });

  it('ZeroPay dialog contains merchantId and institutionCode inputs', async () => {
    const user = userEvent.setup();
    render(<Wrapper />);
    await user.click(screen.getByLabelText('configure-scheme-0'));
    await waitFor(() => {
      expect(screen.getByLabelText('schemes.0.zeropayMerchantId')).toBeInTheDocument();
      expect(screen.getByLabelText('schemes.0.kftcInstitutionCode')).toBeInTheDocument();
    });
  });

  it('closes ZeroPay dialog when "Done" is clicked', async () => {
    const user = userEvent.setup();
    render(<Wrapper />);
    await user.click(screen.getByLabelText('configure-scheme-0'));
    await waitFor(() => {
      expect(screen.getByLabelText('zeropay-config-dialog')).toBeInTheDocument();
    });
    await user.click(screen.getByLabelText('zeropay-dialog-close'));
    await waitFor(() => {
      expect(screen.queryByLabelText('zeropay-config-dialog')).not.toBeInTheDocument();
    });
  });

  it('does not show inline warning when ZEROPAY is disabled (default)', () => {
    render(<Wrapper />);
    expect(screen.queryByLabelText('zeropay-inline-warning')).not.toBeInTheDocument();
  });

  it('shows inline warning when ZEROPAY enabled but merchantId empty', async () => {
    const user = userEvent.setup();
    const schemes = defaultSchemesValue();
    // Enable ZEROPAY without merchantId
    schemes[0] = { ...schemes[0], enabled: true, zeropayMerchantId: '', kftcInstitutionCode: '' };

    render(<Wrapper initialSchemes={schemes} />);
    // The warning should be visible because zeropayMerchantId is empty
    await waitFor(() => {
      expect(screen.getByLabelText('zeropay-inline-warning')).toBeInTheDocument();
    });
  });

  it('renders all 7 enabled switches', () => {
    render(<Wrapper />);
    SCHEME_IDS.forEach((_, index) => {
      expect(screen.getByLabelText(`schemes.${index}.enabled`)).toBeInTheDocument();
    });
  });
});
