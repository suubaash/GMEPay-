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
import IconButton from '@mui/material/IconButton';
import Badge from '@mui/material/Badge';
import Avatar from '@mui/material/Avatar';
import Menu from '@mui/material/Menu';
import MenuItem from '@mui/material/MenuItem';
import ListItemIcon from '@mui/material/ListItemIcon';
import ListItemText from '@mui/material/ListItemText';
import Divider from '@mui/material/Divider';
import Tooltip from '@mui/material/Tooltip';
import NotificationsNoneIcon from '@mui/icons-material/NotificationsNone';
import DarkModeIcon from '@mui/icons-material/DarkMode';
import LightModeIcon from '@mui/icons-material/LightMode';
import LogoutIcon from '@mui/icons-material/Logout';
import { useDispatch, useSelector } from 'react-redux';
import { logoutAction } from '@/store/authSlice';
import { toggleMode } from '@/store/uiSlice';
import { currentPartnerId } from '@/api/client';
import { TOKEN_KEY, PARTNER_ID_KEY } from '@/api/auth';
import { useSnackbar } from '@/components/SnackbarProvider';

/**
 * Authenticated app chrome — top app-bar with brand mark, primary nav,
 * notifications bell, and a user avatar menu (dark-mode toggle + sign-out).
 *
 * The partner identifier in the top-right comes from Redux (set by login or
 * by `hydrateFromStorage`) with a fall-through to the localStorage helper
 * (so it shows up even before the AuthGate has run).
 *
 * Sign-out clears the auth tokens from localStorage (defensive — the
 * `logoutAction` reducer also calls `authLogout()`, but we make the
 * side-effect explicit here so the contract is obvious from this file),
 * dispatches the redux logout action, and redirects to `/login`.
 */
const NAV = [
  { label: 'Overview', href: '/' },
  { label: 'Balance', href: '/balance' },
  { label: 'Transactions', href: '/transactions' },
  { label: 'API Keys', href: '/api-keys' },
  { label: 'Statement', href: '/statement' },
  { label: 'Webhooks', href: '/webhooks' },
  { label: 'Profile', href: '/profile' }
];

function clearAuthStorage() {
  if (typeof window === 'undefined') return;
  try {
    window.localStorage.removeItem(TOKEN_KEY);
    window.localStorage.removeItem(PARTNER_ID_KEY);
  } catch {
    // best-effort
  }
}

export default function AppShell({ children }) {
  const router = useRouter();
  const dispatch = useDispatch();
  const snackbar = useSnackbar();
  const reduxPartnerId = useSelector((s) => s.auth?.partnerId);
  const mode = useSelector((s) => s.ui?.mode ?? 'light');
  const partnerId = reduxPartnerId || currentPartnerId() || 'unknown-partner';

  const [anchorEl, setAnchorEl] = React.useState(null);
  const menuOpen = Boolean(anchorEl);
  const openMenu = (e) => setAnchorEl(e.currentTarget);
  const closeMenu = () => setAnchorEl(null);

  const handleToggleMode = () => {
    dispatch(toggleMode());
    closeMenu();
  };

  const handleSignOut = () => {
    clearAuthStorage();
    dispatch(logoutAction());
    snackbar.showInfo('Signed out');
    closeMenu();
    router.replace('/login');
  };

  const initial = (partnerId || '?').charAt(0).toUpperCase();

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
            GMEPay<span style={{ color: mode === 'dark' ? '#E2E8F0' : '#0F172A' }}>+</span>
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

          <Box sx={{ textAlign: 'center', mr: 1 }}>
            <Typography variant="caption" sx={{ display: 'block', color: 'text.secondary' }}>
              Logged in as
            </Typography>
            <Typography variant="body2" sx={{ fontWeight: 600 }} data-testid="appshell-partner-id">
              {partnerId}
            </Typography>
          </Box>

          <Tooltip title="Notifications">
            <IconButton
              size="small"
              aria-label="notifications"
              data-testid="notifications-button"
              sx={{ color: 'text.primary' }}
            >
              <Badge color="primary" variant="dot" invisible>
                <NotificationsNoneIcon />
              </Badge>
            </IconButton>
          </Tooltip>

          <Tooltip title="Account">
            <IconButton
              size="small"
              onClick={openMenu}
              aria-label="user menu"
              aria-haspopup="menu"
              aria-controls={menuOpen ? 'user-menu' : undefined}
              aria-expanded={menuOpen ? 'true' : undefined}
              data-testid="user-menu-button"
            >
              <Avatar
                sx={{ width: 32, height: 32, bgcolor: 'primary.main', fontSize: '0.85rem' }}
              >
                {initial}
              </Avatar>
            </IconButton>
          </Tooltip>

          <Menu
            id="user-menu"
            anchorEl={anchorEl}
            open={menuOpen}
            onClose={closeMenu}
            anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
            transformOrigin={{ vertical: 'top', horizontal: 'right' }}
            MenuListProps={{ 'aria-labelledby': 'user-menu-button' }}
          >
            <MenuItem disabled>
              <ListItemText
                primary={partnerId}
                secondary="Partner account"
                primaryTypographyProps={{ fontWeight: 600 }}
              />
            </MenuItem>
            <Divider />
            <MenuItem onClick={handleToggleMode} data-testid="toggle-mode-menuitem">
              <ListItemIcon>
                {mode === 'dark' ? <LightModeIcon fontSize="small" /> : <DarkModeIcon fontSize="small" />}
              </ListItemIcon>
              <ListItemText>
                {mode === 'dark' ? 'Switch to light mode' : 'Switch to dark mode'}
              </ListItemText>
            </MenuItem>
            <MenuItem onClick={handleSignOut} data-testid="signout-menuitem">
              <ListItemIcon>
                <LogoutIcon fontSize="small" />
              </ListItemIcon>
              <ListItemText>Sign out</ListItemText>
            </MenuItem>
          </Menu>
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
