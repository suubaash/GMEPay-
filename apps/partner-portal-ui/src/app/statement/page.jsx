'use client';
import * as React from 'react';
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography
} from '@mui/material';
import DownloadIcon from '@mui/icons-material/Download';
import VisibilityIcon from '@mui/icons-material/Visibility';
import { useDispatch, useSelector } from 'react-redux';
import { downloadStatementThunk } from '@/store/statementSlice';
import { currentPartnerId } from '@/api/client';
import * as apiClient from '@/api/client';
import DateRangePicker from '@/components/DateRangePicker';
import ErrorAlert from '@/components/ErrorAlert';
import LoadingSkeleton from '@/components/LoadingSkeleton';
import { useSnackbar } from '@/components/SnackbarProvider';

/**
 * Statement page.
 *
 * Wire shape — GET /v1/portal/{partnerId}/statement?from&to returns the
 * partner's transaction statement as CSV (text/csv; Content-Disposition:
 * attachment). The UI lets the operator pick a date range, preview the
 * first 5 rows, and download the full CSV to disk.
 *
 * Behaviour:
 *   - "Preview" fetches the CSV and parses the first 5 rows client-side.
 *     The Blob is NOT kept in Redux (Blob is non-serializable); only metadata
 *     (from, to, sizeBytes, downloadedAt) is recorded in the statement slice.
 *   - "Download" fetches the CSV and triggers a browser save using a hidden
 *     anchor + object URL named `statement-<partnerId>-<from>-<to>.csv`.
 *   - Both actions require `from` and `to` to be set; the buttons are
 *     disabled until both are populated.
 *
 * Defensive: this page never trusts the BFF blob to be non-empty — empty CSV
 * still renders an empty preview table with a friendly message.
 */

function todayISO() {
  const d = new Date();
  // YYYY-MM-DD (ISO date part) — Intl-safe across locales.
  return d.toISOString().slice(0, 10);
}

function thirtyDaysAgoISO() {
  const d = new Date();
  d.setDate(d.getDate() - 30);
  return d.toISOString().slice(0, 10);
}

/**
 * Minimal RFC-4180 CSV row parser — splits on commas with naive double-quote
 * escaping. Sufficient for previewing the BFF's statement CSV; full CSV
 * libraries are not allowed by the locked stack (no papaparse).
 */
function parseCsvPreview(text, maxRows = 6) {
  if (!text) return { header: [], rows: [] };
  const lines = String(text)
    .replace(/\r\n?/g, '\n')
    .split('\n')
    .filter((l) => l.length > 0)
    .slice(0, maxRows);

  function splitLine(line) {
    const out = [];
    let cur = '';
    let inQuotes = false;
    for (let i = 0; i < line.length; i += 1) {
      const ch = line[i];
      if (inQuotes) {
        if (ch === '"' && line[i + 1] === '"') {
          cur += '"';
          i += 1;
        } else if (ch === '"') {
          inQuotes = false;
        } else {
          cur += ch;
        }
      } else if (ch === '"') {
        inQuotes = true;
      } else if (ch === ',') {
        out.push(cur);
        cur = '';
      } else {
        cur += ch;
      }
    }
    out.push(cur);
    return out;
  }

  const header = splitLine(lines[0] ?? '');
  const rows = lines.slice(1).map(splitLine);
  return { header, rows };
}

function triggerBlobDownload(blob, filename) {
  if (typeof window === 'undefined' || typeof document === 'undefined') return;
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  a.style.display = 'none';
  document.body.appendChild(a);
  a.click();
  // Defer revoke + remove so the browser has time to start the download
  setTimeout(() => {
    URL.revokeObjectURL(url);
    if (a.parentNode) a.parentNode.removeChild(a);
  }, 0);
}

