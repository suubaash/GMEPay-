/**
 * Vitest coverage for RulesEditor (Slice 6A.2).
 *
 * Contract:
 *  - Renders the section wrapper with "rules-editor-section" aria-label.
 *  - Renders "add-rule-row" button.
 *  - Shows "rules-empty-hint" when the rules array is empty.
 *  - "Add rule" appends a rule row with the correct aria-labels.
 *  - Remove button removes the row.
 *  - Shows the margin-sum chip with aria-label "rules.{i}.margin-sum".
 *  - Shows the cross-border floor warning chip when mA + mB < 2% and
 *    currencies are cross-border.
 *  - Does NOT show the floor warning for domestic (same-currency) pairs
 *    even when sum < 2%.
 *  - Section-level submit-block alert is shown when any cross-border rule
 *    has sum < 2%.
 */
import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { useForm } from 'react-hook-form';
import { theme } from '@/theme/theme';
import RulesEditor from '../RulesEditor';

/**
 * Test wrapper: boots a minimal RHF context and renders RulesEditor with
 * the given initial values and currency pair.
 */
function Wrapper({
  initialRules = [],
  collectionCcy = '',
  settleACcy = '',
  schemeOptions = [],
}) {
  const { control, register, formState: { errors } } = useForm({
    defaultValues: { rules: initialRules },
  });
  return (
    <ThemeProvider theme={theme}>
      <RulesEditor
        control={control}
        register={register}
        errors={errors.rules}
        schemeOptions={schemeOptions}
        collectionCcy={collectionCcy}
        settleACcy={settleACcy}
      />
    </ThemeProvider>
  );
}

describe('RulesEditor — initial render', () => {
  it('renders the section wrapper with its aria-label', () => {
    render(<Wrapper />);
    expect(screen.getByLabelText('rules-editor-section')).toBeInTheDocument();
  });

  it('renders the "add-rule-row" button', () => {
    render(<Wrapper />);
    expect(screen.getByLabelText('add-rule-row')).toBeInTheDocument();
  });

  it('shows the empty hint when no rules are configured', () => {
    render(<Wrapper />);
    expect(screen.getByLabelText('rules-empty-hint')).toBeInTheDocument();
  });

  it('does not show the empty hint when rules exist', () => {
    const rule = {
      schemeId: 'ZEROPAY',
      direction: 'OUTBOUND',
      mA: '0.0150',
      mB: '0.0050',
      serviceChargeUsd: '0.0000',
    };
    render(<Wrapper initialRules={[rule]} />);
    expect(screen.queryByLabelText('rules-empty-hint')).not.toBeInTheDocument();
  });
});

describe('RulesEditor — adding and removing rows', () => {
  it('appends a row when "Add rule" is clicked', async () => {
    const user = userEvent.setup();
    render(<Wrapper />);
    await user.click(screen.getByLabelText('add-rule-row'));
    expect(screen.getByLabelText('rules.0.schemeId')).toBeInTheDocument();
    expect(screen.getByLabelText('rules.0.mA')).toBeInTheDocument();
    expect(screen.getByLabelText('rules.0.mB')).toBeInTheDocument();
    expect(screen.getByLabelText('rules.0.serviceChargeUsd')).toBeInTheDocument();
  });

  it('removes the row when the delete button is clicked', async () => {
    const user = userEvent.setup();
    render(<Wrapper />);
    await user.click(screen.getByLabelText('add-rule-row'));
    expect(screen.getByLabelText('rule-row-0')).toBeInTheDocument();
    await user.click(screen.getByLabelText('remove-rule-row-0'));
    expect(screen.queryByLabelText('rule-row-0')).not.toBeInTheDocument();
  });

  it('renders a scheme select when schemeOptions are provided', async () => {
    const user = userEvent.setup();
    render(<Wrapper schemeOptions={['ZEROPAY', 'VIETQR']} />);
    await user.click(screen.getByLabelText('add-rule-row'));
    // With schemeOptions the component uses a Select, not a TextField
    expect(screen.getByLabelText('rules.0.schemeId')).toBeInTheDocument();
  });
});

describe('RulesEditor — margin sum preview', () => {
  it('renders the margin-sum chip for each row', () => {
    const rule = {
      schemeId:  'ZEROPAY',
      direction: 'OUTBOUND',
      mA:        '0.0150',
      mB:        '0.0050',
      serviceChargeUsd: '0.0000',
    };
    render(<Wrapper initialRules={[rule]} />);
    expect(screen.getByLabelText('rules.0.margin-sum')).toBeInTheDocument();
  });
});

describe('RulesEditor — cross-border margin floor warning', () => {
  it('shows the floor warning chip for cross-border rules below 2%', () => {
    // mA=0.0100 + mB=0.0050 = 0.0150 < 0.02  →  warning
    const rule = {
      schemeId: 'ZEROPAY', direction: 'OUTBOUND',
      mA: '0.0100', mB: '0.0050', serviceChargeUsd: '0.0000',
    };
    render(<Wrapper initialRules={[rule]} collectionCcy="KRW" settleACcy="USD" />);
    expect(screen.getByLabelText('rules.0.margin-floor-warning')).toBeInTheDocument();
  });

  it('does not show the floor warning when sum >= 2% (cross-border)', () => {
    // mA=0.0150 + mB=0.0050 = 0.0200 >= 0.02  →  no warning
    const rule = {
      schemeId: 'ZEROPAY', direction: 'OUTBOUND',
      mA: '0.0150', mB: '0.0050', serviceChargeUsd: '0.0000',
    };
    render(<Wrapper initialRules={[rule]} collectionCcy="KRW" settleACcy="USD" />);
    expect(screen.queryByLabelText('rules.0.margin-floor-warning')).not.toBeInTheDocument();
  });

  it('does not show the floor warning for domestic (same-currency) pairs even when sum < 2%', () => {
    // Domestic — floor does not apply
    const rule = {
      schemeId: 'ZEROPAY', direction: 'OUTBOUND',
      mA: '0.0000', mB: '0.0000', serviceChargeUsd: '0.0000',
    };
    render(<Wrapper initialRules={[rule]} collectionCcy="KRW" settleACcy="KRW" />);
    expect(screen.queryByLabelText('rules.0.margin-floor-warning')).not.toBeInTheDocument();
  });

  it('shows the section-level submit-block alert for cross-border rules below floor', () => {
    const rule = {
      schemeId: 'ZEROPAY', direction: 'OUTBOUND',
      mA: '0.0050', mB: '0.0050', serviceChargeUsd: '0.0000',
    };
    render(<Wrapper initialRules={[rule]} collectionCcy="KRW" settleACcy="USD" />);
    expect(screen.getByLabelText('rules-margin-floor-blocked')).toBeInTheDocument();
  });

  it('does NOT show the section-level submit-block alert for domestic pairs', () => {
    const rule = {
      schemeId: 'ZEROPAY', direction: 'OUTBOUND',
      mA: '0.0000', mB: '0.0000', serviceChargeUsd: '0.0000',
    };
    render(<Wrapper initialRules={[rule]} collectionCcy="KRW" settleACcy="KRW" />);
    expect(screen.queryByLabelText('rules-margin-floor-blocked')).not.toBeInTheDocument();
  });

  it('does NOT show the section-level submit-block alert when no rules exist', () => {
    render(<Wrapper collectionCcy="KRW" settleACcy="USD" />);
    expect(screen.queryByLabelText('rules-margin-floor-blocked')).not.toBeInTheDocument();
  });
});
