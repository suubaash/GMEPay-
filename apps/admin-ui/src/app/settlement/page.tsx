'use client';

import { useEffect, useState } from 'react';
import {
  Alert,
  Box,
  CircularProgress,
  Paper,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material';
import { adminApi } from '@/api/client';
import type { SettlementBatch } from '@/api/types';
import MoneyDisplay from '@/components/MoneyDisplay';

/** Recent settlement batches — skeleton from /v1/admin/settlement/recent. */
export default function SettlementPage() {
  const [items, setItems] = useState<SettlementBatch[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    adminApi
      .listSettlementBatches()
      .then((data) => !cancelled && setItems(data))
      .catch((e: unknown) => !cancelled && setError(e instanceof Error ? e.message : String(e)))
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <Box>
      <Typography variant="h1" gutterBottom>
        Settlement
      </Typography>
      {error ? (
        <Alert severity="info" sx={{ mb: 2 }}>
          Settlement endpoint not available yet: {error}
        </Alert>
      ) : null}
      <TableContainer component={Paper}>
        <Table>
          <TableHead>
            <TableRow>
              <TableCell>Batch ID</TableCell>
              <TableCell>Partner</TableCell>
              <TableCell>Status</TableCell>
              <TableCell>Total</TableCell>
              <TableCell>Created</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {loading ? (
              <TableRow>
                <TableCell colSpan={5} align="center">
                  <CircularProgress size={24} />
                </TableCell>
              </TableRow>
            ) : items.length === 0 ? (
              <TableRow>
                <TableCell colSpan={5} align="center">
                  <Typography color="text.secondary">No settlement batches yet.</Typography>
                </TableCell>
              </TableRow>
            ) : (
              items.map((b) => (
                <TableRow key={b.batchId} hover>
                  <TableCell>{b.batchId}</TableCell>
                  <TableCell>{b.partnerId}</TableCell>
                  <TableCell>{b.status}</TableCell>
                  <TableCell><MoneyDisplay value={b.total} /></TableCell>
                  <TableCell>{b.createdAt}</TableCell>
                </TableRow>
              ))
            )}
          </TableBody>
        </Table>
      </TableContainer>
    </Box>
  );
}
