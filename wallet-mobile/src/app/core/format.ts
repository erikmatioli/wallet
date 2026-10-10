import { Schedule, StatementEntry } from './contract';

// How the app writes money, dates and transaction types - pt-BR, in Brasília, from the contract's raw values.
// Same words as the desktop and the console.

const ZONE = 'America/Sao_Paulo';
const brl = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' });
const dateTimeFmt = new Intl.DateTimeFormat('pt-BR', {
  timeZone: ZONE,
  day: '2-digit',
  month: '2-digit',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
});
const timeFmt = new Intl.DateTimeFormat('pt-BR', {
  timeZone: ZONE,
  hour: '2-digit',
  minute: '2-digit',
});
const dayKeyFmt = new Intl.DateTimeFormat('en-CA', {
  timeZone: ZONE,
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
});
const longDayFmt = new Intl.DateTimeFormat('pt-BR', {
  timeZone: 'UTC',
  weekday: 'long',
  day: 'numeric',
  month: 'long',
});

/** Cents to "R$ 1.234,56", from an integer: the division happens only for display. */
export function money(cents: number): string {
  return brl.format(cents / 100).replace(/ /g, ' ');
}

/** "+ R$ 10,00" / "- R$ 10,00", as a statement line shows it. */
export function signed(entry: StatementEntry): string {
  return (entry.credit ? '+ ' : '- ') + money(entry.amountCents);
}

/** "09/10/2026 14:30", in Brasília. */
export function dateTime(iso: string): string {
  return dateTimeFmt.format(new Date(iso)).replace(',', '');
}

/** "14:30", in Brasília. */
export function time(iso: string): string {
  return timeFmt.format(new Date(iso));
}

/** "2026-10-09": the day an instant falls on, in Brasília. */
export function dayKey(iso: string | Date): string {
  return dayKeyFmt.format(typeof iso === 'string' ? new Date(iso) : iso);
}

/** "Hoje", "Ontem" or "quinta-feira, 8 de outubro": the header of a day in the statement. */
export function dayTitle(day: string, now: Date = new Date()): string {
  const today = dayKey(now);
  const yesterday = dayKey(new Date(now.getTime() - 86_400_000));
  if (day === today) return 'Hoje';
  if (day === yesterday) return 'Ontem';
  const text = longDayFmt.format(new Date(`${day}T12:00:00Z`));
  return text.charAt(0).toUpperCase() + text.slice(1);
}

const TYPES: Record<string, string> = {
  DEPOSIT: 'Depósito',
  WITHDRAWAL: 'Saque',
  TRANSFER: 'Transferência',
  PIX_IN: 'Pix recebido',
  PIX_OUT: 'Pix enviado',
  PIX_REFUND: 'Estorno de Pix',
  PIX_RETURN_IN: 'Devolução recebida',
  PIX_RETURN_OUT: 'Devolução enviada',
};

/** Same names as the console's statement. */
export function type(raw: string): string {
  return TYPES[raw] ?? raw;
}

/** "0001 / 00100123-8". */
export function account(branch: string, number: string, checkDigit: string): string {
  return `${branch} / ${number}-${checkDigit}`;
}

/** "2026-10-08" to "08/10/2026". */
export function date(isoDate: string): string {
  const [y, m, d] = isoDate.split('-');
  return `${d}/${m}/${y}`;
}

/**
 * The amount field works like a bank app's: the customer types digits and they fill the cents from the
 * right ("1", "15", "150" -> R$ 0,01, R$ 0,15, R$ 1,50). Only digits count, so there is no decimal
 * separator to get wrong and no floating point anywhere.
 */
export function centsFromTyping(text: string): number {
  const digits = text.replace(/\D/g, '').replace(/^0+/, '').slice(0, 11);
  return digits ? Number(digits) : 0;
}

/** What the amount field shows for `cents`: "" while nothing was typed. */
export function amountField(cents: number): string {
  return cents > 0 ? money(cents) : '';
}

/** "52998224725" -> "529.982.247-25" while typing; at most 11 digits. */
export function cpfMask(text: string): string {
  const d = text.replace(/\D/g, '').slice(0, 11);
  return d
    .replace(/^(\d{3})(\d)/, '$1.$2')
    .replace(/^(\d{3})\.(\d{3})(\d)/, '$1.$2.$3')
    .replace(/\.(\d{3})(\d{1,2})$/, '.$1-$2');
}

/** A CPF has 11 digits. Only the shape: app-api checks the rest. */
export function isCpf(text: string): boolean {
  return text.replace(/\D/g, '').length === 11;
}

const TAX_ID = /^([0-9]{11}|[0-9A-Z]{12}[0-9]{2})$/;

/** A CPF (11 digits) or CNPJ (14 characters, letters allowed in the alphanumeric one), formatting ignored. */
export function isTaxId(text: string): boolean {
  return TAX_ID.test(text.replace(/[^0-9A-Za-z]/g, '').toUpperCase());
}

export const TAX_ID_ERROR = 'Informe o CPF (11 dígitos) ou o CNPJ (14 caracteres) do recebedor.';

/** Tomorrow in Brasília, yyyy-MM-dd: the first day a schedule may run on. */
export function tomorrow(now: Date = new Date()): string {
  const [y, m, d] = dayKey(now).split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, d + 1)).toISOString().slice(0, 10);
}

/** The schedule's situation in one phrase - the words the console and the desktop use. */
export function scheduleStatus(s: Schedule): string {
  if (s.status === 'CANCELLED') return 'Cancelado';
  const e = s.execution;
  switch (e.status) {
    case 'EXECUTED':
      return 'Pago';
    case 'FAILED':
      return 'Não pago';
    case 'PROCESSING':
      return 'Em processamento';
    case 'CANCELLED':
      return 'Cancelado';
    default:
      return e.attemptCount > 0 && e.nextAttemptAt
        ? `Nova tentativa às ${time(e.nextAttemptAt)}`
        : 'Agendado';
  }
}

/** The color of a schedule's chip: done, failed, cancelled or still to come. */
export function scheduleTone(s: Schedule): 'ok' | 'bad' | 'muted' | 'wait' {
  if (s.status === 'CANCELLED' || s.execution.status === 'CANCELLED') return 'muted';
  if (s.execution.status === 'EXECUTED') return 'ok';
  if (s.execution.status === 'FAILED') return 'bad';
  return 'wait';
}

const PIX_STATUS: Record<string, string> = {
  SENT: 'Enviado',
  COMPLETED: 'Concluído',
  REFUNDED: 'Não concluído',
  RETURNED: 'Devolvido',
};

export function pixStatus(status: string): string {
  return PIX_STATUS[status] ?? status;
}

/**
 * A random UUID for the Idempotency-Key. `crypto.randomUUID` exists only in secure contexts; a phone
 * opening the app by the machine's IP over http is not one, so the key is built from getRandomValues.
 */
export function uuid(): string {
  const b = crypto.getRandomValues(new Uint8Array(16));
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  const h = Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('');
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}
