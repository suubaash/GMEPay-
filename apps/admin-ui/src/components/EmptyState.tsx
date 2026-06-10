'use client';

import { Box, Button, Typography } from '@mui/material';
import Lottie from 'lottie-react';
import emptyLottie from '@/lottie/empty.json';

export interface EmptyStateProps {
  /** Main heading line ("No partners yet"). */
  heading: string;
  /** Optional descriptive subtitle. */
  description?: string;
  /** Optional CTA button label — rendered only when `ctaHref` AND `ctaLabel` are provided. */
  ctaLabel?: string;
  /** Optional CTA destination. When set together with `ctaLabel`, a button renders. */
  ctaHref?: string;
  /** Optional CTA click handler (use this OR `ctaHref`). */
  onCta?: () => void;
}

/**
 * Centred empty-state panel with a Lottie animation, heading, optional
 * description and optional CTA button.
 *
 * Used wherever a table / list returns zero rows. The Lottie file is the
 * placeholder JSON in src/lottie/empty.json — replace with a real designer
 * file before launch.
 */
export default function EmptyState({
  heading,
  description,
  ctaLabel,
  ctaHref,
  onCta,
}: EmptyStateProps) {
  const showCta = !!ctaLabel && (!!ctaHref || !!onCta);
  return (
    <Box
      sx={{
        textAlign: 'center',
        py: 6,
        px: 2,
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
      }}
    >
      <Box sx={{ width: 180, height: 180 }}>
        <Lottie animationData={emptyLottie} loop autoplay />
      </Box>
      <Typography variant="h4" sx={{ mt: 1 }}>
        {heading}
      </Typography>
      {description ? (
        <Typography color="text.secondary" sx={{ mt: 1, maxWidth: 480 }}>
          {description}
        </Typography>
      ) : null}
      {showCta ? (
        <Box sx={{ mt: 3 }}>
          {ctaHref ? (
            <Button variant="contained" href={ctaHref}>
              {ctaLabel}
            </Button>
          ) : (
            <Button variant="contained" onClick={onCta}>
              {ctaLabel}
            </Button>
          )}
        </Box>
      ) : null}
    </Box>
  );
}
