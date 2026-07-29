/**
 * Next.js config for the GMEPay+ Ops/Admin Portal.
 *
 * The portal does NOT call backend microservices directly. All /api/* traffic is
 * rewritten to the Ops/Partner BFF (see docs/INTER_SERVICE_CONTRACTS.md — the BFF
 * row).
 *
 * BFF_PROXY_TARGET (server-only) is the supported knob and mirrors
 * partner-portal-ui. NEXT_PUBLIC_BFF_BASE_URL is still honoured as a fallback for
 * older setups, but prefer BFF_PROXY_TARGET: a NEXT_PUBLIC_* value is burned into
 * the browser bundle, and any client code that reads it would start calling the
 * BFF cross-origin — which fails, because the BFF configures no CORS anywhere.
 */
// Default to the IPv4 loopback (not "localhost"): on Windows/Node the rewrite proxy resolves
// "localhost" to ::1 first, but the BFF (Tomcat) binds IPv4, so the server-side proxy 500s.
const bffBaseUrl =
  process.env.BFF_PROXY_TARGET ||
  process.env.NEXT_PUBLIC_BFF_BASE_URL ||
  'http://127.0.0.1:8095';

// The Nepal QR sandbox tab is a NATIVE admin-ui page (not an iframe) whose data
// calls go through this SAME-ORIGIN rewrite. The Next node server ("npm start")
// proxies /sim-nepal-qr/* to the sim, so it works when the admin is reached
// remotely (e.g. over a Cloudflare tunnel) where a client-side localhost iframe
// would resolve to the viewer's own machine and show nothing.
// Server-side env only (NOT NEXT_PUBLIC); default to IPv4 loopback for the same
// reason as the BFF rewrite above (Node resolves "localhost" to ::1 first).
const simNepalQrUrl = process.env.SIM_NEPAL_QR_URL || 'http://127.0.0.1:9103';

// REMOVED (GAP T0-5): a `/e2e/:path*` rewrite to the payment-executor's
// `/v1/sandbox/e2e/*` runner. That runner drives a REAL authorize+capture through
// the real pay path (prefunding debit → scheme call → ledger postings), and the
// rewrite made it reachable from wherever the portal is reachable — including the
// Cloudflare tunnel — as an unauthenticated same-origin proxy that bypassed the
// BFF's OIDC boundary entirely. The runner is now @ConditionalOnProperty
// (404 by default) and internal-token gated when on, so the proxy was already
// dead in a correct deployment; it is gone so the Next server cannot forward a
// client-supplied X-Gme-Internal header the moment someone flips the flag.
// If the E2E tab must come back it belongs behind the BFF (/api/* → ops-partner-bff,
// OIDC + RBAC, internal token held server-side), never as a browser-to-executor proxy.

/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  async rewrites() {
    return [
      {
        source: '/api/:path*',
        destination: `${bffBaseUrl}/:path*`,
      },
      {
        source: '/sim-nepal-qr/:path*',
        destination: `${simNepalQrUrl}/:path*`,
      },
    ];
  },
};

export default nextConfig;
