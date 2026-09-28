import { HttpErrorResponse } from '@angular/common/http';
import { extractErrorMessage } from './http-error.util';

describe('extractErrorMessage', () => {
  it('prefers detail + code from a wallet-core problem+json body', () => {
    const err = new HttpErrorResponse({
      status: 422,
      error: { title: 'Unprocessable Entity', detail: 'account has insufficient funds', code: 'INSUFFICIENT_FUNDS' },
    });
    expect(extractErrorMessage(err)).toBe('account has insufficient funds (INSUFFICIENT_FUNDS)');
  });

  it('falls back to title when detail is missing', () => {
    const err = new HttpErrorResponse({ status: 404, error: { title: 'Not Found' } });
    expect(extractErrorMessage(err)).toBe('Not Found');
  });

  it('gives a connectivity-specific message for status 0 (network failure)', () => {
    const err = new HttpErrorResponse({ status: 0, error: null });
    expect(extractErrorMessage(err)).toContain('wallet-core');
  });

  it('falls back to a generic message for a non-HTTP error', () => {
    expect(extractErrorMessage(new Error('boom'))).toBe('Erro inesperado.');
  });
});
