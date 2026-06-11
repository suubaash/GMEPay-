'use client';
import * as React from 'react';
import dynamic from 'next/dynamic';
import Box from '@mui/material/Box';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import emptyAnimation from '@/lottie/empty.json';

// Lottie is browser-only — import lazily so it doesn't break SSR.
const Lottie = dynamic(() => import('lottie-react'), { ssr: false });

/**
 * Friendly empty-state panel with a small Lottie animation.
 *
 * @param {{ title: string, message?: string, action?: React.ReactNode, size?: number }} props
 */
export default function EmptyState({ title, message, action, size = 160 }) {
  return (
    <Box
      data-testid="empty-state"
      sx={{
        py: 4,
        px: 2,
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        textAlign: 'center'
      }}
    >
      <Box sx={{ width: size, height: size, mb: 1 }} aria-hidden>
        <Lottie animationData={emptyAnimation} loop autoplay />
      </Box>
      <Stack spacing={0.5} alignItems="center">
        <Typography variant="h4" component="p">
          {title}
        </Typography>
        {message && (
          <Typography variant="body2" sx={{ color: 'text.secondary', maxWidth: 360 }}>
            {message}
          </Typography>
        )}
        {action && <Box sx={{ mt: 1 }}>{action}</Box>}
      </Stack>
    </Box>
  );
}
