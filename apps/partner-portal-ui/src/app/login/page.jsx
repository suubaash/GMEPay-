'use client';
import * as React from 'react';
import { useRouter } from 'next/navigation';
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  CircularProgress,
  Container,
  Stack,
  Typography
} from '@mui/material';
import LockIcon from '@mui/icons-material/Lock';
import { useDispatch, useSelector } from 'react-redux';
import { hydrateFromStorage } from '@/store/authSlice';
import { startLogin } from '@/api/oidc';

/**
 * Partner login page — Keycloak SSO only.
 *
 * The Phase-1 `partnerId` + `password=demo` form is GONE, and so is the
 * "Phase 1 demo credentials" hint that told partners to use it. It POSTed to
 * `POST /v1/auth/login` on the BFF, an endpoint that was deleted (gap T0-1):
 * it minted an unsigned `role:ADMIN` token, and the BFF is now an OAuth2
 * resource server that rejects anything Keycloak did not sign.
 *
 * The only affordance is therefore a redirect into the realm configured by
 * `NEXT_PUBLIC_KEYCLOAK_URL` (default `http://localhost:8097/realms/gmepay`,
 * client `partner-portal-ui`, authorization-code + PKCE S256). Keycloak returns
 * to `/auth/callback`, which exchanges the code and stores the session.
 *
 * Mirrors `apps/admin-ui/src/app/login/page.jsx` deliberately: one login shape
 * across both SPAs, so a topology change is a one-line env change in both.
 */
export default function LoginPage() {
  const dispatch = useDispatch();
  const router = useRouter();
  const auth = useSelector((s) => s.auth);
  const [ssoPending, setSsoPending] = React.useState(false);
  const [error, setError] = React.useState(null);

  React.useEffect(() => {
    dispatch(hydrateFromStorage());
  }, [dispatch]);

  // Already signed in (e.g. landed here via a stale link) — go to the portal.
  React.useEffect(() => {
    if (auth.token && auth.partnerId) {
      router.replace('/');
    }
  }, [auth.token, auth.partnerId, router]);

  const handleSso = async () => {
    setError(null);
    setSsoPending(true);
    try {
      // startLogin navigates the window away; reaching the finally block means
      // it could not (no window / sessionStorage blocked), so recover the button.
      await startLogin('/');
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setSsoPending(false);
    }
  };

  return (
    <Container maxWidth="sm" sx={{ py: { xs: 4, md: 10 } }}>
      <Stack spacing={3} alignItems="center">
        <Typography variant="h4" sx={{ fontWeight: 700 }}>
          GMEPay<Box component="span" sx={{ color: 'primary.main' }}>+</Box>
        </Typography>
        <Typography variant="body2" sx={{ color: 'text.secondary' }}>
          Partner Self-Service Portal — sign in
        </Typography>

        <Card sx={{ width: '100%' }}>
          <CardContent>
            <Stack spacing={2}>
              {(error || auth.error) && (
                <Alert severity="error" data-testid="login-error">
                  {error ?? auth.error}
                </Alert>
              )}

              <Button
                variant="contained"
                size="large"
                fullWidth
                onClick={handleSso}
                disabled={ssoPending}
                data-testid="login-sso"
                aria-label="Sign in with Keycloak"
                startIcon={
                  ssoPending ? (
                    <CircularProgress size={18} color="inherit" />
                  ) : (
                    <LockIcon />
                  )
                }
              >
                Sign in with Keycloak
              </Button>

              <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                Single sign-on via the GMEPay+ identity provider. Your partner
                account must carry a <code>partner_id</code> matching your partner
                code — ask your GMEPay+ operator if sign-in succeeds but no data
                appears.
              </Typography>
            </Stack>
          </CardContent>
        </Card>
      </Stack>
    </Container>
  );
}
