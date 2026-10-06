/**
 * Mirrors wallet-core's ApiModels.java records exactly (field names, shapes). If the backend
 * contract changes, this is the file to update first - every page depends on these types.
 */

export interface TokenResponse {
  access_token: string;
  token_type: string;
  expires_in: number;
  scope: string;
}

export interface CustomerResponse {
  id: string;
  name: string;
  documentType: 'CPF' | 'CNPJ';
  externalRef: string | null;
  status: string;
}

export interface AccountResponse {
  id: string;
  ispb: string;
  branch: string;
  number: string;
  checkDigit: string;
  formatted: string;
  type: string;
  status: string;
  balance: number;
  currency: string;
}

/** GET /v1/accounts/{id}: AccountResponse plus who owns it. */
export interface AccountDetailResponse extends AccountResponse {
  customerName: string;
  documentMasked: string;
}

export interface OnboardCustomerRequest {
  name: string;
  taxId: string;
  externalRef?: string;
}

export interface OnboardCustomerResponse {
  customer: CustomerResponse;
  account: AccountResponse;
}

export interface MoneyMovementRequest {
  amount: number;
  description?: string;
}

export interface DestinationNumber {
  branch: string;
  number: string;
  checkDigit: string;
}

export interface TransferRequest {
  sourceAccountId: string;
  destinationAccountId?: string;
  destination?: DestinationNumber;
  amount: number;
  description?: string;
}

export type TransactionType =
  | 'DEPOSIT'
  | 'WITHDRAWAL'
  | 'TRANSFER'
  | 'PIX_IN'
  | 'PIX_OUT'
  | 'PIX_REFUND'
  | 'PIX_RETURN_IN'
  | 'PIX_RETURN_OUT';

export interface TransactionResponse {
  id: string;
  type: TransactionType;
  amount: number;
  currency: string;
  description: string;
  occurredAt: string;
  replayed: boolean;
  /** Only for PIX_* transactions. */
  endToEndId: string | null;
}

/** The other side of a Pix: the payee of a Pix sent, the payer of a Pix received (ADR-010). */
export interface PixCounterpartyResponse {
  name: string;
  taxIdMasked: string;
  ispb: string;
  branch: string | null;
  account: string;
  accountType: string | null;
}

export interface PixDetailResponse {
  endToEndId: string;
  /** Only for returns (PIX_RETURN_*). */
  returnId: string | null;
  /** The original Pix, for a refund or a return. */
  relatedTransactionId: string | null;
  counterparty: PixCounterpartyResponse;
  reasonCode: string | null;
  remittanceInfo: string | null;
}

export interface EntryResponse {
  transactionId: string;
  sequence: number;
  type: TransactionType;
  direction: 'DEBIT' | 'CREDIT';
  amount: number;
  balanceAfter: number;
  description: string;
  occurredAt: string;
  /** Only set for TRANSFER entries - deposit/withdrawal counterparties are internal and never exposed. */
  counterpartyAccountId: string | null;
  counterpartyCustomerName: string | null;
  counterpartyAccountFormatted: string | null;
  /** Only set for PIX_* entries: the Pix as it was posted, counterparty outside the institution included. */
  pix: PixDetailResponse | null;
}

export interface StatementResponse {
  entries: EntryResponse[];
  nextBefore: number | null;
}

export interface BalanceResponse {
  accountId: string;
  balance: number;
  currency: string;
}

export interface AuditResponse {
  accountId: string;
  entryCount: number;
  storedBalance: number;
  replayedBalance: number;
  version: number;
  consistent: boolean;
  findings: string[];
}

/** RFC 9457 problem+json, as produced by wallet-core's ApiExceptionHandler. */
export interface ProblemDetail {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  instance?: string;
  code?: string;
  errors?: string[];
}

export interface AccountListItem {
  accountId: string;
  customerName: string;
  documentMasked: string;
  accountFormatted: string;
  accountType: string;
  status: string;
  balance: number;
  currency: string;
  createdAt: string;
}

export interface AccountListResponse {
  items: AccountListItem[];
  nextCursor: string | null;
}
