import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import Button from '@mui/material/Button';
import EmptyState from '../EmptyState';

// Mock the lottie module so jsdom doesn't try to mount a real animation.
vi.mock('lottie-react', () => ({
  default: ({ animationData }) => (
    <div data-testid="mock-lottie" data-has-animation={animationData ? 'yes' : 'no'} />
  )
}));

describe('EmptyState', () => {
  it('renders the title', () => {
    render(<EmptyState title="No transactions yet" />);
    expect(screen.getByText('No transactions yet')).toBeInTheDocument();
  });

  it('renders the message when provided', () => {
    render(<EmptyState title="Nothing here" message="Try processing a payment first." />);
    expect(screen.getByText('Try processing a payment first.')).toBeInTheDocument();
  });

  it('omits the message paragraph when not provided', () => {
    render(<EmptyState title="Empty" />);
    expect(screen.queryByText(/Try processing/)).toBeNull();
  });

  it('renders an action node when provided and forwards clicks', async () => {
    const onClick = vi.fn();
    render(
      <EmptyState
        title="Empty"
        action={<Button onClick={onClick}>Do thing</Button>}
      />
    );
    const btn = screen.getByRole('button', { name: 'Do thing' });
    await userEvent.click(btn);
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it('exposes a data-testid hook for higher-level tests', () => {
    render(<EmptyState title="x" />);
    expect(screen.getByTestId('empty-state')).toBeInTheDocument();
  });
});
