'use client';

import { useEffect } from 'react';
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
import { useAppDispatch, useAppSelector } from '@/store';
import { fetchRecentTransactions } from '@/store/transactionsSlice';
import MoneyDisplay from '@/components/MoneyDisplay';
import StatusChip from '@/components/StatusChip';

/** Recent transactions skeleton — feeds from /v1/admin/transactions/recent. */
export default function TransactionsPage() {
  const dispatch = useAppDispatch();
  const { items, loading, error } = useAppSelector((s) => s.transactions);

  useEffect(() => {
    dispatch(fetchRecentTransactions());
  }, [dispatch]);

  return (
    <Box>
      <Typography variant="h1" gutterBottom>
        Recent transactions
      </Typography>
      {error ? <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert> : null}
      <TableContainer component={Paper}>
        <Table>
          <TableHead>
            <TableRow>
              <TableCell>Transaction ID</TableCell>
              <TableCell>Partner</TableCell>
              <TableCell>Scheme</TableCell>
              <TableCell>Amount</TableCell>
              <TableCell>Status</TableCell>
              <TableCell>Created</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {loading ? (
              <TableRow>
                <TableCell colSpan={6} align="center">
                  <CircularProgress size={24} />
                </TableCell>
              </TableRow>
            ) : items.length === 0 ? (
              <TableRow>
                <TableCell colSpan={6} align="center">
                  <Typography color="text.secondary">No transactions yet.</Typography>
                </TableCell>
              </TableRow>
            ) : (
              items.map((t) => (
                <TableRow key={t.id} hover>
                  <TableCell>{t.id}</TableCell>
                  <TableCell>{t.partnerId}</TableCell>
                  <TableCell>{t.scheme}</TableCell>
                  <TableCell><MoneyDisplay value={t.amount} /></TableCell>
                  <TableCell><StatusChip status={t.status} /></TableCell>
                  <TableCell>{t.createdAt}</TableCell>
                </TableRow>
              ))
            )}
          </TableBody>
        </Table>
      </TableContainer>
    </Box>
  );
}
