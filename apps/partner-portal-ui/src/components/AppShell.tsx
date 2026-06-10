'use client';
import * as React from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import AppBar from '@mui/material/AppBar';
import Toolbar from '@mui/material/Toolbar';
import Box from '@mui/material/Box';
import Typography from '@mui/material/Typography';
import Button from '@mui/material/Button';
import Container from '@mui/material/Container';
import Stack from '@mui/material/Stack';
import { useDispatch, useSelector } from 'react-redux';
import type { AppDispatch, RootState } from '@/store';
import { logoutAction } from '@/store/authSlice';
import { currentPartnerId } from '@/api/client';
import { useSnackbar } from '@/components/SnackbarProvider';

const NAV: Array<{ label: string; href: string }> = [
  { label: 'Overview', href: '/' },
  { label: 'Balance', href: '/balance' },
  { label: 'Transactions', href: '/transactions' },
  { label: 'Webhooks', href: '/webhooks' },
  { label: 'Profile', href: '/profile' }
];

export default function AppShell({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const dispatch = useDispatch<AppDispatch>();
  const snackbar = useSnackbar();
  const reduxPartnerId = useSelector((s: RootState) => s.auth.partnerId);
  const partnerId = reduxPartnerId || currentPartnerId() || 'unknown-partner';

  const handleSignOut = () => {
    dispatch(logoutAction());
    snackbar.showInfo('Signed out');
    router.replace('/login');
  };

  return (
    <Box sx={{ minHeight: '100vh', display: 'flex', flexDirection: 'column' }}>
      <AppBar position="sticky">
        <Toolbar sx={{ gap: 3 }}>
          <Typography
            component={Link}
            href="/"
            variant="h4"
            sx={{
              color: 'primary.main',
              textDecoration: 'none',
              fontWeight: 700,
              letterSpacing: '-0.01em'
            }}
          >
            GMEPay<span style={{ color: '#0F172A' }}>+</span>
          </Typography>
          <Typography variant="caption" sx={{ color: 'text.secondary' }}>
            Partner Portal
          </Typography>

          <Stack direction="row" spacing={1} sx={{ ml: 4, flexGrow: 1 }}>
            {NAV.map((n) => (
              <Button
                key={n.href}
                component={Link}
                href={n.href}
                size="small"
                sx={{ color: 'text.primary' }}
              >
                {n.label}
              </Button>
            ))}
          </Stack>

          <Stack direction="row" spacing={2} alignItems="center">
            <Box sx={{ textAlign: 'right' }}>
              <Typography variant="caption" sx={{ display: 'block', color: 'text.secondary' }}>
                Partner
              </Typography>
              <Typography variant="body2" sx={{ fontWeight: 600 }}>
                {partnerId}
              </Typography>
            </Box>
            <Button
              size="small"
              variant="outlined"
              onClick={handleSignOut}
              data-testid="signout-button"
            >
              Sign out
            </Button>
          </Stack>
        </Toolbar>
      </AppBar>

      <Container component="main" maxWidth="lg" sx={{ py: 4, flexGrow: 1 }}>
        {children}
      </Container>

      <Box
        component="footer"
        sx={{
          py: 2,
          borderTop: '1px solid',
          borderColor: 'divider',
          textAlign: 'center'
        }}
      >
        <Typography variant="caption" sx={{ color: 'text.secondary' }}>
          GMEPay+ Partner Portal — Read-only Phase 1
        </Typography>
      </Box>
    </Box>
  );
}
