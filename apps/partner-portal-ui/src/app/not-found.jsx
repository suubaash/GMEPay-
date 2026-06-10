'use client';
import * as React from 'react';
import Link from 'next/link';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Container from '@mui/material/Container';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import HomeIcon from '@mui/icons-material/Home';
import EmptyState from '@/components/EmptyState';

/**
 * Custom 404 page rendered by Next's App Router when no route matches.
 *
 * Kept light: a centered card with the EmptyState Lottie + a link back to
 * the Overview page. The auth gate still wraps this through AuthShell so an
 * unauthenticated visitor lands on /login rather than seeing this page.
 */
export default function NotFound() {
  return (
    <Container maxWidth="sm" sx={{ py: 6 }}>
      <Box
        sx={{
          textAlign: 'center',
          py: 4,
          px: 2,
          border: '1px solid',
          borderColor: 'divider',
          borderRadius: 2,
          backgroundColor: 'background.paper'
        }}
        data-testid="not-found-page"
      >
        <Stack spacing={2} alignItems="center">
          <Typography variant="h1" sx={{ color: 'primary.main' }}>
            404
          </Typography>
          <EmptyState
            title="Page not found"
            message="The page you're looking for doesn't exist or has moved. From here you can head back to your Overview."
            size={140}
            action={
              <Button
                component={Link}
                href="/"
                variant="contained"
                startIcon={<HomeIcon />}
                data-testid="not-found-home-button"
              >
                Back to Overview
              </Button>
            }
          />
        </Stack>
      </Box>
    </Container>
  );
}
