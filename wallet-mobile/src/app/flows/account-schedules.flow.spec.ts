import { AppApiError } from '../core/api';
import { TAX_ID_ERROR } from '../core/format';
import { InactivityMonitor } from '../core/inactivity';
import { SessionStore } from '../core/session';
import { entry, fakeApi, schedule } from '../testing/fake-api';
import { HomeFlow, StatementFlow } from './account.flow';
import { NewScheduleFlow, SchedulesFlow } from './schedules.flow';

describe('HomeFlow and StatementFlow', () => {
  it('home loads the account and the last 5 entries together', async () => {
    const api = fakeApi();
    api.me.mockResolvedValue({
      customerName: 'Maria',
      branch: '0001',
      accountNumber: '00100123',
      checkDigit: '8',
      status: 'ACTIVE',
      balanceCents: 35650,
    });
    api.statement.mockResolvedValue({ entries: [entry()], nextBefore: null });
    const flow = new HomeFlow(api);

    await flow.load();

    expect(flow.me()?.balanceCents).toBe(35650);
    expect(flow.recent()).toHaveLength(1);
    expect(api.statement).toHaveBeenCalledWith(null, 5);
  });

  it('a failure on home becomes a message', async () => {
    const api = fakeApi();
    api.me.mockRejectedValue(new AppApiError('ACCOUNT_NOT_FOUND', 'Conta não encontrada.'));
    api.statement.mockResolvedValue({ entries: [] });
    const flow = new HomeFlow(api);
    await flow.load();
    expect(flow.error()).toBe('Conta não encontrada.');
    expect(flow.loading()).toBe(false);
  });

  it('the statement pages with the cursor and groups by day in Brasília', async () => {
    const api = fakeApi();
    api.statement
      .mockResolvedValueOnce({
        entries: [
          entry({ sequence: 3, occurredAt: '2026-10-09T15:00:00Z' }),
          entry({ sequence: 2, occurredAt: '2026-10-09T02:00:00Z' }), // 23:00 of the 8th in Brasília
        ],
        nextBefore: 2,
      })
      .mockResolvedValueOnce({
        entries: [entry({ sequence: 1, occurredAt: '2026-10-08T12:00:00Z' })],
        nextBefore: null,
      });
    const flow = new StatementFlow(api, 2);

    await flow.loadMore();
    await flow.loadMore();
    await flow.loadMore(); // nothing more to load: no call

    expect(api.statement).toHaveBeenNthCalledWith(1, null, 2);
    expect(api.statement).toHaveBeenNthCalledWith(2, 2, 2);
    expect(api.statement).toHaveBeenCalledTimes(2);
    expect(flow.days().map((d) => [d.day, d.entries.length])).toEqual([
      ['2026-10-09', 1],
      ['2026-10-08', 2],
    ]);
    expect(flow.hasMore()).toBe(false);
  });
});

describe('SchedulesFlow and NewScheduleFlow', () => {
  const now = () => new Date('2026-10-09T15:00:00Z');

  it('cancelling updates the open detail and the list', async () => {
    const api = fakeApi();
    api.schedules.mockResolvedValue([schedule(), schedule({ id: 's-2' })]);
    api.cancelSchedule.mockResolvedValue(schedule({ status: 'CANCELLED', canCancel: false }));
    const flow = new SchedulesFlow(api);
    await flow.load();
    flow.open(flow.schedules()[0]);

    await flow.cancel();

    expect(flow.selected()?.status).toBe('CANCELLED');
    expect(flow.schedules().map((s) => s.status)).toEqual(['CANCELLED', 'ACTIVE']);
  });

  it('a Pix schedule needs the payee, a day from tomorrow on and a valid document', () => {
    const flow = new NewScheduleFlow(fakeApi(), now);
    flow.edit({ pix: true, amountCents: 1000 });
    flow.review();
    expect(flow.error()).toBe('Escolha o dia do pagamento.');

    flow.edit({ date: '2026-10-09' });
    flow.review();
    expect(flow.error()).toBe('O agendamento precisa ser a partir de amanhã.');

    flow.edit({ date: '2026-10-10' });
    flow.review();
    expect(flow.error()).toBe('Preencha os dados do recebedor.');

    flow.edit({ name: 'F', taxId: '1', ispb: '99999999', pixBranch: '0042', account: '1234565' });
    flow.review();
    expect(flow.error()).toBe(TAX_ID_ERROR);
    expect(flow.step().kind).toBe('form');
  });

  it('a transfer schedule: form, confirmation, created - and a retry keeps the key', async () => {
    const api = fakeApi();
    api.createSchedule
      .mockRejectedValueOnce(new TypeError('timeout'))
      .mockResolvedValueOnce(schedule());
    const flow = new NewScheduleFlow(api, now);
    flow.edit({
      date: '2026-10-20',
      amountCents: 1000,
      branch: '0001',
      number: '00100161',
      checkDigit: '0',
    });
    flow.review();

    await flow.confirm();
    expect(flow.step().kind).toBe('confirm');
    await flow.confirm();

    expect(api.createSchedule.mock.calls[0][0]).toEqual({
      type: 'TRANSFER',
      executeOn: '2026-10-20',
      amountCents: 1000,
      description: null,
      transfer: { branch: '0001', number: '00100161', checkDigit: '0' },
      pix: null,
    });
    expect(api.createSchedule.mock.calls[1][1]).toBe(api.createSchedule.mock.calls[0][1]);
    expect(flow.step().kind).toBe('done');
  });
});

describe('session and inactivity', () => {
  it('five minutes without input expire; any input restarts the count', () => {
    let t = 0;
    const monitor = new InactivityMonitor(5 * 60_000, () => t);
    t = 4 * 60_000;
    monitor.touch();
    t = 8 * 60_000;
    expect(monitor.expired()).toBe(false);
    t = 9 * 60_000;
    expect(monitor.expired()).toBe(true);
  });

  it('an expired session says why; logging out does not', () => {
    const store = new SessionStore();
    store.start({ token: 't', expiresInSeconds: 1800, customerName: 'Maria' });
    store.expire('Sua sessão expirou. Entre de novo.');
    expect(store.loggedIn()).toBe(false);
    expect(store.endedBecause()).toBe('Sua sessão expirou. Entre de novo.');

    store.start({ token: 't', expiresInSeconds: 1800, customerName: 'Maria' });
    store.logout();
    expect(store.endedBecause()).toBeNull();
    expect(store.token).toBeNull();
  });
});
