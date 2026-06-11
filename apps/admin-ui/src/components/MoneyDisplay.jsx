'use client';

import { Box, Tooltip, Typography } from '@mui/material';

/**
 * MoneyDisplay — renders an amount + currency as `<formatted> <currency>`.
 *
 * Per docs/MONEY_CONVENTION.md, money is exchanged as a decimal STRING; we
 * never parse it to a JS number (precision loss on values like "10500.567"
 * or scale-0 KRW totals over Number.MAX_SAFE_INTEGER).
 *
 * Props:
 *   amount:     string  (preferred — decimal string from the BFF)
 *   currency:   string  (ISO-4217)
 *   value:      { amount, currency }  (legacy shape — still accepted)
 *   withCurrency: boolean (default true)
 *   negativeRed:  boolean (default true)
 */

const CURRENCY_SCALES = {
  KRW: 0,
  JPY: 0,
  VND: 0,
};

function scaleFor(currency) {
  return CURRENCY_SCALES[currency] ?? 2;
}

/**
 * Pad/round the decimal-string `amount` to exactly `scale` fractional digits
 * by string manipulation. No floating-point conversion.
 */
function formatToScale(amount, scale) {
  const negative = amount.startsWith('-');
  const abs = negative ? amount.slice(1) : amount;
  const dotIdx = abs.indexOf('.');
  const intPartRaw = dotIdx === -1 ? abs : abs.slice(0, dotIdx);
  const fracPartRaw = dotIdx === -1 ? '' : abs.slice(dotIdx + 1);
  const intPart = intPartRaw || '0';

  let frac = fracPartRaw;
  if (frac.length > scale) {
    frac = frac.slice(0, scale);
  } else if (frac.length < scale) {
    frac = frac.padEnd(scale, '0');
  }

  const intGrouped = intPart.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  const formatted = scale === 0 ? intGrouped : `${intGrouped}.${frac}`;
  return negative ? `-${formatted}` : formatted;
}

export default function MoneyDisplay(props) {
  // Accept either { amount, currency } directly OR a legacy { value: { amount, currency } } prop.
  const amountInput = props.amount ?? props.value?.amount;
  const currency = props.currency ?? props.value?.currency ?? '';
  const withCurrency = props.withCurrency ?? true;
  const negativeRed = props.negativeRed ?? true;

  // Defensive: a missing amount renders as "—" so a partial payload never throws.
  if (amountInput === undefined || amountInput === null || amountInput === '') {
    return (
      <Box component="span" sx={{ color: 'text.secondary' }}>
        —
      </Box>
    );
  }

  // Coerce to string so number payloads (todayRevenueUsd) still render.
  const amount = String(amountInput);
  const scale = scaleFor(currency);
  let formatted;
  try {
    formatted = formatToScale(amount, scale);
  } catch {
    formatted = amount;
  }
  const isNegative = amount.startsWith('-');
  const color = isNegative && negativeRed ? 'error.main' : 'inherit';
  const tooltipRaw = currency ? `${amount} ${currency}` : amount;

  return (
    <Tooltip title={tooltipRaw} arrow enterDelay={400}>
      <Box component="span" sx={{ fontVariantNumeric: 'tabular-nums', color }}>
        <Typography component="span" sx={{ fontWeight: 600, color: 'inherit' }}>
          {formatted}
        </Typography>
        {withCurrency && currency ? (
          <Typography
            component="span"
            variant="body2"
            color="text.secondary"
            sx={{ ml: 0.5 }}
          >
            {currency}
          </Typography>
        ) : null}
      </Box>
    </Tooltip>
  );
}
