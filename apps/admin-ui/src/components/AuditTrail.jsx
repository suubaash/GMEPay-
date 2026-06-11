'use client';

import { useCallback, useEffect, useState } from 'react';
import {
  Box,
  Chip,
  Collapse,
  IconButton,
  Pagination,
  Stack,
  Tooltip,
  Typography,
} from '@mui/material';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import ExpandLessIcon from '@mui/icons-material/ExpandLess';
import VerifiedIcon from '@mui/icons-material/Verified';
import ErrorOutlineIcon from '@mui/icons-material/ErrorOutline';
import ErrorAlert from '@/components/ErrorAlert';
import LoadingSkeleton from '@/components/LoadingSkeleton';
import { useAppDispatch, useAppSelector } from '@/store';
import { fetchAuditTrail, trailKey } from '@/store/auditTrailSlice';

/**
 * AuditTrail — paginated timeline of audit entries for a single aggregate.
 *
 * Props:
 *   aggregateType  string  e.g. "partner"
 *   aggregateId    string  e.g. the partner code
 *   pageSize       number  default 20
 *
 * Each entry displays:
 *   recordedAt | actorId | eventType
 *   Expandable two-column before / after JSON <pre> pane.
 *
 * A chip at the top shows "Chain verified" (green) or "CHAIN BROKEN" (red)
 * sourced from the chainValid field returned by the backend (ADR-007).
 */

function formatDate(iso) {
  if (!iso) return '—';
  try {
    return new Date(iso).toLocaleString();
  } catch {
    return iso;
  }
}

function JsonPane({ label, json }) {
  if (!json) {
    return (
      <Box sx={{ flex: 1 }}>
        <Typography variant="caption" color="text.secondary">
          {label}
        </Typography>
        <Typography variant="body2" color="text.disabled" sx={{ fontStyle: 'italic' }}>
          (empty)
        </Typography>
      </Box>
    );
  }
  let pretty = json;
  try {
    pretty = JSON.stringify(JSON.parse(json), null, 2);
  } catch {
    /* leave as-is if not valid JSON */
  }
  return (
    <Box sx={{ flex: 1, overflow: 'hidden' }}>
      <Typography variant="caption" color="text.secondary">
        {label}
      </Typography>
      <Box
        component="pre"
        sx={{
          m: 0,
          p: 1,
          bgcolor: 'action.hover',
          borderRadius: 1,
          fontSize: '0.7rem',
          overflowX: 'auto',
          whiteSpace: 'pre-wrap',
          wordBreak: 'break-all',
          maxHeight: 300,
          overflowY: 'auto',
        }}
      >
        {pretty}
      </Box>
    </Box>
  );
}

function AuditEntry({ entry }) {
  const [expanded, setExpanded] = useState(false);
  const hasDiff = entry.beforeJson || entry.afterJson;

  return (
    <Box
      sx={{
        borderBottom: '1px solid',
        borderColor: 'divider',
        py: 1.5,
        px: 2,
      }}
    >
      <Stack direction="row" alignItems="center" spacing={1}>
        <Box sx={{ flexGrow: 1 }}>
          <Stack direction="row" spacing={2} flexWrap="wrap">
            <Typography variant="body2" color="text.secondary" sx={{ minWidth: 180 }}>
              {formatDate(entry.recordedAt)}
            </Typography>
            <Typography variant="body2" sx={{ minWidth: 140 }}>
              {entry.actorId ?? '—'}
            </Typography>
            <Chip
              size="small"
              label={entry.eventType ?? '—'}
              variant="outlined"
              sx={{ fontFamily: 'monospace', fontSize: '0.7rem' }}
            />
          </Stack>
        </Box>
        {hasDiff && (
          <Tooltip title={expanded ? 'Hide diff' : 'Show before / after'}>
            <IconButton
              size="small"
              onClick={() => setExpanded((v) => !v)}
              aria-label={expanded ? 'collapse diff' : 'expand diff'}
            >
              {expanded ? <ExpandLessIcon fontSize="small" /> : <ExpandMoreIcon fontSize="small" />}
            </IconButton>
          </Tooltip>
        )}
      </Stack>

      {hasDiff && (
        <Collapse in={expanded} unmountOnExit>
          <Stack direction="row" spacing={2} sx={{ mt: 1 }}>
            <JsonPane label="Before" json={entry.beforeJson} />
            <JsonPane label="After" json={entry.afterJson} />
          </Stack>
        </Collapse>
      )}
    </Box>
  );
}

export default function AuditTrail({ aggregateType, aggregateId, pageSize = 20 }) {
  const dispatch = useAppDispatch();
  const key = trailKey(aggregateType, aggregateId);
  const trail = useAppSelector((s) => s.auditTrail.byKey[key]);

  const { entries = [], chainValid = null, page = 0, total = 0, loading = false, error = null } =
    trail ?? {};

  const totalPages = Math.max(1, Math.ceil(total / pageSize));

  const load = useCallback(
    (p = 0) => {
      if (!aggregateType || !aggregateId) return;
      dispatch(fetchAuditTrail({ aggregateType, aggregateId, page: p, size: pageSize }));
    },
    [dispatch, aggregateType, aggregateId, pageSize],
  );

  useEffect(() => {
    load(0);
  }, [load]);

  const handlePageChange = (_event, newPage) => {
    load(newPage - 1); // MUI Pagination is 1-based; backend is 0-based
  };

  return (
    <Box>
      {/* Chain integrity chip */}
      <Stack direction="row" alignItems="center" spacing={1} sx={{ mb: 2 }}>
        <Typography variant="subtitle2">Audit Trail</Typography>
        {chainValid === true && (
          <Chip
            icon={<VerifiedIcon />}
            label="Chain verified"
            color="success"
            size="small"
            data-testid="chain-valid-chip"
          />
        )}
        {chainValid === false && (
          <Chip
            icon={<ErrorOutlineIcon />}
            label="CHAIN BROKEN"
            color="error"
            size="small"
            data-testid="chain-broken-chip"
          />
        )}
      </Stack>

      <ErrorAlert message={error} onRetry={() => load(page)} title="Could not load audit trail" />

      {loading && <LoadingSkeleton variant="table" rows={4} />}

      {!loading && !error && entries.length === 0 && (
        <Typography variant="body2" color="text.secondary" sx={{ py: 2 }}>
          No audit entries found.
        </Typography>
      )}

      {!loading && entries.length > 0 && (
        <Box
          sx={{
            border: '1px solid',
            borderColor: 'divider',
            borderRadius: 1,
            mb: 2,
          }}
        >
          {entries.map((entry, idx) => (
            <AuditEntry key={entry.recordedAt ?? idx} entry={entry} />
          ))}
        </Box>
      )}

      {total > pageSize && (
        <Stack alignItems="center">
          <Pagination
            count={totalPages}
            page={page + 1}
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
