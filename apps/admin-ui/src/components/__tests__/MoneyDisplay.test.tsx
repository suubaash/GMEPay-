import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import MoneyDisplay from '../MoneyDisplay';
import { theme } from '@/theme/theme';

function renderWithTheme(ui: React.ReactElement) {
  return render(<ThemeProvider theme={theme}>{ui}</ThemeProvider>);
}

describe('MoneyDisplay', () => {
  it('renders a USD amount with 2 decimal places and thousands separators', () => {
    renderWithTheme(<MoneyDisplay value={{ amount: '1234.5', currency: 'USD' }} />);
    expect(screen.getByText('1,234.50')).toBeInTheDocument();
    expect(screen.getByText('USD')).toBeInTheDocument();
  });

  it('renders a KRW amount with 0 decimal places (CurrencyScale)', () => {
    renderWithTheme(<MoneyDisplay value={{ amount: '1500000', currency: 'KRW' }} />);
    // No decimal point should be present
    expect(screen.getByText('1,500,000')).toBeInTheDocument();
    expect(screen.getByText('KRW')).toBeInTheDocument();
  });

  it('renders a JPY amount with 0 decimal places', () => {
    renderWithTheme(<MoneyDisplay value={{ amount: '12345', currency: 'JPY' }} />);
    expect(screen.getByText('12,345')).toBeInTheDocument();
  });

  it('renders a negative USD amount with a leading minus sign', () => {
    renderWithTheme(<MoneyDisplay value={{ amount: '-5.00', currency: 'USD' }} />);
    expect(screen.getByText('-5.00')).toBeInTheDocument();
    expect(screen.getByText('USD')).toBeInTheDocument();
  });

  it('omits the currency code when withCurrency=false', () => {
    renderWithTheme(
      <MoneyDisplay value={{ amount: '10.00', currency: 'USD' }} withCurrency={false} />,
    );
    expect(screen.getByText('10.00')).toBeInTheDocument();
    expect(screen.queryByText('USD')).toBeNull();
  });

  it('preserves precision on large KRW values past Number.MAX_SAFE_INTEGER', () => {
    // 9_999_999_999_999_999_999 is well above 2^53-1; we must not lose precision.
    renderWithTheme(
      <MoneyDisplay value={{ amount: '9999999999999999999', currency: 'KRW' }} />,
    );
    expect(screen.getByText('9,999,999,999,999,999,999')).toBeInTheDocument();
  });
});
