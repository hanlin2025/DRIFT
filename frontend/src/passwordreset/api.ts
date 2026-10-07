import { ApiError, post } from '../signup/api';

function messageOf(data: unknown) {
  if (typeof data === 'object' && data !== null && 'message' in data && typeof data.message === 'string') return data.message;
  throw new ApiError('DRIFT returned an unexpected response. Please try again shortly.', 502);
}

export async function requestPasswordReset(email: string) {
  return messageOf(await post('/api/auth/forgot-password', { email }));
}

export async function submitPasswordReset(token: string, password: string) {
  return messageOf(await post('/api/auth/reset-password', { token, password }));
}
