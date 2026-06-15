'use client';

import {
  FormControl,
  InputLabel,
  MenuItem,
  Select,
  Stack,
  TextField,
} from '@mui/material';

/**
 * Report-type taxonomy.
 *
 * BOK (Bank of Korea) FX declaration reports:
 *   BOK_FX1014  — Monthly FX declaration (Form 1014), submitted to BOK by the 10th.
 *   BOK_FX1015  — Supplementary FX declaration (Form 1015), filed alongside 1014.
 *
 * Hometax (NTS) e-tax invoice:
 *   HOMETAX_ETAX — National Tax Service electronic tax invoice transmission.
 *
 * KoFIU (Korea Financial Intelligence Unit) STR / CTR:
 *   KOFIU_CTR    — Currency Transaction Report (₩10 M+ cash txns, daily).
 *   KOFIU_STR    — Suspicious Transaction Report (ad-hoc, within 30 days).
 *
 * ZeroPay settlement:
 *   ZEROPAY_SETTLEMENT — Daily ZeroPay net-settlement file (ZP00xx series).
 */
export const REPORT_TYPES = [
  { value: 'BOK_FX1014', label: 'BOK FX1014 — Monthly FX Declaration' },
  {
    value: 'BOK_FX1015',
    label: 'BOK FX1015 — Supplementary FX Declaration',
  },
  {
    value: 'HOMETAX_ETAX',
    label: 'Hometax e-Tax Invoice (NTS)',
  },
  { value: 'KOFIU_CTR', label: 'KoFIU CTR — Currency Transaction Report' },
  { value: 'KOFIU_STR', label: 'KoFIU STR — Suspicious Transaction Report' },
  {
    value: 'ZEROPAY_SETTLEMENT',
    label: 'ZeroPay Settlement (ZP00xx)',
  },
];

/**
 * Filter toolbar for the reports table.
 *
 * Props:
 *   type:       string   — selected report type ('') means "All"
 *   from:       string   — ISO date string
 *   to:         string   — ISO date string
 *   onChange:   (patch: { type?, from?, to? }) => void
 */
export default function ReportTypeFilter({ type, from, to, onChange }) {
  return (
    <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} sx={{ mb: 3 }}>
      <FormControl sx={{ minWidth: 280 }} size="small">
        <InputLabel id="report-type-label">Report type</InputLabel>
        <Select
          labelId="report-type-label"
          label="Report type"
          value={type}
          onChange={(e) => onChange({ type: e.target.value })}
          inputProps={{ 'data-testid': 'report-type-select' }}
        >
          <MenuItem value="">All types</MenuItem>
          {REPORT_TYPES.map((rt) => (
            <MenuItem key={rt.value} value={rt.value}>
              {rt.label}
            </MenuItem>
          ))}
        </Select>
      </FormControl>

      <TextField
        label="From"
        type="date"
        size="small"
        value={from}
        onChange={(e) => onChange({ from: e.target.value })}
        InputLabelProps={{ shrink: true }}
        inputProps={{ 'data-testid': 'report-from-input' }}
      />

      <TextField
        label="To"
        type="date"
        size="small"
        value={to}
        onChange={(e) => onChange({ to: e.target.value })}
        InputLabelProps={{ shrink: true }}
        inputProps={{ 'data-testid': 'report-to-input' }}
      />
    </Stack>
  );
}
