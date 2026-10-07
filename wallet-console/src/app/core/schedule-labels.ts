import { ScheduleAttempt, ScheduleResponse } from './models';

/** How a schedule reads in the list: one label, and whether it is good, bad or still open. */
export interface ScheduleView {
  label: string;
  tone: 'ok' | 'bad' | 'pending';
}

const BRASILIA = 'America/Sao_Paulo';

/** "HH:mm" in Brasília, where the windows are defined, whatever the browser's time zone. */
export function brasiliaTime(instant: string): string {
  return new Intl.DateTimeFormat('pt-BR', { timeZone: BRASILIA, hour: '2-digit', minute: '2-digit' }).format(
    new Date(instant),
  );
}

/** Today's calendar date in Brasília, as yyyy-MM-dd. */
export function brasiliaToday(now: Date = new Date()): string {
  // en-CA formats dates as yyyy-MM-dd.
  return new Intl.DateTimeFormat('en-CA', { timeZone: BRASILIA }).format(now);
}

/** The schedule and its execution, as one status for the customer. */
export function scheduleView(s: ScheduleResponse): ScheduleView {
  if (s.status === 'CANCELLED') return { label: 'Cancelado', tone: 'bad' };
  const e = s.execution;
  switch (e.status) {
    case 'EXECUTED':
      return { label: 'Pago', tone: 'ok' };
    case 'FAILED':
      return { label: 'Não pago', tone: 'bad' };
    case 'PROCESSING':
      return { label: 'Em processamento', tone: 'pending' };
    case 'CANCELLED':
      return { label: 'Cancelado', tone: 'bad' };
    case 'PENDING':
      return e.attemptCount > 0 && e.nextAttemptAt
        ? { label: `Nova tentativa às ${brasiliaTime(e.nextAttemptAt)}`, tone: 'pending' }
        : { label: 'Agendado', tone: 'pending' };
  }
}

/** The scheduler refuses a cancellation from the payment day on; the screen does not offer it then. */
export function canCancel(s: ScheduleResponse, today: string = brasiliaToday()): boolean {
  return s.status === 'ACTIVE' && s.execution.status === 'PENDING' && s.executeOn > today;
}

export function attemptLabel(a: ScheduleAttempt): string {
  if (a.outcome === 'EXECUTED') return 'Pago';
  if (a.outcome === 'REFUSED') return 'Recusado';
  return a.endToEndId ? 'Pix enviado, aguardando liquidação' : 'Em andamento';
}

/** Who receives, for the list. */
export function payeeName(s: ScheduleResponse): string {
  return s.destination?.holderName ?? s.pixPayee?.name ?? '—';
}

export function scheduleTypeLabel(s: ScheduleResponse): string {
  return s.type === 'PIX' ? 'Pix' : 'Transferência';
}
