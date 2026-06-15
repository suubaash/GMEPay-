'use client';

import { Chip } from '@mui/material';

/**
 * Maps report run status to a MUI Chip color.
 *
 *   PENDING   — info (blue)   — job queued / in-progress
 *   GENERATED — success       — file ready for download
 *   SUBMITTED — success       — file delivered to regulator
 *   FAILED    — error         — generation or submission failure
 */
function colorFor(status) {
  switch (status) {
    case 'PENDING':
      return 'info';
    case 'GENERATED':
      return 'success';
    case 'SUBMITTED':
      return 'success';
    case 'FAILED':
      return 'error';
    default:
      return 'default';
  }
}

export default function ReportStatusChip({ status }) {
  if (!status) return <Chip size="small" label="—" color="default" />;
  return <Chip size="small" label={status} color={colorFor(status)} />;
}
