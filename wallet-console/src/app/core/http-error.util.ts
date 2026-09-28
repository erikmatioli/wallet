import { HttpErrorResponse } from '@angular/common/http';
import { ProblemDetail } from './models';

/**
 * wallet-core always answers errors as application/problem+json (RFC 9457) with a stable
 * `code` and a human `detail` - see wallet-core's ApiExceptionHandler. This pulls the best
 * available message out of that shape, falling back gracefully if the body isn't what's expected
 * (a network failure, a proxy error page, etc. won't be a ProblemDetail at all).
 */
export function extractErrorMessage(err: unknown): string {
  if (err instanceof HttpErrorResponse) {
    const body = err.error as ProblemDetail | string | null;
    if (body && typeof body === 'object') {
      const parts = [body.detail ?? body.title, body.code ? `(${body.code})` : null];
      const message = parts.filter(Boolean).join(' ');
      if (message) return message;
    }
    if (err.status === 0) return 'Não foi possível conectar à API. Verifique se o wallet-core está no ar.';
    return `Erro ${err.status}: ${err.statusText}`;
  }
  return 'Erro inesperado.';
}
