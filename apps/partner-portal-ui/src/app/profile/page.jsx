'use client';
import * as React from 'react';
import {
  Alert,
  Box,
  Card,
  CardContent,
  Grid,
  Stack,
  Typography
} from '@mui/material';
import { useDispatch, useSelector } from 'react-redux';
import { fetchProfile } from '@/store/profileSlice';
import { currentPartnerId } from '@/api/client';
import LoadingSkeleton from '@/components/LoadingSkeleton';
import ErrorAlert from '@/components/ErrorAlert';

/**
 * Read-only partner profile.
 *
 * Wire shape (PartnerProfile):
 *   { partnerId, type, settlementCurrency, settlementRoundingMode, onboardedAt }
 *
 * There is NO `displayName` on the wire — the page uses partnerId as the
 * heading identifier. Highlights settlementRoundingMode since it determines
 * how the partner's settlement liability is booked under
 * lib-money/SettlementRounding.book(...) (docs/MONEY_CONVENTION.md).
 */
function Field({ label, value }) {
  return (
    <Box>
      <Typography variant="caption" sx={{ color: 'text.secondary' }}>
        {label}
      </Typography>
      <Typography variant="body1" sx={{ mt: 0.25 }}>
        {value}
      </Typography>
    </Box>
  );
}

export default function ProfilePage() {
  const dispatch = useDispatch();
  const partnerId = currentPartnerId();
  const { data, status, error } = useSelector((s) => s.profile);

  React.useEffect(() => {
    if (partnerId && status === 'idle') dispatch(fetchProfile(partnerId));
  }, [partnerId, status, dispatch]);

  const retry = React.useCallback(() => {
    if (partnerId) dispatch(fetchProfile(partnerId));
  }, [partnerId, dispatch]);

  if (!partnerId) {
    return (
      <Alert severity="warning">
        No partner id available. Sign in or set <code>NEXT_PUBLIC_PARTNER_ID</code>.
      </Alert>
    );
  }

  return (
    <Stack spacing={3}>
      <Box>
        <Typography variant="h1">Profile</Typography>
        <Typography variant="body2" sx={{ color: 'text.secondary' }}>
          Read-only — settings owned by GMEPay+ <code>config-registry</code>. Contact your
          account manager to update.
        </Typography>
      </Box>

      {status === 'loading' && <LoadingSkeleton variant="card" rows={5} />}
      {status === 'failed' && (
        <ErrorAlert message={error ?? 'Failed to load profile.'} onRetry={retry} />
      )}

      {data && (
        <Card>
          <CardContent>
            <Grid container spacing={3}>
              <Grid item xs={12} md={6}>
                <Field label="Partner ID" value={<code>{data.partnerId ?? '—'}</code>} />
              </Grid>
              <Grid item xs={12} md={6}>
                <Field label="Type" value={data.type ?? '—'} />
              </Grid>
              <Grid item xs={12} md={6}>
                <Field label="Settlement currency" value={data.settlementCurrency ?? '—'} />
              </Grid>
              <Grid item xs={12} md={6}>
                <Field
                  label="Onboarded"
                  value={
                    data.onboardedAt
                      ? new Date(data.onboardedAt).toLocaleString()
                      : '—'
                  }
                />
              </Grid>

              {/*
                Highlighted: settlement rounding mode determines how a
                partner's liability is booked (HALF_UP, DOWN, etc) per
                docs/MONEY_CONVENTION.md.
              */}
              <Grid item xs={12} md={6}>
                <Box
                  data-testid="rounding-mode-highlight"
                  sx={{
                    p: 2,
                    borderRadius: 1,
                    border: '1px solid',
                    borderColor: 'primary.light',
                    bgcolor: 'rgba(11, 95, 255, 0.04)'
                  }}
                >
                  <Typography variant="caption" sx={{ color: 'primary.dark', fontWeight: 600 }}>
                    Settlement rounding mode
                  </Typography>
                  <Typography variant="h4" sx={{ fontWeight: 700, mt: 0.5 }}>
                    {data.settlementRoundingMode ?? '—'}
                  </Typography>
                  <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                    Determines how your settlement liability is booked. Residuals between
                    the precise amount and the booked amount are recorded in
                    <code> REVENUE_ROUNDING</code> (see <code>docs/MONEY_CONVENTION.md</code>).
                  </Typography>
                </Box>
              </Grid>
            </Grid>
          </CardContent>
        </Card>
      )}
    </Stack>
  );
}
