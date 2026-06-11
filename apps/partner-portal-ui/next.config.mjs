/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  poweredByHeader: false,
  experimental: {
    // MUI v6 + emotion needs this in app router for SSR style flush
    optimizePackageImports: ['@mui/material', '@mui/icons-material']
  },
  async rewrites() {
    const bff = process.env.NEXT_PUBLIC_BFF_BASE_URL || 'http://localhost:8095';
    return [
      {
        source: '/api/:path*',
        destination: `${bff}/:path*`
      }
    ];
  }
};

export default nextConfig;
