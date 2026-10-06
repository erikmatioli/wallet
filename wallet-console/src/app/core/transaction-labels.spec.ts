import { EntryResponse } from './models';
import { counterpartyName, reasonLabel, transactionLabel } from './transaction-labels';

function entry(pix: EntryResponse['pix']): EntryResponse {
  return {
    transactionId: 't',
    sequence: 1,
    type: 'PIX_IN',
    direction: 'CREDIT',
    amount: 10,
    balanceAfter: 10,
    description: '',
    occurredAt: '2026-10-06T12:00:00Z',
    counterpartyAccountId: null,
    counterpartyCustomerName: null,
    counterpartyAccountFormatted: null,
    pix,
  };
}

describe('transactionLabel', () => {
  it('names every Pix type', () => {
    expect(transactionLabel('PIX_IN')).toBe('Pix recebido');
    expect(transactionLabel('PIX_OUT')).toBe('Pix enviado');
    expect(transactionLabel('PIX_REFUND')).toBe('Estorno de Pix');
    expect(transactionLabel('PIX_RETURN_IN')).toBe('Devolução recebida');
    expect(transactionLabel('PIX_RETURN_OUT')).toBe('Devolução enviada');
  });

  it('shows an unknown type as it came', () => {
    expect(transactionLabel('BOLETO')).toBe('BOLETO');
  });
});


describe('reasonLabel', () => {
  it('translates known refund and return reasons', () => {
    expect(reasonLabel('AC03')).toBe('Conta do recebedor inexistente ou inválida');
    expect(reasonLabel('MD06')).toBe('Devolução solicitada pelo recebedor');
  });

  it('is null for an unknown or missing code', () => {
    expect(reasonLabel('ZZ99')).toBeNull();
    expect(reasonLabel(null)).toBeNull();
  });
});

describe('counterpartyName', () => {
  it('uses the Pix counterparty when there is no transfer counterparty', () => {
    const pix = {
      endToEndId: 'E1',
      returnId: null,
      relatedTransactionId: null,
      counterparty: { name: 'Maria', taxIdMasked: '***7735', ispb: '99999999', branch: null, account: '1', accountType: null },
      reasonCode: null,
      remittanceInfo: null,
    };
    expect(counterpartyName(entry(pix))).toBe('Maria');
  });

  it('is null for a deposit', () => {
    expect(counterpartyName(entry(null))).toBeNull();
  });
});
