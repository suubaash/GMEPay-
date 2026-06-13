/**
 * Vitest coverage for CurrencySplitSection (Slice 6A.2).
 *
 * Contract:
 *  - Renders collectionCcy and settleACcy selects with correct aria-labels.
 *  - Section wrapper has "currency-split-section" aria-label.
 *  - Shows "no-cross-border-hint" when both currencies are the same.
 *  - Shows "cross-border-hint" when currencies differ.
 *  - Neither hint appears when currencies are not yet selected.
 */
import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import { useForm } from 'react-hook-form';
import { theme } from '@/theme/theme';
import CurrencySplitSection from '../CurrencySplitSection';

/**
 * Wrapper that boots a minimal RHF context with the given default values and
 * renders CurrencySplitSection.
 */
function Wrapper({ collectionCcy = '', settleACcy = '' }) {
  const { control, register, formState: { errors } } = useForm({
    defaultValues: {
      currencySplit: { collectionCcy, settleACcy },
    },
  });
  return (
    <ThemeProvider theme={theme}>
      <CurrencySplitSection
        control={control}
        register={register}
        errors={errors.currencySplit}
      />
    </ThemeProvider>
  );
}

describe('CurrencySplitSection', () => {
  it('renders the section wrapper with its aria-label', () => {
    render(<Wrapper />);
    expect(screen.getByLabelText('currency-split-section')).toBeInTheDocument();
  });

  it('renders the collection currency select', () => {
    render(<Wrapper />);
    expect(screen.getByLabelText('currencySplit.collectionCcy')).toBeInTheDocument();
  });

  it('renders the settlement currency select', () => {
    render(<Wrapper />);
    expect(screen.getByLabelText('currencySplit.settleACcy')).toBeInTheDocument();
  });

  it('shows no hint banners when currencies are not yet selected', () => {
    render(<Wrapper />);
    expect(screen.queryByLabelText('cross-border-hint')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('no-cross-border-hint')).not.toBeInTheDocument();
  });

  it('shows the no-cross-border hint when both currencies are the same', () => {
    render(<Wrapper collectionCcy="KRW" settleACcy="KRW" />);
    expect(screen.getByLabelText('no-cross-border-hint')).toBeInTheDocument();
    expect(screen.queryByLabelText('cross-border-hint')).not.toBeInTheDocument();
  });

  it('shows the cross-border hint when currencies differ', () => {
    render(<Wrapper collectionCcy="KRW" settleACcy="USD" />);
    expect(screen.getByLabelText('cross-border-hint')).toBeInTheDocument();
    expect(screen.queryByLabelText('no-cross-border-hint')).not.toBeInTheDocument();
  });

  it('shows the cross-border arrow icon when currencies differ', () => {
    render(<Wrapper collectionCcy="KRW" settleACcy="USD" />);
    expect(screen.getByLabelText('cross-border-arrow')).toBeInTheDocument();
  });

  it('shows the same-currency icon when currencies are equal', () => {
    render(<Wrapper collectionCcy="KRW" settleACcy="KRW" />);
    expect(screen.getByLabelText('same-currency-icon')).toBeInTheDocument();
  });
});
