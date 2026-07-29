'use client';
import * as React from 'react';
import { usePathname, useRouter } from 'next/navigation';
import { useDispatch, useSelector } from 'react-redux';
import { Alert, Box, Button, CircularProgress, Stack, Typography } from '@mui/material';
import { hydrateFromStorage, logoutAction } from '@/store/authSlice';
import { isDevLoginAllowed, startLogin } from '@/api/oidc';
import { isAuthenticated, isPartnerScopeMissing } from '@/api/auth';

/** Routes that do NOT require authentication. */
const PUBLIC_ROUTES = new Set(['/login']);

/**
 * Authentication gate — OIDC-aware.
 *
 * On mount we hydrate the auth slice from localStorage (so a persisted token
 * survives page reloads). Until hydration is complete we render a spinner to
 * avoid a flash of the login screen for already-signed-in partners.
 *
 * Redirect logic (after hydration, on protected routes):
 *   - If `isAuthenticated()` returns true → render children.
 *   - If `NEXT_PUBLIC_ALLOW_DEV_LOGIN=true` → redirect to the in-app /login page
 *     (which offers the Keycloak SSO button; there is no password form any more).
 *   - Otherwise → trigger Keycloak OIDC login via {@link startLogin}, capturing
 *     the current path as the post-login return destination.
 *
 * Signed in but the token carries no `partner_id` claim: every
 * `/v1/portal/{partnerId}/**` call would 403/404, so we stop and say exactly
 * what is missing (the Keycloak user attribute or the `gmepay-partner-id`
 * protocol mapper) instead of rendering a page of failed panels.
 *
 * The `/auth/callback` route and any path starting with `/auth/` are treated
 * as public so the callback page can complete the code exchange without being
 * redirected away.
 */
export default function AuthGate({ children }) {
  const dispatch = useDispatch();
  const router = useRouter();
  const pathname = usePathname() ?? '/';
  const auth = useSelector((s) => s.auth);

  const [hydrated, setHydrated] = React.useState(false);

  React.useEffect(() => {
    dispatch(hydrateFromStorage());
    setHydrated(true);
  }, [dispatch]);

  // Public: the /login page itself and the entire /auth/ subtree (callback).
  const isPublic =
    PUBLIC_ROUTES.has(pathname) || pathname.startsWith('/auth/');

  // Authenticated: use the full isAuthenticated() check (handles expiry).
  const signedIn = isAuthenticated();

  React.useEffect(() => {
    if (!hydrated) return;
    if (isPublic || signedIn) return;

    if (isDevLoginAllowed()) {
      // Dev-skip: redirect to the in-app password form.
      router.replace('/login');
    } else {
      // Production: kick off the Keycloak OIDC flow, capturing the current
      // path so the callback page can redirect back to it after sign-in.
      startLogin(pathname);
    }
  }, [hydrated, isPublic, signedIn, pathname, router]);

  if (!hydrated) {
    return (
      <Box
        sx={{
          minHeight: '60vh',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center'
        }}
      >
        <Stack spacing={1} alignItems="center">
          <CircularProgress />
          <Typography variant="caption" sx={{ color: 'text.secondary' }}>
            Loading session…
          </Typography>
        </Stack>
      </Box>
    );
  }

  if (!isPublic && !signedIn) {
    return null;
  }

  if (!isPublic && isPartnerScopeMissing()) {
    return (
      <Box sx={{ p: 3, display: 'flex', justifyContent: 'center' }}>
        <Stack spacing={2} sx={{ maxWidth: 560 }} data-testid="no-partner-scope">
          <Alert severity="warning">
            You are signed in, but your account is not linked to a partner.
          </Alert>
          <Typography variant="body2" sx={{ color: 'text.secondary' }}>
            The access token carries no <code>partner_id</code> claim, so the
            portal cannot address your data. Ask a GMEPay+ operator to set the{' '}
            <code>partner_id</code> attribute on your Keycloak user to your
            partner code (e.g. <code>GMEREMIT</code>) and confirm the{' '}
            <code>gmepay-partner-id</code> protocol mapper is enabled on the{' '}
            <code>partner-portal-ui</code> client.
          </Typography>
          <Button
            variant="outlined"
            onClick={() => {
              dispatch(logoutAction());
              router.replace('/login');
            }}
          >
            Sign out
          </Button>
        </Stack>
      </Box>
    );
  }

  return <>{children}</>;
}

