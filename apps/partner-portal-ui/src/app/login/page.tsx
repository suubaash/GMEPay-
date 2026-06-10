'use client';
import * as React from 'react';
import { useRouter } from 'next/navigation';
import { useForm, Controller } from 'react-hook-form';
import { yupResolver } from '@hookform/resolvers/yup';
import * as yup from 'yup';
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Container,
  Stack,
  TextField,
  Typography,
  CircularProgress
} from '@mui/material';
import { useDispatch, useSelector } from 'react-redux';
import type { AppDispatch, RootState } from '@/store';
import { clearAuthError, hydrateFromStorage, loginThunk } from '@/store/authSlice';
import { useSnackbar } from '@/components/SnackbarProvider';

interface LoginForm {
  partnerId: string;
  password: string;
}

const schema = yup.object({
  partnerId: yup
    .string()
    .trim()
    .required('Partner ID is required')
    .min(2, 'Partner ID must be at least 2 characters')
    .matches(/^[A-Za-z0-9_-]+$/, 'Partner ID may only contain letters, digits, _ and -'),
  password: yup
    .string()
    .required('Password is required')
    .min(4, 'Password must be at least 4 characters')
});

/**
 * Partner login form.
 *
 * Submits to `POST /v1/auth/login`. On success, persists token under
 * `gmepay.partnerToken` + partner id under `gmepay.partnerId` (handled by
 * `api/auth.ts`) and redirects to /. The AuthGate in `layout.tsx` reads the
 * same storage and gates all other routes.
 */
export default function LoginPage() {
  const dispatch = useDispatch<AppDispatch>();
  const router = useRouter();
  const snackbar = useSnackbar();
  const auth = useSelector((s: RootState) => s.auth);

  const { control, handleSubmit, formState } = useForm<LoginForm>({
    resolver: yupResolver(schema),
    defaultValues: { partnerId: '', password: '' },
    mode: 'onTouched'
  });

  React.useEffect(() => {
    dispatch(hydrateFromStorage());
  }, [dispatch]);

  // Already signed in? Bounce to home.
  React.useEffect(() => {
    if (auth.token && auth.partnerId) {
      router.replace('/');
    }
  }, [auth.token, auth.partnerId, router]);

  const onSubmit = async (values: LoginForm) => {
    dispatch(clearAuthError());
    const result = await dispatch(loginThunk(values));
    if (loginThunk.fulfilled.match(result)) {
      snackbar.showSuccess(`Welcome, ${result.payload.partnerId}`);
      router.replace('/');
    } else {
      // The thunk records the error in state; also surface a toast.
      const msg =
        (result.payload as string | undefined) ?? 'Login failed. Please try again.';
      snackbar.showError(msg);
    }
  };

  const submitting = auth.status === 'loading' || formState.isSubmitting;

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
            <Box component="form" onSubmit={handleSubmit(onSubmit)} noValidate>
              <Stack spacing={2}>
                {auth.error && (
                  <Alert
                    severity="error"
                    onClose={() => dispatch(clearAuthError())}
                    data-testid="login-error"
                  >
                    {auth.error}
                  </Alert>
                )}

                <Controller
                  name="partnerId"
                  control={control}
                  render={({ field, fieldState }) => (
                    <TextField
                      {...field}
                      label="Partner ID"
                      placeholder="e.g. GMEREMIT or SENDMN"
                      autoComplete="username"
                      autoFocus
                      required
                      fullWidth
                      error={Boolean(fieldState.error)}
                      helperText={fieldState.error?.message ?? ' '}
                      inputProps={{ 'data-testid': 'partner-id-input' }}
                    />
                  )}
                />

                <Controller
                  name="password"
                  control={control}
                  render={({ field, fieldState }) => (
                    <TextField
                      {...field}
                      label="Password"
                      type="password"
                      autoComplete="current-password"
                      required
                      fullWidth
                      error={Boolean(fieldState.error)}
                      helperText={fieldState.error?.message ?? ' '}
                      inputProps={{ 'data-testid': 'password-input' }}
                    />
                  )}
                />

                <Button
                  type="submit"
                  variant="contained"
                  size="large"
                  disabled={submitting}
                  data-testid="login-submit"
                  startIcon={
                    submitting ? <CircularProgress size={16} color="inherit" /> : null
                  }
                >
                  {submitting ? 'Signing in…' : 'Sign in'}
                </Button>

                <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                  Phase 1 demo credentials: partnerId{' '}
                  <code>GMEREMIT</code> or <code>SENDMN</code>, password{' '}
                  <code>demo</code>. Production deployments wire OAuth2 / partner SSO.
                </Typography>
              </Stack>
            </Box>
          </CardContent>
        </Card>
      </Stack>
    </Container>
  );
}
