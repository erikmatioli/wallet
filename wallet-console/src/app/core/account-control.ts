import { AccountBlock, AccountDetailResponse, BlockReason, BlockType, CloseReason, TimelineItem } from './models';

/** The three blocks of ADR-013 and the fee block of ADR-014, as the operator reads them. */
export const BLOCK_TYPE_LABELS: Record<BlockType, string> = {
  FRAUD: 'Suspeita de fraude',
  ADMINISTRATIVE: 'Bloqueio total',
  JUDICIAL: 'Ordem judicial',
  FEE_PENDING: 'Tarifas em aberto',
};

/** What each block still lets through, in one line. */
export const BLOCK_EFFECTS: Record<BlockType, string> = {
  FRAUD: 'Recebe; só sai a devolução de Pix (MED).',
  ADMINISTRATIVE: 'Não recebe nem paga; só entram estornos.',
  JUDICIAL: 'Recebe; só sai a transferência por ordem judicial.',
  FEE_PENDING: 'Recebe; só saem a tarifa e a devolução de Pix (MED). Liberado sozinho quando as tarifas são pagas.',
};

export const BLOCK_REASON_LABELS: Record<BlockReason, string> = {
  FRAUD_SUSPICION: 'Suspeita de fraude',
  CUSTOMER_REQUEST: 'Pedido do cliente',
  REGISTRATION_PENDING: 'Pendência cadastral',
  INSTITUTION_DECISION: 'Decisão da instituição',
  COURT_ORDER: 'Ordem judicial',
  UNPAID_FEES: 'Tarifas não pagas',
  OTHER: 'Outro motivo',
};

/** The reasons of a total (administrative) block: fraud and court orders have their own block. */
export const ADMINISTRATIVE_REASONS: BlockReason[] = ['CUSTOMER_REQUEST', 'REGISTRATION_PENDING', 'INSTITUTION_DECISION', 'OTHER'];

export const CLOSE_REASON_LABELS: Record<CloseReason, string> = {
  CUSTOMER_REQUEST: 'Pedido do cliente',
  INSTITUTION_DECISION: 'Decisão da instituição',
  DEATH: 'Falecimento do titular',
  OTHER: 'Outro motivo',
};

/**
 * The scope that creates and releases a block of this type (ADR-013, item 6). The fee block's is wallet-fees' own:
 * the console never releases it, paying the fees does.
 */
export function blockScope(type: BlockType): string {
  if (type === 'FEE_PENDING') return 'fees:write';
  return type === 'JUDICIAL' ? 'accounts:judicial' : 'accounts:block';
}

/** One line for the account's header: what it can do now. */
export function accountSituation(acc: Pick<AccountDetailResponse, 'status' | 'canReceive' | 'canPay'>): string {
  if (acc.status === 'CLOSED') return 'Encerrada';
  if (acc.canReceive && acc.canPay) return 'Ativa';
  if (acc.canReceive) return 'Bloqueada para pagamentos';
  return 'Bloqueada';
}

export function activeJudicialBlocks(blocks: AccountBlock[]): AccountBlock[] {
  return blocks.filter((b) => b.active && b.type === 'JUDICIAL');
}

const TIMELINE_LABELS: Record<TimelineItem['kind'], string> = {
  OPENED: 'Conta aberta',
  CLOSED: 'Conta encerrada',
  BLOCKED: 'Bloqueio',
  RELEASED: 'Liberação',
};

/** "Bloqueio: Ordem judicial" / "Conta encerrada". */
export function timelineLabel(item: TimelineItem): string {
  const kind = TIMELINE_LABELS[item.kind];
  return item.blockType ? `${kind}: ${BLOCK_TYPE_LABELS[item.blockType]}` : kind;
}

/** The reason of a line, readable: a block's or a closing's. */
export function timelineReason(item: TimelineItem): string | null {
  if (!item.reason) return null;
  return (
    BLOCK_REASON_LABELS[item.reason as BlockReason] ?? CLOSE_REASON_LABELS[item.reason as CloseReason] ?? item.reason
  );
}

const OPERATOR_KEY = 'wallet-console.operator';

/** The operator's name, remembered in this browser only: a convenience, the core records what each form sends. */
export function rememberedOperator(): string {
  try {
    return localStorage.getItem(OPERATOR_KEY) ?? '';
  } catch {
    return '';
  }
}

export function rememberOperator(name: string): void {
  try {
    localStorage.setItem(OPERATOR_KEY, name.trim());
  } catch {
    // private window or blocked storage: the field just starts empty next time
  }
}
