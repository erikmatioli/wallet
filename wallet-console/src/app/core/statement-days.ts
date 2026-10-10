import { EntryResponse } from './models';

const BRASILIA = 'America/Sao_Paulo';

/**
 * One day of the statement. The statement comes newest first, so the day's first line is its last entry and
 * `closingBalance` is the balance at the end of the day (wallet-core's ADR-012) - the balance so far, today.
 * Only true for the whole statement: with a type filter (only Pix), the day's last entry may be hidden.
 */
export interface StatementDay {
  /** yyyy-MM-dd, in Brasília. */
  day: string;
  closingBalance: number;
  entries: EntryResponse[];
}

/** The calendar date an instant falls on, in Brasília, as yyyy-MM-dd (en-CA formats it that way). */
export function brasiliaDay(instant: string): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: BRASILIA }).format(new Date(instant));
}

/** "Hoje", "Ontem" or "quinta-feira, 08/10/2026". `day` and `today` are yyyy-MM-dd. */
export function dayLabel(day: string, today: string): string {
  const yesterday = new Date(`${today}T12:00:00Z`);
  yesterday.setUTCDate(yesterday.getUTCDate() - 1);
  if (day === today) return 'Hoje';
  if (day === yesterday.toISOString().slice(0, 10)) return 'Ontem';
  // Noon UTC of that date: the same calendar day in any time zone the formatter is told to use (UTC here).
  return new Intl.DateTimeFormat('pt-BR', {
    timeZone: 'UTC',
    weekday: 'long',
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
  }).format(new Date(`${day}T12:00:00Z`));
}

/** The loaded entries (newest first) grouped by day, in the same order. */
export function statementDays(entries: EntryResponse[]): StatementDay[] {
  const days: StatementDay[] = [];
  for (const e of entries) {
    const day = brasiliaDay(e.occurredAt);
    const last = days.at(-1);
    if (last?.day === day) last.entries.push(e);
    else days.push({ day, closingBalance: e.balanceAfter, entries: [e] });
  }
  return days;
}
