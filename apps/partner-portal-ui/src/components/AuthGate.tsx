'use client';
import * as React from 'react';
import { usePathname, useRouter } from 'next/navigation';
import { useDispatch, useSelector } from 'react-redux';
import { Box, CircularProgress, Stack, Typography } from '@mui/material';
import type { AppDispatch, RootState } from '@/store';
import { hydrateFromStorage } from '@/store/authSlice';

/** Routes that do NOT require authentication. */
const PUBLIC_ROUTES = new Set<string>(['/login']);

interface AuthGateProps {
  children: React.ReactNode;
}

/**
 * Authentication gate.
 *
 * On mount we hydrate the auth slice from localStorage (so the persisted
 * token survives reloads). Until hydration is complete we render a spinner
 * to avoid a flash of the login screen for already-signed-in users. After
 * hydration:
 *  - if the route is public (e.g. /login) we always render the children,
 *  - otherwise, if there's no token we redirect to /login.
 *
 * Production will replace the localStorage scheme with an httpOnly session
 * cookie + a server component check; this gate's surface stays the same.
 */
export default function AuthGate({ children }: AuthGateProps) {
  const dispatch = useDispatch<AppDispatch>();
  const router = useRouter();
  const pathname = usePathname() ?? '/';
  const auth = useSelector((s: RootState) => s.auth);

  const [hydrated, setHydrated] = React.useState(false);

  React.useEffect(() => {
    dispatch(hydrateFromStorage());
    setHydrated(true);
  }, [dispatch]);

  const isPublic = PUBLIC_ROUTES.has(pathname);
  const signedIn = Boolean(auth.token && auth.partnerId);

  React.useEffect(() => {
    if (!hydrated) return;
    if (!isPublic && !signedIn) {
      router.replace('/login');
    }
  }, [hydrated, isPublic, signedIn, router]);

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

  // After hydration: gate non-public routes until the redirect lands.
  if (!isPublic && !signedIn) {
    return null;
  }

  return <>{children}</>;
}
