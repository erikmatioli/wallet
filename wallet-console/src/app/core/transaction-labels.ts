import { EntryResponse, TransactionType } from './models';

const LABELS: Record<TransactionType, string> = {
  DEPOSIT: 'Depósito',
  WITHDRAWAL: 'Saque',
  TRANSFER: 'Transferência',
  PIX_IN: 'Pix recebido',
  PIX_OUT: 'Pix enviado',
  PIX_REFUND: 'Estorno de Pix',
  PIX_RETURN_IN: 'Devolução recebida',
  PIX_RETURN_OUT: 'Devolução enviada',
};

/** Readable name of a transaction type; an unknown one (a newer API) is shown as it came. */
export function transactionLabel(type: string): string {
  return LABELS[type as TransactionType] ?? type;
}


// Rejections (pacs.002) use the texts of the Pix service's RejectionReason; returns (pacs.004) the
// usual return reasons of the arrangement.
const REASONS: Record<string, string> = {
  AC03: 'Conta do recebedor inexistente ou inválida',
  AC06: 'Conta do recebedor bloqueada',
  AC07: 'Conta do recebedor encerrada',
  AC14: 'Tipo de conta incorreto',
  BE01: 'CPF/CNPJ não corresponde ao titular da conta',
  BE08: 'Erro operacional do banco do recebedor',
  FR01: 'Suspeita de fraude',
  MD06: 'Devolução solicitada pelo recebedor',
};

/** Readable reason of a refund or return; null when the code is not one we know. */
export function reasonLabel(code: string | null | undefined): string | null {
  return code ? (REASONS[code] ?? null) : null;
}

/** Who the entry was with, for the list: the other customer of a transfer, or the Pix counterparty. */
export function counterpartyName(entry: EntryResponse): string | null {
  return entry.counterpartyCustomerName ?? entry.pix?.counterparty.name ?? null;
}
