'use client';
import * as React from 'react';
import { usePathname } from 'next/navigation';
import AppShell from '@/components/AppShell';
import AuthGate from '@/components/AuthGate';

/** Routes that render bare (no app chrome) — the login page is its own layout. */
const BARE_ROUTES = new Set(['/login']);

/**
 * Wraps the app body with the auth gate and, for authenticated routes, the
 * navigation chrome (AppShell). The login page renders without chrome.
 */
export default function AuthShell({ children }) {
  const pathname = usePathname() ?? '/';
  const bare = BARE_ROUTES.has(pathname);

  return (
    <AuthGate>{bare ? <>{children}</> : <AppShell>{children}</AppShell>}</AuthGate>
  );
}
