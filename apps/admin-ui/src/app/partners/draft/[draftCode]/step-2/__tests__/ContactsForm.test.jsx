import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { Provider } from 'react-redux';
import { configureStore } from '@reduxjs/toolkit';
import draftsReducer from '@/store/draftsSlice';
import authReducer from '@/store/authSlice';
import { theme } from '@/theme/theme';

/**
 * Vitest coverage for the Slice 2 Step-2 Contacts form.
 *
 * Contract:
 *  - Renders a single empty row on a blank draft.
 *  - "Add contact" appends a new row.
 *  - "Remove" button removes a row (disabled when only one row remains).
 *  - Required fields (role, name, email, phone) are flagged before any submit
 *    reaches the BFF.
 *  - Email and phone (E.164) Yup rules fire inline.
 *  - A valid submit dispatches {@link patchStep2} with the BFF shape and
 *    triggers {@code onSaved} so the wizard can advance.
 *  - A soft warning chip is shown when fewer than 4 activation-required
 *    roles are covered.
 */

// Mock BFF client.
const patchDraftStep = vi.fn();
vi.mock('@/api/client', () => ({
  __esModule: true,
  ApiError: class ApiError extends Error {
    constructor(status, url, message) {
      super(message);
      this.status = status;
      this.url = url;
    }
  },
  adminApi: {
    patchDraftStep: (...args) => patchDraftStep(...args),
  },
}));

// Snackbar mock.
const snackSuccess = vi.fn();
const snackError = vi.fn();
vi.mock('@/components/SnackbarProvider', () => ({
  __esModule: true,
  default: ({ children }) => <>{children}</>,
  useSnackbar: () => ({
    success: snackSuccess,
    error: snackError,
    info: vi.fn(),
    warning: vi.fn(),
  }),
}));

import ContactsForm from '../ContactsForm';

function buildStore() {
  return configureStore({
    reducer: {
      drafts: draftsReducer,
      auth: authReducer,
    },
  });
}

function renderForm({ draft, partnerCode = 'GME_KR_001', onSaved } = {}) {
  const store = buildStore();
  return {
    store,
    ...render(
      <Provider store={store}>
        <ThemeProvider theme={theme}>
          <ContactsForm
            draft={draft ?? { partnerCode }}
            partnerCode={partnerCode}
            onSaved={onSaved ?? vi.fn()}
          />
        </ThemeProvider>
      </Provider>,
    ),
  };
}

/** A fully-filled contact row values. */
function validContact(overrides = {}) {
  return {
    role: 'OPS_24X7',
    name: 'Alice Smith',
    email: 'alice@example.com',
    phoneE164: '+821012345678',
    isAuthorizedSignatory: false,
    notes: '',
    ...overrides,
  };
}

/** A draft with one pre-populated contact. */
function draftWithContact(contact = validContact()) {
  return {
    partnerCode: 'GME_KR_001',
    contacts: [contact],
  };
}

/**
 * Fill a contact row at the given index via aria-labels.
 * Skips fields whose value is undefined.
 */
async function fillRow(user, index, { role, name, email, phone } = {}) {
  if (role) {
    // MUI Select — click to open, then click the option.
    const roleSelect = screen.getByLabelText(`contacts[${index}].role`);
    await user.click(roleSelect);
    const option = await screen.findByRole('option', { name: new RegExp(role, 'i') });
    await user.click(option);
  }
  if (name) {
    const nameInput = screen.getByLabelText(`contacts[${index}].name`, { selector: 'input' });
    await user.clear(nameInput);
    await user.type(nameInput, name);
  }
  if (email) {
    const emailInput = screen.getByLabelText(`contacts[${index}].email`, { selector: 'input' });
    await user.clear(emailInput);
    await user.type(emailInput, email);
  }
  if (phone) {
    const phoneInput = screen.getByLabelText(`contacts[${index}].phoneE164`, { selector: 'input' });
    await user.clear(phoneInput);
    await user.type(phoneInput, phone);
  }
}

