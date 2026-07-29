'use client';

import { useCallback, useEffect } from 'react';
import {
  Alert,
  AlertTitle,
  Box,
  Button,
  Chip,
  Drawer,
  FormControl,
  IconButton,
  InputLabel,
  MenuItem,
  Paper,
  Select,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TablePagination,
  TableRow,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material';
import CloseIcon from '@mui/icons-material/Close';
import DateField, { DATE_FLOOR, todayISO } from '@/components/DateField';
import RefreshIcon from '@mui/icons-material/Refresh';
import { useAppDispatch, useAppSelector } from '@/store';
import {
  fetchComplianceOverview,
  fetchFilingChannels,
  fetchRegulatoryConfig,
  fetchPartnerKyb,
  fetchAuditLog,
  selectPartner,
  clearSelection,
  setKybFilter,
  setSanctionsFilter,
  setLifecycleFilter,
  setAuditAggregate,
  setAuditFrom,
  setAuditTo,
  setAuditPage,
  clearOverviewError,
  clearAuditError,
} from '@/store/complianceSlice';
import ErrorAlert from '@/components/ErrorAlert';
import FilingChannelBoard from '@/components/FilingChannelBoard';
import LoadingSkeleton from '@/components/LoadingSkeleton';
import EmptyState from '@/components/EmptyState';
import StatusChip from '@/components/StatusChip';
import DrillDownPanel from './DrillDownPanel';

// KST offset = UTC+9
const KST_OFFSET_MS = 9 * 60 * 60 * 1000;

/**
 * Format an ISO-8601 UTC timestamp as KST "YYYY-MM-DD HH:mm:ss KST".
 * Returns '—' for falsy input.
 * @param {string|null|undefined} iso
 * @returns {string}
 */
export function toKst(iso) {
  if (!iso) return '—';
  try {
    const ms = Date.parse(iso);
    if (Number.isNaN(ms)) return iso;
    const kstMs = ms + KST_OFFSET_MS;
    const d = new Date(kstMs);
    const pad = (n) => String(n).padStart(2, '0');
    return (
      `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())} ` +
      `${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}:${pad(d.getUTCSeconds())} KST`
    );
  } catch {
    return iso;
  }
}

/** Map KYB/sanctions/lifecycle status strings to MUI Chip colors. */
function kybColor(status) {
  switch (status) {
    case 'APPROVED': return 'success';
    case 'PENDING': return 'warning';
    case 'REVIEW': return 'info';
    case 'HIT': return 'error';
    default: return 'default';
  }
}

function sanctionsColor(status) {
  switch (status) {
    case 'CLEAR': return 'success';
    case 'NEEDS_REVIEW': return 'warning';
    case 'HIT': return 'error';
    default: return 'default';
  }
}

function lifecycleColor(status) {
  switch (status) {
    case 'LIVE': return 'success';
    case 'SUSPENDED': return 'error';
    case 'ONBOARDING': return 'info';
    case 'TERMINATED': return 'default';
    default: return 'default';
  }
}

/**
 * Per-lane CONFIG badge (GAP T5-2).
 *
 * This was `SetBadge`: a green "Set" tick on `bokSet`/`hometaxSet`/`kofiuSet`. Green
 * plus a lane name reads as "this lane is live", when the flag only ever meant "somebody
 * typed a value into the per-partner regulatory config" — and, until the BFF fix, was
 * even true for the shipped placeholders `stub-cert-id` and `TODO_OI03`.
 *
 * So: no success colour here. "Configured" is a neutral fact; whether the lane can file
 * is the separate channel board above the table, and whether anything WAS filed is a
 * third fact (nothing has been). The boolean is rendered exactly as the BFF reports it —
 * nothing is re-derived from cert ids or codes in the UI.
 */
function ConfigBadge({ set, lane }) {
  const label = set ? 'Configured' : 'Not configured';
  const tip = set
    ? `${lane}: per-partner config entered. This does NOT mean the lane can file — see the `
      + 'filing channel status above; no filing has taken place.'
    : `${lane}: no per-partner config entered (placeholder values such as stub-cert-id or `
      + 'TODO_OI03 do not count as configured).';
  return (
    <Tooltip title={tip}>
      <Chip size="small" variant="outlined" color={set ? 'default' : 'warning'} label={label} />
    </Tooltip>
  );
}

export default function CompliancePage() {
  const dispatch = useAppDispatch();

  const {
    overview,
    overviewLoading,
    overviewError,
    filingChannels,
    filingChannelReason,
    filingChannelsError,
    selectedPartnerCode,
    kybFilter,
    sanctionsFilter,
    lifecycleFilter,
    auditPage,
    auditMeta,
    auditChainValid,
    auditLoading,
    auditError,
    auditAggregate,
    auditFrom,
    auditTo,
    auditCurrentPage,
    auditPageSize,
  } = useAppSelector((s) => s.compliance);

  // ---- initial loads ----
  const loadOverview = useCallback(() => {
    dispatch(fetchComplianceOverview());
    // The readiness board is only honest alongside the filing-channel truth.
    dispatch(fetchFilingChannels());
  }, [dispatch]);

  const loadAudit = useCallback(() => {
    dispatch(
      fetchAuditLog({
        aggregate: auditAggregate || undefined,
        from: auditFrom || undefined,
        to: auditTo || undefined,
        page: auditCurrentPage,
        size: auditPageSize,
      }),
    );
  }, [dispatch, auditAggregate, auditFrom, auditTo, auditCurrentPage, auditPageSize]);

  useEffect(() => {
    loadOverview();
  }, [loadOverview]);

  useEffect(() => {
    loadAudit();
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [auditAggregate, auditFrom, auditTo, auditCurrentPage, auditPageSize]);

  // ---- drill-down ----
  const openDrillDown = useCallback(
    (code) => {
      dispatch(selectPartner(code));
      dispatch(fetchRegulatoryConfig(code));
      dispatch(fetchPartnerKyb(code));
      dispatch(
        fetchAuditLog({
          aggregate: code,
          page: 0,
          size: auditPageSize,
        }),
      );
    },
    [dispatch, auditPageSize],
  );

  const closeDrillDown = useCallback(() => {
    dispatch(clearSelection());
  }, [dispatch]);

  // ---- filtered rows ----
  // Only the backend can say a lane is live; an absent board stays "unknown".
  const anyChannelLive =
    Array.isArray(filingChannels) && filingChannels.some((c) => c.channelLive === true);

  const rows = Array.isArray(overview) ? overview : [];
  const filteredRows = rows.filter((r) => {
    if (kybFilter !== 'ALL' && r.kybStatus !== kybFilter) return false;
    if (sanctionsFilter !== 'ALL' && r.sanctionsResult !== sanctionsFilter) return false;
    if (lifecycleFilter !== 'ALL' && r.lifecycleStatus !== lifecycleFilter) return false;
    return true;
  });

  return (
    <Box>
      <Box sx={{ display: 'flex', alignItems: 'center', mb: 2 }}>
        <Typography variant="h1" sx={{ flexGrow: 1 }}>
          Compliance
        </Typography>
        <Tooltip title="Refresh overview">
          <IconButton onClick={loadOverview} aria-label="Refresh compliance overview">
            <RefreshIcon />
          </IconButton>
        </Tooltip>
      </Box>

      <Stack spacing={4}>
        {/* ---- Overview table ---- */}
        <Box>
          <Typography variant="h2" sx={{ mb: 1 }}>
            Partner compliance overview
          </Typography>

          {/*
            GAP T5-2: the table below reports CONFIG only. State the other two facts —
            can a lane transmit, and has anything been filed — before an operator or
            auditor reads a row of "Configured" as a live regulatory lane.
          */}
          <Alert
            severity={anyChannelLive ? 'info' : 'warning'}
            sx={{ mb: 2 }}
            data-testid="filing-channel-status"
          >
            <AlertTitle>
              {anyChannelLive
                ? 'Regulatory filing channels'
                : 'No regulatory filing channel is live — nothing has been filed'}
            </AlertTitle>
            <Typography variant="body2" sx={{ mb: 1 }}>
              Three separate facts: a lane can be <strong>configured</strong> (a value entered
              per partner, shown in the table), it can have a <strong>filing channel
              available</strong> (a real transmission path, shown here), and a report can have
              been <strong>filed</strong> (an authority received it — see the Reports page).
              Configuration alone files nothing.
            </Typography>
            <FilingChannelBoard channels={filingChannels} reason={filingChannelReason} />
            {filingChannelsError && (
              <Typography variant="caption" color="text.secondary" component="p" sx={{ mt: 0.75 }}>
                Filing channel status could not be loaded ({filingChannelsError}) — treat it as
                unknown, not as available.
              </Typography>
            )}
          </Alert>

          {/* Filters */}
          <Stack direction="row" spacing={2} sx={{ mb: 2 }} flexWrap="wrap">
            <FormControl size="small" sx={{ minWidth: 150 }}>
              <InputLabel id="kyb-filter-label">KYB status</InputLabel>
              <Select
                labelId="kyb-filter-label"
                label="KYB status"
                value={kybFilter}
                onChange={(e) => dispatch(setKybFilter(e.target.value))}
                inputProps={{ 'aria-label': 'Filter by KYB status' }}
              >
                <MenuItem value="ALL">All</MenuItem>
                <MenuItem value="APPROVED">Approved</MenuItem>
                <MenuItem value="PENDING">Pending</MenuItem>
                <MenuItem value="REVIEW">Review</MenuItem>
                <MenuItem value="HIT">Hit</MenuItem>
              </Select>
            </FormControl>

            <FormControl size="small" sx={{ minWidth: 180 }}>
              <InputLabel id="sanctions-filter-label">Sanctions result</InputLabel>
              <Select
                labelId="sanctions-filter-label"
                label="Sanctions result"
                value={sanctionsFilter}
                onChange={(e) => dispatch(setSanctionsFilter(e.target.value))}
                inputProps={{ 'aria-label': 'Filter by sanctions result' }}
              >
                <MenuItem value="ALL">All</MenuItem>
                <MenuItem value="CLEAR">Clear</MenuItem>
                <MenuItem value="NEEDS_REVIEW">Needs review</MenuItem>
                <MenuItem value="HIT">Hit</MenuItem>
              </Select>
            </FormControl>

            <FormControl size="small" sx={{ minWidth: 170 }}>
              <InputLabel id="lifecycle-filter-label">Lifecycle status</InputLabel>
              <Select
                labelId="lifecycle-filter-label"
                label="Lifecycle status"
                value={lifecycleFilter}
                onChange={(e) => dispatch(setLifecycleFilter(e.target.value))}
                inputProps={{ 'aria-label': 'Filter by lifecycle status' }}
              >
                <MenuItem value="ALL">All</MenuItem>
                <MenuItem value="LIVE">Live</MenuItem>
                <MenuItem value="SUSPENDED">Suspended</MenuItem>
                <MenuItem value="ONBOARDING">Onboarding</MenuItem>
                <MenuItem value="TERMINATED">Terminated</MenuItem>
              </Select>
            </FormControl>
          </Stack>

          <ErrorAlert
            message={overviewError}
            onRetry={loadOverview}
            title="Could not load compliance overview"
          />

          {overviewLoading && filteredRows.length === 0 ? (
            <LoadingSkeleton variant="table" rows={5} />
          ) : !overviewLoading && filteredRows.length === 0 && !overviewError ? (
            <Paper variant="outlined">
              <EmptyState
                heading="No partners match the current filters"
                description="Adjust the KYB, sanctions, or lifecycle status filters above."
              />
            </Paper>
          ) : (
            <TableContainer component={Paper}>
              <Table aria-label="Partner compliance overview">
                <TableHead>
                  <TableRow>
                    <TableCell>Partner code</TableCell>
                    <TableCell>Name</TableCell>
                    <TableCell>KYB status</TableCell>
                    <TableCell>Sanctions</TableCell>
                    {/* Config-only columns — filing capability is the board above. */}
                    <TableCell>BOK config</TableCell>
                    <TableCell>Hometax config</TableCell>
                    <TableCell>KoFIU config</TableCell>
                    <TableCell>Travel Rule config</TableCell>
                    <TableCell>Lifecycle</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {filteredRows.map((row) => (
                    <TableRow
                      key={row.partnerCode}
                      hover
                      sx={{ cursor: 'pointer' }}
                      onClick={() => openDrillDown(row.partnerCode)}
                    >
                      <TableCell>
                        <Button
                          variant="text"
                          size="small"
                          onClick={(e) => {
                            e.stopPropagation();
                            openDrillDown(row.partnerCode);
                          }}
                          aria-label={`Open drill-down for ${row.partnerCode}`}
                        >
                          {row.partnerCode}
                        </Button>
                      </TableCell>
                      <TableCell>{row.partnerName ?? '—'}</TableCell>
                      <TableCell>
                        <Chip
                          size="small"
                          label={row.kybStatus ?? '—'}
                          color={kybColor(row.kybStatus)}
                        />
                      </TableCell>
                      <TableCell>
                        <Chip
                          size="small"
                          label={row.sanctionsResult ?? '—'}
                          color={sanctionsColor(row.sanctionsResult)}
                        />
                      </TableCell>
                      <TableCell>
                        <ConfigBadge set={row.regulatoryConfig?.bokSet} lane="BOK" />
                      </TableCell>
                      <TableCell>
                        <ConfigBadge set={row.regulatoryConfig?.hometaxSet} lane="Hometax" />
                      </TableCell>
                      <TableCell>
                        <ConfigBadge set={row.regulatoryConfig?.kofiuSet} lane="KoFIU" />
                      </TableCell>
                      <TableCell>
                        <ConfigBadge set={row.regulatoryConfig?.travelRuleSet} lane="Travel Rule" />
                      </TableCell>
                      <TableCell>
                        <Chip
                          size="small"
                          label={row.lifecycleStatus ?? '—'}
                          color={lifecycleColor(row.lifecycleStatus)}
                        />
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          )}
        </Box>

        {/* ---- Global Audit Log viewer ---- */}
        <Box>
          <Typography variant="h2" sx={{ mb: 1 }}>
            Audit log
          </Typography>

          {/* Audit filters */}
          <Stack direction="row" spacing={2} sx={{ mb: 2 }} flexWrap="wrap" alignItems="center">
            <TextField
              size="small"
              label="Partner / aggregate"
              value={auditAggregate}
              onChange={(e) => dispatch(setAuditAggregate(e.target.value))}
              placeholder="e.g. GME_KR_001"
              sx={{ minWidth: 200 }}
              inputProps={{ 'aria-label': 'Filter audit log by aggregate' }}
            />
            <DateField
              size="small"
              label="From"
              value={auditFrom}
              onChange={(e) => dispatch(setAuditFrom(e.target.value))}
              min={DATE_FLOOR}
              max={auditTo || todayISO()}
              sx={{ minWidth: 160 }}
              inputProps={{ 'aria-label': 'Filter audit log from date' }}
            />
            <DateField
              size="small"
              label="To"
              value={auditTo}
              onChange={(e) => dispatch(setAuditTo(e.target.value))}
              min={auditFrom || DATE_FLOOR}
              max={todayISO()}
              sx={{ minWidth: 160 }}
              inputProps={{ 'aria-label': 'Filter audit log to date' }}
            />
            <Button
              variant="outlined"
              size="small"
              startIcon={<RefreshIcon />}
              onClick={loadAudit}
            >
              Refresh
            </Button>
            {auditPage.length > 0 && (
              <Chip
                size="small"
                color={auditChainValid ? 'success' : 'error'}
                label={auditChainValid ? 'Hash chain verified' : 'Hash chain BROKEN'}
                aria-label="audit-chain-integrity"
              />
            )}
          </Stack>

          <ErrorAlert
            message={auditError}
            onRetry={loadAudit}
            title="Could not load audit log"
          />

          {auditLoading && auditPage.length === 0 ? (
            <LoadingSkeleton variant="table" rows={5} />
          ) : !auditLoading && auditPage.length === 0 && !auditError ? (
            <Paper variant="outlined">
              <EmptyState
                heading="No audit entries found"
                description="Try adjusting the aggregate or date filters."
              />
            </Paper>
          ) : (
            <Paper>
              <TableContainer>
                <Table aria-label="Audit log">
                  <TableHead>
                    <TableRow>
                      <TableCell>Event</TableCell>
                      <TableCell>Aggregate</TableCell>
                      <TableCell>Actor</TableCell>
                      <TableCell>Timestamp (KST)</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {auditPage.map((entry) => (
                      <TableRow key={entry.id} hover>
                        <TableCell>{entry.event ?? '—'}</TableCell>
                        <TableCell>{entry.aggregate ?? '—'}</TableCell>
                        <TableCell>{entry.actor ?? '—'}</TableCell>
                        <TableCell>{toKst(entry.at)}</TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </TableContainer>
              <TablePagination
                component="div"
                count={auditMeta.total}
                page={auditCurrentPage}
                rowsPerPage={auditPageSize}
                rowsPerPageOptions={[10, 20, 50]}
                onPageChange={(_, newPage) => dispatch(setAuditPage(newPage))}
                onRowsPerPageChange={() => {
                  // rowsPerPage change is not wired to keep the slice lean;
                  // operators can use the aggregate filter to narrow results.
                }}
                labelDisplayedRows={({ from, to, count }) =>
                  `${from}–${to} of ${count !== -1 ? count : `more than ${to}`}`
                }
              />
            </Paper>
          )}
        </Box>
      </Stack>

      {/* ---- Drill-down side panel ---- */}
      <Drawer
        anchor="right"
        open={!!selectedPartnerCode}
        onClose={closeDrillDown}
        PaperProps={{ sx: { width: { xs: '100%', md: 600 }, p: 3 } }}
        aria-label="Partner compliance drill-down panel"
      >
        <Box sx={{ display: 'flex', alignItems: 'center', mb: 2 }}>
          <Typography variant="h2" sx={{ flexGrow: 1 }}>
            {selectedPartnerCode}
          </Typography>
          <IconButton onClick={closeDrillDown} aria-label="Close drill-down panel">
            <CloseIcon />
          </IconButton>
        </Box>
        {selectedPartnerCode && (
          <DrillDownPanel partnerCode={selectedPartnerCode} />
        )}
      </Drawer>
    </Box>
  );
}
