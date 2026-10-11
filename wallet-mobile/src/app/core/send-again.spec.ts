import { entry } from '../testing/fake-api';
import { counterpartyAccountText, sendAgain } from './send-again';

describe('sendAgain', () => {
  it('a transfer, sent or received, goes back to the other customer account', () => {
    const e = entry({
      type: 'TRANSFER',
      credit: false,
      counterpartyName: 'Joao Souza',
      counterpartyAccount: { branch: '0001', number: '00100002', checkDigit: '9' },
    });

    expect(sendAgain(e)).toEqual({
      kind: 'transfer',
      path: '/pagar/transferencia',
      prefill: { branch: '0001', number: '00100002', checkDigit: '9', name: 'Joao Souza' },
    });
    expect(counterpartyAccountText(e)).toBe('0001 / 00100002-9');
  });

  it('a Pix fills everything but the document, which comes only masked', () => {
    const e = entry({
      type: 'PIX_IN',
      counterpartyName: 'Ana Externa',
      pix: {
        endToEndId: 'E999',
        counterpartyTaxIdMasked: '***7735',
        counterpartyInstitution: '99999999',
        counterpartyBranch: '0042',
        counterpartyAccount: '1234565',
      },
    });

    expect(sendAgain(e)).toEqual({
      kind: 'pix',
      path: '/pagar/pix',
      prefill: {
        name: 'Ana Externa',
        ispb: '99999999',
        branch: '0042',
        account: '1234565',
        taxIdMasked: '***7735',
      },
    });
  });

  it('a deposit, a refund, or an entry without the account offers nothing', () => {
    expect(sendAgain(entry({ type: 'DEPOSIT' }))).toBeNull();
    expect(
      sendAgain(
        entry({
          type: 'PIX_REFUND',
          pix: { endToEndId: 'E1', counterpartyInstitution: '99999999', counterpartyAccount: '1' },
        }),
      ),
    ).toBeNull();
    expect(sendAgain(entry({ type: 'TRANSFER', counterpartyName: 'Joao' }))).toBeNull(); // older app-api
    expect(sendAgain(entry({ type: 'PIX_OUT', pix: { endToEndId: 'E2' } }))).toBeNull();
  });
});
