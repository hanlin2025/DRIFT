import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SignupPage } from './SignupPage';
import { ApiError, registerCompany, resendVerification, resolveInvitation } from './api';

vi.mock('./api', async importOriginal => ({
  ...await importOriginal<typeof import('./api')>(),
  registerCompany: vi.fn(), resendVerification: vi.fn(), resolveInvitation: vi.fn(), registerAccount: vi.fn(),
}));

const ACCEPTED = { message: 'Check your email to verify your account before signing in.' };

beforeEach(() => {
  vi.resetAllMocks();
  window.history.replaceState(null, '', '/signup');
  vi.mocked(registerCompany).mockResolvedValue(ACCEPTED);
  vi.mocked(resendVerification).mockResolvedValue({ message: 'If this email is awaiting verification, a new link has been sent.' });
});

async function openCreate() {
  const user = userEvent.setup();
  render(<SignupPage />);
  await user.click(screen.getByRole('button', { name: /Create a company/ }));
  return user;
}

async function fill(user: ReturnType<typeof userEvent.setup>, overrides: Partial<Record<string, string>> = {}) {
  const values = { code: 'pacific_gate', name: 'Pacific Gate Imports', fullName: 'Alex Tan', email: 'alex@pacificgate.example', password: 'Str0ngPass', confirmation: 'Str0ngPass', ...overrides };
  if (values.code) await user.type(screen.getByLabelText('Company code'), values.code);
  if (values.name) await user.type(screen.getByLabelText('Company name'), values.name);
  if (values.fullName) await user.type(screen.getByLabelText('Full name'), values.fullName);
  if (values.email) await user.type(screen.getByLabelText('Work email'), values.email);
  if (values.password) await user.type(screen.getByLabelText('Password', { exact: true }), values.password);
  if (values.confirmation) await user.type(screen.getByLabelText('Confirm password'), values.confirmation);
}

function describedText(element: HTMLElement) {
  return (element.getAttribute('aria-describedby') ?? '').split(' ').filter(Boolean)
    .map(id => document.getElementById(id)?.textContent ?? '').join(' ');
}

