'use client';

import {
  AppBar,
  Box,
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
import Link from 'next/link';
import { usePathname } from 'next/navigation';

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
 */
export default function AppShell({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();

  return (
    <Box sx={{ display: 'flex', minHeight: '100vh' }}>
      <AppBar
        position="fixed"
        sx={{ zIndex: (t) => t.zIndex.drawer + 1 }}
        elevation={0}
      >
        <Toolbar>
          <Typography variant="h6" noWrap component="div" sx={{ fontWeight: 700 }}>
            GMEPay+ Ops
          </Typography>
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
