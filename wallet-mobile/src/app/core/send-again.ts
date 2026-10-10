import { StatementEntry } from './contract';

/** What a transfer form can be filled with from a statement entry. */
export interface TransferPrefill {
  branch: string;
  number: string;
  checkDigit: string;
  /** Shown above the form: who this account is. The confirmation still asks app-api. */
  name: string;
}

/** What a Pix form can be filled with. The CPF/CNPJ comes only masked, so it is typed again. */
export interface PixPrefill {
  name: string;
  ispb: string;
  branch: string;
  account: string;
  taxIdMasked: string;
}

export type SendAgain =
  | { kind: 'transfer'; path: '/pagar/transferencia'; prefill: TransferPrefill }
  | { kind: 'pix'; path: '/pagar/pix'; prefill: PixPrefill };

/**
 * Whether money can be sent to the other side of a statement entry, and with what. A transfer, in or out,
 * to the other customer's account; a Pix received or sent, to the counterparty's account at the other
 * institution. Not a refund or a return: the account of a Pix that came back is the one that refused it.
 */
export function sendAgain(e: StatementEntry): SendAgain | null {
  if (e.type === 'TRANSFER' && e.counterpartyAccount) {
    const a = e.counterpartyAccount;
    return {
      kind: 'transfer',
      path: '/pagar/transferencia',
      prefill: {
        branch: a.branch,
        number: a.number,
        checkDigit: a.checkDigit,
        name: e.counterpartyName ?? '',
      },
    };
  }
  const p = e.pix;
  if (
    (e.type === 'PIX_IN' || e.type === 'PIX_OUT') &&
    p?.counterpartyInstitution &&
    p.counterpartyAccount
  ) {
    return {
      kind: 'pix',
      path: '/pagar/pix',
      prefill: {
        name: e.counterpartyName ?? '',
        ispb: p.counterpartyInstitution,
        branch: p.counterpartyBranch ?? '',
        account: p.counterpartyAccount,
        taxIdMasked: p.counterpartyTaxIdMasked ?? '',
      },
    };
  }
  return null;
}

/** "0001 / 00100002-9". */
export function counterpartyAccountText(e: StatementEntry): string | null {
  if (e.counterpartyAccount) {
    const a = e.counterpartyAccount;
    return `${a.branch} / ${a.number}-${a.checkDigit}`;
  }
  return null;
}
