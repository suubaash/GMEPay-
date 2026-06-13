'use client';

import { useEffect, useState } from 'react';
import { usePathname, useRouter } from 'next/navigation';
import { Box, CircularProgress } from '@mui/material';
import { isAuthenticated } from '@/api/auth';
import { isDevLoginAllowed, startLogin } from '@/api/oidc';

/**
 * AuthGate — client-side auth gate.
 *
 * Wrapped around the AppShell in the root layout. On every navigation:
 *   - If the current path is `/login` or `/auth/callback` -> render
 *     children unconditionally (no redirect loop; these routes are part of
 *     the login flow itself).
 *   - Otherwise, check {@link isAuthenticated}; if absent:
 *       * When the dev escape hatch is enabled (NEXT_PUBLIC_ALLOW_DEV_LOGIN=true),
 *         redirect to the in-app `/login` page so vitest + local-no-Keycloak
 *         iteration still works.
 *       * Otherwise, kick straight to Keycloak via {@link startLogin}
 *         (Slice 1 retired the in-app password form for the production flow).
 *
 * The first render gates on a "checked" flag so we don't briefly flash the
 * authenticated UI before the redirect kicks in. localStorage is only
 * available in the browser, so SSR returns a small spinner placeholder while
 * the client hydrates.
 */
export default function AuthGate({ children }) {
  const router = useRouter();
  const pathname = usePathname();
  const [checked, setChecked] = useState(false);
  const [allowed, setAllowed] = useState(false);

  useEffect(() => {
    if (pathname === '/login' || pathname?.startsWith('/auth/')) {
      setAllowed(true);
      setChecked(true);
      return;
    }
    if (isAuthenticated()) {
      setAllowed(true);
      setChecked(true);
      return;
    }
    setAllowed(false);
    setChecked(true);
    if (isDevLoginAllowed()) {
      router.replace('/login');
    } else {
      // Redirect straight to Keycloak; capture current path so we land
      // back on the same page after sign-in (callback uses sessionStorage).
      startLogin(pathname || '/');
    }
  }, [pathname, router]);

  if (!checked) {
    return (
      <Box
        sx={{
          minHeight: '100vh',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
        }}
      >
        <CircularProgress />
      </Box>
    );
  }
  if (!allowed) {
    return null;
  }
  return <>{children}</>;
}
