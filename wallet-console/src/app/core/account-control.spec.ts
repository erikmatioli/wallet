import { accountSituation, activeJudicialBlocks, blockScope, timelineLabel, timelineReason } from './account-control';
import { AccountBlock, TimelineItem } from './models';

const block = (type: AccountBlock['type'], active = true): AccountBlock => ({
  id: type,
  accountId: 'a',
  type,
  reason: type === 'JUDICIAL' ? 'COURT_ORDER' : 'FRAUD_SUSPICION',
  note: null,
  court: null,
  createdBy: { operator: 'maria', clientId: 'demo-tenant' },
  createdAt: '2026-10-11T12:00:00Z',
  active,
  releasedBy: null,
  releasedAt: null,
  releaseNote: null,
});

describe('account control', () => {
  it('says what the account can do now', () => {
    expect(accountSituation({ status: 'ACTIVE', canReceive: true, canPay: true })).toBe('Ativa');
    expect(accountSituation({ status: 'ACTIVE', canReceive: true, canPay: false })).toBe('Bloqueada para pagamentos');
    expect(accountSituation({ status: 'ACTIVE', canReceive: false, canPay: false })).toBe('Bloqueada');
    expect(accountSituation({ status: 'CLOSED', canReceive: false, canPay: false })).toBe('Encerrada');
  });

  it('only the judicial scope handles a judicial block', () => {
    expect(blockScope('JUDICIAL')).toBe('accounts:judicial');
    expect(blockScope('FRAUD')).toBe('accounts:block');
    expect(blockScope('ADMINISTRATIVE')).toBe('accounts:block');
  });

  it('finds the active court orders', () => {
    const blocks = [block('FRAUD'), block('JUDICIAL'), { ...block('JUDICIAL'), id: 'old', active: false }];
    expect(activeJudicialBlocks(blocks).map((b) => b.id)).toEqual(['JUDICIAL']);
  });

  it('writes the timeline lines', () => {
    const item: TimelineItem = {
      kind: 'BLOCKED',
      at: '2026-10-11T12:00:00Z',
      actor: { operator: 'maria', clientId: 'demo-tenant' },
      reason: 'COURT_ORDER',
      note: null,
      blockId: 'b',
      blockType: 'JUDICIAL',
      court: { process: '0001234-56.2026.8.26.0100' },
    };
    expect(timelineLabel(item)).toBe('Bloqueio: Ordem judicial');
    expect(timelineReason(item)).toBe('Ordem judicial');
    expect(timelineLabel({ ...item, kind: 'CLOSED', blockType: null })).toBe('Conta encerrada');
    expect(timelineReason({ ...item, reason: 'DEATH' })).toBe('Falecimento do titular');
  });
});
