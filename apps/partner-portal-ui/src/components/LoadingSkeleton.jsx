'use client';
import * as React from 'react';
import Box from '@mui/material/Box';
import Skeleton from '@mui/material/Skeleton';
import Stack from '@mui/material/Stack';

/**
 * Centralised loading state. Uses Skeletons rather than spinners so the page
 * reserves space and layout doesn't jump when data arrives.
 *
 * @param {{ variant?: 'card'|'table'|'detail'|'stat', rows?: number }} props
 */
export default function LoadingSkeleton({ variant = 'card', rows = 3 }) {
  switch (variant) {
    case 'stat':
      return (
        <Stack spacing={1} data-testid="loading-skeleton">
          <Skeleton variant="text" width={120} height={16} />
          <Skeleton variant="text" width={180} height={40} />
        </Stack>
      );
    case 'detail':
      return (
        <Stack spacing={2} data-testid="loading-skeleton">
          <Skeleton variant="rectangular" height={120} sx={{ borderRadius: 1 }} />
          <Skeleton variant="rectangular" height={80} sx={{ borderRadius: 1 }} />
          <Skeleton variant="rectangular" height={200} sx={{ borderRadius: 1 }} />
        </Stack>
      );
    case 'table':
      return (
        <Stack spacing={1} data-testid="loading-skeleton">
          <Skeleton variant="rectangular" height={40} sx={{ borderRadius: 1 }} />
          {Array.from({ length: rows }).map((_, i) => (
            <Skeleton key={i} variant="rectangular" height={36} sx={{ borderRadius: 1 }} />
          ))}
        </Stack>
      );
    case 'card':
    default:
      return (
        <Box data-testid="loading-skeleton">
          <Stack spacing={1.5}>
            {Array.from({ length: rows }).map((_, i) => (
              <Skeleton
                key={i}
                variant="rectangular"
                height={i === 0 ? 28 : 18}
                width={i === 0 ? '40%' : '100%'}
                sx={{ borderRadius: 1 }}
              />
            ))}
          </Stack>
        </Box>
      );
  }
}
