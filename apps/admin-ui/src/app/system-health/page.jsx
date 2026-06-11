'use client';

import { useCallback, useEffect } from 'react';
import {
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  Grid2 as Grid,
  Typography,
} from '@mui/material';
import RefreshIcon from '@mui/icons-material/Refresh';
import { useAppDispatch, useAppSelector } from '@/store';
import { fetchSystemHealth } from '@/store/systemHealthSlice';
import ErrorAlert from '@/components/ErrorAlert';
import LoadingSkeleton from '@/components/LoadingSkeleton';

/**
 * /system-health — Live BFF/service status.
 *
 * GET /v1/admin/system/health -> SystemHealth
 *   { checkedAt:ISO, services:[ServiceHealth] }
 *
 * ServiceHealth { name, status:"UP"|"DOWN"|"DEGRADED", lastSeenAt, uptimeSec }
 *
 * Polls every 30s (window.setInterval; interval is cleared on unmount).
 * Each service is rendered as a card with a colored status chip plus a
 * human-readable uptime breakdown (days / hours / minutes via
 * Intl.NumberFormat).
 */
const POLL_MS = 30_000;

const NUM_FORMAT = new Intl.NumberFormat(undefined);

function statusColor(status) {
  if (status === 'UP') return 'success';
  if (status === 'DEGRADED') return 'warning';
  if (status === 'DOWN') return 'error';
  return 'default';
}

/**
 * Format `uptimeSec` (non-negative number) as e.g. "3d 4h 12m". Falls back
 * to "—" for missing/invalid values.
 */
function formatUptime(uptimeSec) {
  if (uptimeSec === null || uptimeSec === undefined) return '—';
  const total = Number(uptimeSec);
  if (!Number.isFinite(total) || total < 0) return '—';
  const days = Math.floor(total / 86400);
  const hours = Math.floor((total % 86400) / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const parts = [];
  if (days > 0) parts.push(`${NUM_FORMAT.format(days)}d`);
  if (hours > 0 || days > 0) parts.push(`${hours}h`);
  parts.push(`${minutes}m`);
  return parts.join(' ');
}

function formatChecked(iso) {
  if (!iso) return '—';
  const ts = Date.parse(iso);
  if (Number.isNaN(ts)) return iso;
  try {
    return new Date(ts).toLocaleString();
  } catch {
    return iso;
  }
}

export default function SystemHealthPage() {
  const dispatch = useAppDispatch();
  const { services, checkedAt, loading, error } = useAppSelector(
    (s) => s.systemHealth,
  );

  const reload = useCallback(() => {
    dispatch(fetchSystemHealth());
  }, [dispatch]);

  // Initial fetch + 30s polling. Interval cleared on unmount.
  useEffect(() => {
    reload();
    if (typeof window === 'undefined') return undefined;
    const id = window.setInterval(reload, POLL_MS);
    return () => window.clearInterval(id);
  }, [reload]);

  const list = Array.isArray(services) ? services : [];

  return (
    <Box>
      <Box sx={{ display: 'flex', alignItems: 'center', mb: 2 }}>
        <Box sx={{ flexGrow: 1 }}>
          <Typography variant="h1">System Health</Typography>
          <Typography variant="body2" color="text.secondary">
            Last checked: {formatChecked(checkedAt)}
            {loading ? ' · refreshing…' : ''}
          </Typography>
        </Box>
        <Button
          variant="outlined"
          startIcon={<RefreshIcon />}
          onClick={reload}
          disabled={loading}
          aria-label="refresh system health"
        >
          Refresh
        </Button>
      </Box>

      <ErrorAlert
        message={error}
        onRetry={reload}
        title="Could not load system health"
      />

      {loading && list.length === 0 ? (
        <Grid container spacing={2}>
          {[0, 1, 2, 3].map((i) => (
            <Grid key={i} size={{ xs: 12, sm: 6, md: 4 }}>
              <LoadingSkeleton variant="card" />
            </Grid>
          ))}
        </Grid>
      ) : (
        <Grid container spacing={2}>
          {list.map((svc, idx) => (
            <Grid key={svc.name ?? idx} size={{ xs: 12, sm: 6, md: 4 }}>
              <Card>
                <CardContent>
                  <Box sx={{ display: 'flex', alignItems: 'center', mb: 1 }}>
                    <Typography
                      variant="h4"
                      sx={{ flexGrow: 1, wordBreak: 'break-all' }}
                    >
                      {svc.name ?? '—'}
                    </Typography>
                    <Chip
                      size="small"
                      label={svc.status ?? 'UNKNOWN'}
                      color={statusColor(svc.status)}
                    />
                  </Box>
                  <Typography variant="body2" color="text.secondary">
                    Uptime
                  </Typography>
                  <Typography sx={{ fontVariantNumeric: 'tabular-nums', mb: 1 }}>
                    {formatUptime(svc.uptimeSec)}
                  </Typography>
                  <Typography variant="body2" color="text.secondary">
                    Last seen
                  </Typography>
                  <Typography variant="body2">
                    {formatChecked(svc.lastSeenAt)}
                  </Typography>
                </CardContent>
              </Card>
            </Grid>
          ))}
          {list.length === 0 && !loading && !error ? (
            <Grid size={12}>
              <Card variant="outlined">
                <CardContent>
                  <Typography color="text.secondary">
                    No services reported.
                  </Typography>
                </CardContent>
              </Card>
            </Grid>
          ) : null}
        </Grid>
      )}
    </Box>
  );
}
