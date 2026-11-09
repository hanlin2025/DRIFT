import { describe, expect, it } from 'vitest';
import { resetToken, validateEmail, validateReset } from './validation';

const valid = { password: 'Example123', confirmation: 'Example123' };

describe('password reset validation', () => {
  it('reads a reset token from the link fragment', () => {
    const token = 'a'.repeat(43);
    expect(resetToken(`#token=${token}`)).toBe(token);
    expect(resetToken('#token=short')).toBe('');
    expect(resetToken('')).toBe('');
  });

  it('checks the email before a reset is requested', () => {
    expect(validateEmail('  ')).toBe('Email is required');
    expect(validateEmail('not-an-email')).toBe('Email must be a valid email address');
    expect(validateEmail(' alex@example.com ')).toBe('');
  });

  it('requires a policy password and a matching confirmation', () => {
    expect(validateReset(valid)).toEqual({});
    expect(validateReset({ password: 'short1A', confirmation: 'short1A' }).password).toBe('Password must be at least 8 characters');
    expect(validateReset({ password: 'Example123', confirmation: 'Example124' }).confirmation).toBe('Passwords do not match');
  });
});
