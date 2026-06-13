'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { useForm } from 'react-hook-form';
import { yupResolver } from '@hookform/resolvers/yup';
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  CircularProgress,
  Divider,
  Snackbar,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import LoginIcon from '@mui/icons-material/Login';
import LockIcon from '@mui/icons-material/Lock';
import { loginSchema } from '@/schemas/loginSchema';
import { useAppDispatch, useAppSelector } from '@/store';
import { loginThunk } from '@/store/authSlice';
import { isDevLoginAllowed, startLogin } from '@/api/oidc';

/**
 * Operator login page.
 *
 * As of Slice 1 (PARTNER_SETUP_PLAN.md) the legacy `password=demo` form is
 * retired in favour of Keycloak SSO via OIDC authorization-code + PKCE
 * (ADR-011). The primary affordance on this page is now a single
 * "Sign in with Keycloak" button that redirects the browser to the realm
 * configured by `NEXT_PUBLIC_KEYCLOAK_URL` (default
 * `http://localhost:8090/realms/gmepay`).
 *
 * Dev escape hatch: when `NEXT_PUBLIC_ALLOW_DEV_LOGIN=true` is set at build
 * time the legacy username/password form is rendered underneath the SSO
 * button so vitest + local-no-Keycloak iteration still work. The form
 * dispatches the original {@link loginThunk} against the BFF
 * `/v1/auth/login` endpoint (which the BFF will eventually retire once the
 * gateway is the only resource server).
 */
export default function LoginPage() {
  const router = useRouter();
  const dispatch = useAppDispatch();
  const { loading, error } = useAppSelector((s) => s.auth);
  const [snackOpen, setSnackOpen] = useState(false);
  const [ssoPending, setSsoPending] = useState(false);
  const devAllowed = isDevLoginAllowed();

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm({
    resolver: yupResolver(loginSchema),
    defaultValues: { username: '', password: '' },
  });

  const handleSso = async () => {
    setSsoPending(true);
    try {
      await startLogin('/');
      // startLogin navigates the browser away; if it returns we hit an
      // error path (no window etc.) — flip pending back so the button
      // recovers.
    } finally {
      setSsoPending(false);
    }
  };

  const onSubmit = async (values) => {
    const result = await dispatch(loginThunk(values));
    if (loginThunk.fulfilled.match(result)) {
      router.replace('/');
    } else {
      setSnackOpen(true);
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
              aria-label="Sign in with Keycloak"
            >
              Sign in with Keycloak
            </Button>
            <Typography variant="caption" color="text.secondary" sx={{ textAlign: 'center' }}>
              Single sign-on via the GMEPay+ identity provider.
            </Typography>

            {devAllowed && (
              <>
                <Divider>
                  <Typography variant="caption" color="text.secondary">
                    Dev: skip login
                  </Typography>
                </Divider>

                <Alert severity="warning" variant="outlined" sx={{ py: 0.5 }}>
                  This form is only available when
                  <code style={{ marginLeft: 4, marginRight: 4 }}>
                    NEXT_PUBLIC_ALLOW_DEV_LOGIN=true
                  </code>
                  and is intended for local iteration only.
                </Alert>

                <Box component="form" onSubmit={handleSubmit(onSubmit)} noValidate>
                  <Stack spacing={2}>
                    <TextField
                      label="Username"
                      fullWidth
                      autoComplete="username"
                      {...register('username')}
                      error={!!errors.username}
                      helperText={errors.username?.message}
                      inputProps={{ 'aria-label': 'Username' }}
                    />
                    <TextField
                      label="Password"
                      type="password"
                      fullWidth
                      autoComplete="current-password"
                      {...register('password')}
                      error={!!errors.password}
                      helperText={errors.password?.message}
                      inputProps={{ 'aria-label': 'Password' }}
                    />

                    <Button
                      type="submit"
                      variant="outlined"
                      size="large"
                      startIcon={
                        loading || isSubmitting ? (
                          <CircularProgress size={18} color="inherit" />
                        ) : (
                          <LoginIcon />
                        )
                      }
                      disabled={loading || isSubmitting}
                      fullWidth
                    >
                      Dev sign in (BFF)
                    </Button>
                  </Stack>
                </Box>
              </>
            )}
          </Stack>
        </CardContent>
      </Card>

      <Snackbar
        open={snackOpen}
        autoHideDuration={6000}
        onClose={() => setSnackOpen(false)}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
      >
        <Alert
          severity="error"
          onClose={() => setSnackOpen(false)}
          variant="filled"
        >
          {error ?? 'Login failed'}
        </Alert>
      </Snackbar>
    </Box>
  );
}
