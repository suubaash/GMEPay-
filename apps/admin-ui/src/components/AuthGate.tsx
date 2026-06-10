'use client';

import { useEffect, useState } from 'react';
import { usePathname, useRouter } from 'next/navigation';
import { Box, CircularProgress } from '@mui/material';
import { isAuthenticated } from '@/api/auth';

/**
 * Client-side auth gate.
 *
 * Wrapped around the AppShell in the root layout. On every navigation:
 *   - If the current path is /login -> render children unconditionally
 *     (no redirect loop).
 *   - Otherwise, check localStorage for a JWT via {@link isAuthenticated};
 *     if absent, replace the URL with /login. If present, render children.
 *
 * The first render gates on a "checked" flag so we don't briefly flash the
 * authenticated UI before the redirect kicks in. localStorage is only
 * available in the browser, so SSR returns a small spinner placeholder
 * while the client hydrates.
 *
 * Phase-D will replace localStorage with an httpOnly session cookie set by
 * the BFF; the gate's logic will move server-side via middleware.
 */
export default function AuthGate({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const pathname = usePathname();
  const [checked, setChecked] = useState(false);
  const [allowed, setAllowed] = useState(false);

  useEffect(() => {
    // /login is always open — it's the only place a user can acquire a token.
    if (pathname === '/login') {
      setAllowed(true);
      setChecked(true);
      return;
    }
    if (isAuthenticated()) {
      setAllowed(true);
      setChecked(true);
    } else {
      setAllowed(false);
      setChecked(true);
      router.replace('/login');
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
    // While the redirect is in flight, render nothing rather than leaking
    // the underlying page tree to an unauthenticated user.
    return null;
  }
  return <>{children}</>;
}
