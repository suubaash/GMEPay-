'use client';

import { useEffect } from 'react';
import {
  Box,
  Button,
  CircularProgress,
  Paper,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography,
  Alert,
} from '@mui/material';
import AddIcon from '@mui/icons-material/Add';
import Link from 'next/link';
import { useAppDispatch, useAppSelector } from '@/store';
import { fetchPartners } from '@/store/partnersSlice';

/** Partner list page — feeds from /v1/admin/partners (BFF projection of config-registry). */
export default function PartnersListPage() {
  const dispatch = useAppDispatch();
  const { items, loading, error } = useAppSelector((s) => s.partners);

  useEffect(() => {
    dispatch(fetchPartners());
  }, [dispatch]);

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

      {error ? (
        <Alert severity="error" sx={{ mb: 2 }}>
          {error}
        </Alert>
      ) : null}

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
            {loading ? (
              <TableRow>
                <TableCell colSpan={4} align="center">
                  <CircularProgress size={24} />
                </TableCell>
              </TableRow>
            ) : items.length === 0 ? (
              <TableRow>
                <TableCell colSpan={4} align="center">
                  <Typography color="text.secondary">No partners yet.</Typography>
                </TableCell>
              </TableRow>
            ) : (
              items.map((p) => (
                <TableRow key={p.partnerId} hover>
                  <TableCell>
                    <Link href={`/partners/${encodeURIComponent(p.partnerId)}`}>
                      {p.partnerId}
                    </Link>
                  </TableCell>
                  <TableCell>{p.type}</TableCell>
                  <TableCell>{p.settlementCurrency}</TableCell>
                  <TableCell>{p.settlementRoundingMode}</TableCell>
                </TableRow>
              ))
            )}
          </TableBody>
        </Table>
      </TableContainer>
    </Box>
  );
}
