'use client';

import { Box, Card, CardContent, Skeleton, Stack } from '@mui/material';

export interface LoadingSkeletonProps {
  /** Which template to render. Default: "card". */
  variant?: 'card' | 'table' | 'page';
  /** Row count for the "table" variant. Default 5. */
  rows?: number;
}

/**
 * Reusable MUI Skeleton placeholders in three sizes.
 *
 *   - "card"  : 4 stacked rectangles roughly card-sized.
 *   - "table" : header strip + N rows.
 *   - "page"  : full-page placeholder (heading + 3 chunks of body).
 *
 * Used by every page during initial fetch so the layout never collapses.
 */
export default function LoadingSkeleton({
  variant = 'card',
  rows = 5,
}: LoadingSkeletonProps) {
  if (variant === 'table') {
    return (
      <Stack spacing={1} sx={{ width: '100%' }}>
        <Skeleton variant="rectangular" height={48} animation="wave" />
        {Array.from({ length: rows }).map((_, i) => (
          <Skeleton key={i} variant="rectangular" height={36} animation="wave" />
        ))}
      </Stack>
    );
  }

  if (variant === 'page') {
    return (
      <Box>
        <Skeleton variant="text" width={240} height={48} animation="wave" />
        <Skeleton variant="rectangular" height={120} animation="wave" sx={{ mt: 2 }} />
        <Skeleton variant="rectangular" height={120} animation="wave" sx={{ mt: 2 }} />
        <Skeleton variant="rectangular" height={120} animation="wave" sx={{ mt: 2 }} />
      </Box>
    );
  }

  // card variant
  return (
    <Card variant="outlined">
      <CardContent>
        <Skeleton variant="text" width="60%" animation="wave" />
        <Skeleton variant="text" width="40%" animation="wave" />
        <Skeleton variant="rectangular" height={80} animation="wave" sx={{ mt: 2 }} />
      </CardContent>
    </Card>
  );
}
