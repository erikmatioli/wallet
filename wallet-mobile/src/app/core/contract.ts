// The app-api contract (/app/v1), as wallet-app/app-contract defines it in Kotlin. A change there has to be
// repeated here (ADR-001, consequences). Money is always in cents (integers); instants are ISO-8601 strings.
//
// kotlinx.serialization leaves out a field that holds its default value: every field with a default in
// Kotlin is optional here, and the code reading it supplies the same default.

/** `GET /app/v1/me`: the logged-in customer's account. */
export interface Me {
  customerName: string;
  branch: string;
  accountNumber: string;
  checkDigit: string;
  status: string;
  balanceCents: number;
}

/** `GET /app/v1/statement?before=&limit=`: newest first; `nextBefore` is the next page's cursor, or null. */
export interface StatementPage {
  entries: StatementEntry[];
  nextBefore?: number | null;
}

/** One line of the statement, with everything its detail shows. */
export interface StatementEntry {
  transactionId: string;
  sequence: number;
  /** DEPOSIT, WITHDRAWAL, TRANSFER, PIX_IN, PIX_OUT, PIX_REFUND, PIX_RETURN_IN, PIX_RETURN_OUT... */
  type: string;
  credit: boolean;
  amountCents: number;
  balanceAfterCents: number;
  description?: string | null;
  occurredAt: string;
  counterpartyName?: string | null;
  pix?: PixInfo | null;
}

/** The Pix part of a statement entry. The counterparty's CPF/CNPJ only masked. */
export interface PixInfo {
  endToEndId: string;
  counterpartyTaxIdMasked?: string | null;
  counterpartyInstitution?: string | null;
  reasonCode?: string | null;
}

/** `GET /app/v1/info`: which app-api (and so which fintech) this is. */
export interface AppInfo {
  name: string;
  version: string;
  /** Default "". */
  tenant?: string;
}

/** Every error of the app's API: a stable code and a message written for the customer. */
export interface AppError {
  code: string;
  message: string;
  retryAfterSeconds?: number | null;
}

/** What a signup or login "start" answers: the code went to the email. */
export interface CodeSent {
  challengeId: string;
  message: string;
  /** Default 60. */
  resendAfterSeconds?: number;
}

/** What a confirmed signup or login answers. The token lives only in memory (ADR-001, decision 5). */
export interface Session {
  token: string;
  expiresInSeconds: number;
  customerName: string;
}

/** `GET /app/v1/transfers/destination`: who receives, for the confirmation step. */
export interface TransferDestination {
  holderName: string;
  branch: string;
  number: string;
  checkDigit: string;
}

/** `POST /app/v1/transfers`. The payer account comes from the token, never from here. */
export interface TransferRequest {
  branch: string;
  number: string;
  checkDigit: string;
  amountCents: number;
  description?: string | null;
}

export interface TransferReceipt {
  transactionId: string;
  amountCents: number;
  occurredAt: string;
  destination: TransferDestination;
  description?: string | null;
}

/** The payee of a Pix: another institution's account. `accountNumber` includes the check digit. */
export interface PixPayee {
  ispb: string;
  branch: string;
  accountNumber: string;
  taxId: string;
  name: string;
}

/** `POST /app/v1/pix`. The payer's CPF comes from the login. */
export interface PixRequest {
  payee: PixPayee;
  amountCents: number;
  description?: string | null;
}

/** SENT (on its way), COMPLETED (settled), REFUNDED (rejected, the money came back) or RETURNED. */
export interface PixReceipt {
  endToEndId: string;
  status: string;
  amountCents: number;
  payeeName: string;
  payeeIspb: string;
  reasonCode?: string | null;
  reasonMessage?: string | null;
}

/** Where a scheduled transfer goes. */
export interface TransferTarget {
  branch: string;
  number: string;
  checkDigit: string;
}

/** `POST /app/v1/schedules`. TRANSFER needs `transfer`, PIX needs `pix`; `executeOn` is yyyy-MM-dd. */
export interface ScheduleRequest {
  type: 'TRANSFER' | 'PIX';
  executeOn: string;
  amountCents: number;
  description?: string | null;
  transfer?: TransferTarget | null;
  pix?: PixPayee | null;
}

/** A schedule: list line and detail at once. `canCancel` is decided by app-api, in Brasília. */
export interface Schedule {
  id: string;
  type: string;
  status: string;
  executeOn: string;
  amountCents: number;
  description?: string | null;
  payee: string;
  payeeDetail: string;
  createdAt: string;
  canCancel: boolean;
  execution: ScheduleExecution;
  /** Default empty. */
  attempts?: ScheduleAttempt[];
}

/** PENDING, PROCESSING, EXECUTED, FAILED or CANCELLED. */
export interface ScheduleExecution {
  status: string;
  attemptCount: number;
  nextAttemptAt?: string | null;
  failureMessage?: string | null;
  transactionId?: string | null;
  endToEndId?: string | null;
}

/** One try on the day: EXECUTED, REFUSED, or null while unknown. */
export interface ScheduleAttempt {
  startedAt: string;
  outcome?: string | null;
  reasonMessage?: string | null;
}
