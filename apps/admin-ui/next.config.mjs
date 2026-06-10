/**
 * Next.js config for the GMEPay+ Ops/Admin Portal.
 *
 * The portal does NOT call backend microservices directly. All /api/* traffic is
 * rewritten to the Ops/Partner BFF (see docs/INTER_SERVICE_CONTRACTS.md — the BFF
 * row), whose base URL is provided via NEXT_PUBLIC_BFF_BASE_URL.
 */
const bffBaseUrl = process.env.NEXT_PUBLIC_BFF_BASE_URL || 'http://localhost:8095';

/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  async rewrites() {
    return [
      {
        source: '/api/:path*',
        destination: `${bffBaseUrl}/:path*`,
      },
    ];
  },
};

export default nextConfig;
