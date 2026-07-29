'use client';

/**
 * E2E Test tab — the automated payment-journey runner, DISABLED by design.
 *
 * What this used to be: a full console (country/partner/amount/MPM form, run
 * button, step-by-step pass/fail log, run history) that called the
 * payment-executor's sandbox runner over SAME-ORIGIN `/e2e/...` paths, proxied by
 * a Next rewrite (`PAYMENT_EXECUTOR_URL` → `/v1/sandbox/e2e/*`).
 *
 * Why it is gone (GAP T0-5):
 *   1. `POST /v1/sandbox/e2e/run` is not a read-only test helper. It drives a REAL
 *      authorize+capture through the real pay path — prefunding debit, scheme call,
 *      ledger postings. It was an always-on, unauthenticated "spend money" button.
 *   2. payment-executor now serves that surface only under
 *      `gmepay.sandbox.e2e.enabled=true` (@ConditionalOnProperty → **404** when off,
 *      which is the default in every shipped config), and requires the internal
 *      `X-Gme-Internal` token when on.
 *   3. The admin portal's `/e2e/:path*` rewrite was deleted from next.config.mjs.
 *      It was a same-origin proxy from wherever the portal is reachable (including
 *      the Cloudflare tunnel) straight to that runner, and it bypassed the BFF's
 *      OIDC boundary by design.
 *
 * So there is no browser path to the runner any more, in any deployment. Rather
 * than keep ~500 lines of fetch code that can only ever render "couldn't reach the
 * test runner — is the fleet running?" (a false diagnosis: the fleet is fine, the
 * surface is deliberately off), this tab now states the situation and how to drive
 * the journey from a dev machine. No network calls are made from this component.
 *
 * If the tab should come back as a product feature it belongs behind the BFF
 * (`/api/*` → ops-partner-bff, OIDC-authenticated + RBAC-checked, internal token
 * held server-side) — never as a browser-to-payment-executor proxy.
 */

import { Alert, AlertTitle, Box, Paper, Stack, Typography } from '@mui/material';

/** One "how to run it yourself" step. */
function Step({ n, children }) {
  return (
    <Box sx={{ display: 'flex', gap: 1.5 }}>
      <Typography variant="body2" color="text.disabled" sx={{ minWidth: 18 }}>
        {n}.
      </Typography>
      <Typography variant="body2" color="text.secondary">
        {children}
      </Typography>
    </Box>
  );
}

export default function E2eTestConsole() {
  return (
    <Box sx={{ py: 1, maxWidth: 900 }} data-testid="e2e-runner-disabled">
      <Alert severity="info">
        <AlertTitle>Sandbox runner not enabled — this tab is a developer tool</AlertTitle>
        <Typography variant="body2" sx={{ mb: 1 }}>
          The automated end-to-end payment-journey runner is <strong>not reachable from the
          browser</strong>, and that is deliberate — not a fault to report. It executes a real
          authorize + capture (prefunding debit, scheme call, ledger postings), so it is not
          exposed through the admin portal.
        </Typography>
        <Typography variant="body2">
          In payment-executor the surface is off unless{' '}
          <code>gmepay.sandbox.e2e.enabled=true</code> (it answers <strong>404</strong> otherwise,
          which is the default everywhere), and when on it requires the internal{' '}
          <code>X-Gme-Internal</code> token. The portal&apos;s same-origin <code>/e2e/*</code>{' '}
          proxy to it was removed (GAP T0-5).
        </Typography>
      </Alert>

      <Paper variant="outlined" sx={{ p: 2, mt: 2 }}>
        <Typography variant="h6" gutterBottom>
          Running the journey from a dev machine
        </Typography>
        <Stack spacing={0.75}>
          <Step n={1}>
            Start payment-executor locally with{' '}
            <code>-Dgmepay.sandbox.e2e.enabled=true</code> and{' '}
            <code>GMEPAY_INTERNAL_AUTH_SECRET</code> set (it refuses to start with the surface on
            and no secret).
          </Step>
          <Step n={2}>
            Call it directly on loopback — <code>GET /v1/sandbox/e2e/options</code>,{' '}
            <code>POST /v1/sandbox/e2e/run</code>, <code>GET /v1/sandbox/e2e/runs</code> — passing
            the <code>X-Gme-Internal</code> header. Never expose that port.
          </Step>
          <Step n={3}>
            Or walk the journey by hand in the other tabs: Merchant Terminal → GMERemit Wallet →
            Nepal QR, with Service Trace showing the calls between services.
          </Step>
        </Stack>
        <Typography variant="caption" color="text.disabled" component="p" sx={{ mt: 1.5 }}>
          To bring this tab back as a product feature the runner must be fronted by the BFF
          (OIDC-authenticated, RBAC-checked, internal token held server-side) rather than proxied
          from the browser.
        </Typography>
      </Paper>
    </Box>
  );
}
