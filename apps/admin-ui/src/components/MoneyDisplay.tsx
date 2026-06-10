'use client';

import { Box, Tooltip, Typography } from '@mui/material';
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
 *
 * Negative amounts (e.g. rounding loss, refunds) are formatted with a leading
 * minus sign on the integer portion ("-$5.00") and rendered in the MUI error
 * color so they stand out in finance tables. A tooltip shows the raw amount
 * string as received from the BFF — useful for ops debugging when a settlement
 * residual contains more decimals than the display scale.
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
  /** Whether to color negative amounts red. Default true. */
  negativeRed?: boolean;
}

export default function MoneyDisplay({
  value,
  withCurrency = true,
  negativeRed = true,
}: MoneyDisplayProps) {
  const scale = scaleFor(value.currency);
  const formatted = formatToScale(value.amount, scale);
  const isNegative = value.amount.startsWith('-');
  const color = isNegative && negativeRed ? 'error.main' : 'inherit';
  const tooltipRaw = `${value.amount} ${value.currency}`;

  return (
    <Tooltip title={tooltipRaw} arrow enterDelay={400}>
      <Box component="span" sx={{ fontVariantNumeric: 'tabular-nums', color }}>
        <Typography component="span" sx={{ fontWeight: 600, color: 'inherit' }}>
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
    </Tooltip>
  );
}
