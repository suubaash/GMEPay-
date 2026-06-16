'use client';

import {
  AppBar,
  Avatar,
  Box,
  Container,
  Divider,
  Drawer,
  IconButton,
  InputBase,
  List,
  ListItem,
  ListItemButton,
  ListItemIcon,
  ListItemText,
  Menu,
  MenuItem,
  Toolbar,
  Tooltip,
  Typography,
  alpha,
} from '@mui/material';
import RequestInspector from './RequestInspector';
import DashboardIcon from '@mui/icons-material/Dashboard';
import GroupsIcon from '@mui/icons-material/Groups';
import HowToVoteIcon from '@mui/icons-material/HowToVote';
import QrCode2Icon from '@mui/icons-material/QrCode2';
import ReceiptLongIcon from '@mui/icons-material/ReceiptLong';
import AccountBalanceIcon from '@mui/icons-material/AccountBalance';
import TrendingUpIcon from '@mui/icons-material/TrendingUp';
import LogoutIcon from '@mui/icons-material/Logout';
import SearchIcon from '@mui/icons-material/Search';
import SettingsIcon from '@mui/icons-material/Settings';
import NotificationsNoneIcon from '@mui/icons-material/NotificationsNone';
import DarkModeIcon from '@mui/icons-material/DarkMode';
import LightModeIcon from '@mui/icons-material/LightMode';
import ShowChartIcon from '@mui/icons-material/ShowChart';
import HistoryIcon from '@mui/icons-material/History';
import MonitorHeartIcon from '@mui/icons-material/MonitorHeart';
import ScienceIcon from '@mui/icons-material/Science';
import ReportProblemOutlinedIcon from '@mui/icons-material/ReportProblemOutlined';
import ManageAccountsIcon from '@mui/icons-material/ManageAccounts';
import AdminPanelSettingsIcon from '@mui/icons-material/AdminPanelSettings';
import AssessmentIcon from '@mui/icons-material/Assessment';
import ManageSearchIcon from '@mui/icons-material/ManageSearch';
import PolicyIcon from '@mui/icons-material/Policy';
import Link from 'next/link';
import { usePathname, useRouter } from 'next/navigation';
import { useEffect, useState } from 'react';
import { clearAuth, getIdToken, getUsername } from '@/api/auth';
import { logoutUrl as oidcLogoutUrl } from '@/api/oidc';
import { useAppDispatch, useAppSelector } from '@/store';
import { logout as logoutAction } from '@/store/authSlice';
import { toggleMode } from '@/store/uiSlice';

const drawerWidth = 240;

const navItems = [
  { label: 'Dashboard', href: '/', icon: <DashboardIcon /> },
  { label: 'Partners', href: '/partners', icon: <GroupsIcon /> },
  { label: 'Approvals', href: '/approvals', icon: <HowToVoteIcon /> },
  { label: 'Compliance', href: '/compliance', icon: <PolicyIcon /> },
  { label: 'Schemes', href: '/schemes', icon: <QrCode2Icon /> },
  { label: 'Transactions', href: '/transactions', icon: <ReceiptLongIcon /> },
  { label: 'Transaction Search', href: '/transactions/search', icon: <ManageSearchIcon /> },
  { label: 'Settlement', href: '/settlement', icon: <AccountBalanceIcon /> },
  { label: 'Exceptions', href: '/settlement/exceptions', icon: <ReportProblemOutlinedIcon /> },
  { label: 'Revenue', href: '/revenue', icon: <TrendingUpIcon /> },
  { label: 'Reports', href: '/reports', icon: <AssessmentIcon /> },
  { label: 'Rates Preview', href: '/rates', icon: <ShowChartIcon /> },
  { label: 'Audit Log', href: '/audit', icon: <HistoryIcon /> },
  { label: 'Users', href: '/users', icon: <ManageAccountsIcon /> },
  { label: 'RBAC', href: '/rbac', icon: <AdminPanelSettingsIcon /> },
  { label: 'System Health', href: '/system-health', icon: <MonitorHeartIcon /> },
  { label: 'Sandbox', href: '/sandbox', icon: <ScienceIcon /> },
];

