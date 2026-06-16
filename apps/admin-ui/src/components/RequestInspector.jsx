'use client';

import { useState, useSyncExternalStore } from 'react';
import {
  Box,
  Chip,
  CircularProgress,
  Collapse,
  IconButton,
  Paper,
  Tooltip,
  Typography,
} from '@mui/material';
import ExpandLessIcon from '@mui/icons-material/ExpandLess';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import DeleteSweepIcon from '@mui/icons-material/DeleteSweep';
import { subscribe, getSnapshot, getServerSnapshot, clearRequests } from '@/api/requestLog';
import { useCanInspect } from '@/hooks/useCanInspect';

const preSx = {
  m: 0,
  mt: 0.5,
  p: 1,
  bgcolor: 'action.hover',
  borderRadius: 1,
  fontSize: 11,
  lineHeight: 1.45,
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  maxHeight: 200,
  overflow: 'auto',
  whiteSpace: 'pre-wrap',
  wordBreak: 'break-word',
};

function pretty(v) {
  if (v == null || v === '') return '';
  if (typeof v === 'string') {
    const t = v.trim();
    if (t.startsWith('{') || t.startsWith('[')) {
      try {
        return JSON.stringify(JSON.parse(t), null, 2);
      } catch {
        return v;
      }
    }
    return v;
  }
  try {
    return JSON.stringify(v, null, 2);
  } catch {
    return String(v);
  }
}

function statusColor(s) {
  if (s == null || s === 0) return 'error';
  if (s >= 500) return 'error';
  if (s >= 400) return 'warning';
  if (s >= 200 && s < 300) return 'success';
  return 'default';
}

function shortPath(url) {
  try {
    return String(url).replace(/^.*?\/api(?=\/)/, '/api').replace(/^https?:\/\/[^/]+/, '');
  } catch {
    return String(url);
  }
}

function Row({ e }) {
  const [open, setOpen] = useState(false);
  return (
    <Box sx={{ borderTop: '1px solid', borderColor: 'divider', py: 0.5 }}>
      <Box
        sx={{ display: 'flex', alignItems: 'center', gap: 1, cursor: 'pointer' }}
        onClick={() => setOpen((o) => !o)}
      >
        <Chip
          label={e.method}
          size="small"
          variant="outlined"
          sx={{ height: 20, fontFamily: 'monospace', '& .MuiChip-label': { px: 0.75 } }}
        />
        <Typography
          variant="caption"
          sx={{
            flexGrow: 1,
            fontFamily: 'monospace',
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            whiteSpace: 'nowrap',
          }}
          title={e.url}
        >
          {shortPath(e.url)}
        </Typography>
        {e.inFlight ? (
          <CircularProgress size={14} thickness={6} />
        ) : (
          <Chip label={e.status ?? 'ERR'} size="small" color={statusColor(e.status)} sx={{ height: 20 }} />
        )}
        {e.durationMs != null && (
          <Typography variant="caption" color="text.secondary" sx={{ minWidth: 42, textAlign: 'right' }}>
            {e.durationMs}ms
          </Typography>
        )}
      </Box>
      <Collapse in={open} unmountOnExit>
        {e.reqBody ? (
          <>
            <Typography variant="caption" color="text.secondary">request body</Typography>
            <Box component="pre" sx={preSx}>{pretty(e.reqBody)}</Box>
          </>
        ) : null}
        {e.error || e.resBody !== undefined ? (
          <>
            <Typography variant="caption" color={e.error ? 'error' : 'text.secondary'}>
              {e.error ? 'error' : 'response'}
            </Typography>
            <Box component="pre" sx={preSx}>
              {e.error
                ? e.error + (e.resBody ? `\n${pretty(e.resBody)}` : '')
                : pretty(e.resBody)}
            </Box>
          </>
        ) : null}
      </Collapse>
    </Box>
  );
}

/**
 * RequestInspector — a role-gated floating overlay that surfaces the live
 * request/response for every BFF call as it happens, in place of an opaque
 * loading spinner. Only operators with the `inspector.view` permission (ADMIN
 * by default) see it; everyone else sees the normal per-page loading UI.
 *
 * It auto-appears and force-expands while any request is in flight (the
 * "instead of the revolving logo" moment), then shows the response. Mounted
 * once in {@link ./AppShell.jsx} so it works across every page.
 */
export default function RequestInspector() {
  const canInspect = useCanInspect();
  const entries = useSyncExternalStore(subscribe, getSnapshot, getServerSnapshot);
  const [collapsed, setCollapsed] = useState(false);

  if (!canInspect || entries.length === 0) return null;

  const anyInFlight = entries.some((e) => e.inFlight);
  const expanded = anyInFlight || !collapsed; // force-open during activity
  const newestFirst = [...entries].reverse();

  return (
    <Paper
      elevation={8}
      aria-label="request-inspector"
      sx={{
        position: 'fixed',
        bottom: 16,
        right: 16,
        width: 440,
        maxWidth: 'calc(100vw - 32px)',
        zIndex: (t) => t.zIndex.drawer + 2,
        borderRadius: 2,
        overflow: 'hidden',
        border: '1px solid',
        borderColor: 'divider',
      }}
    >
      <Box
        sx={{
          display: 'flex',
          alignItems: 'center',
          gap: 1,
          px: 1.5,
          py: 0.75,
          bgcolor: 'background.default',
          borderBottom: expanded ? '1px solid' : 'none',
          borderColor: 'divider',
        }}
      >
        {anyInFlight ? (
          <CircularProgress size={16} thickness={6} />
        ) : (
          <Box component="span" aria-hidden sx={{ fontSize: 14, lineHeight: 1 }}>🔍</Box>
        )}
        <Typography variant="subtitle2" sx={{ flexGrow: 1 }}>
          Request Inspector
        </Typography>
        <Typography variant="caption" color="text.secondary">{entries.length}</Typography>
        <Tooltip title="Clear">
          <IconButton size="small" onClick={clearRequests} aria-label="clear-inspector">
            <DeleteSweepIcon fontSize="small" />
          </IconButton>
        </Tooltip>
        <IconButton
          size="small"
          onClick={() => setCollapsed((c) => !c)}
          aria-label={expanded ? 'collapse-inspector' : 'expand-inspector'}
        >
          {expanded ? <ExpandMoreIcon fontSize="small" /> : <ExpandLessIcon fontSize="small" />}
        </IconButton>
      </Box>
      <Collapse in={expanded}>
        <Box sx={{ maxHeight: '50vh', overflow: 'auto', px: 1.5, pb: 1 }}>
          {newestFirst.map((e) => (
            <Row key={e.id} e={e} />
          ))}
        </Box>
      </Collapse>
    </Paper>
  );
}
