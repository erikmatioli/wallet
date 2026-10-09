import {
  centsFromTyping,
  amountField,
  cpfMask,
  dayTitle,
  dateTime,
  isTaxId,
  money,
  scheduleStatus,
  tomorrow,
  uuid,
} from './format';
import { schedule } from '../testing/fake-api';

describe('format', () => {
  it('writes money in pt-BR from cents, never through a float of the input', () => {
    expect(money(123456)).toBe('R$ 1.234,56');
    expect(money(5)).toBe('R$ 0,05');
    expect(money(0)).toBe('R$ 0,00');
  });

  it('fills the cents from the right as digits are typed, ignoring anything else', () => {
    expect(centsFromTyping('1')).toBe(1);
    expect(centsFromTyping('R$ 0,15')).toBe(15);
    expect(centsFromTyping('R$ 1.234,567')).toBe(1234567);
    expect(centsFromTyping('')).toBe(0);
    expect(amountField(0)).toBe('');
    expect(amountField(150)).toBe('R$ 1,50');
  });

  it('masks a CPF while it is typed', () => {
    expect(cpfMask('529')).toBe('529');
    expect(cpfMask('5299822')).toBe('529.982.2');
    expect(cpfMask('52998224725')).toBe('529.982.247-25');
    expect(cpfMask('529.982.247-2599')).toBe('529.982.247-25');
  });

  it('accepts a CPF or a CNPJ, alphanumeric included, and nothing of another size', () => {
    expect(isTaxId('111.444.777-35')).toBe(true);
    expect(isTaxId('12.abc.345/01de-35')).toBe(true);
    expect(isTaxId('111.444.777-3')).toBe(false);
    expect(isTaxId('12.ABC.345/01DE-3X')).toBe(false);
  });

  it('shows instants and days in Brasília', () => {
    expect(dateTime('2026-10-09T02:30:00Z')).toBe('08/10/2026 23:30');
    const now = new Date('2026-10-09T15:00:00Z');
    expect(dayTitle('2026-10-09', now)).toBe('Hoje');
    expect(dayTitle('2026-10-08', now)).toBe('Ontem');
    expect(dayTitle('2026-10-06', now)).toBe('Terça-feira, 6 de outubro');
    // 01:00 UTC is still the day before in Brasília: tomorrow is the 9th, not the 10th.
    expect(tomorrow(new Date('2026-10-08T01:00:00Z'))).toBe('2026-10-08');
    expect(tomorrow(new Date('2026-12-31T15:00:00Z'))).toBe('2027-01-01');
  });

  it('says where a schedule is in the words of the console', () => {
    expect(scheduleStatus(schedule())).toBe('Agendado');
    expect(scheduleStatus(schedule({ status: 'CANCELLED' }))).toBe('Cancelado');
    expect(scheduleStatus(schedule({ execution: { status: 'EXECUTED', attemptCount: 1 } }))).toBe(
      'Pago',
    );
    expect(
      scheduleStatus(
        schedule({
          execution: { status: 'PENDING', attemptCount: 1, nextAttemptAt: '2026-10-20T17:00:00Z' },
        }),
      ),
    ).toBe('Nova tentativa às 14:00');
  });

  it('makes v4 UUIDs without crypto.randomUUID (not there over plain http)', () => {
    const id = uuid();
    expect(id).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
    expect(uuid()).not.toBe(id);
  });
});
