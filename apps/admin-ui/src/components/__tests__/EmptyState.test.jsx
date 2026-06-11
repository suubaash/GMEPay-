import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import { theme } from '@/theme/theme';

// Mock lottie-react: the real component animates inside a <canvas>-ish element
// that jsdom doesn't fully implement. We just render a no-op div so the
// EmptyState DOM is otherwise complete.
vi.mock('lottie-react', () => ({
  default: () => <div data-testid="lottie" />,
}));

import EmptyState from '../EmptyState';

function renderWithTheme(ui) {
  return render(<ThemeProvider theme={theme}>{ui}</ThemeProvider>);
}

describe('EmptyState', () => {
  it('renders the heading', () => {
    renderWithTheme(<EmptyState heading="No partners yet" />);
    expect(screen.getByText('No partners yet')).toBeInTheDocument();
  });

  it('renders the optional description when provided', () => {
    renderWithTheme(
      <EmptyState heading="Nothing here" description="Try again later" />,
    );
    expect(screen.getByText('Try again later')).toBeInTheDocument();
  });

  it('does NOT render a CTA button when only the label is supplied', () => {
    renderWithTheme(<EmptyState heading="Nothing here" ctaLabel="Add" />);
    expect(screen.queryByRole('button', { name: /add/i })).toBeNull();
  });

  it('renders a CTA button when both label and href are supplied', () => {
    renderWithTheme(
      <EmptyState heading="Empty" ctaLabel="Create one" ctaHref="/partners/new" />,
    );
    const btn = screen.getByRole('button', { name: /create one/i });
    expect(btn).toBeInTheDocument();
  });

  it('renders a CTA button when label + onCta are supplied', () => {
    const onCta = vi.fn();
    renderWithTheme(
      <EmptyState heading="Empty" ctaLabel="Do thing" onCta={onCta} />,
    );
    expect(screen.getByRole('button', { name: /do thing/i })).toBeInTheDocument();
  });
});
