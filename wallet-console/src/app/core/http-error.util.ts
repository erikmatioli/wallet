import { HttpErrorResponse } from '@angular/common/http';
import { ProblemDetail } from './models';

/**
 * wallet-core always answers errors as application/problem+json (RFC 9457) with a stable
 * `code` and a human `detail` - see wallet-core's ApiExceptionHandler. This pulls the best
 * available message out of that shape, falling back gracefully if the body isn't what's expected
 * (a network failure, a proxy error page, etc. won't be a ProblemDetail at all).
 */
/**
 * The refusals an operator meets in the blocks, the closing and the treasury (ADR-011 and ADR-013 of the core), in
 * their words. Any other code keeps wallet-core's own detail.
 */
const OPERATOR_MESSAGES: Record<string, string> = {
  ACCOUNT_BLOCKED: 'A conta está bloqueada para este movimento.',
  ACCOUNT_NOT_ACTIVE: 'A conta está encerrada.',
  ACCOUNT_NOT_EMPTY: 'A conta ainda tem saldo: devolva o dinheiro ao cliente (Pix ou saque) antes de encerrar.',
  JUDICIAL_BLOCK_ACTIVE: 'Há um bloqueio judicial ativo: cumpra ou libere a ordem antes de encerrar.',
  ACCOUNT_ALREADY_CLOSED: 'A conta já está encerrada.',
  ACCOUNT_CLOSED: 'Uma conta encerrada não pode ser bloqueada.',
  BLOCK_ALREADY_RELEASED: 'Este bloqueio já foi liberado.',
  SCOPE_REQUIRED: 'Seu acesso não permite este tipo de bloqueio.',
  COURT_PROCESS_REQUIRED: 'Informe o número do processo.',
  NOTE_REQUIRED: 'Com o motivo "Outro", a observação é obrigatória.',
  RELEASE_NOTE_REQUIRED: 'Diga por que o bloqueio é liberado.',
  INVALID_OPERATOR: 'Informe o nome do operador (até 80 caracteres).',
  JUDICIAL_BLOCK_NOT_ACTIVE: 'O bloqueio judicial foi liberado: a ordem não transfere mais nada.',
  JUDICIAL_BLOCK_NOT_FOUND: 'Este bloqueio judicial não é desta conta.',
  TRANSIT_OUT_EXCEEDS_TRANSFER: 'As TEDs passariam do valor da transferência judicial.',
  INVALID_TREASURY_SOURCE: 'A TED sai de um lugar real, nunca de "A regularizar".',
  DOCUMENT_REQUIRED: 'Informe o comprovante da TED.',
  INSUFFICIENT_FUNDS: 'Saldo insuficiente.',
  COVERAGE_EXCEEDS_UNCOVERED: 'A cobertura é maior que o dinheiro descoberto.',
};

export function extractErrorMessage(err: unknown): string {
  if (err instanceof HttpErrorResponse) {
    const body = err.error as ProblemDetail | string | null;
    if (body && typeof body === 'object') {
      const known = body.code ? OPERATOR_MESSAGES[body.code] : undefined;
      const parts = [known ?? body.detail ?? body.title, body.code ? `(${body.code})` : null];
      const message = parts.filter(Boolean).join(' ');
      if (message) return message;
    }
    if (err.status === 0) return 'Não foi possível conectar à API. Verifique se o wallet-core está no ar.';
    return `Erro ${err.status}: ${err.statusText}`;
  }
  return 'Erro inesperado.';
}
