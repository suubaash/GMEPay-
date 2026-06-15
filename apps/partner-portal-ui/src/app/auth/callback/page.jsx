'use client';

import * as React from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { Alert, Box, CircularProgress, Stack, Typography } from '@mui/material';
import { exchangeCode, consumeReturnTo } from '@/api/oidc';
import { useDispatch } from 'react-redux';
import { applyOidcSessionThunk } from '@/store/authSlice';

/**
 * OIDC callback page — Partner Portal.
 *
 * Keycloak redirects here as `/auth/callback?code=...&state=...` after the
 * partner authenticates. This page:
 *   1. Pulls `code` + `state` from the URL query params.
 *   2. Calls {@link exchangeCode} which POSTs to Keycloak's token endpoint
 *      with the cached PKCE verifier and validates the state (CSRF check).
 *   3. Dispatches {@link applyOidcSessionThunk} to persist the tokens into
 *      localStorage + the Redux auth slice.
 *   4. Navigates to the path the partner was originally trying to reach, or
 *      `/` if none was captured.
 *
 * Errors (state mismatch, network failure, Keycloak error response) are
 * surfaced inline with a "Back to login" link rather than silently re-
 * redirecting — silent loops mask misconfiguration.
 *
 * The component guards against React 18 Strict Mode double-mount via a ref
 * so the single-use auth code is not consumed twice.
 */
export default function OidcCallbackPage() {
  const router = useRouter();
  const params = useSearchParams();
  const dispatch = useDispatch();
  const [error, setError] = React.useState(null);
  const ran = React.useRef(false);

  React.useEffect(() => {
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
        await dispatch(applyOidcSessionThunk(tokenResponse));
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
