import { EntryResponse } from './models';
import { brasiliaDay, dayLabel, statementDays } from './statement-days';

function entry(sequence: number, occurredAt: string, balanceAfter: number): EntryResponse {
  return {
    transactionId: `tx-${sequence}`,
    sequence,
    type: 'DEPOSIT',
    direction: 'CREDIT',
    amount: 1,
    balanceAfter,
    description: '',
    occurredAt,
  } as EntryResponse;
}

describe('statementDays', () => {
  it('groups by day in Brasília and takes each day closing balance from its first line', () => {
    const days = statementDays([
      entry(3, '2026-10-09T15:00:00Z', 70),
      entry(2, '2026-10-09T02:00:00Z', 60), // 23:00 of the 8th in Brasília
      entry(1, '2026-10-08T12:00:00Z', 55),
    ]);

    expect(days.map((d) => [d.day, d.closingBalance, d.entries.length])).toEqual([
      ['2026-10-09', 70, 1],
      ['2026-10-08', 60, 2],
    ]);
  });

  it('names the day the way the statement shows it', () => {
    expect(dayLabel('2026-10-09', '2026-10-09')).toBe('Hoje');
    expect(dayLabel('2026-10-08', '2026-10-09')).toBe('Ontem');
    expect(dayLabel('2026-10-06', '2026-10-09')).toBe('terça-feira, 06/10/2026');
    expect(dayLabel('2026-02-28', '2026-03-01')).toBe('Ontem');
  });

  it('reads the day in Brasília whatever the browser time zone', () => {
    expect(brasiliaDay('2026-10-10T02:59:59Z')).toBe('2026-10-09');
    expect(brasiliaDay('2026-10-10T03:00:00Z')).toBe('2026-10-10');
  });
});
