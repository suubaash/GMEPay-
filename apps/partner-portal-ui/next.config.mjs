/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  poweredByHeader: false,
  experimental: {
    // MUI v6 + emotion needs this in app router for SSR style flush
    optimizePackageImports: ['@mui/material', '@mui/icons-material']
  },
  async rewrites() {
    // Proxy target for the /api/* rewrite. BFF_PROXY_TARGET (server-only) lets local
    // dev override just the proxy destination WITHOUT setting NEXT_PUBLIC_BFF_BASE_URL —
    // which would flip the browser client into direct cross-origin calls (the BFF has no
    // CORS, so those 403 on preflight). Falls back to the public var, then the default.
    const bff =
      process.env.BFF_PROXY_TARGET ||
      process.env.NEXT_PUBLIC_BFF_BASE_URL ||
      'http://localhost:8095';
    return [
      {
        source: '/api/:path*',
        destination: `${bff}/:path*`
      }
    ];
  }
};

export default nextConfig;
