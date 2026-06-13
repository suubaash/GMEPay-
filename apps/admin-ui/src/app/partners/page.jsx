'use client';

import { useCallback, useEffect } from 'react';
import {
  Box,
  Button,
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
import AddIcon from '@mui/icons-material/Add';
import PlayArrowIcon from '@mui/icons-material/PlayArrow';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useAppDispatch, useAppSelector } from '@/store';
import { createDraft, fetchDrafts, fetchPartners } from '@/store/partnersSlice';
import ErrorAlert from '@/components/ErrorAlert';
import EmptyState from '@/components/EmptyState';
import LoadingSkeleton from '@/components/LoadingSkeleton';
import { useSnackbar } from '@/components/SnackbarProvider';

/**
 * Partners landing page — split into two tables per the Slice 1 wizard
 * (see {@code docs/PARTNER_SETUP_PLAN.md} §"Slice 1 — Identity + Foundation"):
 *
 *   "Active partners" — GET /v1/admin/partners. Each row is a
 *     PartnerSummary { partnerId, type, settlementCurrency, settlementRoundingMode }.
 *     We display rows whose status is LIVE; the deprecated PartnerSummary wire
 *     shape carries no status field today, so partners without status are also
 *     shown (the BFF currently never serialises one). When config-registry
 *     starts emitting status on this endpoint the filter narrows to LIVE
 *     automatically — no code change required.
 *
 *   "Drafts" — GET /v1/admin/partners/drafts. Each row is a PartnerView with
 *     status=ONBOARDING; a "Resume" button routes to
 *     /partners/draft/{partnerCode}/step-1 so the operator can keep editing.
 *
 * The "New partner" button no longer navigates to /partners/new directly —
 * instead it POSTs /v1/admin/partners/draft to mint a fresh PartnerView
 * (status=ONBOARDING, paired change_request in state=DRAFT per ADR-008) and
 * then routes to /partners/draft/{partnerCode}/step-1.
 */
