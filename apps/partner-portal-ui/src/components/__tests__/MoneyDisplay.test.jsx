import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import MoneyDisplay, { currencyScale, formatMoney } from '../MoneyDisplay';

/**
 * MoneyDisplay accepts EITHER `amount`+`currency` props (preferred — matches
 * the BFF wire shape where balance/threshold are bare BigDecimal strings on
 * the parent BalanceView) OR a `money` object (`{ amount, currency }`) for
 * back-compat with detail payloads that pre-bundle the two.
 */
describe('MoneyDisplay', () => {
  describe('currencyScale', () => {
    it('returns 0 for zero-decimal currencies (KRW/JPY/VND)', () => {
      expect(currencyScale('KRW')).toBe(0);
      expect(currencyScale('JPY')).toBe(0);
      expect(currencyScale('VND')).toBe(0);
      expect(currencyScale('krw')).toBe(0);
    });

    it('returns 2 for default currencies', () => {
      expect(currencyScale('USD')).toBe(2);
      expect(currencyScale('EUR')).toBe(2);
      expect(currencyScale('NPR')).toBe(2);
    });
  });

  describe('formatMoney', () => {
    it('formats USD with exactly 2 decimal places (amount + currency call form)', () => {
      expect(formatMoney('10.20', false, 'USD')).toMatch(/^10\.20$/);
      expect(formatMoney('10', false, 'USD')).toMatch(/^10\.00$/);
      expect(formatMoney('10.2', false, 'USD')).toMatch(/^10\.20$/);
    });

    it('formats USD via the money-object call form', () => {
      expect(formatMoney({ amount: '10.20', currency: 'USD' })).toMatch(/^10\.20$/);
    });

    it('formats KRW with 0 decimal places', () => {
      const out = formatMoney('50000', false, 'KRW');
      expect(out).not.toMatch(/\./);
      expect(out.replace(/[^\d]/g, '')).toBe('50000');
    });

    it('renders negative amounts with a leading minus by default', () => {
      expect(formatMoney('-12.34', false, 'USD')).toMatch(/^-12\.34$/);
    });

    it('renders negative amounts with parentheses when requested', () => {
      expect(formatMoney('-12.34', true, 'USD')).toBe('(12.34)');
      expect(formatMoney('-500', true, 'KRW')).toBe('(500)');
    });
  });

  describe('rendering', () => {
    it('renders USD with 2 decimal places and currency code', () => {
      render(<MoneyDisplay amount="10.20" currency="USD" />);
      expect(screen.getByTestId('money-amount').textContent).toMatch(/^10\.20$/);
      expect(screen.getByTestId('money-currency').textContent).toBe('USD');
    });

    it('renders KRW with 0 decimal places', () => {
      render(<MoneyDisplay amount="50000" currency="KRW" />);
      const amount = screen.getByTestId('money-amount').textContent ?? '';
      expect(amount).not.toMatch(/\./);
      expect(amount.replace(/[^\d]/g, '')).toBe('50000');
      expect(screen.getByTestId('money-currency').textContent).toBe('KRW');
    });

    it('accepts the legacy `money` object form', () => {
      render(<MoneyDisplay money={{ amount: '10.20', currency: 'USD' }} />);
      expect(screen.getByTestId('money-amount').textContent).toMatch(/^10\.20$/);
      expect(screen.getByTestId('money-currency').textContent).toBe('USD');
    });

    it('shows negative amounts with a leading minus by default', () => {
      render(<MoneyDisplay amount="-7.50" currency="USD" />);
      expect(screen.getByTestId('money-amount').textContent).toMatch(/^-7\.50$/);
    });

    it('shows negative amounts with parentheses when parenthesizeNegative is set', () => {
      render(<MoneyDisplay amount="-7.50" currency="USD" parenthesizeNegative />);
      expect(screen.getByTestId('money-amount').textContent).toBe('(7.50)');
    });

    it('exposes an aria-label with amount + currency', () => {
      render(<MoneyDisplay amount="10.20" currency="USD" />);
      expect(screen.getByLabelText(/10\.20 USD/)).toBeInTheDocument();
    });

    it('hides currency code when showCurrency=false', () => {
      render(<MoneyDisplay amount="10.20" currency="USD" showCurrency={false} />);
      expect(screen.queryByTestId('money-currency')).toBeNull();
    });

    it('wraps the amount in a tooltip when showRawTooltip is set', () => {
      render(<MoneyDisplay amount="10500.567" currency="USD" showRawTooltip />);
      expect(screen.getByTestId('money-raw-tooltip')).toBeInTheDocument();
      expect(screen.getByTestId('money-amount').textContent).toMatch(/^10,?500\.57$/);
    });

    it('does NOT wrap in tooltip when showRawTooltip is unset', () => {
      render(<MoneyDisplay amount="10.20" currency="USD" />);
      expect(screen.queryByTestId('money-raw-tooltip')).toBeNull();
    });

    it('renders 0 instead of crashing when amount is missing', () => {
      render(<MoneyDisplay currency="USD" />);
      expect(screen.getByTestId('money-amount').textContent).toMatch(/^0\.00$/);
    });
  });
});
