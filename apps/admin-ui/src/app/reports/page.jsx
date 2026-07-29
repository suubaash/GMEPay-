'use client';

import { useCallback, useEffect, useState } from 'react';
import {
  Alert,
  AlertTitle,
  Box,
  Button,
  CircularProgress,
  IconButton,
  Paper,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Tooltip,
  Typography,
} from '@mui/material';
import DownloadIcon from '@mui/icons-material/Download';
import PlayArrowIcon from '@mui/icons-material/PlayArrow';
import RefreshIcon from '@mui/icons-material/Refresh';
import { useAppDispatch, useAppSelector } from '@/store';
import {
  fetchReports,
  triggerGenerate,
  downloadReportRun,
  setFilters,
  clearGenerateError,
} from '@/store/reportsSlice';
import ErrorAlert from '@/components/ErrorAlert';
import LoadingSkeleton from '@/components/LoadingSkeleton';
import EmptyState from '@/components/EmptyState';
import ConfirmDialog from '@/components/ConfirmDialog';
import FilingChannelBoard from '@/components/FilingChannelBoard';
import { hasLocalArtifact, notFiledSummary } from '@/api/filingStatus';
import ReportTypeFilter, { REPORT_TYPES } from './ReportTypeFilter';
import ReportStatusChip from './ReportStatusChip';

/**
 * KST offset is UTC+9 (Korea Standard Time — no DST).
 * We format timestamps server-side as UTC ISO-8601 and convert in the browser
 * so the display always reflects the local regulatory timezone.
 */
function toKST(isoUtc) {
  if (!isoUtc) return '—';
  try {
    return new Intl.DateTimeFormat('ko-KR', {
      timeZone: 'Asia/Seoul',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      hour12: false,
    }).format(new Date(isoUtc));
  } catch {
    return isoUtc;
  }
}

/** Human-readable label for a REPORT_TYPES value. */
function labelFor(typeValue) {
  const found = REPORT_TYPES.find((rt) => rt.value === typeValue);
  return found ? found.label : typeValue;
}

