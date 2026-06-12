/**
 * Vitest coverage for ContractSection (Slice 6B.2).
 *
 * Contract:
 *  - Renders effectiveFrom, effectiveTo date inputs.
 *  - Renders autoRenewal switch.
 *  - Renders noticePeriodDays input.
 *  - Renders refundChargebackPolicy radio group.
 *  - Renders terminationReason text area.
 *  - Section wrapper has aria-label "contract-section".
 */
import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import { useForm } from 'react-hook-form';
import { yupResolver } from '@hookform/resolvers/yup';
import { theme } from '@/theme/theme';
import partnerStep6CommercialSchema from '@/schemas/partnerStep6CommercialSchema';
import ContractSection from '../ContractSection';
import { REFUND_CHARGEBACK_POLICIES } from '@/schemas/partnerStep6CommercialSchema';

function defaultValues() {
  return {
    feeSchedule: { scheme: 'ZEROPAY', direction: 'OUTBOUND', fixedFeeUsd: '0.00', bpsFee: '0.00', tiers: [] },
    fxConfig: { marginBps: '0', referenceRateSource: 'SEOUL_FX_BROKER', quoteHoldSeconds: 300 },
    limits: { perTxnMinUsd: '1.00', perTxnMaxUsd: '5000.00', dailyCapUsd: '50000.00', monthlyCapUsd: '200000.00', annualCapUsd: '2000000.00', licenseType: 'MSB' },
    contract: {
      effectiveFrom: '2026-01-01',
      effectiveTo: null,
      autoRenewal: true,
      noticePeriodDays: 30,
      refundChargebackPolicy: 'PARTNER_BEARS',
      terminationReason: null,
    },
  };
}

function Wrapper() {
  const { control, register, formState: { errors } } = useForm({
    resolver: yupResolver(partnerStep6CommercialSchema),
    defaultValues: defaultValues(),
  });
  return (
    <ThemeProvider theme={theme}>
      <ContractSection control={control} register={register} errors={errors.contract} />
    </ThemeProvider>
  );
}

describe('ContractSection', () => {
  it('renders section wrapper with aria-label', () => {
    render(<Wrapper />);
    expect(screen.getByLabelText('contract-section')).toBeInTheDocument();
  });

  it('renders effectiveFrom date input', () => {
    render(<Wrapper />);
    expect(screen.getByLabelText('contract.effectiveFrom')).toBeInTheDocument();
  });

  it('renders effectiveTo date input', () => {
    render(<Wrapper />);
    expect(screen.getByLabelText('contract.effectiveTo')).toBeInTheDocument();
  });

  it('renders autoRenewal switch', () => {
    render(<Wrapper />);
    expect(screen.getByLabelText('contract.autoRenewal')).toBeInTheDocument();
  });

  it('renders noticePeriodDays input', () => {
    render(<Wrapper />);
    expect(screen.getByLabelText('contract.noticePeriodDays')).toBeInTheDocument();
  });

  it('renders refundChargebackPolicy radio group', () => {
    render(<Wrapper />);
    expect(screen.getByLabelText('refund-chargeback-policy-group')).toBeInTheDocument();
  });

  it('renders all refund/chargeback policy radio options', () => {
    render(<Wrapper />);
    REFUND_CHARGEBACK_POLICIES.forEach((policy) => {
      expect(screen.getByLabelText(`refundChargebackPolicy-${policy}`)).toBeInTheDocument();
    });
  });

  it('renders terminationReason text input', () => {
    render(<Wrapper />);
    expect(screen.getByLabelText('contract.terminationReason')).toBeInTheDocument();
  });

  it('effectiveFrom shows pre-populated value', () => {
    render(<Wrapper />);
    const input = screen.getByLabelText('contract.effectiveFrom');
    expect(input.value).toBe('2026-01-01');
  });
});
