import { describe, expect, it } from 'vitest';
import { validateSetup } from './validation';

const complete = {
  organisationId: '2',
  fullName: 'Ada Admin',
  email: 'ada@example.com',
  password: 'Example123',
  confirmation: 'Example123',
};

describe('setup form validation', () => {
  it('accepts a complete administrator', () => {
    expect(validateSetup(complete, 1)).toEqual({});
  });

  it('requires an organisation, identity and a password that meets the existing rules', () => {
    expect(validateSetup({ ...complete, organisationId: '', password: 'short', confirmation: 'other' }, 1)).toMatchObject({
      organisationId: 'Choose an organisation.',
      password: 'Your password must meet all four requirements below.',
      confirmation: 'Your passwords do not match.',
    });
    expect(validateSetup(complete, 0).organisationId).toBe('No eligible organisation is available.');
  });
});
