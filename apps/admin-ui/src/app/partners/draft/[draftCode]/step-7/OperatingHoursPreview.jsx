'use client';

import { useEffect, useState } from 'react';
import {
  Box,
  CircularProgress,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material';
import { adminApi } from '@/api/client';

/**
 * Ordered day labels matching the BFF enum.
 */
const DAYS = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN'];

const DAY_LABELS = {
  MON: 'Monday',
  TUE: 'Tuesday',
  WED: 'Wednesday',
  THU: 'Thursday',
  FRI: 'Friday',
  SAT: 'Saturday',
  SUN: 'Sunday',
};

/**
 * Operating Hours Preview for Step 7 (Schemes & Corridors).
 *
 * Read-only week grid for the scheme currently selected in SchemesMatrix.
 * Calls GET /v1/admin/schemes/{schemeId}/operating-hours on `schemeId` change.
 * Shows "—" when the slot is empty or the scheme has no hours configured.
 *
 * @param {object}       props
 * @param {string|null}  props.schemeId  The scheme currently being previewed.
 */
export default function OperatingHoursPreview({ schemeId }) {
  const [hours, setHours] = useState([]);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!schemeId) {
      setHours([]);
      return;
    }

    let cancelled = false;
    setLoading(true);

    adminApi
      .listSchemeOperatingHours(schemeId)
      .then((data) => {
        if (!cancelled) {
          setHours(Array.isArray(data) ? data : []);
        }
      })
      .catch(() => {
        if (!cancelled) setHours([]);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [schemeId]);

  // Build a lookup from dayOfWeek → OperatingHoursView
  const byDay = Object.fromEntries(hours.map((h) => [h.dayOfWeek, h]));

  return (
    <Box aria-label="operating-hours-preview-section">
      <Stack spacing={2}>
        <Box>
          <Typography variant="h6" gutterBottom>
            Scheme operating hours
          </Typography>
          <Typography variant="body2" color="text.secondary">
            {schemeId
              ? `Operating hours for ${schemeId} (read-only — managed at the scheme level).`
              : 'Enable a scheme in the matrix above to preview its operating hours.'}
          </Typography>
        </Box>

        {loading && (
          <Box sx={{ display: 'flex', justifyContent: 'center', py: 2 }}>
            <CircularProgress size={24} aria-label="operating-hours-loading" />
          </Box>
        )}

        {!loading && (
          <Paper variant="outlined" aria-label="operating-hours-table-container">
            <Table size="small" aria-label="operating-hours-table">
              <TableHead>
                <TableRow>
                  <TableCell>Day</TableCell>
                  <TableCell>Opens</TableCell>
                  <TableCell>Closes</TableCell>
                  <TableCell>Status</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {DAYS.map((day) => {
                  const entry = byDay[day];
                  const closed = entry ? !!entry.closed : true;
                  const openTime = entry?.openTime ?? null;
                  const closeTime = entry?.closeTime ?? null;
                  return (
                    <TableRow key={day} hover>
                      <TableCell>
                        <Typography variant="body2" sx={{ fontWeight: 500 }}>
                          {DAY_LABELS[day]}
                        </Typography>
                      </TableCell>
                      <TableCell>
                        <Typography
                          variant="body2"
                          color={!openTime ? 'text.secondary' : undefined}
                          aria-label={`op-hours-open-${day}`}
                        >
                          {openTime ?? '—'}
                        </Typography>
                      </TableCell>
                      <TableCell>
                        <Typography
                          variant="body2"
                          color={!closeTime ? 'text.secondary' : undefined}
                          aria-label={`op-hours-close-${day}`}
                        >
                          {closeTime ?? '—'}
                        </Typography>
                      </TableCell>
                      <TableCell>
                        <Typography
                          variant="body2"
                          color={closed ? 'error.main' : 'success.main'}
                          aria-label={`op-hours-status-${day}`}
                        >
                          {closed ? 'Closed' : 'Open'}
                        </Typography>
                      </TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
          </Paper>
        )}
      </Stack>
    </Box>
  );
}
