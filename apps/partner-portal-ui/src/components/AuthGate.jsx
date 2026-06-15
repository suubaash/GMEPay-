'use client';
import * as React from 'react';
import { usePathname, useRouter } from 'next/navigation';
import { useDispatch, useSelector } from 'react-redux';
import { Box, CircularProgress, Stack, Typography } from '@mui/material';
import { hydrateFromStorage } from '@/store/authSlice';
import { isDevLoginAllowed, startLogin } from '@/api/oidc';
import { isAuthenticated } from '@/api/auth';

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
 *   - If `NEXT_PUBLIC_ALLOW_DEV_LOGIN=true` → redirect to in-app /login form
 *     (Phase-1 password form, also used by vitest).
 *   - Otherwise → trigger Keycloak OIDC login via {@link startLogin}, capturing
 *     the current path as the post-login return destination.
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

  return <>{children}</>;
}

