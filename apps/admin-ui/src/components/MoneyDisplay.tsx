'use client';

import { Box, Typography } from '@mui/material';
import type { Money } from '@/api/types';

/**
 * Renders a {@link Money} value as `<amount> <currency>` using the currency's
 * native scale.
 *
 * Per docs/MONEY_CONVENTION.md money is exchanged as a decimal STRING; we never
 * convert it to a JS number before display, because Number() would lose
 * precision on values like "10500.567" or scale-0 KRW totals.
 *
 * The currency scales table mirrors `lib-money/CurrencyScale`:
 *   KRW / JPY / VND -> 0 decimals
 *   default         -> 2 decimals
 */
const CURRENCY_SCALES: Readonly<Record<string, number>> = {
  KRW: 0,
  JPY: 0,
  VND: 0,
};

function scaleFor(currency: string): number {
  return CURRENCY_SCALES[currency] ?? 2;
}

/**
 * Pad/round the decimal-string `amount` to exactly `scale` fractional digits
 * by string manipulation. No floating-point conversion — important for KRW
 * totals that may exceed Number.MAX_SAFE_INTEGER.
 */
function formatToScale(amount: string, scale: number): string {
  const negative = amount.startsWith('-');
  const abs = negative ? amount.slice(1) : amount;
  const [intPartRaw, fracPartRaw = ''] = abs.split('.');
  const intPart = intPartRaw || '0';

  let frac = fracPartRaw;
  if (frac.length > scale) {
    // truncate (display-only — server already booked under the partner's mode)
    frac = frac.slice(0, scale);
  } else if (frac.length < scale) {
    frac = frac.padEnd(scale, '0');
  }

  // thousands separators on the integer portion
  const intGrouped = intPart.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  const formatted = scale === 0 ? intGrouped : `${intGrouped}.${frac}`;
  return negative ? `-${formatted}` : formatted;
}

export interface MoneyDisplayProps {
  value: Money;
  /** Show the currency code after the amount. Default true. */
  withCurrency?: boolean;
}

export default function MoneyDisplay({ value, withCurrency = true }: MoneyDisplayProps) {
  const scale = scaleFor(value.currency);
  const formatted = formatToScale(value.amount, scale);
  return (
    <Box component="span" sx={{ fontVariantNumeric: 'tabular-nums' }}>
      <Typography component="span" sx={{ fontWeight: 600 }}>
        {formatted}
      </Typography>
      {withCurrency ? (
        <Typography
          component="span"
          variant="body2"
          color="text.secondary"
          sx={{ ml: 0.5 }}
        >
          {value.currency}
        </Typography>
      ) : null}
    </Box>
  );
}