export default function ReportsPage() {
  const dispatch = useAppDispatch();

  const { items, loading, generating, downloading, error, generateError, filters } =
    useAppSelector((s) => s.reports);

  // Confirm dialog state — holds the report type + period that the operator
  // wants to generate, or null when the dialog is closed.
  const [confirmTarget, setConfirmTarget] = useState(null);

  const reload = useCallback(() => {
    dispatch(fetchReports(filters));
  }, [dispatch, filters]);

  useEffect(() => {
    dispatch(fetchReports(filters));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dispatch]);

  const onFilterChange = useCallback(
    (patch) => {
      const next = { ...filters, ...patch };
      dispatch(setFilters(patch));
      dispatch(fetchReports(next));
    },
    [dispatch, filters],
  );

  const onGenerateClick = useCallback((run) => {
    setConfirmTarget(run);
  }, []);

  const onConfirmGenerate = useCallback(async () => {
    if (!confirmTarget) return;
    setConfirmTarget(null);
    await dispatch(
      triggerGenerate({ type: confirmTarget.type, period: confirmTarget.period }),
    );
    // Refresh the list so the new PENDING run appears.
    dispatch(fetchReports(filters));
  }, [confirmTarget, dispatch, filters]);

  const onCancelGenerate = useCallback(() => {
    setConfirmTarget(null);
    dispatch(clearGenerateError());
  }, [dispatch]);

  const onDownload = useCallback(
    (run) => {
      dispatch(
        downloadReportRun({
          id: run.id,
          filename: `${run.type}_${run.period}.zip`,
        }),
      );
    },
    [dispatch],
  );

  const rows = Array.isArray(items) ? items : [];

  // GAP T5-2: a "Status" column alone let an operator read a locally generated file as
  // a completed filing. State it once, from what the rows actually say — the banner
  // disappears the moment any run reports a real TRANSMITTED/ACKNOWLEDGED.
  const notFiled = notFiledSummary(rows);
  const channelRow = rows.find((r) => Array.isArray(r.filingChannels) && r.filingChannels.length);

  return (
    <Box>
      {/* Page header */}
      <Box sx={{ display: 'flex', alignItems: 'center', mb: 2 }}>
        <Typography variant="h1" sx={{ flexGrow: 1 }}>
          Reports
        </Typography>
        <Tooltip title="Refresh list">
          <span>
            <IconButton onClick={reload} disabled={loading} aria-label="Refresh reports">
              <RefreshIcon />
            </IconButton>
          </span>
        </Tooltip>
      </Box>

      {/* Filters */}
      <ReportTypeFilter
        type={filters.type}
        from={filters.from}
        to={filters.to}
        onChange={onFilterChange}
      />

      {/* Errors */}
      <ErrorAlert
        message={error}
        onRetry={reload}
        title="Could not load reports"
      />
      <ErrorAlert
        message={generateError}
        title="Could not generate report"
        severity="warning"
      />

      {/* Filing truth — no green "done" affordance for a lane that cannot transmit. */}
      {notFiled && (
        <Alert severity="warning" sx={{ mb: 2 }} data-testid="not-filed-banner">
          <AlertTitle>Nothing on this page has been filed</AlertTitle>
          {notFiled}
          {channelRow && (
            <Box sx={{ mt: 1 }}>
              <FilingChannelBoard
                channels={channelRow.filingChannels}
                reason={channelRow.filingChannelUnavailableReason}
                dense
              />
            </Box>
          )}
        </Alert>
      )}

      {/* Table */}
      {loading && rows.length === 0 ? (
        <LoadingSkeleton variant="table" rows={6} />
      ) : !loading && rows.length === 0 && !error ? (
        <Paper variant="outlined">
          <EmptyState
            heading="No report runs found"
            description="Adjust the filters above or trigger a new generation run."
          />
        </Paper>
      ) : (
        <TableContainer component={Paper}>
          <Table aria-label="Report runs">
            <TableHead>
              <TableRow>
                <TableCell>Type</TableCell>
                <TableCell>Period / Date</TableCell>
                <TableCell>Filing status</TableCell>
                <TableCell align="right">Records</TableCell>
                <TableCell>Generated at (KST)</TableCell>
                <TableCell align="center">Download</TableCell>
                <TableCell align="center">Generate now</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {rows.map((run) => (
                <ReportRow
                  key={run.id}
                  run={run}
                  labelFor={labelFor}
                  toKST={toKST}
                  isDownloading={!!downloading[run.id]}
                  isGenerating={generating}
                  onDownload={onDownload}
                  onGenerate={onGenerateClick}
                />
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}

      {/* Generate-now confirm dialog */}
      <ConfirmDialog
        open={!!confirmTarget}
        title="Generate report"
        message={
          confirmTarget
            ? `Trigger a new generation run for ${labelFor(confirmTarget.type)} — period "${confirmTarget.period}"? This may overwrite the previous run.`
            : ''
        }
        confirmLabel="Generate"
        confirmColor="primary"
        onConfirm={onConfirmGenerate}
        onCancel={onCancelGenerate}
      />
    </Box>
  );
}

/**
 * Single report-run row.  Extracted so the confirm-dialog interaction
 * stays in the parent without prop drilling the full dispatcher.
 */
function ReportRow({
  run,
  labelFor,
  toKST,
  isDownloading,
  isGenerating,
  onDownload,
  onGenerate,
}) {
  // A download needs a locally produced artifact — which is exactly what
  // GENERATED / VALIDATED / NOT_FILED_CHANNEL_UNAVAILABLE (and, one day,
  // TRANSMITTED / ACKNOWLEDGED) mean. The old rule keyed off `SUBMITTED`, a state
  // that can no longer be produced, so those rows would have lost their download.
  const canDownload = hasLocalArtifact(run.status) && !!run.downloadUrl;

  return (
    <TableRow hover>
      <TableCell>{labelFor(run.type)}</TableCell>
      <TableCell>{run.period ?? '—'}</TableCell>
      <TableCell>
        <ReportStatusChip
          status={run.status}
          reason={run.filingChannelUnavailableReason}
        />
      </TableCell>
      {/* recordCount is BigDecimal-as-string — render as-is, never Number()-cast */}
      <TableCell align="right">{run.recordCount ?? '—'}</TableCell>
      <TableCell>{toKST(run.generatedAt)}</TableCell>

      {/* Download */}
      <TableCell align="center">
        {canDownload ? (
          <Tooltip title="Download file">
            <span>
              <IconButton
                size="small"
                aria-label={`Download ${run.type} ${run.period}`}
                onClick={() => onDownload(run)}
                disabled={isDownloading}
              >
                {isDownloading ? (
                  <CircularProgress size={18} />
                ) : (
                  <DownloadIcon fontSize="small" />
                )}
              </IconButton>
            </span>
          </Tooltip>
        ) : (
          '—'
        )}
      </TableCell>

      {/* Generate now */}
      <TableCell align="center">
        <Tooltip title="Trigger a new generation run">
          <span>
            <Button
              size="small"
              variant="outlined"
              startIcon={<PlayArrowIcon />}
              aria-label={`Generate ${run.type} for ${run.period}`}
              onClick={() => onGenerate(run)}
              disabled={isGenerating}
            >
              Generate
            </Button>
          </span>
        </Tooltip>
      </TableCell>
    </TableRow>
  );
}
