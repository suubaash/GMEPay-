'use client';

import { Box, Button, Typography } from '@mui/material';
import Lottie from 'lottie-react';
import Link from 'next/link';
import emptyLottie from '@/lottie/empty.json';

/**
 * Custom App-Router 404 page.
 *
 * Centered Lottie + heading + Home button. Lives at /not-found.jsx so
 * Next.js renders it for any unmatched route under the App Router tree.
 */
export default function NotFound() {
  return (
    <Box
      sx={{
        minHeight: '70vh',
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        textAlign: 'center',
        px: 2,
        py: 6,
      }}
    >
      <Box sx={{ width: 200, height: 200 }} aria-hidden>
        <Lottie animationData={emptyLottie} loop autoplay />
      </Box>
      <Typography variant="h1" sx={{ mt: 2 }}>
        Page not found
      </Typography>
      <Typography color="text.secondary" sx={{ mt: 1, maxWidth: 520 }}>
        The page you&apos;re looking for doesn&apos;t exist, has been moved,
        or you don&apos;t have access to it.
      </Typography>
      <Button
        component={Link}
        href="/"
        variant="contained"
        sx={{ mt: 3 }}
        aria-label="go home"
      >
        Go to dashboard
      </Button>
    </Box>
  );
}
