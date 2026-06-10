import type { Metadata } from 'next';
import ReduxProvider from '@/components/ReduxProvider';
import ThemeRegistry from '@/components/ThemeRegistry';
import AppShellChrome from '@/components/AppShellChrome';
import AuthGate from '@/components/AuthGate';
import SnackbarProvider from '@/components/SnackbarProvider';

export const metadata: Metadata = {
  title: 'GMEPay+ Ops',
  description: 'GMEPay+ Ops/Admin Portal',
};

/**
 * Root layout for the App Router tree. Order matters:
 *  - ReduxProvider     : Redux store must be available to every client component.
 *  - ThemeRegistry     : MUI theme + CssBaseline.
 *  - SnackbarProvider  : app-wide useSnackbar() hook for toast notifications.
 *  - AuthGate          : redirects unauthenticated users to /login (skips /login itself).
 *  - AppShellChrome    : draws the sidebar + top bar EXCEPT on /login.
 */
export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>
        <ReduxProvider>
          <ThemeRegistry>
            <SnackbarProvider>
              <AuthGate>
                <AppShellChrome>{children}</AppShellChrome>
              </AuthGate>
            </SnackbarProvider>
          </ThemeRegistry>
        </ReduxProvider>
      </body>
    </html>
  );
}
