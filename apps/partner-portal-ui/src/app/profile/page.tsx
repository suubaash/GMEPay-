'use client';
import * as React from 'react';
import {
  Alert,
  Box,
  Card,
  CardContent,
  CircularProgress,
  Grid,
  Stack,
  Typography
} from '@mui/material';
import { useDispatch, useSelector } from 'react-redux';
import type { AppDispatch, RootState } from '@/store';
import { fetchProfile } from '@/store/portalSlice';
import { currentPartnerId } from '@/api/client';

function Field({ label, value }: { label: string; value: React.ReactNode }) {
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
  const dispatch = useDispatch<AppDispatch>();
  const partnerId = currentPartnerId();
  const { data, status, error } = useSelector((s: RootState) => s.portal.profile);

  React.useEffect(() => {
    if (partnerId && status === 'idle') dispatch(fetchProfile(partnerId));
  }, [partnerId, status, dispatch]);

  if (!partnerId) {
    return (
      <Alert severity="warning">
        No partner id configured. Set <code>NEXT_PUBLIC_PARTNER_ID</code>.
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

      {status === 'loading' && <CircularProgress />}
      {status === 'failed' && <Alert severity="error">{error}</Alert>}

      {data && (
        <Card>
          <CardContent>
            <Grid container spacing={3}>
              <Grid item xs={12} md={6}>
                <Field label="Partner ID" value={<code>{data.partnerId}</code>} />
              </Grid>
              <Grid item xs={12} md={6}>
                <Field label="Display name" value={data.displayName} />
              </Grid>
              <Grid item xs={12} md={6}>
                <Field label="Type" value={data.type} />
              </Grid>
              <Grid item xs={12} md={6}>
                <Field label="Settlement currency" value={data.settlementCurrency} />
              </Grid>
              <Grid item xs={12} md={6}>
                <Field
                  label="Settlement rounding mode"
                  value={
                    <Stack direction="row" spacing={1} alignItems="center">
                      <span>{data.settlementRoundingMode}</span>
                      <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                        (see docs/MONEY_CONVENTION.md)
                      </Typography>
                    </Stack>
                  }
                />
              </Grid>
              <Grid item xs={12} md={6}>
                <Field
                  label="Onboarded"
                  value={new Date(data.onboardedAt).toLocaleString()}
                />
              </Grid>
            </Grid>
          </CardContent>
        </Card>
      )}
    </Stack>
  );
}
