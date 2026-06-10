'use client';

import { FormControl, InputLabel, MenuItem, Select, FormHelperText } from '@mui/material';
import type { SelectChangeEvent } from '@mui/material';
import { forwardRef } from 'react';
import { ROUNDING_MODES, type RoundingMode } from '@/api/types';

export interface RoundingModeSelectProps {
  value: RoundingMode;
  onChange: (next: RoundingMode) => void;
  label?: string;
  helperText?: string;
  error?: boolean;
  disabled?: boolean;
  name?: string;
  id?: string;
}

/**
 * Reusable MUI Select bound to the 7 java.math.RoundingMode values supported
 * for per-partner settlement booking (see docs/MONEY_CONVENTION.md).
 *
 * Default for new partners is HALF_UP — same as the engine default. Caller
 * controls the value; this component is presentation only.
 *
 * `forwardRef` so it composes cleanly with react-hook-form's Controller.
 */
const RoundingModeSelect = forwardRef<HTMLInputElement, RoundingModeSelectProps>(
  function RoundingModeSelect(
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
    const handleChange = (e: SelectChangeEvent<RoundingMode>) => {
      onChange(e.target.value as RoundingMode);
    };

    const labelId = `${id}-label`;
    return (
      <FormControl fullWidth error={error} disabled={disabled}>
        <InputLabel id={labelId}>{label}</InputLabel>
        <Select<RoundingMode>
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
  },
);

export default RoundingModeSelect;
