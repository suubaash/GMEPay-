'use client';

import { useEffect, useRef, useState } from 'react';
import {
  Box,
  Button,
  Chip,
  CircularProgress,
  Paper,
  Stack,
  Typography,
} from '@mui/material';
import EastIcon from '@mui/icons-material/East';
import ReplayIcon from '@mui/icons-material/Replay';
import { adminApi } from '@/api/client';

/**
 * Service-trace flows. Each is a real admin action: `run()` fires the actual BFF
 * call (so hop 1 is live-measured), and `hops` is the documented downstream
 * cascade through the microservices (server-side hops aren't visible to the
 * browser — they're captured for real by the trace-console tap proxies, and
 * shown here as the architecture). Sourced from the ops-partner-bff controllers.
 */
const FLOWS = [
  {
    key: 'txn-search',
    label: 'Transaction search',
    run: () => adminApi.searchTransactions({ page: 0, size: 5 }),
    hops: [
      { from: 'admin-ui', to: 'ops-partner-bff', label: 'GET /v1/admin/transactions' },
      { from: 'ops-partner-bff', to: 'transaction-mgmt', label: 'GET /v1/transactions' },
    ],
  },
  {
    key: 'dashboard',
    label: 'Dashboard',
    run: () => adminApi.fetchDashboard(),
    hops: [
      { from: 'admin-ui', to: 'ops-partner-bff', label: 'GET /v1/admin/dashboard' },
      { from: 'ops-partner-bff', to: 'config-registry', label: 'GET /v1/partners (count)' },
      { from: 'ops-partner-bff', to: 'transaction-mgmt', label: 'GET /v1/transactions (recent)' },
      { from: 'ops-partner-bff', to: 'prefunding', label: 'GET balances (low-balance)' },
      { from: 'ops-partner-bff', to: 'revenue-ledger', label: 'GET /v1/revenue (today)' },
    ],
  },
  {
    key: 'partners',
    label: 'Partner list',
    run: () => adminApi.listPartners(),
    hops: [
      { from: 'admin-ui', to: 'ops-partner-bff', label: 'GET /v1/admin/partners' },
      { from: 'ops-partner-bff', to: 'config-registry', label: 'GET /v1/partners' },
    ],
  },
  {
    key: 'schemes',
    label: 'Scheme catalog',
    run: () => adminApi.listSchemes(),
    hops: [
      { from: 'admin-ui', to: 'ops-partner-bff', label: 'GET /v1/admin/schemes' },
      { from: 'ops-partner-bff', to: 'config-registry', label: 'GET /v1/schemes' },
    ],
  },
  {
    key: 'settlement-exceptions',
    label: 'Settlement exceptions',
    run: () => adminApi.listReconExceptions({}),
    hops: [
      { from: 'admin-ui', to: 'ops-partner-bff', label: 'GET /v1/settlement/exceptions' },
      { from: 'ops-partner-bff', to: 'settlement-reconciliation', label: 'GET /v1/settlement/exceptions' },
    ],
  },
  {
    key: 'revenue',
    label: 'Revenue summary',
    run: () => adminApi.getRevenueSummary({}),
    hops: [
      { from: 'admin-ui', to: 'ops-partner-bff', label: 'GET /v1/admin/revenue/summary' },
      { from: 'ops-partner-bff', to: 'revenue-ledger', label: 'GET /v1/revenue/summary' },
    ],
  },
];

const STEP_MS = 650;

function participantsOf(flow) {
  const seen = [];
  for (const h of flow.hops) {
    for (const s of [h.from, h.to]) if (!seen.includes(s)) seen.push(s);
  }
  return seen;
}

function now() {
  return typeof performance !== 'undefined' ? performance.now() : Date.now();
}

function ParticipantBox({ name, active }) {
  return (
    <Paper
      variant="outlined"
      sx={{
        px: 1.5,
        py: 1,
        minWidth: 124,
        textAlign: 'center',
        transition: 'all .25s ease',
        borderColor: active ? 'primary.main' : 'divider',
        bgcolor: active ? 'action.hover' : 'background.paper',
        boxShadow: active ? 6 : 0,
        transform: active ? 'translateY(-2px)' : 'none',
      }}
    >
      <Typography variant="caption" color="text.secondary" sx={{ letterSpacing: 1 }}>
        MSA
      </Typography>
      <Typography variant="body2" sx={{ fontFamily: 'monospace', fontWeight: 600 }}>
        {name}
      </Typography>
    </Paper>
  );
}