describe('ContactsForm', () => {
  beforeEach(() => {
    patchDraftStep.mockReset();
    snackSuccess.mockReset();
    snackError.mockReset();
  });

  it('renders a single empty row when the draft has no contacts', () => {
    renderForm();
    // Row 0 fields should be in the DOM.
    expect(screen.getByLabelText('contacts[0].name', { selector: 'input' })).toBeInTheDocument();
    expect(screen.getByLabelText('contacts[0].email', { selector: 'input' })).toBeInTheDocument();
    expect(screen.getByLabelText('contacts[0].phoneE164', { selector: 'input' })).toBeInTheDocument();
  });

  it('pre-populates rows from the draft contacts prop', () => {
    renderForm({ draft: draftWithContact() });
    expect(
      screen.getByLabelText('contacts[0].name', { selector: 'input' }),
    ).toHaveValue('Alice Smith');
    expect(
      screen.getByLabelText('contacts[0].email', { selector: 'input' }),
    ).toHaveValue('alice@example.com');
  });

  it('adds a new row when "Add contact" is clicked', async () => {
    const user = userEvent.setup();
    renderForm();

    await user.click(screen.getByRole('button', { name: /Add contact/i }));

    expect(screen.getByLabelText('contacts[1].name', { selector: 'input' })).toBeInTheDocument();
  });

  it('removes a row when the remove button is clicked', async () => {
    const user = userEvent.setup();
    renderForm({ draft: { partnerCode: 'GME_KR_001', contacts: [validContact(), validContact({ name: 'Bob Jones' })] } });

    expect(screen.getByLabelText('contacts[1].name', { selector: 'input' })).toBeInTheDocument();

    await user.click(screen.getByLabelText('remove-contact-1'));

    await waitFor(() => {
      expect(screen.queryByLabelText('contacts[1].name', { selector: 'input' })).not.toBeInTheDocument();
    });
  });

  it('disables the remove button when only one row remains', () => {
    renderForm();
    const removeBtn = screen.getByLabelText('remove-contact-0');
    expect(removeBtn).toBeDisabled();
  });

  it('flags required fields when the form is submitted empty', async () => {
    const user = userEvent.setup();
    renderForm();

    await user.click(screen.getByRole('button', { name: /save & next/i }));

    // Yup oneOf fires "Pick a contact role" when role is '' (empty string),
    // and "required" fires for text fields that are blank.
    expect(await screen.findByText(/Pick a contact role/i)).toBeInTheDocument();
    expect(screen.getByText(/Name is required/i)).toBeInTheDocument();
    expect(patchDraftStep).not.toHaveBeenCalled();
  });

  it('flags an invalid email address', async () => {
    const user = userEvent.setup();
    renderForm();

    await fillRow(user, 0, {
      role: 'Operations 24x7',
      name: 'Test User',
      email: 'not-an-email',
      phone: '+821012345678',
    });

    await user.click(screen.getByRole('button', { name: /save & next/i }));

    expect(await screen.findByText(/valid email/i)).toBeInTheDocument();
    expect(patchDraftStep).not.toHaveBeenCalled();
  });

  it('flags a phone number that is not E.164', async () => {
    const user = userEvent.setup();
    renderForm();

    await fillRow(user, 0, {
      role: 'Operations 24x7',
      name: 'Test User',
      email: 'test@example.com',
      phone: '01012345678', // missing leading +
    });

    await user.click(screen.getByRole('button', { name: /save & next/i }));

    expect(await screen.findByText(/E\.164 format/i)).toBeInTheDocument();
    expect(patchDraftStep).not.toHaveBeenCalled();
  });

  it('dispatches patchStep2 with the BFF shape on a valid submit', async () => {
    patchDraftStep.mockResolvedValueOnce({
      id: 1,
      partnerCode: 'GME_KR_001',
      status: 'ONBOARDING',
    });

    const onSaved = vi.fn();
    const user = userEvent.setup();
    renderForm({ draft: draftWithContact(), onSaved });

    await user.click(screen.getByRole('button', { name: /save & next/i }));

    await waitFor(() => {
      expect(patchDraftStep).toHaveBeenCalledTimes(1);
    });

    const [step, partnerCode, body] = patchDraftStep.mock.calls[0];
    expect(step).toBe(2);
    expect(partnerCode).toBe('GME_KR_001');
    expect(body).toMatchObject({
      contacts: [
        expect.objectContaining({
          role: 'OPS_24X7',
          name: 'Alice Smith',
          email: 'alice@example.com',
          phoneE164: '+821012345678',
          isAuthorizedSignatory: false,
        }),
      ],
    });

    await waitFor(() => {
      expect(snackSuccess).toHaveBeenCalled();
      expect(onSaved).toHaveBeenCalledTimes(1);
    });
  });

  it('surfaces a server error via the snackbar without advancing', async () => {
    patchDraftStep.mockRejectedValueOnce(new Error('BFF error'));
    const onSaved = vi.fn();
    const user = userEvent.setup();
    renderForm({ draft: draftWithContact(), onSaved });

    await user.click(screen.getByRole('button', { name: /save & next/i }));

    await waitFor(() => {
      expect(snackError).toHaveBeenCalled();
    });
    expect(onSaved).not.toHaveBeenCalled();
  });

  it('shows the role-coverage warning when fewer than 4 activation roles are present', () => {
    // Draft has only one contact with OPS_24X7 — Finance, Compliance, Tech are missing.
    renderForm({ draft: draftWithContact() });

    const warning = screen.getByLabelText('role-coverage-warning');
    expect(warning).toBeInTheDocument();
  });

  it('hides the role-coverage warning when all 4 activation roles are covered', () => {
    const draft = {
      partnerCode: 'GME_KR_001',
      contacts: [
        validContact({ role: 'OPS_24X7' }),
        validContact({ role: 'FINANCE', name: 'Bob', email: 'bob@ex.com' }),
        validContact({ role: 'COMPLIANCE', name: 'Carol', email: 'carol@ex.com' }),
        validContact({ role: 'TECH', name: 'Dave', email: 'dave@ex.com' }),
      ],
    };
    renderForm({ draft });

    const warning = screen.getByLabelText('role-coverage-warning');
    // The alert is rendered but hidden via sx={{ display: 'none' }} when
    // missingActivationRoles is empty — not removed from the DOM. Check that
    // it has no visible text content containing "Missing:".
    expect(warning).not.toHaveTextContent('Missing:');
  });
});
