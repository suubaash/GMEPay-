'use client';

import { TextField } from '@mui/material';

/**
 * Shared, ALWAYS-BOUNDED date input.
 *
 * A native `<input type="date">` with no `min`/`max` happily accepts absurd
 * values — 6-digit years (e.g. `188888`), `0001-01-01`, `9999-12-31` — which
 * then flow downstream (filters, and in several forms straight to the system of
 * record). This wrapper guarantees a sane window even when the caller passes
 * nothing: `min` defaults to {@link DATE_FLOOR} and `max` to 10 years out.
 *
 * Callers pass tighter bounds where the domain needs them:
 *   • past-only filters:  `max={todayISO()}`
 *   • range pairs:        From `max={to}` and To `min={from}` to enforce from ≤ to
 *   • future windows:     `max={yearsFromTodayISO(n)}`
 *
 * Otherwise it behaves exactly like a MUI `<TextField>`: `onChange` receives the
 * native change event, so it drops into controlled `value`/`onChange` call sites
 * unchanged. `inputProps` (e.g. `aria-label`) and `InputLabelProps` are merged,
 * not replaced.
 */

/** Lower bound applied to every date field unless the caller overrides `min`. */
export const DATE_FLOOR = '2000-01-01';

/** Today as `YYYY-MM-DD` (local). */
export function todayISO() {
  return new Date().toISOString().slice(0, 10);
}

/** `YYYY-MM-DD` for `years` from today (negative for the past). */
export function yearsFromTodayISO(years) {
  const d = new Date();
  d.setFullYear(d.getFullYear() + years);
  return d.toISOString().slice(0, 10);
}

export default function DateField({
  value,
  min,
  max,
  inputProps,
  InputLabelProps,
  ...rest
}) {
  return (
    <TextField
      type="date"
      value={value ?? ''}
      InputLabelProps={{ shrink: true, ...InputLabelProps }}
      inputProps={{
        min: min ?? DATE_FLOOR,
        max: max ?? yearsFromTodayISO(10),
        ...inputProps,
      }}
      {...rest}
    />
  );
}
