'use client';

import { Breadcrumbs as MuiBreadcrumbs, Link as MuiLink, Typography } from '@mui/material';
import Link from 'next/link';
import NavigateNextIcon from '@mui/icons-material/NavigateNext';

/**
 * Breadcrumbs — small wrapper around MUI Breadcrumbs that accepts an
 * array of crumbs. The last crumb renders as plain text (current page);
 * earlier crumbs render as Next.js Links.
 *
 * Props:
 *   crumbs: Array<{ label: string, href?: string }>
 *
 * Example:
 *   <Breadcrumbs crumbs={[
 *     { label: 'Partners', href: '/partners' },
 *     { label: 'GME_KR_001' },
 *   ]} />
 */
export default function Breadcrumbs({ crumbs }) {
  const list = Array.isArray(crumbs) ? crumbs : [];
  if (list.length === 0) return null;
  return (
    <MuiBreadcrumbs
      separator={<NavigateNextIcon fontSize="small" />}
      aria-label="breadcrumb"
      sx={{ mb: 1 }}
    >
      {list.map((c, i) => {
        const isLast = i === list.length - 1;
        if (isLast || !c.href) {
          return (
            <Typography key={i} color="text.primary" variant="body2">
              {c.label}
            </Typography>
          );
        }
        return (
          <MuiLink
            key={i}
            component={Link}
            href={c.href}
            underline="hover"
            color="inherit"
            variant="body2"
          >
            {c.label}
          </MuiLink>
        );
      })}
    </MuiBreadcrumbs>
  );
}
