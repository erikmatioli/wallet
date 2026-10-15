import {
  ChargeStatus,
  CreateFeeScheduleRequest,
  CustomerType,
  FeeCharge,
  FeeOperation,
  FeePackage,
  FeeSchedule,
} from './models';

/** The operations a schedule prices, in the order the screens list them. */
export const FEE_OPERATIONS: FeeOperation[] = ['PIX_OUT', 'PIX_IN', 'TRANSFER_OUT', 'WITHDRAWAL'];

const OPERATION_LABELS: Record<string, string> = {
  PIX_OUT: 'Pix enviado',
  PIX_IN: 'Pix recebido',
  TRANSFER_OUT: 'Transferência enviada',
  WITHDRAWAL: 'Saque',
  MONTHLY: 'Mensalidade',
};

/** Readable name of a fee's operation; an unknown one (a newer API) is shown as it came. */
export function operationLabel(operation: string): string {
  return OPERATION_LABELS[operation] ?? operation;
}

export const CUSTOMER_TYPE_LABELS: Record<CustomerType, string> = {
  PF: 'Pessoa física',
  PJ: 'Pessoa jurídica',
};

export const CHARGE_STATUS_LABELS: Record<ChargeStatus, string> = {
  PENDING: 'Pendente',
  CHARGED: 'Cobrada',
  CANCELLED: 'Cancelada',
  REFUNDED: 'Estornada',
};

/** How a fee's situation is colored: a pending fee is what the operator should look at. */
export function chargeTone(status: ChargeStatus): 'ok' | 'bad' | 'muted' {
  if (status === 'CHARGED') return 'ok';
  if (status === 'PENDING') return 'bad';
  return 'muted';
}

const PENDING_REASONS: Record<string, string> = {
  INSUFFICIENT_FUNDS: 'sem saldo: é cobrada no próximo crédito',
  ACCOUNT_BLOCKED: 'conta bloqueada: é cobrada quando o bloqueio for liberado',
};

const CANCEL_REASONS: Record<string, string> = {
  ACCOUNT_CLOSED: 'conta encerrada',
  ACCOUNT_NOT_ACTIVE: 'conta encerrada',
  ACCOUNT_NOT_FOUND: 'conta não encontrada no core',
  REVERSED: 'a transação foi estornada antes da cobrança',
};

/** Why a fee is still pending, was cancelled or given back; null for a charged one. */
export function chargeReason(charge: Pick<FeeCharge, 'status' | 'lastError'>): string | null {
  switch (charge.status) {
    case 'PENDING':
      if (!charge.lastError) return 'aguardando a cobrança';
      return PENDING_REASONS[charge.lastError] ?? 'o core não respondeu: nova tentativa em breve';
    case 'CANCELLED':
      return (charge.lastError && CANCEL_REASONS[charge.lastError]) || null;
    case 'REFUNDED':
      return 'a transação foi estornada';
    default:
      return null;
  }
}

/** "2026-10" → "10/2026". */
export function monthLabel(month: string): string {
  const [year, m] = month.split('-');
  return m ? `${m}/${year}` : month;
}

/** The packages of one customer type, the default (basic) one first. */
export function packagesOf(packages: FeePackage[], type: CustomerType): FeePackage[] {
  return packages.filter((p) => p.customerType === type).sort((a, b) => Number(b.isDefault) - Number(a.isDefault));
}

/** A schedule's line per operation, in the screen's order. An operation the schedule does not list is free. */
export function scheduleLines(schedule: FeeSchedule): {
  operation: FeeOperation;
  freePerMonth: number;
  price: number;
  priceFromCompany: number | null;
}[] {
  return FEE_OPERATIONS.map(
    (operation) =>
      schedule.operations.find((o) => o.operation === operation) ?? {
        operation,
        freePerMonth: 0,
        price: 0,
        priceFromCompany: null,
      },
  );
}

/** What the new-schedule form edits: numbers as typed, the company price only for a Pix received. */
export interface ScheduleDraft {
  validFrom: string;
  monthlyFee: number;
  lines: { operation: FeeOperation; freePerMonth: number; price: number; priceFromCompany: number | null }[];
}

/** A form that starts from the prices the package will have on that day: the next schedule, or the current one. */
export function draftFrom(pkg: FeePackage, validFrom: string): ScheduleDraft {
  const base = pkg.next ?? pkg.current;
  const lines = base
    ? scheduleLines(base)
    : FEE_OPERATIONS.map((operation) => ({ operation, freePerMonth: 0, price: 0, priceFromCompany: null }));
  return {
    validFrom,
    monthlyFee: base?.monthlyFee ?? 0,
    lines: lines.map((l) => ({
      ...l,
      priceFromCompany: l.operation === 'PIX_IN' ? (l.priceFromCompany ?? l.price) : null,
    })),
  };
}

/** The request of a draft. A company price equal to the person's is still sent: it is what the operator saw. */
export function scheduleRequest(draft: ScheduleDraft, operator: string): CreateFeeScheduleRequest {
  return {
    validFrom: draft.validFrom,
    monthlyFee: Number(draft.monthlyFee) || 0,
    operations: draft.lines.map((l) => ({
      operation: l.operation,
      freePerMonth: Math.trunc(Number(l.freePerMonth) || 0),
      price: Number(l.price) || 0,
      priceFromCompany: l.operation === 'PIX_IN' ? Number(l.priceFromCompany ?? l.price) || 0 : null,
    })),
    operator: operator.trim(),
  };
}

/** "Basic → Standard", from the package ids of a change. */
export function changeLabel(
  change: { fromPackageId: string | null; toPackageId: string },
  packages: Pick<FeePackage, 'id' | 'name'>[],
): string {
  const name = (id: string | null) => (id ? (packages.find((p) => p.id === id)?.name ?? 'outro pacote') : null);
  const from = name(change.fromPackageId);
  const to = name(change.toPackageId) ?? 'outro pacote';
  return from ? `${from} → ${to}` : `Entrou no ${to}`;
}
