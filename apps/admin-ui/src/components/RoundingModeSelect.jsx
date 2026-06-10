'use client';

import { FormControl, InputLabel, MenuItem, Select, FormHelperText } from '@mui/material';
import { forwardRef } from 'react';
import { ROUNDING_MODES } from '@/api/constants';

/**
 * Reusable MUI Select bound to the 7 java.math.RoundingMode values supported
 * for per-partner settlement booking (see docs/MONEY_CONVENTION.md).
 *
 * Props:
 *   value:       string  — one of ROUNDING_MODES
 *   onChange:    (next:string) => void
 *   label:       string  (default "Settlement rounding mode")
 *   helperText:  string
 *   error:       boolean
 *   disabled:    boolean
 *   name:        string
 *   id:          string  (default "rounding-mode-select")
 *
 * `forwardRef` so it composes cleanly with react-hook-form's Controller.
 */
const RoundingModeSelect = forwardRef(function RoundingModeSelect(
  {
    value,
    onChange,
    label = 'Settlement rounding mode',
    helperText,
    error,
    disabled,
    name,
    id = 'rounding-mode-select',
  },
  ref,
) {
  const handleChange = (e) => {
    onChange(e.target.value);
  };
  const labelId = `${id}-label`;
  return (
    <FormControl fullWidth error={!!error} disabled={!!disabled}>
      <InputLabel id={labelId}>{label}</InputLabel>
      <Select
        labelId={labelId}
        id={id}
        name={name}
        label={label}
        value={value}
        onChange={handleChange}
        inputRef={ref}
      >
        {ROUNDING_MODES.map((mode) => (
          <MenuItem key={mode} value={mode}>
            {mode}
          </MenuItem>
        ))}
      </Select>
      {helperText ? <FormHelperText>{helperText}</FormHelperText> : null}
    </FormControl>
  );
});

export default RoundingModeSelect;
