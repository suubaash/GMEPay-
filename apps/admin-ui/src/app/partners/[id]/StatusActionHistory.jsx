'use client';

import { useCallback, useEffect, useState } from 'react';
import {
  Box,
  Chip,
  Pagination,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material';
import ErrorAlert from '@/components/ErrorAlert';
import LoadingSkeleton from '@/components/LoadingSkeleton';
import { useAppDispatch, useAppSelector } from '@/store';
import { fetchAuditTrail, trailKey } from '@/store/auditTrailSlice';

/**
 * StatusActionHistory — table of past PARTNER_LIFECYCLE_* transitions from the
 * audit trail.
 *
 * Columns: timestamp, action (eventType), operator (actorId), reason (extracted
 * from afterJson).
 *
 * Filters audit entries by eventType prefix 'PARTNER_LIFECYCLE_' so non-lifecycle
 * events are hidden.
 *
 * Props:
 *   partnerCode:  string
 *   pageSize:     number (default 20)
 */

function formatDate(iso) {
  if (!iso) return '—';
  try {
    return new Date(iso).toLocaleString();
  } catch {
    return iso;
  }
}

function extractReason(afterJson) {
  if (!afterJson) return '—';
  try {
    const obj = JSON.parse(afterJson);
    return obj.reason ?? obj.terminationReason ?? '—';
  } catch {
    return '—';
  }
}

function actionColor(eventType) {
  if (!eventType) return 'default';
  if (eventType.includes('SUSPEND')) return 'warning';
  if (eventType.includes('TERMINATE')) return 'error';
  if (eventType.includes('REACTIVATE') || eventType.includes('ACTIVATE')) return 'success';
  return 'info';
}

export default function StatusActionHistory({ partnerCode, pageSize = 20 }) {
  const dispatch = useAppDispatch();
  const [page, setPage] = useState(0);

  const key = trailKey('partner', partnerCode);
  const trail = useAppSelector((s) => s.auditTrail.byKey[key]);

  const {
    entries = [],
    total = 0,
    page: trailPage = 0,
    loading = false,
    error = null,
  } = trail ?? {};

  // Filter to lifecycle events only
  const lifecycleEntries = entries.filter(
    (e) => e.eventType && e.eventType.startsWith('PARTNER_LIFECYCLE_'),
  );

  const totalPages = Math.max(1, Math.ceil(total / pageSize));

  const load = useCallback(
    (p = 0) => {
      if (!partnerCode) return;
      dispatch(
        fetchAuditTrail({
          aggregateType: 'partner',
          aggregateId: partnerCode,
          page: p,
          size: pageSize,
        }),
      );
    },
    [dispatch, partnerCode, pageSize],
  );

  useEffect(() => {
    load(0);
  }, [load]);

  const handlePageChange = (_e, newPage) => {
    setPage(newPage - 1);
    load(newPage - 1);
  };

  return (
    <Box data-testid="status-action-history">
      <ErrorAlert
        message={error}
        onRetry={() => load(page)}
        title="Could not load lifecycle history"
      />

      {loading && <LoadingSkeleton variant="table" rows={3} />}

      {!loading && !error && lifecycleEntries.length === 0 && (
        <Typography variant="body2" color="text.secondary" sx={{ py: 2 }}>
          No lifecycle transitions recorded yet.
        </Typography>
      )}

      {!loading && lifecycleEntries.length > 0 && (
        <Table size="small" aria-label="Lifecycle history">
          <TableHead>
            <TableRow>
              <TableCell>Timestamp</TableCell>
              <TableCell>Action</TableCell>
              <TableCell>Operator</TableCell>
              <TableCell>Reason</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {lifecycleEntries.map((entry, idx) => (
              <TableRow key={entry.recordedAt ?? idx} data-testid="lifecycle-history-row">
                <TableCell sx={{ whiteSpace: 'nowrap' }}>
                  {formatDate(entry.recordedAt)}
                </TableCell>
                <TableCell>
                  <Chip
                    size="small"
                    label={entry.eventType ?? '—'}
                    color={actionColor(entry.eventType)}
                    sx={{ fontFamily: 'monospace', fontSize: '0.7rem' }}
                  />
                </TableCell>
                <TableCell>{entry.actorId ?? '—'}</TableCell>
                <TableCell>{extractReason(entry.afterJson)}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}

      {total > pageSize && (
        <Stack alignItems="center" sx={{ mt: 1 }}>
          <Pagination
            count={totalPages}
            page={trailPage + 1}
            onChange={handlePageChange}
            color="primary"
            size="small"
            disabled={loading}
          />
        </Stack>
      )}
    </Box>
  );
}