/**
 * AppShell — top app-bar + permanent left navigation drawer.
 *
 * Top bar (left to right):
 *   - GMEPay+ Ops brand mark
 *   - search placeholder (non-functional; reserves the slot)
 *   - notifications bell (placeholder)
 *   - settings icon
 *   - user-avatar menu: "Logged in as <username>", divider,
 *     "Toggle dark mode", "Sign out"
 *
 * The dark-mode toggle dispatches uiSlice.toggleMode (which persists to
 * localStorage). Sign out clears auth (token + cached username/role/expiry),
 * dispatches authSlice.logout, and redirects to /login.
 *
 * The active sidebar item is highlighted by URL-prefix match. Main content
 * is rendered inside a MUI Container (maxWidth="xl") so wide pages don't
 * stretch edge-to-edge on large monitors.
 */
export default function AppShell({ children }) {
  const pathname = usePathname();
  const router = useRouter();
  const dispatch = useAppDispatch();
  const mode = useAppSelector((s) => s.ui?.mode ?? 'light');
  const [username, setUsername] = useState(null);
  const [menuAnchor, setMenuAnchor] = useState(null);
  const menuOpen = Boolean(menuAnchor);

  useEffect(() => {
    setUsername(getUsername());
  }, []);

  const openMenu = (e) => setMenuAnchor(e.currentTarget);
  const closeMenu = () => setMenuAnchor(null);

  const handleToggleMode = () => {
    dispatch(toggleMode());
    closeMenu();
  };

  const handleLogout = () => {
    closeMenu();
    // Capture the OIDC id_token (if any) BEFORE clearing localStorage so we
    // can hint it to Keycloak's end-session endpoint and clear the SSO
    // cookie on the IdP — otherwise the operator can be silently re-logged-in
    // on the next /login click. When there's no id_token (dev-skip path /
    // legacy session), fall back to the in-app /login route.
    const idToken = getIdToken();
    clearAuth();
    dispatch(logoutAction());
    if (idToken) {
      window.location.assign(oidcLogoutUrl(idToken));
    } else {
      router.replace('/login');
    }
  };

  const avatarLetter = (username ?? 'A').charAt(0).toUpperCase();

  return (
    <Box sx={{ display: 'flex', minHeight: '100vh' }}>
      <AppBar
        position="fixed"
        elevation={0}
        sx={(t) => ({
          zIndex: t.zIndex.drawer + 1,
          color: 'text.primary',
          backgroundColor: t.palette.mode === 'dark' ? 'rgba(18,18,24,0.72)' : 'rgba(255,255,255,0.72)',
          backdropFilter: 'blur(14px)',
          WebkitBackdropFilter: 'blur(14px)',
          borderBottom: `1px solid ${t.palette.divider}`,
          boxShadow: '0 4px 24px rgba(16,24,40,0.06)',
          '&::before': {
            content: '""',
            position: 'absolute',
            top: 0,
            left: 0,
            right: 0,
            height: 3,
            background: 'linear-gradient(90deg,#FF4D63,#E11B2E)',
          },
        })}
      >
        <Toolbar>
          <Typography
            variant="h6"
            noWrap
            component={Link}
            href="/"
            sx={{
              fontWeight: 800,
              textDecoration: 'none',
              mr: 3,
              letterSpacing: '-0.02em',
              display: 'flex',
              alignItems: 'center',
              gap: 1,
            }}
            aria-label="GMEPay+ Ops home"
          >
            <Box
              component="span"
              sx={{
                width: 28,
                height: 28,
                borderRadius: '9px',
                background: 'linear-gradient(135deg,#FF4D63,#E11B2E)',
                boxShadow: '0 4px 12px rgba(225,27,46,.45)',
                display: 'inline-flex',
                alignItems: 'center',
                justifyContent: 'center',
                color: '#fff',
                fontSize: 15,
                fontWeight: 900,
              }}
            >
              G
            </Box>
            <Box
              component="span"
              sx={{
                background: 'linear-gradient(135deg,#FF4D63,#E11B2E)',
                WebkitBackgroundClip: 'text',
                WebkitTextFillColor: 'transparent',
                backgroundClip: 'text',
              }}
            >
              GMEPay+
            </Box>
            <Box component="span" sx={{ color: 'text.secondary', fontWeight: 700 }}>
              Ops
            </Box>
          </Typography>

          {/* Search placeholder (no real data; reserves the slot). */}
          <Box
            sx={(t) => ({
              position: 'relative',
              borderRadius: 999,
              border: `1px solid ${t.palette.divider}`,
              backgroundColor: alpha(t.palette.text.primary, 0.04),
              '&:hover': {
                backgroundColor: alpha(t.palette.text.primary, 0.07),
                borderColor: 'rgba(225,27,46,0.3)',
              },
              transition: 'background-color .2s, border-color .2s',
              mr: 2,
              ml: 0,
              flexGrow: 1,
              maxWidth: 460,
              display: 'flex',
              alignItems: 'center',
              pl: 1.75,
              py: 0.25,
            })}
          >
            <SearchIcon fontSize="small" />
            <InputBase
              placeholder="Search…"
              inputProps={{ 'aria-label': 'search placeholder' }}
              sx={{ color: 'inherit', ml: 1, flex: 1 }}
              disabled
            />
          </Box>

          <Box sx={{ flexGrow: 1 }} />

          <Tooltip title="Notifications">
            <span>
              <IconButton
                color="inherit"
                size="large"
                aria-label="notifications"
                disabled
              >
                <NotificationsNoneIcon />
              </IconButton>
            </span>
          </Tooltip>

          <Tooltip title="Settings">
            <span>
              <IconButton
                color="inherit"
                size="large"
                aria-label="settings"
                disabled
              >
                <SettingsIcon />
              </IconButton>
            </span>
          </Tooltip>

          <Tooltip title="Account">
            <IconButton
              color="inherit"
              size="large"
              onClick={openMenu}
              aria-label="user menu"
              aria-controls={menuOpen ? 'user-menu' : undefined}
              aria-haspopup="true"
              aria-expanded={menuOpen ? 'true' : undefined}
            >
              <Avatar sx={{ width: 34, height: 34, background: 'linear-gradient(135deg,#FF4D63,#E11B2E)', boxShadow: '0 4px 10px rgba(225,27,46,.35)' }}>
                {avatarLetter}
              </Avatar>
            </IconButton>
          </Tooltip>
          <Menu
            id="user-menu"
            anchorEl={menuAnchor}
            open={menuOpen}
            onClose={closeMenu}
            anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
            transformOrigin={{ vertical: 'top', horizontal: 'right' }}
          >
            <MenuItem disabled sx={{ opacity: '1 !important' }}>
              <Typography variant="body2" color="text.secondary">
                Logged in as <b>{username ?? 'unknown'}</b>
              </Typography>
            </MenuItem>
            <Divider />
            <MenuItem onClick={handleToggleMode} aria-label="toggle dark mode">
              <ListItemIcon>
                {mode === 'dark' ? <LightModeIcon fontSize="small" /> : <DarkModeIcon fontSize="small" />}
              </ListItemIcon>
              <ListItemText>
                {mode === 'dark' ? 'Switch to light mode' : 'Toggle dark mode'}
              </ListItemText>
            </MenuItem>
            <MenuItem onClick={handleLogout} aria-label="sign out">
              <ListItemIcon>
                <LogoutIcon fontSize="small" />
              </ListItemIcon>
              <ListItemText>Sign out</ListItemText>
            </MenuItem>
          </Menu>
        </Toolbar>
      </AppBar>
      <Drawer
        variant="permanent"
        sx={{
          width: drawerWidth,
          flexShrink: 0,
          [`& .MuiDrawer-paper`]: {
            width: drawerWidth,
            boxSizing: 'border-box',
          },
        }}
      >
        <Toolbar />
        <Box sx={{ overflow: 'auto' }}>
          <List>
            {navItems.map((item) => {
              const selected =
                item.href === '/'
                  ? pathname === '/'
                  : pathname?.startsWith(item.href);
              return (
                <ListItem key={item.href} disablePadding>
                  <ListItemButton
                    component={Link}
                    href={item.href}
                    selected={!!selected}
                  >
                    <ListItemIcon>{item.icon}</ListItemIcon>
                    <ListItemText primary={item.label} />
                  </ListItemButton>
                </ListItem>
              );
            })}
          </List>
        </Box>
      </Drawer>
      <Box component="main" sx={{ flexGrow: 1, p: { xs: 2, md: 3 }, minWidth: 0 }}>
        <Toolbar />
        <Container maxWidth="xl" disableGutters>
          <Box key={pathname} sx={{ animation: 'gmeFadeUp .45s cubic-bezier(.2,.7,.2,1)' }}>
            {children}
          </Box>
        </Container>
      </Box>
      <RequestInspector />
    </Box>
  );
}
