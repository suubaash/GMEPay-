'use client';

import { Chip } from '@mui/material';
import type { TxnStatus } from '@/api/types';

/** Maps a transaction status to a MUI Chip color. */
function colorFor(status: TxnStatus): 'default' | 'info' | 'success' | 'error' | 'warning' {
  switch (status) {
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

export default function StatusChip({ status }: { status: TxnStatus }) {
  return <Chip size="small" label={status} color={colorFor(status)} />;
}