export default function PartnersListPage() {
  const dispatch = useAppDispatch();
  const router = useRouter();
  const snackbar = useSnackbar();
  const {
    items,
    drafts,
    loading,
    draftsLoading,
    error,
    draftsError,
    creatingDraft,
  } = useAppSelector((s) => s.partners);

  const reload = useCallback(() => {
    dispatch(fetchPartners());
    dispatch(fetchDrafts());
  }, [dispatch]);

  useEffect(() => {
    reload();
  }, [reload]);

  const reloadActive = useCallback(() => {
    dispatch(fetchPartners());
  }, [dispatch]);
  const reloadDrafts = useCallback(() => {
    dispatch(fetchDrafts());
  }, [dispatch]);

  const allItems = Array.isArray(items) ? items : [];
  // The deprecated PartnerSummary shape (status absent) and the canonical
  // PartnerView shape (status present) are both possible on this endpoint
  // during the Expand/Backfill window. Treat "no status" as LIVE so existing
  // demo rows keep rendering.
  const activeRows = allItems.filter(
    (p) => p.status === undefined || p.status === null || p.status === 'LIVE',
  );
  const draftRows = Array.isArray(drafts) ? drafts : [];

  const onNewPartner = async () => {
    try {
      const created = await dispatch(createDraft({})).unwrap();
      if (!created || !created.partnerCode) {
        snackbar.error('Draft created but missing partnerCode');
        return;
      }
      router.push(
        `/partners/draft/${encodeURIComponent(created.partnerCode)}/step-1`,
      );
    } catch (e) {
      const message = e?.message || (typeof e === 'string' ? e : 'unknown error');
      snackbar.error(`Could not start a new draft: ${message}`);
    }
  };

  return (
    <Box>
      <Box sx={{ display: 'flex', alignItems: 'center', mb: 2 }}>
        <Typography variant="h1" sx={{ flexGrow: 1 }}>
          Partners
        </Typography>
        <Button
          variant="contained"
          startIcon={<AddIcon />}
          onClick={onNewPartner}
          disabled={creatingDraft}
          aria-label="New partner (start draft)"
        >
          New partner
        </Button>
      </Box>

      <Stack spacing={4}>
        {/* ---------- Active partners ---------- */}
        <Box>
          <Typography variant="h2" sx={{ mb: 1 }}>
            Active partners
          </Typography>
          <ErrorAlert
            message={error}
            onRetry={reloadActive}
            title="Could not load partners"
          />

          {loading && activeRows.length === 0 ? (
            <LoadingSkeleton variant="table" rows={4} />
          ) : !loading && activeRows.length === 0 && !error ? (
            <Paper variant="outlined">
              <EmptyState
                heading="No active partners yet"
                description="Once a draft completes the activation gate it shows up here."
                ctaLabel="New partner"
                onCta={onNewPartner}
              />
            </Paper>
          ) : (
            <TableContainer component={Paper}>
              <Table aria-label="Active partners">
                <TableHead>
                  <TableRow>
                    <TableCell>Partner ID</TableCell>
                    <TableCell>Type</TableCell>
                    <TableCell>Settlement currency</TableCell>
                    <TableCell>Rounding mode</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {activeRows.map((p) => (
                    <TableRow
                      key={p.partnerId}
                      hover
                      sx={{ cursor: 'pointer' }}
                      onClick={() =>
                        router.push(`/partners/${encodeURIComponent(p.partnerId)}`)
                      }
                    >
                      <TableCell>
                        <Link
                          href={`/partners/${encodeURIComponent(p.partnerId)}`}
                          onClick={(e) => e.stopPropagation()}
                        >
                          {p.partnerId}
                        </Link>
                      </TableCell>
                      <TableCell>{p.type ?? '—'}</TableCell>
                      <TableCell>{p.settlementCurrency ?? '—'}</TableCell>
                      <TableCell>{p.settlementRoundingMode ?? '—'}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          )}
        </Box>

        {/* ---------- Drafts ---------- */}
        <Box>
          <Typography variant="h2" sx={{ mb: 1 }}>
            Drafts
          </Typography>
          <ErrorAlert
            message={draftsError}
            onRetry={reloadDrafts}
            title="Could not load drafts"
          />

          {draftsLoading && draftRows.length === 0 ? (
            <LoadingSkeleton variant="table" rows={3} />
          ) : !draftsLoading && draftRows.length === 0 && !draftsError ? (
            <Paper variant="outlined">
              <EmptyState
                heading="No drafts in flight"
                description='Click "New partner" above to start a draft. Drafts persist on the server so you can close the tab and come back later.'
              />
            </Paper>
          ) : (
            <TableContainer component={Paper}>
              <Table aria-label="Partner drafts">
                <TableHead>
                  <TableRow>
                    <TableCell>Partner code</TableCell>
                    <TableCell>Legal name</TableCell>
                    <TableCell>Country</TableCell>
                    <TableCell>Status</TableCell>
                    <TableCell align="right">Resume</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {draftRows.map((d) => (
                    <DraftRow key={d.partnerCode ?? d.id} draft={d} />
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          )}
        </Box>
      </Stack>
    </Box>
  );
}

/**
 * One Drafts table row. Split out as a tiny component so the "Resume" link
 * keeps its own click handling without bubbling to the row.
 */
function DraftRow({ draft }) {
  const code = draft.partnerCode;
  const resumeHref = code
    ? `/partners/draft/${encodeURIComponent(code)}/step-1`
    : undefined;
  // Prefer the romanised name (admin-ui is en-US first); fall back to local
  // script when only the local name has been entered so the row never reads
  // as "—" once the operator has populated something.
  const displayName =
    draft.legalNameRomanized ?? draft.legalNameLocal ?? '—';
  return (
    <TableRow hover>
      <TableCell>{code ?? '—'}</TableCell>
      <TableCell>{displayName}</TableCell>
      <TableCell>{draft.countryOfIncorporation ?? '—'}</TableCell>
      <TableCell>{draft.status ?? 'ONBOARDING'}</TableCell>
      <TableCell align="right">
        {resumeHref ? (
          <Button
            component={Link}
            href={resumeHref}
            size="small"
            variant="outlined"
            startIcon={<PlayArrowIcon />}
          >
            Resume
          </Button>
        ) : (
          '—'
        )}
      </TableCell>
    </TableRow>
  );
}
