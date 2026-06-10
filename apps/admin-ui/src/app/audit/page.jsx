'use client';

import { useCallback, useEffect, useState } from 'react';
import {
  Box,
  Chip,
  FormControl,
  InputLabel,
  MenuItem,
  Pagination,
  Paper,
  Select,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material';
import { useAppDispatch, useAppSelector } from '@/store';
import { fetchAuditPage } from '@/store/auditSlice';
import ErrorAlert from '@/components/ErrorAlert';
import EmptyState from '@/components/EmptyState';
import LoadingSkeleton from '@/components/LoadingSkeleton';

/**
 * /audit — Audit log.
 *
 * GET /v1/admin/audit?page=&size= -> Page<AuditEntry>
 *   AuditEntry { id, actor, action, target, at:ISO, detail }
 *
 * Page-size selector (10/25/50). Page nav via MUI Pagination component
 * (the BFF Page<T> uses 0-based page indices; MUI Pagination is 1-based,
 * so we convert at the boundary).
 */
const PAGE_SIZES = [10, 25, 50];

const DATE_FORMAT = new Intl.DateTimeFormat(undefined, {
  year: 'numeric',
  month: 'short',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
});

function formatWhen(iso) {
  if (!iso) return '—';
  const ts = Date.parse(iso);
  if (Number.isNaN(ts)) return iso;
  try {
    return DATE_FORMAT.format(new Date(ts));
  } catch {
    return iso;
  }
}

/** Color-code an action verb for the chip. Falls back to "default" so new
 * action codes never crash the row. */
function actionColor(action) {
  const a = (action ?? '').toLowerCase();
  if (a.includes('create')) return 'success';
  if (a.includes('update') || a.includes('edit') || a.includes('modify')) return 'info';
  if (a.includes('suspend') || a.includes('disable') || a.includes('lock')) return 'warning';
  if (a.includes('delete') || a.includes('remove')) return 'error';
  return 'default';
}

export default function AuditLogPage() {
  const dispatch = useAppDispatch();
  const { items, loading, error, page, size, total } = useAppSelector((s) => s.audit);
  const [pageSize, setPageSize] = useState(20);

  const load = useCallback(
    (p, s) => {
      dispatch(fetchAuditPage({ page: p, size: s }));
    },
    [dispatch],
  );

  useEffect(() => {
    load(0, pageSize);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const onSizeChange = (e) => {
    const next = Number.parseInt(e.target.value, 10) || 20;
    setPageSize(next);
    load(0, next);
  };

  // MUI Pagination is 1-based; BFF is 0-based. Convert on the boundary.
  const muiPage = (page ?? 0) + 1;
  const pageCount = Math.max(1, Math.ceil((total ?? 0) / (size || pageSize || 20)));
  const onPageChange = (_e, nextOneBased) => {
    load(nextOneBased - 1, size || pageSize);
  };

  const rows = Array.isArray(items) ? items : [];
  const isEmpty = !loading && rows.length === 0;

  return (
    <Box>
      <Typography variant="h1" gutterBottom>
        Audit Log
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        Every operator action against config-registry is recorded here.
      </Typography>

      <ErrorAlert
        message={error}
        onRetry={() => load(page ?? 0, size || pageSize)}
        title="Could not load audit log"
      />

      <Stack
        direction="row"
        spacing={2}
        alignItems="center"
        sx={{ mb: 2 }}
      >
        <FormControl size="small" sx={{ minWidth: 120 }}>
          <InputLabel id="audit-size-label">Page size</InputLabel>
          <Select
            labelId="audit-size-label"
            label="Page size"
            value={size || pageSize}
            onChange={onSizeChange}
            inputProps={{ 'aria-label': 'Page size' }}
          >
            {PAGE_SIZES.map((n) => (
              <MenuItem key={n} value={n}>
                {n}
              </MenuItem>
            ))}
          </Select>
        </FormControl>
        <Typography variant="body2" color="text.secondary">
          {total ?? 0} entries
        </Typography>
      </Stack>

      {loading && rows.length === 0 ? (
        <LoadingSkeleton variant="table" rows={8} />
      ) : isEmpty && !error ? (
        <Paper variant="outlined">
          <EmptyState
            heading="No audit entries yet"
            description="Operator actions will appear here as they happen."
          />
        </Paper>
      ) : (
        <>
          <TableContainer component={Paper}>
            <Table>
              <TableHead>
                <TableRow>
                  <TableCell>When</TableCell>
                  <TableCell>Actor</TableCell>
                  <TableCell>Action</TableCell>
                  <TableCell>Target</TableCell>
                  <TableCell>Detail</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {rows.map((row, idx) => (
                  <TableRow key={row.id ?? `${row.at ?? ''}-${idx}`} hover>
                    <TableCell>{formatWhen(row.at)}</TableCell>
                    <TableCell>{row.actor ?? '—'}</TableCell>
                    <TableCell>
                      <Chip
                        size="small"
                        label={row.action ?? '—'}
                        color={actionColor(row.action)}
                      />
                    </TableCell>
                    <TableCell>{row.target ?? '—'}</TableCell>
                    <TableCell>
                      <Typography
                        variant="body2"
                        sx={{
                          wordBreak: 'break-word',
                          maxWidth: 480,
                          color: 'text.secondary',
                        }}
                      >
                        {row.detail ?? '—'}
                      </Typography>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
          <Box sx={{ display: 'flex', justifyContent: 'flex-end', mt: 2 }}>
            <Pagination
              count={pageCount}
              page={muiPage}
              onChange={onPageChange}
              showFirstButton
              showLastButton
              color="primary"
            />
          </Box>
        </>
      )}
    </Box>
  );
}
