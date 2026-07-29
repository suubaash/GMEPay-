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
  // API key statuses — the real api_keys.status roster from auth-identity (V002):
  // ACTIVE | PENDING_EXPIRY | REVOKED. (gap T1-3: the portal previously showed
  // fabricated PRIMARY/ROTATING labels invented by the BFF stub.)
  ACTIVE: 'success',
  PENDING_EXPIRY: 'warning',
  REVOKED: 'error',
  // Webhook endpoint states from notification-webhook.
  INACTIVE: 'default'
};

export default function StatusChip({ status, size = 'small' }) {
  const label = status ?? 'UNKNOWN';
  const color = STATUS_COLOR[String(label).toUpperCase()] ?? 'default';
  return <Chip label={label} color={color} size={size} variant="outlined" />;
}
