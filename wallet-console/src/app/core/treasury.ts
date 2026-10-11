import { BackingType } from './models';

/** What each backing account is, for someone who does not know the type codes. */
export const BACKING_TYPE_LABELS: Record<BackingType, string> = {
  PIX_PI: 'Conta PI (Pix)',
  RESERVES: 'Reservas / Conta de Liquidação',
  CCME: 'CCME / títulos',
  INVESTMENT: 'Investimento',
  CASH: 'Caixa (saque)',
  CREDIT_PORTFOLIO: 'Carteira de crédito',
  UNASSIGNED: 'Dinheiro criado por depósito',
};

/** The types the treasury registers; Conta PI, Caixa and A regularizar are born with the tenant. */
export const REGISTRABLE_TYPES: BackingType[] = ['RESERVES', 'CCME', 'INVESTMENT', 'CREDIT_PORTFOLIO'];

/** The last day whose closing is final: yesterday in Brasília, once the minute of grace after midnight is over. */
export function lastClosedDay(now: Date = new Date()): string {
  const brasilia = new Date(now.getTime() - 3 * 3600_000 - 2 * 60_000);
  brasilia.setUTCDate(brasilia.getUTCDate() - 1);
  return brasilia.toISOString().slice(0, 10);
}

export function daysBefore(day: string, n: number): string {
  const d = new Date(`${day}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() - n);
  return d.toISOString().slice(0, 10);
}

/** "2026-10-09" as "09/10/2026", without a Date: parsing it would move the day in a browser east of UTC. */
export function dayLabelBr(day: string): string {
  const [y, m, d] = day.split('-');
  return `${d}/${m}/${y}`;
}
