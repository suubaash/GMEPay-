'use client';

import {
  Box,
  Chip,
  Divider,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material';
import { useAppSelector } from '@/store';
import ErrorAlert from '@/components/ErrorAlert';
import LoadingSkeleton from '@/components/LoadingSkeleton';
import { toKst } from './page';

/**
 * DrillDownPanel — rendered inside the compliance page's right-hand Drawer.
 *
 * Shows three sections for a single partner:
 *   1. KYB documents + screening detail
 *   2. Regulatory configuration (BOK / Hometax / KoFIU / Travel Rule)
 *   3. Recent audit log entries (first page fetched by the parent on open)
 *
 * Props:
 *   partnerCode: string
 */
export default function DrillDownPanel({ partnerCode }) {
  const {
    regulatory,
    regulatoryLoading,
    regulatoryError,
    kyb,
    kybLoading,
    kybError,
    auditPage,
    auditLoading,
    auditError,
  } = useAppSelector((s) => s.compliance);

  return (
    <Stack spacing={3}>
      {/* ---- KYB section ---- */}
      <Box>
        <Typography variant="h3" sx={{ mb: 1 }}>
          KYB / Screening
        </Typography>
        <ErrorAlert message={kybError} title="Could not load KYB data" />
        {kybLoading ? (
          <LoadingSkeleton variant="card" />
        ) : kyb ? (
          <Paper variant="outlined" sx={{ p: 2 }}>
            <Stack spacing={1}>
              <LabelValue label="Risk rating" value={kyb.riskRating} />
              <LabelValue label="Risk rationale" value={kyb.riskRationale} />
              <LabelValue label="License type" value={kyb.licenseType} />
              <LabelValue label="License number" value={kyb.licenseNumber} />
              <LabelValue label="License authority" value={kyb.licenseAuthority} />
              <LabelValue label="License expiry" value={kyb.licenseExpiry} />
              <LabelValue label="Next review date" value={kyb.nextReviewDate} />
              <Divider />
              <Box sx={{ display: 'flex', gap: 1, alignItems: 'center' }}>
                <Typography variant="body2" color="text.secondary" sx={{ minWidth: 140 }}>
                  Screening status
                </Typography>
                <ScreeningChip status={kyb.screeningStatus} />
              </Box>
              <LabelValue label="Screening ref" value={kyb.screeningProviderRef} />
              <LabelValue label="Screened at (KST)" value={toKst(kyb.screenedAt)} />
              {Array.isArray(kyb.screeningHits) && kyb.screeningHits.length > 0 && (
                <Box>
                  <Typography variant="body2" color="text.secondary" sx={{ mb: 0.5 }}>
                    Screening hits
                  </Typography>
                  <TableContainer>
                    <Table size="small" aria-label="Screening hits">
                      <TableHead>
                        <TableRow>
                          <TableCell>Name</TableCell>
                          <TableCell>Score</TableCell>
                          <TableCell>Type</TableCell>
                          <TableCell>Source</TableCell>
                        </TableRow>
                      </TableHead>
                      <TableBody>
                        {kyb.screeningHits.map((hit, i) => (
                          <TableRow key={i}>
                            <TableCell>{hit.name}</TableCell>
                            <TableCell>{hit.matchScore}</TableCell>
                            <TableCell>{hit.matchType}</TableCell>
                            <TableCell>{hit.source}</TableCell>
                          </TableRow>
                        ))}
                      </TableBody>
                    </Table>
                  </TableContainer>
                </Box>
              )}
              {Array.isArray(kyb.uboList) && kyb.uboList.length > 0 && (
                <Box>
                  <Typography variant="body2" color="text.secondary" sx={{ mb: 0.5 }}>
                    UBO list
                  </Typography>
                  <TableContainer>
                    <Table size="small" aria-label="UBO list">
                      <TableHead>
                        <TableRow>
                          <TableCell>Name</TableCell>
                          <TableCell>Ownership %</TableCell>
                          <TableCell>PEP</TableCell>
                          <TableCell>Country</TableCell>
                        </TableRow>
                      </TableHead>
                      <TableBody>
                        {kyb.uboList.map((ubo, i) => (
                          <TableRow key={i}>
                            <TableCell>{ubo.name}</TableCell>
                            <TableCell>{ubo.ownershipPct}</TableCell>
                            <TableCell>{ubo.isPep ? 'Yes' : 'No'}</TableCell>
                            <TableCell>{ubo.country}</TableCell>
                          </TableRow>
                        ))}
                      </TableBody>
                    </Table>
                  </TableContainer>
                </Box>
              )}
            </Stack>
          </Paper>
        ) : null}
      </Box>

      <Divider />

      {/* ---- Regulatory config section ---- */}
      <Box>
        <Typography variant="h3" sx={{ mb: 1 }}>
          Regulatory configuration
        </Typography>
        <ErrorAlert message={regulatoryError} title="Could not load regulatory config" />
        {regulatoryLoading ? (
          <LoadingSkeleton variant="card" />
        ) : regulatory ? (
          <Stack spacing={2}>
            <RegSection title="BOK" data={regulatory.bok} aria-label="BOK configuration" />
            <RegSection title="Hometax" data={regulatory.hometax} aria-label="Hometax configuration" />
            <RegSection title="KoFIU" data={regulatory.kofiu} aria-label="KoFIU configuration" />
            <RegSection title="PIPA" data={regulatory.pipa} aria-label="PIPA configuration" />
            <RegSection title="Travel Rule" data={regulatory.travelRule} aria-label="Travel Rule configuration" />
          </Stack>
        ) : null}
      </Box>

      <Divider />

      {/* ---- Audit log (recent entries for this partner) ---- */}
      <Box>
        <Typography variant="h3" sx={{ mb: 1 }}>
          Recent audit entries
        </Typography>
        <ErrorAlert message={auditError} title="Could not load audit entries" />
        {auditLoading ? (
          <LoadingSkeleton variant="table" rows={4} />
        ) : Array.isArray(auditPage) && auditPage.length > 0 ? (
          <TableContainer component={Paper} variant="outlined">
            <Table size="small" aria-label="Recent audit entries">
              <TableHead>
                <TableRow>
                  <TableCell>Event</TableCell>
                  <TableCell>Actor</TableCell>
                  <TableCell>Timestamp (KST)</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {auditPage.slice(0, 10).map((entry) => (
                  <TableRow key={entry.id} hover>
                    <TableCell>{entry.event ?? '—'}</TableCell>
                    <TableCell>{entry.actor ?? '—'}</TableCell>
                    <TableCell>{toKst(entry.at)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
        ) : !auditLoading && !auditError ? (
          <Typography variant="body2" color="text.secondary">
            No recent audit entries for {partnerCode}.
          </Typography>
        ) : null}
      </Box>
    </Stack>
  );
}

// ---------------------------------------------------------------------------
// Local helpers
// ---------------------------------------------------------------------------

function LabelValue({ label, value }) {
  return (
    <Box sx={{ display: 'flex', gap: 1 }}>
      <Typography variant="body2" color="text.secondary" sx={{ minWidth: 140 }}>
        {label}
      </Typography>
      <Typography variant="body2">{value ?? '—'}</Typography>
    </Box>
  );
}

function ScreeningChip({ status }) {
  const colorMap = { CLEAR: 'success', NEEDS_REVIEW: 'warning', HIT: 'error' };
  return (
    <Chip
      size="small"
      label={status ?? '—'}
      color={colorMap[status] ?? 'default'}
    />
  );
}

/**
 * Renders one regulatory section (BOK / Hometax / etc.) as a small key/value
 * card. If `data` is null the section shows "Not configured".
 */
function RegSection({ title, data }) {
  return (
    <Paper variant="outlined" sx={{ p: 2 }} aria-label={`${title} configuration`}>
      <Typography variant="subtitle2" sx={{ mb: 1 }}>
        {title}
      </Typography>
      {data ? (
        <Stack spacing={0.5}>
          {Object.entries(data).map(([k, v]) => {
            const display = Array.isArray(v) ? v.join(', ') : String(v ?? '—');
            return <LabelValue key={k} label={k} value={display} />;
          })}
        </Stack>
      ) : (
        <Typography variant="body2" color="text.secondary">
          Not configured
        </Typography>
      )}
    </Paper>
  );
}
