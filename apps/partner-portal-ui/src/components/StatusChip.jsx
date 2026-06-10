'use client';
import * as React from 'react';
import Chip from '@mui/material/Chip';

/**
 * Color-coded chip for the transaction "state" field.
 *
 * The BFF emits free-form state strings on TransactionSummary
 * (e.g. "COMMITTED", "FAILED", "PENDING", "SETTLED"). Unknown values fall
 * back to a neutral grey chip rather than crashing.
 *
 * @param {{ status?: string, size?: 'small'|'medium' }} props
 */
const STATUS_COLOR = {
  PENDING: 'warning',
  APPROVED: 'success',
  COMMITTED: 'success',
  FAILED: 'error',
  CANCELLED: 'default',
  REVERSED: 'default',
  SETTLED: 'primary',
  // API key statuses (see store/apiKeysSlice.js):
  ACTIVE: 'success',
  ROTATING: 'warning',
  REVOKED: 'error'
};

export default function StatusChip({ status, size = 'small' }) {
  const label = status ?? 'UNKNOWN';
  const color = STATUS_COLOR[String(label).toUpperCase()] ?? 'default';
  return <Chip label={label} color={color} size={size} variant="outlined" />;
}
