import type { Metadata } from 'next';
import ReduxProvider from '@/components/ReduxProvider';
import ThemeRegistry from '@/components/ThemeRegistry';
import AppShell from '@/components/AppShell';

export const metadata: Metadata = {
  title: 'GMEPay+ Ops',
  description: 'GMEPay+ Ops/Admin Portal',
};

/**
 * Root layout for the App Router tree. Order matters:
 *  - ReduxProvider must wrap ThemeRegistry so theme-aware client components
 *    can dispatch actions.
 *  - AppShell provides the persistent navigation chrome.
 */
export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>
        <ReduxProvider>
          <ThemeRegistry>
            <AppShell>{children}</AppShell>
          </ThemeRegistry>
        </ReduxProvider>
      </body>
    </html>
  );
}
