'use client';
import * as React from 'react';
import Box from '@mui/material/Box';
import Tooltip from '@mui/material/Tooltip';

/**
 * Money rendering primitive.
 *
 * Accepts EITHER:
 *   <MoneyDisplay amount="10.20" currency="USD" />
 *   <MoneyDisplay money={{ amount: "10.20", currency: "USD" }} />
 *
 * The BFF returns money as a BigDecimal-decimal-string + ISO-4217 currency
 * (docs/MONEY_CONVENTION.md). Some payloads keep them on a single object
 * (PartnerOverview's `balance`: { balance, lowBalanceThreshold, currency } —
 * the BalanceView shape), so callers usually pass `amount` + `currency`
 * directly and read sibling fields off the parent DTO.
 *
 * Currency scale matches lib-money/CurrencyScale:
 *   KRW / JPY / VND => 0 decimals
 *   default         => 2 decimals
 *
 * @typedef {object} MoneyDisplayProps
 * @property {string} [amount]                - Decimal string in major units (preferred).
 * @property {string} [currency]              - ISO-4217 code.
 * @property {{ amount:string, currency:string }} [money] - Legacy combined-object form.
 * @property {boolean} [parenthesizeNegative] - Render -123.45 as (123.45). Default false.
 * @property {boolean} [showCurrency]         - Show ISO-4217 code after amount. Default true.
 * @property {boolean} [showRawTooltip]       - Wrap in tooltip revealing the unrounded amount.
 * @property {string}  [ariaLabel]            - Override the aria-label.
 * @property {string}  [className]
 */

const ZERO_DECIMAL_CURRENCIES = new Set(['KRW', 'JPY', 'VND']);

export function currencyScale(currency) {
  if (!currency) return 2;
  return ZERO_DECIMAL_CURRENCIES.has(String(currency).toUpperCase()) ? 0 : 2;
}

/**
 * Format a decimal string in major units to a localized display. Defensive:
 * a missing/non-finite amount renders as "0" rather than crashing.
 */
export function formatMoney(amountOrMoney, parenthesizeNegative = false, currencyArg) {
  // Support both (money) and (amount, paren, currency) call styles.
  let amount;
  let currency;
  if (amountOrMoney && typeof amountOrMoney === 'object') {
    amount = amountOrMoney.amount;
    currency = amountOrMoney.currency;
  } else {
    amount = amountOrMoney;
    currency = currencyArg;
  }

  const scale = currencyScale(currency);
  const raw = String(amount ?? '').trim();
  const isNegative = raw.startsWith('-');
  const absStr = isNegative ? raw.slice(1) : raw;

  const n = Number(absStr);
  let formatted;
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

export default function MoneyDisplay(props) {
  const {
    money,
    amount: amountProp,
    currency: currencyProp,
    parenthesizeNegative = false,
    showCurrency = true,
    ariaLabel,
    className,
    showRawTooltip = false
  } = props;

  const amount = money ? money.amount : amountProp;
  const currency = money ? money.currency : currencyProp;

  const text = formatMoney(amount, parenthesizeNegative, currency);
  const isNegative = String(amount ?? '').trim().startsWith('-');
  const label = ariaLabel ?? `${text} ${currency ?? ''}`.trim();

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
      {showCurrency && currency ? (
        <>
          {' '}
          <Box
            component="span"
            data-testid="money-currency"
            sx={{ color: 'text.secondary', fontWeight: 500 }}
          >
            {currency}
          </Box>
        </>
      ) : null}
    </Box>
  );

  if (showRawTooltip) {
    const raw = `${amount ?? ''} ${currency ?? ''}`.trim();
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
