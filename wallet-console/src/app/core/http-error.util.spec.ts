import { HttpErrorResponse } from '@angular/common/http';
import { extractErrorMessage } from './http-error.util';

describe('extractErrorMessage', () => {
  it('prefers detail + code from a wallet-core problem+json body', () => {
    const err = new HttpErrorResponse({
      status: 422,
      error: { title: 'Unprocessable Entity', detail: 'this Pix was already refunded', code: 'PIX_ALREADY_REFUNDED' },
    });
    expect(extractErrorMessage(err)).toBe('this Pix was already refunded (PIX_ALREADY_REFUNDED)');
  });

  it("says the operator's refusals in Portuguese, keeping the code", () => {
    const err = new HttpErrorResponse({
      status: 422,
      error: { detail: 'the court order must be carried out first', code: 'JUDICIAL_BLOCK_ACTIVE' },
    });
    expect(extractErrorMessage(err)).toBe(
      'Há um bloqueio judicial ativo: cumpra ou libere a ordem antes de encerrar. (JUDICIAL_BLOCK_ACTIVE)',
    );
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
