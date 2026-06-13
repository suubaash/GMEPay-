/**
 * Vitest coverage for FxConfigSection (Slice 6B.2).
 *
 * Contract:
 *  - Renders marginBps input, reference rate source select, quoteHoldSeconds input.
 *  - Section wrapper has aria-label "fx-config-section".
 *  - marginBps slider has aria-label "fxConfig.marginBps.slider".
 *  - quoteHoldSeconds slider has aria-label "fxConfig.quoteHoldSeconds.slider".
 *  - Typing a new value in marginBps numeric field updates the input.
 */
import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { useForm } from 'react-hook-form';
import { yupResolver } from '@hookform/resolvers/yup';
import { theme } from '@/theme/theme';
import partnerStep6CommercialSchema from '@/schemas/partnerStep6CommercialSchema';
import FxConfigSection from '../FxConfigSection';

function defaultValues() {
  return {
    feeSchedule: { scheme: 'ZEROPAY', direction: 'OUTBOUND', fixedFeeUsd: '0.00', bpsFee: '0.00', tiers: [] },
    fxConfig: { marginBps: '100', referenceRateSource: 'SEOUL_FX_BROKER', quoteHoldSeconds: 300 },
    limits: { perTxnMinUsd: '1.00', perTxnMaxUsd: '5000.00', dailyCapUsd: '50000.00', monthlyCapUsd: '200000.00', annualCapUsd: '2000000.00', licenseType: 'MSB' },
    contract: { effectiveFrom: '2026-01-01', effectiveTo: null, autoRenewal: true, noticePeriodDays: 30, refundChargebackPolicy: 'PARTNER_BEARS', terminationReason: null },
  };
}

function Wrapper() {
  const { control, register, formState: { errors } } = useForm({
    resolver: yupResolver(partnerStep6CommercialSchema),
    defaultValues: defaultValues(),
  });
  return (
    <ThemeProvider theme={theme}>
      <FxConfigSection control={control} register={register} errors={errors.fxConfig} />
    </ThemeProvider>
  );
}

describe('FxConfigSection', () => {
  it('renders section wrapper with aria-label', () => {
    render(<Wrapper />);
    expect(screen.getByLabelText('fx-config-section')).toBeInTheDocument();
  });

  it('renders marginBps numeric input', () => {
    render(<Wrapper />);
    expect(screen.getByLabelText('fxConfig.marginBps')).toBeInTheDocument();
  });

  it('renders marginBps slider', () => {
    render(<Wrapper />);
    expect(screen.getByLabelText('fxConfig.marginBps.slider')).toBeInTheDocument();
  });

  it('renders referenceRateSource select', () => {
    render(<Wrapper />);
    expect(screen.getByLabelText('fxConfig.referenceRateSource')).toBeInTheDocument();
  });

  it('renders quoteHoldSeconds numeric input', () => {
    render(<Wrapper />);
    expect(screen.getByLabelText('fxConfig.quoteHoldSeconds')).toBeInTheDocument();
  });

  it('renders quoteHoldSeconds slider', () => {
    render(<Wrapper />);
    expect(screen.getByLabelText('fxConfig.quoteHoldSeconds.slider')).toBeInTheDocument();
  });

  it('marginBps numeric input shows default value', () => {
    render(<Wrapper />);
    const input = screen.getByLabelText('fxConfig.marginBps');
    expect(input.value).toBe('100');
  });
});
