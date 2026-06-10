import * as React from 'react';
import Providers from './providers';
import AuthShell from '@/components/AuthShell';

export const metadata = {
  title: 'GMEPay+ Partner Portal',
  description: 'Self-service portal for GMEPay+ partners',
  robots: { index: false, follow: false }
};

export const viewport = {
  width: 'device-width',
  initialScale: 1,
  themeColor: '#0B5FFF'
};

export default function RootLayout({ children }) {
  return (
    <html lang="en">
      <body>
        <Providers>
          <AuthShell>{children}</AuthShell>
        </Providers>
      </body>
    </html>
  );
}
