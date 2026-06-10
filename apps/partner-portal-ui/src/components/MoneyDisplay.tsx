'use client';
import * as React from 'react';
import Box from '@mui/material/Box';
import Tooltip from '@mui/material/Tooltip';
import type { MoneyDto } from '@/api/types';

/**
 * ISO-4217 currency scale (number of decimal places to display).
 *
 * Mirrors lib-money/CurrencyScale on the backend (docs/MONEY_CONVENTION.md):
 *  - KRW / JPY / VND => 0 decimals
 *  - default         => 2 decimals
 *
 * NOTE: this is a UI display helper. The authoritative scale lives in the
 * backend's ISO-4217 config table and is applied at booking time, not here.
 */
const ZERO_DECIMAL_CURRENCIES = new Set(['KRW', 'JPY', 'VND']);

export function currencyScale(currency: string): number {
  return ZERO_DECIMAL_CURRENCIES.has(currency.toUpperCase()) ? 0 : 2;
}

export interface MoneyDisplayProps {
  money: MoneyDto;
  /** Render negative amounts as `(123.45)` instead of `-123.45`. Defaults to false. */
  parenthesizeNegative?: boolean;
  /** Show the ISO-4217 code after the amount. Defaults to true. */
  showCurrency?: boolean;
  /** Optional ARIA label override; otherwise composed from amount + currency. */
  ariaLabel?: string;
  className?: string;
  /**
   * When true, wraps the rendered amount in a tooltip that reveals the raw
   * (unrounded, server-supplied) decimal string on hover/focus. Useful for
   * detail screens where users need to audit the precise value behind a
   * display-rounded total. Defaults to false.
   */
  showRawTooltip?: boolean;
}

/**
 * Format a decimal string in major currency units to a localized display
 * with the correct number of decimal places for the currency.
 *
 * Negative amounts always render with either a leading `-` or surrounding
 * parentheses (per `parenthesizeNegative`) — never as a positive number.
 */
export function formatMoney(money: MoneyDto, parenthesizeNegative = false): string {
  const scale = currencyScale(money.currency);
  const raw = (money.amount ?? '').trim();
  const isNegative = raw.startsWith('-');
  const absStr = isNegative ? raw.slice(1) : raw;

  // Parse defensively: invalid strings should not crash the UI.
  const n = Number(absStr);
  let formatted: string;
  if (!Number.isFinite(n)) {
    formatted = absStr || '0';
  } else {
    formatted = n.toLocaleString(undefined, {
      minimumFractionDigits: scale,
      maximumFractionDigits: scale
    });
  }

  if (isNegative) {
    return parenthesizeNegative ? `(${formatted})` : `-${formatted}`;
  }
  return formatted;
}

export default function MoneyDisplay({
  money,
  parenthesizeNegative = false,
  showCurrency = true,
  ariaLabel,
  className,
  showRawTooltip = false
}: MoneyDisplayProps) {
  const text = formatMoney(money, parenthesizeNegative);
  const isNegative = (money.amount ?? '').trim().startsWith('-');
  const label = ariaLabel ?? `${text} ${money.currency}`;

  const inner = (
    <Box
      component="span"
      className={className}
      aria-label={label}
      sx={{
        fontVariantNumeric: 'tabular-nums',
        color: isNegative ? 'error.main' : 'inherit',
        whiteSpace: 'nowrap'
      }}
    >
      <span data-testid="money-amount">{text}</span>
      {showCurrency && (
        <>
          {' '}
          <Box
            component="span"
            data-testid="money-currency"
            sx={{ color: 'text.secondary', fontWeight: 500 }}
          >
            {money.currency}
          </Box>
        </>
      )}
    </Box>
  );

  if (showRawTooltip) {
    const raw = `${money.amount ?? ''} ${money.currency}`.trim();
    return (
      <Tooltip title={`Raw: ${raw}`} arrow placement="top">
        <Box
          component="span"
          data-testid="money-raw-tooltip"
          sx={{ display: 'inline-block', cursor: 'help' }}
        >
          {inner}
        </Box>
      </Tooltip>
    );
  }

  return inner;
}
