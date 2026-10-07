import { ScheduleResponse } from './models';
import { attemptLabel, brasiliaToday, canCancel, payeeName, scheduleView } from './schedule-labels';

function schedule(overrides: Partial<ScheduleResponse> = {}, execution: Partial<ScheduleResponse['execution']> = {}): ScheduleResponse {
  return {
    id: 's1',
    type: 'TRANSFER',
    status: 'ACTIVE',
    payerAccountId: 'a1',
    destination: { branch: '0001', number: '1234567', checkDigit: '5', accountId: 'a2', holderName: 'João' },
    pixPayee: null,
    amount: 10,
    description: null,
    executeOn: '2026-10-08',
    createdAt: '2026-10-07T13:00:00Z',
    cancelledAt: null,
    attempts: [],
    ...overrides,
    execution: {
      status: 'PENDING',
      attemptCount: 0,
      nextAttemptAt: '2026-10-08T09:00:00Z',
      transactionId: null,
      endToEndId: null,
      failure: null,
      ...execution,
    },
  };
}

describe('scheduleView', () => {
  it('reads a waiting schedule as scheduled', () => {
    expect(scheduleView(schedule())).toEqual({ label: 'Agendado', tone: 'pending' });
  });

  it('shows the next window, in Brasília time, after a refusal', () => {
    // 15:00 UTC is 12:00 in Brasília.
    const s = schedule({}, { attemptCount: 1, nextAttemptAt: '2026-10-08T15:00:00Z' });
    expect(scheduleView(s).label).toBe('Nova tentativa às 12:00');
  });

  it('reads the final states', () => {
    expect(scheduleView(schedule({ status: 'COMPLETED' }, { status: 'EXECUTED' })).label).toBe('Pago');
    expect(scheduleView(schedule({ status: 'COMPLETED' }, { status: 'FAILED' }))).toEqual({ label: 'Não pago', tone: 'bad' });
    expect(scheduleView(schedule({ status: 'CANCELLED' }, { status: 'CANCELLED' })).label).toBe('Cancelado');
  });
});

describe('canCancel', () => {
  it('only until the day before the payment', () => {
    expect(canCancel(schedule(), '2026-10-07')).toBe(true);
    expect(canCancel(schedule(), '2026-10-08')).toBe(false);
    expect(canCancel(schedule({ status: 'CANCELLED' }), '2026-10-07')).toBe(false);
  });
});

describe('brasiliaToday', () => {
  it('uses the calendar day in Brasília', () => {
    // 01:00 UTC on the 9th is still 22:00 on the 8th in Brasília.
    expect(brasiliaToday(new Date('2026-10-09T01:00:00Z'))).toBe('2026-10-08');
  });
});

describe('attemptLabel and payeeName', () => {
  it('tells a Pix waiting for settlement from an attempt with no answer yet', () => {
    const base = { number: 1, startedAt: '', finishedAt: null, reason: null, transactionId: null };
    expect(attemptLabel({ ...base, outcome: null, endToEndId: 'E1' })).toBe('Pix enviado, aguardando liquidação');
    expect(attemptLabel({ ...base, outcome: null, endToEndId: null })).toBe('Em andamento');
    expect(attemptLabel({ ...base, outcome: 'REFUSED', endToEndId: null })).toBe('Recusado');
  });

  it('names the payee of a transfer or a Pix', () => {
    expect(payeeName(schedule())).toBe('João');
    const pix = schedule({
      type: 'PIX',
      destination: null,
      pixPayee: { ispb: '99999999', branch: '0042', accountNumber: '1234565', taxIdMasked: '***7735', name: 'Fulano' },
    });
    expect(payeeName(pix)).toBe('Fulano');
  });
});