export default function StatementPage() {
  const dispatch = useDispatch();
  const snackbar = useSnackbar();
  const partnerId = currentPartnerId();
  const { status, error, lastDownload } = useSelector((s) => s.statement);

  const [range, setRange] = React.useState({
    from: thirtyDaysAgoISO(),
    to: todayISO()
  });
  const [preview, setPreview] = React.useState(null);
  const [previewLoading, setPreviewLoading] = React.useState(false);
  const [previewError, setPreviewError] = React.useState(null);

  const disabledRange = !range.from || !range.to || (!!range.from && !!range.to && range.from > range.to);

  if (!partnerId) {
    return (
      <Alert severity="warning">
        No partner id available. Sign in or set <code>NEXT_PUBLIC_PARTNER_ID</code>.
      </Alert>
    );
  }

  const filename = () => `statement-${partnerId}-${range.from}-${range.to}.csv`;

  const handleDownload = async () => {
    if (disabledRange) {
      snackbar.showError('Pick a valid From/To date range first.');
      return;
    }
    try {
      await dispatch(
        downloadStatementThunk({
          partnerId,
          from: range.from,
          to: range.to,
          onBlob: (blob) => {
            triggerBlobDownload(blob, filename());
          }
        })
      ).unwrap();
      snackbar.showSuccess(`Statement downloaded (${range.from} → ${range.to})`);
    } catch (e) {
      snackbar.showError(
        typeof e === 'string' ? e : (e && e.message) || 'Failed to download statement.'
      );
    }
  };

  const handlePreview = async () => {
    if (disabledRange) {
      snackbar.showError('Pick a valid From/To date range first.');
      return;
    }
    setPreviewLoading(true);
    setPreviewError(null);
    try {
      const blob = await apiClient.downloadStatement(range.from, range.to, partnerId);
      const text = await blob.text();
      setPreview(parseCsvPreview(text, 6));
    } catch (e) {
      const msg =
        typeof e === 'string' ? e : (e && e.message) || 'Failed to preview statement.';
      setPreviewError(msg);
    } finally {
      setPreviewLoading(false);
    }
  };

  return (
    <Stack spacing={3}>
      <Box>
        <Typography variant="h1">Statement</Typography>
        <Typography variant="body2" sx={{ color: 'text.secondary' }}>
          Download a CSV statement of your transactions for a given date range.
        </Typography>
      </Box>

      <Card>
        <CardContent>
          <Stack spacing={2}>
            <DateRangePicker
              from={range.from}
              to={range.to}
              max={todayISO()}
              onChange={(next) => setRange(next)}
            />
            <Stack direction="row" spacing={2} flexWrap="wrap">
              <Button
                variant="outlined"
                startIcon={<VisibilityIcon />}
                onClick={handlePreview}
                disabled={disabledRange || previewLoading}
                data-testid="preview-button"
              >
                Preview first 5 rows
              </Button>
              <Button
                variant="contained"
                startIcon={<DownloadIcon />}
                onClick={handleDownload}
                disabled={disabledRange || status === 'loading'}
                data-testid="download-button"
              >
                Download CSV
              </Button>
            </Stack>

            {disabledRange && (
              <Typography variant="caption" sx={{ color: 'warning.main' }} data-testid="range-required">
                Pick a valid From and To date (From must be on or before To).
              </Typography>
            )}

            {lastDownload && (
              <Typography variant="caption" sx={{ color: 'text.secondary' }} data-testid="last-download">
                Last download: {lastDownload.from} → {lastDownload.to} (
                {Number(lastDownload.sizeBytes ?? 0).toLocaleString()} bytes) at{' '}
                {new Date(lastDownload.downloadedAt).toLocaleString()}
              </Typography>
            )}
          </Stack>
        </CardContent>
      </Card>

      {status === 'failed' && (
        <ErrorAlert message={error ?? 'Failed to download statement.'} />
      )}

      <Card>
        <CardContent>
          <Typography variant="h3" sx={{ mb: 2 }}>
            Preview
          </Typography>
          {previewLoading && <LoadingSkeleton variant="table" rows={3} />}
          {previewError && (
            <ErrorAlert message={previewError} onRetry={handlePreview} />
          )}
          {!previewLoading && !previewError && !preview && (
            <Typography variant="body2" sx={{ color: 'text.secondary' }}>
              Click "Preview first 5 rows" to see a sample of the statement.
            </Typography>
          )}
          {!previewLoading && preview && (
            <TableContainer>
              <Table size="small" data-testid="preview-table">
                <TableHead>
                  <TableRow>
                    {(preview.header ?? []).map((h, i) => (
                      <TableCell key={`${h}-${i}`}>{h}</TableCell>
                    ))}
                  </TableRow>
                </TableHead>
                <TableBody>
                  {(preview.rows ?? []).length === 0 ? (
                    <TableRow>
                      <TableCell colSpan={Math.max(1, (preview.header ?? []).length)}>
                        <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                          No data rows in the selected range.
                        </Typography>
                      </TableCell>
                    </TableRow>
                  ) : (
                    (preview.rows ?? []).map((row, idx) => (
                      <TableRow key={`row-${idx}`}>
                        {row.map((cell, i) => (
                          <TableCell key={`cell-${idx}-${i}`}>{cell}</TableCell>
                        ))}
                      </TableRow>
                    ))
                  )}
                </TableBody>
              </Table>
            </TableContainer>
          )}
        </CardContent>
      </Card>
    </Stack>
  );
}
