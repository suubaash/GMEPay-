'use client';

import { useEffect, useRef, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { Alert, Box, CircularProgress, Stack, Typography } from '@mui/material';
import { consumeReturnTo, exchangeCode } from '@/api/oidc';
import { useAppDispatch } from '@/store';
import { applyOidcSessionThunk } from '@/store/authSlice';

/**
 * OIDC callback page.
 *
 * Keycloak redirects to `/auth/callback?code=...&state=...` after a
 * successful authentication. This page:
 *   1. Pulls `code` + `state` from the URL.
 *   2. Calls {@link exchangeCode} which talks to Keycloak's token endpoint
 *      with the cached PKCE verifier.
 *   3. Persists the resulting access/id/refresh tokens via
 *      {@link applyOidcSessionThunk} (which mirrors the data into the Redux
 *      `auth` slice + localStorage).
 *   4. Navigates to the original deep-link the user was trying to reach,
 *      or `/` if none was captured.
 *
 * On any error (state mismatch, network, Keycloak refused) the error is
 * surfaced inline with a "back to login" link — we do NOT silently
 * re-redirect because that masks misconfiguration.
 *
 * The page guards against React 18 strict-mode double-mount via a ref so
 * the auth code (single-use) is not consumed twice.
 */
export default function OidcCallbackPage() {
  const router = useRouter();
  const params = useSearchParams();
  const dispatch = useAppDispatch();
  const [error, setError] = useState(null);
  const ran = useRef(false);

  useEffect(() => {
    if (ran.current) return;
    ran.current = true;

    const code = params.get('code');
    const state = params.get('state');
    const oidcError = params.get('error');

    if (oidcError) {
      const desc = params.get('error_description');
      setError(desc ? `${oidcError}: ${desc}` : oidcError);
      return;
    }
    if (!code || !state) {
      setError('Missing code or state on OIDC callback URL');
      return;
    }

    (async () => {
      try {
        const tokenResponse = await exchangeCode({ code, state });
        dispatch(applyOidcSessionThunk(tokenResponse));
        const dest = consumeReturnTo();
        router.replace(dest || '/');
      } catch (e) {
        setError(e instanceof Error ? e.message : String(e));
      }
    })();
  }, [params, router, dispatch]);

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
      <Stack spacing={2} alignItems="center" sx={{ maxWidth: 480, width: '100%' }}>
        {error ? (
          <>
            <Alert severity="error" variant="filled" sx={{ width: '100%' }}>
              {error}
            </Alert>
            <Typography variant="body2" color="text.secondary">
              <a href="/login">Back to login</a>
            </Typography>
          </>
        ) : (
          <>
            <CircularProgress />
            <Typography variant="body2" color="text.secondary">
              Completing sign-in…
            </Typography>
          </>
        )}
      </Stack>
    </Box>
  );
}
