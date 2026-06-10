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
  Snackbar,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import LoginIcon from '@mui/icons-material/Login';
import { loginSchema } from '@/schemas/loginSchema';
import { useAppDispatch, useAppSelector } from '@/store';
import { loginThunk } from '@/store/authSlice';

/**
 * Operator login page.
 *
 * Form fields: username + password (RHF + Yup validation).
 * On submit, dispatches loginThunk which:
 *   1. POSTs to /v1/auth/login on the BFF.
 *   2. Stores the returned JWT under "gmepay.adminToken" in localStorage.
 *   3. Caches the form username under "gmepay.adminUser" for the AppShell.
 *      (The BFF response is { token, expiresAt, role } — it does NOT echo
 *       the username back.)
 *
 * On success, redirects to `/`. On error, a MUI Snackbar surfaces the message.
 *
 * Dev credentials: username=admin, password=demo.
 */
export default function LoginPage() {
  const router = useRouter();
  const dispatch = useAppDispatch();
  const { loading, error } = useAppSelector((s) => s.auth);
  const [snackOpen, setSnackOpen] = useState(false);

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm({
    resolver: yupResolver(loginSchema),
    defaultValues: { username: '', password: '' },
  });

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
                Sign in with your operator credentials.
              </Typography>
            </Box>

            <Box component="form" onSubmit={handleSubmit(onSubmit)} noValidate>
              <Stack spacing={2}>
                <TextField
                  label="Username"
                  fullWidth
                  required
                  autoFocus
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
                  required
                  autoComplete="current-password"
                  {...register('password')}
                  error={!!errors.password}
                  helperText={errors.password?.message}
                  inputProps={{ 'aria-label': 'Password' }}
                />

                <Button
                  type="submit"
                  variant="contained"
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
                  Sign in
                </Button>

                <Typography variant="caption" color="text.secondary" sx={{ textAlign: 'center' }}>
                  Dev credentials: admin / demo
                </Typography>
              </Stack>
            </Box>
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
