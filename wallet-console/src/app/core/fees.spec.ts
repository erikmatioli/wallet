import { FeePackage, FeeSchedule } from './models';
import {
  changeLabel,
  chargeReason,
  chargeTone,
  draftFrom,
  monthLabel,
  operationLabel,
  packagesOf,
  scheduleLines,
  scheduleRequest,
} from './fees';

function schedule(validFrom: string, operations: FeeSchedule['operations'], monthlyFee = 0): FeeSchedule {
  return { id: validFrom, validFrom, monthlyFee, operations, createdBy: 'sistema', createdAt: `${validFrom}T03:00:00Z` };
}

function pkg(id: string, type: 'PF' | 'PJ', isDefault: boolean, current: FeeSchedule | null = null, next: FeeSchedule | null = null): FeePackage {
  return { id, code: id, name: id, customerType: type, isDefault, current, next };
}

describe('labels', () => {
  it('names the operations, the monthly fee included', () => {
    expect(operationLabel('PIX_IN')).toBe('Pix recebido');
    expect(operationLabel('MONTHLY')).toBe('Mensalidade');
    expect(operationLabel('NEW_ONE')).toBe('NEW_ONE');
  });

  it('writes a month as the statement does', () => {
    expect(monthLabel('2026-10')).toBe('10/2026');
  });
});

describe('chargeReason', () => {
  it('says what a pending fee waits for', () => {
    expect(chargeReason({ status: 'PENDING', lastError: 'INSUFFICIENT_FUNDS' })).toContain('próximo crédito');
    expect(chargeReason({ status: 'PENDING', lastError: 'ACCOUNT_BLOCKED' })).toContain('bloqueio');
    expect(chargeReason({ status: 'PENDING', lastError: 'UNAVAILABLE' })).toContain('não respondeu');
    expect(chargeReason({ status: 'PENDING', lastError: null })).toBe('aguardando a cobrança');
  });

  it('says why a fee was cancelled or given back', () => {
    expect(chargeReason({ status: 'CANCELLED', lastError: 'ACCOUNT_CLOSED' })).toBe('conta encerrada');
    expect(chargeReason({ status: 'CANCELLED', lastError: 'REVERSED' })).toContain('estornada antes');
    expect(chargeReason({ status: 'REFUNDED', lastError: null })).toContain('estornada');
    expect(chargeReason({ status: 'CHARGED', lastError: null })).toBeNull();
  });

  it('colors only a pending fee as a problem', () => {
    expect(chargeTone('PENDING')).toBe('bad');
    expect(chargeTone('CHARGED')).toBe('ok');
    expect(chargeTone('REFUNDED')).toBe('muted');
  });
});

describe('packagesOf', () => {
  it('keeps one customer type, the default first', () => {
    const list = [pkg('PF_VIP', 'PF', false), pkg('PJ_BASIC', 'PJ', true), pkg('PF_BASIC', 'PF', true)];
    expect(packagesOf(list, 'PF').map((p) => p.id)).toEqual(['PF_BASIC', 'PF_VIP']);
  });
});

describe('schedules', () => {
  const current = schedule('2026-10-01', [
    { operation: 'WITHDRAWAL', freePerMonth: 0, price: 1, priceFromCompany: null },
    { operation: 'PIX_IN', freePerMonth: 0, price: 0, priceFromCompany: 0.5 },
  ]);

  it('lists every operation, the missing ones as free', () => {
    const lines = scheduleLines(current);
    expect(lines.map((l) => l.operation)).toEqual(['PIX_OUT', 'PIX_IN', 'TRANSFER_OUT', 'WITHDRAWAL']);
    expect(lines[0]).toEqual({ operation: 'PIX_OUT', freePerMonth: 0, price: 0, priceFromCompany: null });
    expect(lines[3].price).toBe(1);
  });

  it('starts the form from the next schedule when there is one', () => {
    const next = schedule('2026-11-01', [], 9.9);
    expect(draftFrom(pkg('p', 'PF', true, current, next), '2026-11-01').monthlyFee).toBe(9.9);
    expect(draftFrom(pkg('p', 'PF', true, current), '2026-11-01').lines[1].priceFromCompany).toBe(0.5);
  });

  it('sends the company price only for a Pix received', () => {
    const draft = draftFrom(pkg('p', 'PF', true, current), '2026-11-01');
    draft.lines[0].priceFromCompany = 3;
    const request = scheduleRequest(draft, '  Ana ');
    expect(request.operator).toBe('Ana');
    expect(request.operations.find((o) => o.operation === 'PIX_OUT')!.priceFromCompany).toBeNull();
    expect(request.operations.find((o) => o.operation === 'PIX_IN')!.priceFromCompany).toBe(0.5);
  });
});

describe('changeLabel', () => {
  const packages = [{ id: 'a', name: 'Basic' }, { id: 'b', name: 'Standard' }];

  it('names both packages of a migration', () => {
    expect(changeLabel({ fromPackageId: 'a', toPackageId: 'b' }, packages)).toBe('Basic → Standard');
    expect(changeLabel({ fromPackageId: null, toPackageId: 'a' }, packages)).toBe('Entrou no Basic');
  });
});
