/**
 * Vitest coverage for LimitsSection (Slice 6B.2).
 *
 * Contract:
 *  - Renders all limit fields with correct aria-labels.
 *  - License type select renders.
 *  - Selecting 소액해외송금업 shows the restriction banner.
 *  - Per-txn max > $5000 with 소액해외송금업 shows breach alert.
 *  - Monthly cap > $50000 with 소액해외송금업 shows breach alert.
 *  - isSoaekLimitBreached helper returns correct values.
 */
import { describe, expect, it } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { useForm } from 'react-hook-form';
import { yupResolver } from '@hookform/resolvers/yup';
import { theme } from '@/theme/theme';
import partnerStep6CommercialSchema from '@/schemas/partnerStep6CommercialSchema';
import LimitsSection, { isSoaekLimitBreached } from '../LimitsSection';

function defaultValues(overrides = {}) {
  return {
    feeSchedule: { scheme: 'ZEROPAY', direction: 'OUTBOUND', fixedFeeUsd: '0.00', bpsFee: '0.00', tiers: [] },
    fxConfig: { marginBps: '0', referenceRateSource: 'SEOUL_FX_BROKER', quoteHoldSeconds: 300 },
    limits: {
      perTxnMinUsd: '1.00',
      perTxnMaxUsd: '5000.00',
      dailyCapUsd: '50000.00',
      monthlyCapUsd: '50000.00',
      annualCapUsd: '2000000.00',
      licenseType: 'MSB',
      ...overrides,
    },
    contract: { effectiveFrom: '2026-01-01', effectiveTo: null, autoRenewal: true, noticePeriodDays: 30, refundChargebackPolicy: 'PARTNER_BEARS', terminationReason: null },
  };
}

function Wrapper({ limitOverrides } = {}) {
  const { control, register, formState: { errors } } = useForm({
    resolver: yupResolver(partnerStep6CommercialSchema),
    defaultValues: defaultValues(limitOverrides),
  });
  return (
    <ThemeProvider theme={theme}>
      <LimitsSection control={control} register={register} errors={errors.limits} />
    </ThemeProvider>
  );
}

describe('LimitsSection', () => {
  it('renders section wrapper with aria-label', () => {
    render(<Wrapper />);
    expect(screen.getByLabelText('limits-section')).toBeInTheDocument();
  });

  it('renders perTxnMinUsd input', () => {
    render(<Wrapper />);
    expect(screen.getByLabelText('limits.perTxnMinUsd')).toBeInTheDocument();
  });

  it('renders perTxnMaxUsd input', () => {
    render(<Wrapper />);
    expect(screen.getByLabelText('limits.perTxnMaxUsd')).toBeInTheDocument();
  });

  it('renders dailyCapUsd input', () => {
    render(<Wrapper />);
    expect(screen.getByLabelText('limits.dailyCapUsd')).toBeInTheDocument();
  });

  it('renders monthlyCapUsd input', () => {
    render(<Wrapper />);
    expect(screen.getByLabelText('limits.monthlyCapUsd')).toBeInTheDocument();
  });

  it('renders annualCapUsd input', () => {
    render(<Wrapper />);
    expect(screen.getByLabelText('limits.annualCapUsd')).toBeInTheDocument();
  });

  it('renders licenseType select', () => {
    render(<Wrapper />);
    expect(screen.getByLabelText('limits.licenseType')).toBeInTheDocument();
  });

  it('does not show soaek banner when licenseType is MSB', () => {
    render(<Wrapper />);
    expect(screen.queryByLabelText('soaek-restriction-info')).not.toBeInTheDocument();
  });

  it('shows soaek restriction banner when licenseType is 소액해외송금업', () => {
    render(<Wrapper limitOverrides={{ licenseType: '소액해외송금업' }} />);
    expect(screen.getByLabelText('soaek-restriction-info')).toBeInTheDocument();
  });

  it('shows per-txn breach alert when perTxnMax exceeds cap with 소액해외송금업', () => {
    render(<Wrapper limitOverrides={{ licenseType: '소액해외송금업', perTxnMaxUsd: '6000.00' }} />);
    expect(screen.getByLabelText('soaek-per-txn-breach')).toBeInTheDocument();
  });

  it('shows monthly breach alert when monthlyCapUsd exceeds cap with 소액해외송금업', () => {
    render(<Wrapper limitOverrides={{ licenseType: '소액해외송금업', monthlyCapUsd: '60000.00' }} />);
    expect(screen.getByLabelText('soaek-monthly-breach')).toBeInTheDocument();
  });

  it('does not show breach alerts when under caps with 소액해외송금업', () => {
    render(<Wrapper limitOverrides={{ licenseType: '소액해외송금업', perTxnMaxUsd: '4999.00', monthlyCapUsd: '49999.00' }} />);
    expect(screen.queryByLabelText('soaek-per-txn-breach')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('soaek-monthly-breach')).not.toBeInTheDocument();
  });
});

describe('isSoaekLimitBreached', () => {
  it('returns false for non-소액해외송금업 license', () => {
    expect(isSoaekLimitBreached('MSB', '10000.00', '100000.00')).toBe(false);
  });

  it('returns false when under caps', () => {
    expect(isSoaekLimitBreached('소액해외송금업', '5000.00', '50000.00')).toBe(false);
  });

  it('returns true when perTxnMax exceeds cap', () => {
    expect(isSoaekLimitBreached('소액해외송금업', '5000.01', '50000.00')).toBe(true);
  });

  it('returns true when monthlyCapUsd exceeds cap', () => {
    expect(isSoaekLimitBreached('소액해외송금업', '5000.00', '50000.01')).toBe(true);
  });

  it('returns true when both caps exceeded', () => {
    expect(isSoaekLimitBreached('소액해외송금업', '6000.00', '60000.00')).toBe(true);
  });
});
