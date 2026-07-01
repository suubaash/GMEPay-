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
