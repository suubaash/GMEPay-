'use client';

import {
  AppBar,
  Box,
  Button,
  Drawer,
  List,
  ListItem,
  ListItemButton,
  ListItemIcon,
  ListItemText,
  Toolbar,
  Typography,
} from '@mui/material';
import DashboardIcon from '@mui/icons-material/Dashboard';
import GroupsIcon from '@mui/icons-material/Groups';
import QrCode2Icon from '@mui/icons-material/QrCode2';
import ReceiptLongIcon from '@mui/icons-material/ReceiptLong';
import AccountBalanceIcon from '@mui/icons-material/AccountBalance';
import TrendingUpIcon from '@mui/icons-material/TrendingUp';
import LogoutIcon from '@mui/icons-material/Logout';
import Link from 'next/link';
import { usePathname, useRouter } from 'next/navigation';
import { useEffect, useState } from 'react';
import { clearAuth, getUsername } from '@/api/auth';
import { useAppDispatch } from '@/store';
import { logout as logoutAction } from '@/store/authSlice';

const drawerWidth = 240;

interface NavItem {
  label: string;
  href: string;
  icon: React.ReactNode;
}

const navItems: NavItem[] = [
  { label: 'Dashboard', href: '/', icon: <DashboardIcon /> },
  { label: 'Partners', href: '/partners', icon: <GroupsIcon /> },
  { label: 'Schemes', href: '/schemes', icon: <QrCode2Icon /> },
  { label: 'Transactions', href: '/transactions', icon: <ReceiptLongIcon /> },
  { label: 'Settlement', href: '/settlement', icon: <AccountBalanceIcon /> },
  { label: 'Revenue', href: '/revenue', icon: <TrendingUpIcon /> },
];

/**
 * Top app-bar + permanent left navigation drawer for the Ops/Admin Portal.
 * The current section is highlighted by matching the URL pathname prefix.
 *
 * The app bar also shows the signed-in username (read from localStorage so
 * the value survives a page refresh without an extra round-trip) and a
 * logout button that clears the token and redirects to /login.
 */
export default function AppShell({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const dispatch = useAppDispatch();
  // username is only available client-side; read it after mount to avoid SSR mismatch.
  const [username, setUsername] = useState<string | null>(null);
  useEffect(() => {
    setUsername(getUsername());
  }, []);

  const handleLogout = () => {
    clearAuth();
    dispatch(logoutAction());
    router.replace('/login');
  };

  return (
    <Box sx={{ display: 'flex', minHeight: '100vh' }}>
      <AppBar
        position="fixed"
        sx={{ zIndex: (t) => t.zIndex.drawer + 1 }}
        elevation={0}
      >
        <Toolbar>
          <Typography variant="h6" noWrap component="div" sx={{ fontWeight: 700, flexGrow: 1 }}>
            GMEPay+ Ops
          </Typography>
          {username ? (
            <Typography variant="body2" sx={{ mr: 2, opacity: 0.85 }}>
              {username}
            </Typography>
          ) : null}
          <Button
            color="inherit"
            size="small"
            startIcon={<LogoutIcon />}
            onClick={handleLogout}
          >
            Logout
          </Button>
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
      <Box component="main" sx={{ flexGrow: 1, p: 3, bgcolor: 'background.default' }}>
        <Toolbar />
        {children}
      </Box>
    </Box>
  );
}
