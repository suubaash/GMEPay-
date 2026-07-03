/**
 * Next.js config for the GMEPay+ Ops/Admin Portal.
 *
 * The portal does NOT call backend microservices directly. All /api/* traffic is
 * rewritten to the Ops/Partner BFF (see docs/INTER_SERVICE_CONTRACTS.md — the BFF
 * row), whose base URL is provided via NEXT_PUBLIC_BFF_BASE_URL.
 */
// Default to the IPv4 loopback (not "localhost"): on Windows/Node the rewrite proxy resolves
// "localhost" to ::1 first, but the BFF (Tomcat) binds IPv4, so the server-side proxy 500s.
const bffBaseUrl = process.env.NEXT_PUBLIC_BFF_BASE_URL || 'http://127.0.0.1:8095';

// The Nepal QR sandbox tab is a NATIVE admin-ui page (not an iframe) whose data
// calls go through this SAME-ORIGIN rewrite. The Next node server ("npm start")
// proxies /sim-nepal-qr/* to the sim, so it works when the admin is reached
// remotely (e.g. over a Cloudflare tunnel) where a client-side localhost iframe
// would resolve to the viewer's own machine and show nothing.
// Server-side env only (NOT NEXT_PUBLIC); default to IPv4 loopback for the same
// reason as the BFF rewrite above (Node resolves "localhost" to ::1 first).
const simNepalQrUrl = process.env.SIM_NEPAL_QR_URL || 'http://127.0.0.1:9103';

// The E2E Test sandbox tab is a NATIVE admin-ui page whose data calls go through
// this SAME-ORIGIN rewrite to the payment-executor's sandbox e2e API, so it works
// over the Cloudflare tunnel too (see the Nepal QR note above). Server-side env
// only; default to IPv4 loopback for the same "localhost → ::1" reason.
const paymentExecutorUrl = process.env.PAYMENT_EXECUTOR_URL || 'http://127.0.0.1:18084';

// The three ZeroPay sandbox tabs (Merchant Terminal / Wallet / Rate Board) are
// still IFRAMES. Proxy them same-origin (like the rewrites above) so they render
// over the public tunnel from any PC. Distinct API prefixes, no collision with /api/*.
const simMerchant = process.env.SIM_MERCHANT_PROXY || 'http://127.0.0.1:9104';
const simWallet   = process.env.SIM_WALLET_PROXY   || 'http://127.0.0.1:9105';
const simRates    = process.env.SIM_RATE_PROXY     || 'http://127.0.0.1:9101';

/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  // Keep trailing slash on /sims/<x>/ (don't 308-redirect) so the iframed sims'
  // relative assets resolve under the subpath. App-wide but benign.
  skipTrailingSlashRedirect: true,
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
      {
        source: '/e2e/:path*',
        destination: `${paymentExecutorUrl}/v1/sandbox/e2e/:path*`,
      },
      // --- ZeroPay sandbox sim UIs served under same-origin subpaths ---
      { source: '/sims/merchant/:path*', destination: `${simMerchant}/:path*` },
      { source: '/sims/wallet/:path*',   destination: `${simWallet}/:path*` },
      { source: '/sims/rates/:path*',    destination: `${simRates}/:path*` },
      // --- their APIs (called root-absolute from inside each sim page) ---
      { source: '/v1/merchant/:path*',   destination: `${simMerchant}/v1/merchant/:path*` },
      { source: '/v1/gmeremit',          destination: `${simWallet}/v1/gmeremit` },
      { source: '/v1/gmeremit/:path*',   destination: `${simWallet}/v1/gmeremit/:path*` },
      { source: '/v1/rates',             destination: `${simRates}/v1/rates` },
      { source: '/v1/rates/:path*',      destination: `${simRates}/v1/rates/:path*` },
    ];
  },
};

export default nextConfig;
