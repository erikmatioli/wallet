import { AppApiError } from '../core/api';
import { TAX_ID_ERROR } from '../core/format';
import { fakeApi, pix } from '../testing/fake-api';
import { AMOUNT_ERROR, PixFlow, TransferFlow } from './payments.flow';

const destination = {
  holderName: 'João Souza',
  branch: '0001',
  number: '00100161',
  checkDigit: '0',
};

describe('TransferFlow', () => {
  it('form, confirmation with who receives, then the receipt', async () => {
    const api = fakeApi();
    api.transferDestination.mockResolvedValue(destination);
    api.transfer.mockResolvedValue({
      transactionId: 't-1',
      amountCents: 1050,
      occurredAt: '2026-10-09T15:00:00Z',
      destination,
    });
    const flow = new TransferFlow(api);
    flow.edit({
      branch: '0001',
      number: '00100161',
      checkDigit: '0',
      amountCents: 1050,
      description: ' aluguel ',
    });

    await flow.review();
    const step = flow.step();
    expect(step.kind).toBe('confirm');
    if (step.kind !== 'confirm') return;
    expect(step.preview.destination.holderName).toBe('João Souza');

    await flow.confirm();

    expect(api.transfer).toHaveBeenCalledWith(
      {
        branch: '0001',
        number: '00100161',
        checkDigit: '0',
        amountCents: 1050,
        description: 'aluguel',
      },
      step.idempotencyKey,
    );
    expect(flow.step().kind).toBe('done');
  });

  it('a failed confirmation stays on the confirmation, and trying again repeats the same key', async () => {
    const api = fakeApi();
    api.transferDestination.mockResolvedValue(destination);
    api.transfer
      .mockRejectedValueOnce(new AppApiError('INSUFFICIENT_FUNDS', 'Saldo insuficiente.'))
      .mockResolvedValueOnce({
        transactionId: 't-1',
        amountCents: 100,
        occurredAt: '2026-10-09T15:00:00Z',
        destination,
      });
    const flow = new TransferFlow(api);
    flow.edit({ branch: '0001', number: '00100161', checkDigit: '0', amountCents: 100 });
    await flow.review();

    await flow.confirm();
    expect(flow.error()).toBe('Saldo insuficiente.');
    expect(flow.step().kind).toBe('confirm');
    await flow.confirm();

    const [first, second] = api.transfer.mock.calls.map((c) => c[1]);
    expect(second).toBe(first);
    expect(flow.step().kind).toBe('done');
  });

  it('changing the data gives the next confirmation a new key', async () => {
    const api = fakeApi();
    api.transferDestination.mockResolvedValue(destination);
    const flow = new TransferFlow(api);
    flow.edit({ branch: '0001', number: '00100161', checkDigit: '0', amountCents: 100 });
    await flow.review();
    const first = flow.step();
    flow.change();
    await flow.review();
    const second = flow.step();
    expect(
      first.kind === 'confirm' &&
        second.kind === 'confirm' &&
        first.idempotencyKey !== second.idempotencyKey,
    ).toBe(true);
  });

  it('no amount, or no account, never reaches the server', async () => {
    const api = fakeApi();
    const flow = new TransferFlow(api);
    flow.edit({ branch: '0001', number: '200', checkDigit: '2' });
    await flow.review();
    expect(flow.error()).toBe(AMOUNT_ERROR);

    flow.edit({ number: '', amountCents: 500 });
    await flow.review();
    expect(flow.error()).toContain('agência, conta e dígito');
    expect(api.transferDestination).not.toHaveBeenCalled();
  });
});

describe('PixFlow', () => {
  const filled = {
    name: 'Fulano',
    taxId: '111.444.777-35',
    ispb: '99999999',
    branch: '0042',
    account: '1234565',
    amountCents: 1500,
  };

  afterEach(() => vi.useRealTimers());

  it('a payee document of the wrong size never reaches the server', () => {
    const api = fakeApi();
    const flow = new PixFlow(api);
    flow.edit({ ...filled, taxId: '111.444.777-3' });

    flow.review();

    expect(flow.error()).toBe(TAX_ID_ERROR);
    expect(flow.step().kind).toBe('form');
    flow.edit({ taxId: '12.abc.345/01de-35' }); // alphanumeric CNPJ
    flow.review();
    expect(flow.step().kind).toBe('confirm');
  });

  it('the receipt follows the Pix until it is settled', async () => {
    vi.useFakeTimers();
    const api = fakeApi();
    api.sendPix.mockResolvedValue(pix('SENT'));
    api.pixStatus.mockResolvedValueOnce(pix('SENT')).mockResolvedValueOnce(pix('COMPLETED'));
    const flow = new PixFlow(api, 1_000);
    flow.edit(filled);
    flow.review();

    const done = flow.confirm();
    await vi.runAllTimersAsync();
    await done;

    const step = flow.step();
    expect(step.kind === 'done' && step.receipt.status).toBe('COMPLETED');
    expect(api.pixStatus).toHaveBeenCalledTimes(2);
    expect(api.sendPix.mock.calls[0][0].payee).toEqual({
      ispb: '99999999',
      branch: '0042',
      accountNumber: '1234565',
      taxId: '111.444.777-35',
      name: 'Fulano',
    });
  });

  it('a rejected Pix says why on the receipt', async () => {
    const api = fakeApi();
    api.sendPix.mockResolvedValue(
      pix('REFUNDED', {
        reasonCode: 'AC03',
        reasonMessage: 'Conta do recebedor inexistente ou inválida.',
      }),
    );
    const flow = new PixFlow(api);
    flow.edit(filled);
    flow.review();

    await flow.confirm();

    const step = flow.step();
    expect(step.kind === 'done' && step.receipt.reasonMessage).toBe(
      'Conta do recebedor inexistente ou inválida.',
    );
    expect(api.pixStatus).not.toHaveBeenCalled();
  });
});
