'use client';

import { Box, Button, Typography } from '@mui/material';
import Lottie from 'lottie-react';
import emptyLottie from '@/lottie/empty.json';

/**
 * EmptyState — centred empty-state panel with a Lottie animation, heading,
 * optional description and optional CTA button.
 *
 * Props:
 *   heading:      string  (required)
 *   description:  string
 *   ctaLabel:     string
 *   ctaHref:      string  (rendered as a button when paired with ctaLabel)
 *   onCta:        () => void  (alternative to ctaHref)
 */
export default function EmptyState({
  heading,
  description,
  ctaLabel,
  ctaHref,
  onCta,
}) {
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
