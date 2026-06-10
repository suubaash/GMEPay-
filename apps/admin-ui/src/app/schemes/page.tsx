'use client';

import { useEffect, useState } from 'react';
import {
  Alert,
  Box,
  Chip,
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
import type { QrScheme } from '@/api/types';

/**
 * QR scheme list — placeholder skeleton.
 *
 * Pulls from /v1/admin/schemes when the BFF endpoint is available; until then,
 * the table renders empty without error (the catch swallows network failure
 * so the page is still navigable).
 */
export default function SchemesPage() {
  const [items, setItems] = useState<QrScheme[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    adminApi
      .listSchemes()
      .then((data) => {
        if (cancelled) return;
        setItems(data);
      })
      .catch((e: unknown) => {
        if (cancelled) return;
        setError(e instanceof Error ? e.message : String(e));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <Box>
      <Typography variant="h1" gutterBottom>
        QR Schemes
      </Typography>
      {error ? (
        <Alert severity="info" sx={{ mb: 2 }}>
          Schemes endpoint not available yet: {error}
        </Alert>
      ) : null}
      <TableContainer component={Paper}>
        <Table>
          <TableHead>
            <TableRow>
              <TableCell>Scheme ID</TableCell>
              <TableCell>Display name</TableCell>
              <TableCell>Active</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {loading ? (
              <TableRow>
                <TableCell colSpan={3} align="center">
                  <CircularProgress size={24} />
                </TableCell>
              </TableRow>
            ) : items.length === 0 ? (
              <TableRow>
                <TableCell colSpan={3} align="center">
                  <Typography color="text.secondary">No schemes available.</Typography>
                </TableCell>
              </TableRow>
            ) : (
              items.map((s) => (
                <TableRow key={s.schemeId} hover>
                  <TableCell>{s.schemeId}</TableCell>
                  <TableCell>{s.displayName}</TableCell>
                  <TableCell>
                    <Chip
                      size="small"
                      label={s.active ? 'ACTIVE' : 'INACTIVE'}
                      color={s.active ? 'success' : 'default'}
                    />
                  </TableCell>
                </TableRow>
              ))
            )}
          </TableBody>
        </Table>
      </TableContainer>
    </Box>
  );
}
