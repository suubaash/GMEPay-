'use client';
import * as React from 'react';
import { usePathname, useRouter } from 'next/navigation';
import { useDispatch, useSelector } from 'react-redux';
import { Box, CircularProgress, Stack, Typography } from '@mui/material';
import { hydrateFromStorage } from '@/store/authSlice';

/** Routes that do NOT require authentication. */
const PUBLIC_ROUTES = new Set(['/login']);

/**
 * Authentication gate.
 *
 * On mount we hydrate the auth slice from localStorage (so the persisted
 * token survives reloads). Until hydration is complete we render a spinner
 * to avoid a flash of the login screen for already-signed-in users.
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

  if (!isPublic && !signedIn) {
    return null;
  }

  return <>{children}</>;
}
