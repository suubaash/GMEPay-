'use client';

import { useState } from 'react';
import { Box, Tab, Tabs, Typography, Alert } from '@mui/material';
import TerminalIcon from '@mui/icons-material/Terminal';
import ServiceTrace from '@/components/ServiceTrace';
import NepalQrConsole from './NepalQrConsole';

const SIM_MERCHANT_URL =
  process.env.NEXT_PUBLIC_SIM_MERCHANT_URL ?? 'http://localhost:9104';
const SIM_WALLET_URL =
  process.env.NEXT_PUBLIC_SIM_WALLET_URL ?? 'http://localhost:9105';
const SIM_RATE_URL =
  process.env.NEXT_PUBLIC_SIM_RATE_URL ?? 'http://localhost:9101';

// Tabs are either an iframe sim (has `url`) or a native React panel (has
// `component`). The three ZeroPay sims stay iframes; the Nepal QR tab is now a
// NATIVE console (NepalQrConsole) whose data calls go through the same-origin
// /sim-nepal-qr rewrite in next.config.mjs — so it works when the admin portal
// is reached remotely (over a Cloudflare tunnel), where a client-side
// localhost:9103 iframe resolves to the viewer's own machine and shows nothing.
const TABS = [
  {
    label: 'Merchant Terminal',
    url: SIM_MERCHANT_URL,
    sim: 'sim-merchant',
    caption:
      'ZeroPay merchant terminal — register a shop, generate & display the counter QR code, and view payments received.',
  },
  {
    label: 'GMERemit Wallet',
    url: SIM_WALLET_URL,
    sim: 'sim-gmeremit',
    caption:
      'GMERemit customer wallet — scan the QR payload, review the amount, confirm payment, and see the receipt.',
  },
  {
    label: 'FX Rate Board',
    url: SIM_RATE_URL,
    sim: 'sim-rate-provider',
    caption:
      'Live FX rate board — shows the KRW exchange rates the platform uses for settlement calculations.',
  },
  {
    label: 'Nepal QR',
    component: NepalQrConsole,
    sim: 'sim-nepal-qr',
    caption:
      'Nepal QR partner simulator (Khalti/Fonepay) — decode a Nepali QR, enter the amount, pay, and inspect the stored request/response the partner API exchanges with GMEPay+. Native console: data calls are proxied same-origin (/sim-nepal-qr) so it works remotely.',
  },
];

function TabPanel({ children, value, index }) {
  return (
    <Box
      role="tabpanel"
      hidden={value !== index}
      id={`sandbox-tabpanel-${index}`}
      aria-labelledby={`sandbox-tab-${index}`}
      sx={{ flexGrow: 1, display: value === index ? 'flex' : 'none', flexDirection: 'column' }}
    >
      {value === index && children}
    </Box>
  );
}

/**
 * Sandbox Console — embeds the three ZeroPay sandbox simulators (as iframes)
 * side-by-side in tabs, plus a NATIVE Nepal QR console and the Service Trace
 * panel, so an operator can walk through the full payment journey without
 * leaving the admin portal.
 *
 * End-to-end journey:
 *  1. Open "Merchant Terminal": register a shop and generate the counter QR.
 *  2. Copy the QR payload string from the merchant terminal.
 *  3. Switch to "GMERemit Wallet": paste the payload, enter the amount (if
 *     MPM-Static), confirm payment, and see the success receipt.
 *  4. Return to "Merchant Terminal" — the "Payments received" panel updates
 *     with the new transaction.
 *  5. Check "FX Rate Board" any time to see the live KRW rates that feed
 *     the settlement calculation.
 *
 * All three simulators must be started separately:
 *   gradlew -p simulators/<name> bootRun
 */
export default function SandboxPage() {
  const [tab, setTab] = useState(0);

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', minHeight: 'calc(100vh - 112px)' }}>
      <Box sx={{ mb: 2 }}>
        <Typography variant="h1" gutterBottom>
          Simulation Console
        </Typography>
        <Alert severity="info" icon={<TerminalIcon />} sx={{ mb: 2 }}>
          <Typography variant="body2" component="span">
            <strong>End-to-end sandbox journey: </strong>
            (1) Open <em>Merchant Terminal</em> — register a shop and click <em>Display QR</em> to
            show the counter QR code. &nbsp;
            (2) Copy the QR payload. &nbsp;
            (3) Switch to <em>GMERemit Wallet</em> — paste the payload, enter or confirm the
            amount, and tap <em>Pay</em>. &nbsp;
            (4) Return to <em>Merchant Terminal</em> — the <em>Payments received</em> list
            refreshes with the new transaction. &nbsp;
            (5) Consult <em>FX Rate Board</em> at any time to inspect the live KRW rates used
            for settlement. &nbsp;
            (6) Open <em>Nepal QR</em> to decode a Nepali QR (Khalti/Fonepay), pay in NPR, and
            inspect the request/response the partner API exchanges with GMEPay+.
          </Typography>
        </Alert>
      </Box>

      <Box sx={{ borderBottom: 1, borderColor: 'divider' }}>
        <Tabs
          value={tab}
          onChange={(_e, v) => setTab(v)}
          aria-label="Sandbox simulator tabs"
        >
          {TABS.map((t, i) => (
            <Tab
              key={t.label}
              label={t.label}
              id={`sandbox-tab-${i}`}
              aria-controls={`sandbox-tabpanel-${i}`}
            />
          ))}
          <Tab
            key="service-trace"
            label="Service Trace"
            id={`sandbox-tab-${TABS.length}`}
            aria-controls={`sandbox-tabpanel-${TABS.length}`}
          />
        </Tabs>
      </Box>

      {TABS.map((t, i) => {
        const Component = t.component;
        return (
          <TabPanel key={t.label} value={tab} index={i}>
            <Box sx={{ py: 1, px: 0 }}>
              <Typography variant="caption" color="text.secondary">
                {t.caption}
              </Typography>
              <Typography variant="caption" color="text.disabled" sx={{ ml: 1 }}>
                &mdash;{' '}
                <strong>{Component ? 'native console (same-origin proxy)' : t.url}</strong>{' '}
                &nbsp;|&nbsp; start with:{' '}
                <code>gradlew -p simulators/{t.sim} bootRun</code>
              </Typography>
            </Box>
            {Component ? (
              <Component />
            ) : (
              <Box
                component="iframe"
                src={t.url}
                title={t.label}
                data-testid={`iframe-${i}`}
                sx={{
                  border: '1px solid',
                  borderColor: 'divider',
                  borderRadius: 1,
                  width: '100%',
                  height: 'calc(80vh - 56px)',
                  flexGrow: 1,
                }}
              />
            )}
          </TabPanel>
        );
      })}

      <TabPanel key="service-trace" value={tab} index={TABS.length}>
        <ServiceTrace />
      </TabPanel>
    </Box>
  );
}
