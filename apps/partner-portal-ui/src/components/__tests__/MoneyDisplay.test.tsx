import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import MoneyDisplay, { currencyScale, formatMoney } from '../MoneyDisplay';

describe('MoneyDisplay', () => {
  describe('currencyScale', () => {
    it('returns 0 for zero-decimal currencies (KRW/JPY/VND)', () => {
      expect(currencyScale('KRW')).toBe(0);
      expect(currencyScale('JPY')).toBe(0);
      expect(currencyScale('VND')).toBe(0);
      expect(currencyScale('krw')).toBe(0); // case-insensitive
    });

    it('returns 2 for default currencies', () => {
      expect(currencyScale('USD')).toBe(2);
      expect(currencyScale('EUR')).toBe(2);
      expect(currencyScale('NPR')).toBe(2);
    });
  });

  describe('formatMoney', () => {
    it('formats USD with exactly 2 decimal places', () => {
      expect(formatMoney({ amount: '10.20', currency: 'USD' })).toMatch(/^10\.20$/);
      expect(formatMoney({ amount: '10', currency: 'USD' })).toMatch(/^10\.00$/);
      expect(formatMoney({ amount: '10.2', currency: 'USD' })).toMatch(/^10\.20$/);
    });

    it('formats KRW with 0 decimal places', () => {
      const out = formatMoney({ amount: '50000', currency: 'KRW' });
      // Locale-aware grouping may add separators; just confirm no decimal point.
      expect(out).not.toMatch(/\./);
      expect(out.replace(/[^\d]/g, '')).toBe('50000');
    });

    it('renders negative amounts with a leading minus by default', () => {
      expect(formatMoney({ amount: '-12.34', currency: 'USD' })).toMatch(/^-12\.34$/);
    });

    it('renders negative amounts with parentheses when requested', () => {
      expect(formatMoney({ amount: '-12.34', currency: 'USD' }, true)).toBe('(12.34)');
      expect(formatMoney({ amount: '-500', currency: 'KRW' }, true)).toBe('(500)');
    });
  });

  describe('rendering', () => {
    it('renders USD with 2 decimal places and currency code', () => {
      render(<MoneyDisplay money={{ amount: '10.20', currency: 'USD' }} />);
      expect(screen.getByTestId('money-amount').textContent).toMatch(/^10\.20$/);
      expect(screen.getByTestId('money-currency').textContent).toBe('USD');
    });

    it('renders KRW with 0 decimal places', () => {
      render(<MoneyDisplay money={{ amount: '50000', currency: 'KRW' }} />);
      const amount = screen.getByTestId('money-amount').textContent ?? '';
      expect(amount).not.toMatch(/\./);
      expect(amount.replace(/[^\d]/g, '')).toBe('50000');
      expect(screen.getByTestId('money-currency').textContent).toBe('KRW');
    });

    it('shows negative amounts with a leading minus by default', () => {
      render(<MoneyDisplay money={{ amount: '-7.50', currency: 'USD' }} />);
      expect(screen.getByTestId('money-amount').textContent).toMatch(/^-7\.50$/);
    });

    it('shows negative amounts with parentheses when parenthesizeNegative is set', () => {
      render(
        <MoneyDisplay money={{ amount: '-7.50', currency: 'USD' }} parenthesizeNegative />
      );
      expect(screen.getByTestId('money-amount').textContent).toBe('(7.50)');
    });

    it('exposes an aria-label with amount + currency', () => {
      render(<MoneyDisplay money={{ amount: '10.20', currency: 'USD' }} />);
      expect(screen.getByLabelText(/10\.20 USD/)).toBeInTheDocument();
    });

    it('hides currency code when showCurrency=false', () => {
      render(
        <MoneyDisplay money={{ amount: '10.20', currency: 'USD' }} showCurrency={false} />
      );
      expect(screen.queryByTestId('money-currency')).toBeNull();
    });
  });
});
