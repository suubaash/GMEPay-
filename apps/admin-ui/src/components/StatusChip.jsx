'use client';

import { Chip } from '@mui/material';

/**
 * StatusChip — maps a transaction state (CREATED..SETTLED) or a generic status
 * string to a MUI Chip color.
 *
 * Backed by BFF TransactionSummary.state (not `status` — that's a settlement
 * field; the field-name drift was the bug that this whole reconciliation is
 * fixing). Falls back to "default" for unknown values so a new state code
 * never crashes the row.
 *
 * Props:
 *   status:  string  (defensive: accepted as either prop name)
 *   state:   string
 */
function colorFor(value) {
  switch (value) {
    case 'CREATED':
      return 'default';
    case 'QUOTED':
      return 'info';
    case 'APPROVED':
    case 'SETTLED':
      return 'success';
    case 'FAILED':
      return 'error';
    case 'CANCELLED':
      return 'warning';
    default:
      return 'default';
  }
}

export default function StatusChip(props) {
  const value = props.state ?? props.status ?? '';
  if (!value) {
    return <Chip size="small" label="—" color="default" />;
  }
  return <Chip size="small" label={value} color={colorFor(value)} />;
}
