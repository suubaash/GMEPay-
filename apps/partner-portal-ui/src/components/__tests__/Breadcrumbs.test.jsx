import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import Breadcrumbs from '../Breadcrumbs';

describe('Breadcrumbs', () => {
  it('renders nothing when items is empty', () => {
    const { container } = render(<Breadcrumbs items={[]} />);
    expect(container.firstChild).toBeNull();
  });

  it('renders link items + a plain text last item', () => {
    render(
      <Breadcrumbs
        items={[
          { label: 'Overview', href: '/' },
          { label: 'Transactions', href: '/transactions' },
          { label: 'T-123' }
        ]}
      />
    );
    expect(screen.getByText('Overview').closest('a')).toHaveAttribute('href', '/');
    expect(screen.getByText('Transactions').closest('a')).toHaveAttribute(
      'href',
      '/transactions'
    );
    // Last crumb is not a link.
    expect(screen.getByText('T-123').closest('a')).toBeNull();
  });

  it('tolerates non-array input', () => {
    const { container } = render(<Breadcrumbs items={null} />);
    expect(container.firstChild).toBeNull();
  });
});
