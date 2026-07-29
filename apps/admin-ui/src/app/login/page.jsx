'use client';

import { useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  CircularProgress,
  Stack,
  Typography,
} from '@mui/material';
import LockIcon from '@mui/icons-material/Lock';
import { startLogin } from '@/api/oidc';

/**
 * Operator login page — Keycloak SSO only.
 *
 * The `password=demo` dev form is gone (it was rendered behind
 * `NEXT_PUBLIC_ALLOW_DEV_LOGIN=true`): `POST /v1/auth/login` was DELETED from
 * ops-partner-bff (gap T0-1), so the form could only ever produce a 404, and the
 * unsigned token it used to mint is rejected by the resource server. Keeping a
 * dead password field on a login screen is worse than not having one.
 *
 * The single affordance redirects to the realm configured by
 * `NEXT_PUBLIC_KEYCLOAK_URL` (default `http://localhost:8097/realms/gmepay`,
 * client `admin-ui`, authorization-code + PKCE S256 — see
 * docker/keycloak/README.md §1). Keycloak returns to `/auth/callback`, which
 * exchanges the code and stores the session.
 */
export default function LoginPage() {
  const [ssoPending, setSsoPending] = useState(false);
  const [error, setError] = useState(null);

  const handleSso = async () => {
    setError(null);
    setSsoPending(true);
    try {
      // startLogin navigates the browser away; if it returns we hit an error
      // path (no window, sessionStorage blocked) — flip pending back so the
      // button recovers instead of spinning forever.
      await startLogin('/');
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setSsoPending(false);
    }
  };

  return (
    <Box
      sx={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        bgcolor: 'background.default',
        p: 2,
      }}
    >
      <Card sx={{ width: '100%', maxWidth: 420 }}>
        <CardContent sx={{ p: 4 }}>
          <Stack spacing={3}>
            <Box sx={{ textAlign: 'center' }}>
              <Typography variant="h2" sx={{ fontWeight: 700 }}>
                GMEPay+ Ops
              </Typography>
              <Typography color="text.secondary" sx={{ mt: 1 }}>
                Sign in to manage partners, schemes, and settlement.
              </Typography>
            </Box>

            {error && (
              <Alert severity="error" onClose={() => setError(null)} data-testid="login-error">
                {error}
              </Alert>
            )}

            <Button
              variant="contained"
              size="large"
              startIcon={
                ssoPending ? (
                  <CircularProgress size={18} color="inherit" />
                ) : (
                  <LockIcon />
                )
              }
              onClick={handleSso}
              disabled={ssoPending}
              fullWidth
              data-testid="login-sso"
              aria-label="Sign in with Keycloak"
            >
              Sign in with Keycloak
            </Button>
            <Typography variant="caption" color="text.secondary" sx={{ textAlign: 'center' }}>
              Single sign-on via the GMEPay+ identity provider. Your operator
              account needs the platform permissions carried in the token&apos;s{' '}
              <code>permissions</code> claim — without them the console loads but
              every admin call returns 403.
            </Typography>
          </Stack>
        </CardContent>
      </Card>
    </Box>
  );
}
