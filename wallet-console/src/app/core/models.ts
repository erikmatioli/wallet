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

export interface TransactionResponse {
  id: string;
  type: 'DEPOSIT' | 'WITHDRAWAL' | 'TRANSFER';
  amount: number;
  currency: string;
  description: string;
  occurredAt: string;
  replayed: boolean;
}

export interface EntryResponse {
  transactionId: string;
  sequence: number;
  type: string;
  direction: 'DEBIT' | 'CREDIT';
  amount: number;
  balanceAfter: number;
  description: string;
  occurredAt: string;
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
