'use client';

import { Box, Card, CardContent, Skeleton, Stack } from '@mui/material';

/**
 * LoadingSkeleton — reusable MUI Skeleton placeholders in three sizes.
 *
 *   - "card"  : 4 stacked rectangles roughly card-sized.
 *   - "table" : header strip + N rows.
 *   - "page"  : full-page placeholder (heading + 3 chunks of body).
 *
 * Props:
 *   variant: 'card' | 'table' | 'page'  (default 'card')
 *   rows:    number                     (default 5)
 */
export default function LoadingSkeleton({ variant = 'card', rows = 5 }) {
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
