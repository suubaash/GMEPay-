'use client';
import * as React from 'react';
import Link from 'next/link';
import Box from '@mui/material/Box';
import MuiBreadcrumbs from '@mui/material/Breadcrumbs';
import Typography from '@mui/material/Typography';
import NavigateNextIcon from '@mui/icons-material/NavigateNext';

/**
 * Page-scoped breadcrumbs.
 *
 * @param {{ items: Array<{ label: string, href?: string }> }} props
 *   The last item is treated as the current page and rendered as plain text
 *   (no link). Items without `href` are also rendered as text.
 */
export default function Breadcrumbs({ items }) {
  const safeItems = Array.isArray(items) ? items : [];
  if (safeItems.length === 0) return null;
  const lastIndex = safeItems.length - 1;

  return (
    <Box sx={{ mb: 1 }} data-testid="breadcrumbs">
      <MuiBreadcrumbs separator={<NavigateNextIcon fontSize="small" />} aria-label="breadcrumb">
        {safeItems.map((item, idx) => {
          const isLast = idx === lastIndex;
          const label = item?.label ?? '';
          if (isLast || !item?.href) {
            return (
              <Typography
                key={`crumb-${idx}-${label}`}
                variant="body2"
                sx={{ color: isLast ? 'text.primary' : 'text.secondary' }}
              >
                {label}
              </Typography>
            );
          }
          return (
            <Typography
              key={`crumb-${idx}-${label}`}
              variant="body2"
              component={Link}
              href={item.href}
              sx={{
                color: 'text.secondary',
                textDecoration: 'none',
                '&:hover': { textDecoration: 'underline' }
              }}
            >
              {label}
            </Typography>
          );
        })}
      </MuiBreadcrumbs>
    </Box>
  );
}
