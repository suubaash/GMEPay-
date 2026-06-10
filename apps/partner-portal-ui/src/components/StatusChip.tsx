'use client';
import * as React from 'react';
import Chip from '@mui/material/Chip';
import type { TransactionStatus } from '@/api/types';

type ChipColor = 'default' | 'primary' | 'success' | 'warning' | 'error' | 'info';

const STATUS_COLOR: Record<TransactionStatus, ChipColor> = {
  PENDING: 'warning',
  APPROVED: 'success',
  FAILED: 'error',
  CANCELLED: 'default',
  REVERSED: 'default',
  SETTLED: 'primary'
};

export interface StatusChipProps {
  status: TransactionStatus | string;
  size?: 'small' | 'medium';
}

export default function StatusChip({ status, size = 'small' }: StatusChipProps) {
  const color = (STATUS_COLOR as Record<string, ChipColor>)[status] ?? 'default';
  return <Chip label={status} color={color} size={size} variant="outlined" />;
}
