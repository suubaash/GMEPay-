import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, within, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { theme } from '@/theme/theme';

import E2eTestConsole from './E2eTestConsole';

const OPTIONS = {
  countries: [
    { code: 'NP', label: 'Nepal', currency: 'NPR' },
    { code: 'KR', label: 'Korea', currency: 'KRW' },
  ],
  partners: [
    { code: 'GMEREMIT', label: 'GMEREMIT' },
    { code: 'SENDMN', label: 'SENDMN' },
  ],
  mpmTypes: ['STATIC', 'DYNAMIC'],
};

const HISTORY = [
  {
    id: 'run-1',
    createdAt: '2026-07-03T10:00:00Z',
    country: 'NP',
    partner: 'GMEREMIT',
    amount: 100,
    currency: 'NPR',
    mpmType: 'STATIC',
    status: 'PASS',
    failedStep: null,
    stepCount: 4,
  },
  {
    id: 'run-2',
    createdAt: '2026-07-03T09:00:00Z',
    country: 'KR',
    partner: 'SENDMN',
    amount: 5000,
    currency: 'KRW',
    mpmType: 'DYNAMIC',
    status: 'FAIL',
    failedStep: 'Pay',
    stepCount: 3,
  },
];

const PASS_RUN = {
  id: 'run-3',
  createdAt: '2026-07-03T11:00:00Z',
  country: 'NP',
  partner: 'GMEREMIT',
  amount: 100,
  currency: 'NPR',
  mpmType: 'STATIC',
  status: 'PASS',
  failedStep: null,
  stepCount: 2,
  steps: [
    { seq: 1, name: 'Create QR', status: 'PASS', detail: 'QR generated', latencyMs: 12, httpStatus: 200 },
    { seq: 2, name: 'Pay', status: 'PASS', detail: 'Payment approved', latencyMs: 30, httpStatus: 200 },
  ],
};

const FAIL_RUN = {
  id: 'run-4',
  createdAt: '2026-07-03T11:05:00Z',
  country: 'NP',
  partner: 'GMEREMIT',
  amount: 100,
  currency: 'NPR',
  mpmType: 'STATIC',
  status: 'FAIL',
  failedStep: 'Pay',
  stepCount: 2,
  steps: [
    { seq: 1, name: 'Create QR', status: 'PASS', detail: 'QR generated', latencyMs: 12, httpStatus: 200 },
    { seq: 2, name: 'Pay', status: 'FAIL', detail: 'Wallet declined', latencyMs: 8, httpStatus: 402 },
  ],
};

function jsonResponse(body, ok = true, status = 200) {
  return Promise.resolve({ ok, status, json: () => Promise.resolve(body) });
}

// Route fetch by path. `runBody` is the RunDetail returned by POST /e2e/run.
function installFetch(runBody = PASS_RUN, history = HISTORY) {
  const fetchMock = vi.fn((url, opts) => {
    const u = String(url);
    if (u.includes('/e2e/options')) return jsonResponse(OPTIONS);
    if (u.includes('/e2e/run') && opts?.method === 'POST') return jsonResponse(runBody);
    if (u.match(/\/e2e\/runs\/[^?]+$/)) {
      const id = u.split('/').pop();
      const found = history.find((h) => h.id === id) || runBody;
      return jsonResponse(found);
    }
    if (u.includes('/e2e/runs')) return jsonResponse(history);
    return jsonResponse({}, false, 404);
  });
  global.fetch = fetchMock;
  return fetchMock;
}

function renderConsole() {
  return render(
    <ThemeProvider theme={theme}>
      <E2eTestConsole />
    </ThemeProvider>,
  );
}

describe('E2eTestConsole', () => {
  beforeEach(() => {
    installFetch();
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('renders the run form inputs', async () => {
    renderConsole();
    expect(screen.getByRole('button', { name: /run test/i })).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: /static/i })).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: /dynamic/i })).toBeInTheDocument();
    // Amount input (label starts with "Amount"; use the spinbutton role to avoid
    // matching the "amount is in the QR" helper/radio text).
    expect(screen.getByRole('spinbutton')).toBeInTheDocument();
    // Country + Partner selects present.
    expect(screen.getByLabelText('Country')).toBeInTheDocument();
    expect(screen.getByLabelText('Partner')).toBeInTheDocument();
  });

  it('renders the history table rows from GET /e2e/runs', async () => {
    renderConsole();
    await waitFor(() => {
      expect(screen.getByTestId('history-row-run-1')).toBeInTheDocument();
    });
    expect(screen.getByTestId('history-row-run-2')).toBeInTheDocument();
    const passRow = screen.getByTestId('history-row-run-1');
    expect(within(passRow).getByText('PASS')).toBeInTheDocument();
    const failRow = screen.getByTestId('history-row-run-2');
    expect(within(failRow).getByText('FAIL')).toBeInTheDocument();
  });

  it('clicking Run posts to /e2e/run and shows the PASS banner + steps', async () => {
    const fetchMock = installFetch(PASS_RUN);
    const user = userEvent.setup();
    renderConsole();

    await user.click(screen.getByRole('button', { name: /run test/i }));

    await waitFor(() => {
      // The result banner heading (distinct from the status Alert line).
      expect(screen.getByText('✅ Payment journey passed')).toBeInTheDocument();
    });
    // POST body carried the form values.
    const postCall = fetchMock.mock.calls.find(
      ([u, o]) => String(u).includes('/e2e/run') && o?.method === 'POST',
    );
    expect(postCall).toBeTruthy();
    expect(JSON.parse(postCall[1].body)).toMatchObject({
      country: 'NP',
      partner: 'GMEREMIT',
      mpmType: 'STATIC',
      amount: 100,
    });
    // Steps rendered.
    expect(screen.getByText(/1\. Create QR/)).toBeInTheDocument();
    expect(screen.getByText(/2\. Pay/)).toBeInTheDocument();
  });

  it('a FAIL run shows "Failed at: Pay" and the failed step', async () => {
    installFetch(FAIL_RUN);
    const user = userEvent.setup();
    renderConsole();

    await user.click(screen.getByRole('button', { name: /run test/i }));

    await waitFor(() => {
      // The result banner heading carries the ❌ prefix (distinct from the status Alert).
      expect(screen.getByText('❌ Failed at: Pay')).toBeInTheDocument();
    });
    expect(screen.getByText(/Wallet declined/)).toBeInTheDocument();
  });
});
