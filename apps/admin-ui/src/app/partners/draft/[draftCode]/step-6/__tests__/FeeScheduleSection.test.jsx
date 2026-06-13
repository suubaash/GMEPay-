/**
 * Vitest coverage for FeeScheduleSection (Slice 6B.2).
 *
 * Contract:
 *  - Renders scheme input, direction select, fixedFeeUsd, bpsFee fields.
 *  - "Add tier" button appends a tier row.
 *  - Remove button removes the correct tier row.
 *  - aria-labels are present for all inputs and the section wrapper.
 */
import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { useForm } from 'react-hook-form';
import { yupResolver } from '@hookform/resolvers/yup';
import { theme } from '@/theme/theme';
import partnerStep6CommercialSchema from '@/schemas/partnerStep6CommercialSchema';
import FeeScheduleSection from '../FeeScheduleSection';

function defaultValues() {
  return {
    feeSchedule: {
      scheme: '',
      direction: 'OUTBOUND',
      fixedFeeUsd: '0.00',
      bpsFee: '0.00',
      tiers: [],
    },
    fxConfig: { marginBps: '0', referenceRateSource: 'SEOUL_FX_BROKER', quoteHoldSeconds: 300 },
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
      <FeeScheduleSection
        control={control}
        register={register}
        errors={errors.feeSchedule}
      />
    </ThemeProvider>
  );
}

describe('FeeScheduleSection', () => {
  it('renders scheme input', () => {
    render(<Wrapper />);
    expect(screen.getByLabelText('feeSchedule.scheme')).toBeInTheDocument();
  });

  it('renders direction select', () => {
    render(<Wrapper />);
    expect(screen.getByLabelText('feeSchedule.direction')).toBeInTheDocument();
  });

  it('renders fixedFeeUsd input', () => {
    render(<Wrapper />);
    expect(screen.getByLabelText('feeSchedule.fixedFeeUsd')).toBeInTheDocument();
  });

  it('renders bpsFee input', () => {
    render(<Wrapper />);
    expect(screen.getByLabelText('feeSchedule.bpsFee')).toBeInTheDocument();
  });

  it('renders section wrapper with aria-label', () => {
    render(<Wrapper />);
    expect(screen.getByLabelText('fee-schedule-section')).toBeInTheDocument();
  });

  it('renders "Add tier" button', () => {
    render(<Wrapper />);
    expect(screen.getByLabelText('add-fee-tier')).toBeInTheDocument();
  });

  it('shows no tier rows initially', () => {
    render(<Wrapper />);
    expect(screen.queryByLabelText('feeSchedule.tiers.0.fromVolumeUsd')).not.toBeInTheDocument();
  });

  it('appends a tier row when "Add tier" is clicked', async () => {
    const user = userEvent.setup();
    render(<Wrapper />);
    await user.click(screen.getByLabelText('add-fee-tier'));
    expect(screen.getByLabelText('feeSchedule.tiers.0.fromVolumeUsd')).toBeInTheDocument();
    expect(screen.getByLabelText('feeSchedule.tiers.0.bpsOverride')).toBeInTheDocument();
  });

  it('removes a tier row when remove button is clicked', async () => {
    const user = userEvent.setup();
    render(<Wrapper />);
    await user.click(screen.getByLabelText('add-fee-tier'));
    expect(screen.getByLabelText('feeSchedule.tiers.0.fromVolumeUsd')).toBeInTheDocument();
    await user.click(screen.getByLabelText('remove-fee-tier-0'));
    expect(screen.queryByLabelText('feeSchedule.tiers.0.fromVolumeUsd')).not.toBeInTheDocument();
  });
});