describe('create a company', () => {
  it('validates every field before calling the server and focuses the first problem', async () => {
    const user = await openCreate();
    await user.click(screen.getByRole('button', { name: /Create company/ }));

    expect(registerCompany).not.toHaveBeenCalled();
    expect(screen.getByLabelText('Company code')).toHaveFocus();
    expect(describedText(screen.getByLabelText('Company code'))).toContain('Enter an organisation code using letters, digits, and underscores.');
    expect(describedText(screen.getByLabelText('Company name'))).toContain('Enter an organisation name.');
    expect(describedText(screen.getByLabelText('Full name'))).toContain('Enter your full name.');
    expect(describedText(screen.getByLabelText('Work email'))).toContain('Enter your work email.');
    expect(describedText(screen.getByLabelText('Password', { exact: true }))).toContain('Create a password.');
    expect(describedText(screen.getByLabelText('Confirm password'))).toContain('Enter your password again.');
    for (const label of ['Company code', 'Company name', 'Full name', 'Work email', 'Confirm password']) {
      expect(screen.getByLabelText(label)).toHaveAttribute('aria-invalid', 'true');
    }
  });

  it.each([
    ['Company code', { code: '1PACIFIC' }, 'Enter an organisation code using letters, digits, and underscores.'],
    ['Company code', { code: 'PACIFIC-GATE' }, 'Enter an organisation code using letters, digits, and underscores.'],
    ['Work email', { email: 'not-an-email' }, 'Enter a valid email address.'],
    ['Password', { password: 'weakpass', confirmation: 'weakpass' }, 'Your password must meet all four requirements below.'],
    ['Confirm password', { confirmation: 'Different1' }, 'Your passwords do not match.'],
  ])('rejects an invalid %s', async (label, overrides, message) => {
    const user = await openCreate();
    await fill(user, overrides);
    await user.click(screen.getByRole('button', { name: /Create company/ }));
    expect(registerCompany).not.toHaveBeenCalled();
    expect(describedText(screen.getByLabelText(label, { exact: true }))).toContain(message);
  });

  it('shows the live password rules', async () => {
    const user = await openCreate();
    await user.type(screen.getByLabelText('Password', { exact: true }), 'Str0ng');
    const rules = screen.getByRole('list', { name: 'Password requirements' });
    expect(rules).toHaveTextContent('Met: Uppercase and lowercase');
    expect(rules).toHaveTextContent('Required: At least 8 characters');
  });

  it('submits trimmed values and shows the check-your-email screen with a resend button', async () => {
    const user = await openCreate();
    await fill(user, { code: '  pacific_gate ', email: ' alex@pacificgate.example ' });
    await user.click(screen.getByRole('button', { name: /Create company/ }));

    expect(registerCompany).toHaveBeenCalledExactlyOnceWith({
      companyCode: 'pacific_gate', companyName: 'Pacific Gate Imports', fullName: 'Alex Tan',
      email: 'alex@pacificgate.example', password: 'Str0ngPass',
    });
    expect(await screen.findByRole('status')).toHaveTextContent('Check your email to verify your account before signing in.');
    expect(screen.getAllByRole('link', { name: 'Log in' }).map(link => link.getAttribute('href'))).toContain('/login');
    expect(screen.getByLabelText('Signup email')).toHaveValue('alex@pacificgate.example');
    await user.click(screen.getByRole('button', { name: 'Resend verification email' }));
    expect(resendVerification).toHaveBeenCalledWith('alex@pacificgate.example');
    expect(await screen.findByText('If this email is awaiting verification, a new link has been sent.')).toBeInTheDocument();
  });

  it('shows a taken company code under the company code field', async () => {
    const message = 'An organisation with this code already exists.';
    vi.mocked(registerCompany).mockRejectedValueOnce(new ApiError(message, 409, { companyCode: message }));
    const user = await openCreate();
    await fill(user);
    await user.click(screen.getByRole('button', { name: /Create company/ }));

    const code = await screen.findByLabelText('Company code');
    expect(await screen.findByText(message)).toBeInTheDocument();
    expect(describedText(code)).toContain(message);
    expect(code).toHaveAttribute('aria-invalid', 'true');
    expect(code).toHaveValue('pacific_gate');
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });

  it('shows server field errors under each field', async () => {
    vi.mocked(registerCompany).mockRejectedValueOnce(new ApiError('Registration information is missing or invalid', 400, {
      companyName: 'Enter an organisation name.', password: 'Password must not contain spaces',
    }));
    const user = await openCreate();
    await fill(user);
    await user.click(screen.getByRole('button', { name: /Create company/ }));

    expect(await screen.findByText('Password must not contain spaces')).toBeInTheDocument();
    expect(describedText(screen.getByLabelText('Company name'))).toContain('Enter an organisation name.');
    expect(describedText(screen.getByLabelText('Password', { exact: true }))).toContain('Password must not contain spaces');
  });

  it('keeps the typed values after a rate limit', async () => {
    vi.mocked(registerCompany).mockRejectedValueOnce(new ApiError('Too many requests. Try again later.', 429));
    const user = await openCreate();
    await fill(user);
    await user.click(screen.getByRole('button', { name: /Create company/ }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Too many requests. Try again later.');
    expect(screen.getByLabelText('Company code')).toHaveValue('pacific_gate');
    expect(screen.getByLabelText('Work email')).toHaveValue('alex@pacificgate.example');
    expect(screen.getByLabelText('Password', { exact: true })).toHaveValue('Str0ngPass');
  });

  it.each([
    ['a network failure', new ApiError('We could not reach DRIFT. Check your connection and try again.', 0)],
    ['a server error', new ApiError('Internal error', 500)],
  ])('offers a retry after %s and keeps the typed values', async (_name, failure) => {
    vi.mocked(registerCompany).mockRejectedValueOnce(failure);
    const user = await openCreate();
    await fill(user);
    await user.click(screen.getByRole('button', { name: /Create company/ }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Your details are still here');
    expect(screen.getByLabelText('Company name')).toHaveValue('Pacific Gate Imports');
    await user.click(screen.getByRole('button', { name: /Create company/ }));
    expect(await screen.findByRole('status')).toHaveTextContent('Check your email');
    expect(registerCompany).toHaveBeenCalledTimes(2);
  });

  it('cannot submit twice while the request is in flight', async () => {
    let finish: (value: { message: string }) => void = () => undefined;
    vi.mocked(registerCompany).mockReturnValueOnce(new Promise(resolve => { finish = resolve; }));
    const user = await openCreate();
    await fill(user);
    const button = screen.getByRole('button', { name: /Create company/ });
    await user.click(button);
    await user.click(button);

    expect(screen.getByRole('button', { name: /Creating your company/ })).toBeDisabled();
    expect(registerCompany).toHaveBeenCalledTimes(1);
    finish(ACCEPTED);
    expect(await screen.findByRole('status')).toHaveTextContent('Check your email');
  });

  it('never asks the invitation API on the create path', async () => {
    const user = await openCreate();
    await fill(user);
    await user.click(screen.getByRole('button', { name: /Create company/ }));
    await screen.findByRole('status');
    expect(resolveInvitation).not.toHaveBeenCalled();
  });
});
