'use client';

import { usePathname } from 'next/navigation';
import AppShell from './AppShell';

/**
 * Conditionally wraps children in the {@link AppShell} navigation chrome.
 *
 * The /login route is excluded — login is a full-page centered card with no
 * sidebar or top bar. Everything else gets the standard shell.
 */
export default function AppShellChrome({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  if (pathname === '/login') {
    return <>{children}</>;
  }
  return <AppShell>{children}</AppShell>;
}
