'use client';

import { useCallback, useEffect } from 'react';
import {
  Box,
  Button,
  Paper,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material';
import AddIcon from '@mui/icons-material/Add';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useAppDispatch, useAppSelector } from '@/store';
import { fetchPartners } from '@/store/partnersSlice';
import ErrorAlert from '@/components/ErrorAlert';
import EmptyState from '@/components/EmptyState';
import LoadingSkeleton from '@/components/LoadingSkeleton';

/**
 * Partner list page — GET /v1/admin/partners.
 *
 * Each row is a PartnerSummary:
 *   { partnerId, type, settlementCurrency, settlementRoundingMode }
 *
 * Clicking a row navigates to /partners/{id}.
 */
export default function PartnersListPage() {
  const dispatch = useAppDispatch();
  const router = useRouter();
  const { items, loading, error } = useAppSelector((s) => s.partners);

  const reload = useCallback(() => {
    dispatch(fetchPartners());
  }, [dispatch]);

  useEffect(() => {
    reload();
  }, [reload]);

  const rows = Array.isArray(items) ? items : [];

  return (
    <Box>
      <Box sx={{ display: 'flex', alignItems: 'center', mb: 2 }}>
        <Typography variant="h1" sx={{ flexGrow: 1 }}>
          Partners
        </Typography>
        <Button
          variant="contained"
          startIcon={<AddIcon />}
          component={Link}
          href="/partners/new"
        >
          New partner
        </Button>
      </Box>

      <ErrorAlert message={error} onRetry={reload} title="Could not load partners" />

      {loading && rows.length === 0 ? (
        <LoadingSkeleton variant="table" rows={6} />
      ) : !loading && rows.length === 0 && !error ? (
        <Paper variant="outlined">
          <EmptyState
            heading="No partners yet"
            description="Onboard your first partner to start transacting."
            ctaLabel="New partner"
            ctaHref="/partners/new"
          />
        </Paper>
      ) : (
        <TableContainer component={Paper}>
          <Table>
            <TableHead>
              <TableRow>
                <TableCell>Partner ID</TableCell>
                <TableCell>Type</TableCell>
                <TableCell>Settlement currency</TableCell>
                <TableCell>Rounding mode</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {rows.map((p) => (
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
  );
}