export default function ServiceTrace() {
  const [selected, setSelected] = useState(FLOWS[0].key);
  const [step, setStep] = useState(0); // number of hops revealed (0..hops.length)
  const [running, setRunning] = useState(false);
  const [hop0, setHop0] = useState(null); // { ok, status, ms, error }
  const timers = useRef([]);

  const flow = FLOWS.find((f) => f.key === selected) ?? FLOWS[0];
  const participants = participantsOf(flow);
  const activeStepHop = step > 0 && step <= flow.hops.length ? flow.hops[step - 1] : null;
  const activeServices = activeStepHop ? [activeStepHop.from, activeStepHop.to] : [];

  function clearTimers() {
    timers.current.forEach(clearTimeout);
    timers.current = [];
  }
  useEffect(
    () => () => {
      timers.current.forEach(clearTimeout);
      timers.current = [];
    },
    [],
  );

  function run(f) {
    clearTimers();
    setSelected(f.key);
    setStep(0);
    setHop0(null);
    setRunning(true);

    const t0 = now();
    Promise.resolve()
      .then(() => f.run())
      .then(() => setHop0({ ok: true, status: 200, ms: Math.round(now() - t0) }))
      .catch((e) =>
        setHop0({ ok: false, status: e?.status ?? 0, ms: Math.round(now() - t0), error: e?.message }),
      );

    for (let i = 1; i <= f.hops.length; i++) {
      timers.current.push(setTimeout(() => setStep(i), i * STEP_MS));
    }
    timers.current.push(setTimeout(() => setRunning(false), (f.hops.length + 1) * STEP_MS));
  }

  return (
    <Box sx={{ py: 2, '@keyframes traceIn': { from: { opacity: 0, transform: 'translateY(8px)' }, to: { opacity: 1, transform: 'none' } } }}>
      <Typography variant="h6" gutterBottom>
        Service Trace
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        Pick an admin action — it fires the real call and draws the microservice cascade
        step by step: which service receives the request, then which services it calls.
      </Typography>

      {/* Action picker */}
      <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap sx={{ mb: 3 }}>
        {FLOWS.map((f) => (
          <Button
            key={f.key}
            size="small"
            variant={f.key === selected ? 'contained' : 'outlined'}
            onClick={() => run(f)}
            startIcon={running && f.key === selected ? <CircularProgress size={14} color="inherit" /> : null}
          >
            {f.label}
          </Button>
        ))}
        <Button
          size="small"
          variant="text"
          startIcon={<ReplayIcon fontSize="small" />}
          onClick={() => run(flow)}
          disabled={running}
        >
          Replay
        </Button>
      </Stack>

      {/* Participants (the MSAs involved in this flow) */}
      <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap alignItems="center" sx={{ mb: 3 }}>
        {participants.map((p, i) => (
          <Box key={p} sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
            <ParticipantBox name={p} active={activeServices.includes(p)} />
            {i < participants.length - 1 && <EastIcon fontSize="small" color="disabled" />}
          </Box>
        ))}
      </Stack>

      {/* Sequence — revealed one hop at a time */}
      <Stack spacing={1}>
        {flow.hops.slice(0, step).map((h, idx) => {
          const isHop0 = idx === 0;
          const isActive = idx === step - 1 && running;
          return (
            <Paper
              key={`${flow.key}-${idx}`}
              variant="outlined"
              sx={{
                p: 1,
                display: 'flex',
                alignItems: 'center',
                gap: 1,
                animation: 'traceIn .35s ease',
                borderColor: isActive ? 'primary.main' : 'divider',
                boxShadow: isActive ? 3 : 0,
              }}
            >
              <Chip size="small" label={idx + 1} sx={{ height: 22 }} />
              <Chip size="small" variant="outlined" label={h.from} sx={{ fontFamily: 'monospace', height: 22 }} />
              <EastIcon fontSize="small" color="action" />
              <Chip size="small" variant="outlined" label={h.to} sx={{ fontFamily: 'monospace', height: 22 }} />
              <Typography variant="caption" sx={{ flexGrow: 1, fontFamily: 'monospace', color: 'text.secondary' }}>
                {h.label}
              </Typography>
              {isHop0 ? (
                hop0 ? (
                  <Chip
                    size="small"
                    color={hop0.ok ? 'success' : 'error'}
                    label={`${hop0.ok ? '200' : hop0.status || 'ERR'} · ${hop0.ms}ms`}
                    sx={{ height: 22 }}
                  />
                ) : (
                  <CircularProgress size={14} thickness={6} />
                )
              ) : (
                <Chip size="small" variant="outlined" color="default" label="routed" sx={{ height: 22 }} />
              )}
            </Paper>
          );
        })}
        {step === 0 && (
          <Typography variant="caption" color="text.disabled">
            Select an action above to trace its cascade.
          </Typography>
        )}
      </Stack>

      <Typography variant="caption" color="text.disabled" sx={{ display: 'block', mt: 3 }}>
        Hop&nbsp;1 (admin-ui → ops-partner-bff) is live-measured against the running BFF.
        Downstream hops show the documented service routing — the real server-side calls are
        captured by the trace-console tap proxies (http://localhost:7099).
      </Typography>
    </Box>
  );
}
