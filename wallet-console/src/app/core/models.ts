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
  email?: string | null;
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
  /** The full CPF/CNPJ, unformatted: only with the customers:document scope (null or absent without it). */
  document?: string | null;
  customerId: string;
  /** Where the customer's app sends login codes (wallet-app ADR-003); absent when none was registered. */
  customerEmail?: string | null;
  /** ADR-013: when the account was closed; absent while active. */
  closedAt?: string | null;
  /** What the active blocks let the account do now. */
  canReceive: boolean;
  canPay: boolean;
  /** The active blocks, newest first. */
  blocks: AccountBlock[];
}

// ---------------------------------------------------------------- blocks and closing (ADR-013 of the core)

/** FEE_PENDING is wallet-fees' (ADR-014 of the core): created and released by it, never by the console. */
export type BlockType = 'FRAUD' | 'ADMINISTRATIVE' | 'JUDICIAL' | 'FEE_PENDING';
export type BlockReason =
  | 'FRAUD_SUSPICION'
  | 'CUSTOMER_REQUEST'
  | 'REGISTRATION_PENDING'
  | 'INSTITUTION_DECISION'
  | 'COURT_ORDER'
  | 'UNPAID_FEES'
  | 'OTHER';
export type CloseReason = 'CUSTOMER_REQUEST' | 'INSTITUTION_DECISION' | 'DEATH' | 'OTHER';

export interface CourtOrder {
  process: string;
  protocol?: string | null;
}

/** Who did it: the operator the console declared, and the token's client. */
export interface Actor {
  operator: string;
  clientId: string;
}

export interface AccountBlock {
  id: string;
  accountId: string;
  type: BlockType;
  reason: BlockReason;
  note: string | null;
  court: CourtOrder | null;
  createdBy: Actor;
  createdAt: string;
  active: boolean;
  releasedBy: Actor | null;
  releasedAt: string | null;
  releaseNote: string | null;
}

export interface BlockRequest {
  type: BlockType;
  reason: BlockReason;
  operator: string;
  court?: CourtOrder;
  note?: string;
}

export interface TimelineItem {
  kind: 'OPENED' | 'CLOSED' | 'BLOCKED' | 'RELEASED';
  at: string;
  actor: Actor;
  reason: string | null;
  note: string | null;
  blockId: string | null;
  blockType: BlockType | null;
  court: CourtOrder | null;
}

export interface JudicialTransferRequest {
  blockId: string;
  amount: number;
  destination:
    | { kind: 'JUDICIAL_TRANSIT' }
    | { kind: 'ACCOUNT'; accountId?: string; branch?: string; number?: string; checkDigit?: string };
  operator: string;
  note?: string;
}

export interface PendingTransit {
  transferId: string;
  accountId: string;
  process: string;
  protocol: string | null;
  amount: number;
  sent: number;
  remaining: number;
  occurredAt: string;
}

export interface JudicialTedRequest {
  backingAccountId: string;
  amount: number;
  document: string;
  operator: string;
  note?: string;
}

export interface OnboardCustomerRequest {
  name: string;
  taxId: string;
  externalRef?: string;
  email?: string;
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
  | 'PIX_RETURN_OUT'
  | 'TREASURY_TRANSFER'
  | 'RECLASSIFICATION'
  | 'JUDICIAL_TRANSFER'
  | 'JUDICIAL_TRANSIT_OUT'
  | 'FEE'
  | 'FEE_REFUND'
  | 'REVENUE_WITHDRAWAL';

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
  /** The full CPF/CNPJ, unformatted: only with the customers:document scope (null or absent without it). */
  document?: string | null;
  accountFormatted: string;
  accountType: string;
  status: string;
  balance: number;
  currency: string;
  createdAt: string;
  canReceive: boolean;
  canPay: boolean;
}

export interface AccountListResponse {
  items: AccountListItem[];
  nextCursor: string | null;
}

// ---------------------------------------------------------------- wallet-scheduler (its ADR-001)

export type ScheduleType = 'TRANSFER' | 'PIX';
export type ScheduleStatus = 'ACTIVE' | 'CANCELLED' | 'COMPLETED';
export type ExecutionStatus = 'PENDING' | 'PROCESSING' | 'EXECUTED' | 'FAILED' | 'CANCELLED';

export interface ScheduleReason {
  code: string;
  message: string;
  /** Whether a later window of the same day may still succeed. */
  retryToday: boolean;
}

export interface ScheduleAttempt {
  number: number;
  startedAt: string;
  finishedAt: string | null;
  /** Null while the result is unknown or, for a Pix, while its settlement is awaited. */
  outcome: 'EXECUTED' | 'REFUSED' | null;
  reason: ScheduleReason | null;
  transactionId: string | null;
  endToEndId: string | null;
}

export interface ScheduleResponse {
  id: string;
  type: ScheduleType;
  status: ScheduleStatus;
  payerAccountId: string;
  /** Set for a TRANSFER. */
  destination: { branch: string; number: string; checkDigit: string; accountId: string; holderName: string } | null;
  /** Set for a PIX. The CPF/CNPJ only masked. */
  pixPayee: { ispb: string; branch: string; accountNumber: string; taxIdMasked: string; name: string } | null;
  amount: number;
  description: string | null;
  executeOn: string;
  createdAt: string;
  cancelledAt: string | null;
  execution: {
    status: ExecutionStatus;
    attemptCount: number;
    nextAttemptAt: string | null;
    /** The wallet-core transaction that moved the money (for a Pix, its debit). */
    transactionId: string | null;
    endToEndId: string | null;
    /** The last refusal - also while a later window of the day is still to come. */
    failure: ScheduleReason | null;
  };
  attempts: ScheduleAttempt[];
}

export interface CreateScheduleRequest {
  type: ScheduleType;
  payerAccountId: string;
  executeOn: string;
  amount: number;
  description?: string;
  destination?: { branch: string; number: string; checkDigit: string };
  pix?: {
    payerTaxId: string;
    payee: { ispb: string; branch: string; accountNumber: string; taxId: string; name: string };
  };
}

// ---------------------------------------------------------------- treasury (ADR-011 of wallet-core)
// Amounts of a backing account are its "expected balance": what the tenant should have in that place.

export type BackingType = 'PIX_PI' | 'RESERVES' | 'CCME' | 'INVESTMENT' | 'CASH' | 'CREDIT_PORTFOLIO' | 'UNASSIGNED';

export interface BackingAccount {
  id: string;
  type: BackingType;
  name: string;
  externalRef: string | null;
  createdAt: string;
}

export interface BackingPosition {
  account: BackingAccount;
  expected: number;
  /** Null in the first minute after midnight, while yesterday may still be closing. */
  expectedYesterday: number | null;
  currency: string;
}

export interface PositionsResponse {
  accounts: BackingPosition[];
  total: number;
  currency: string;
}

export interface TreasuryReference {
  document: string | null;
  note: string | null;
}

export interface BackingEntry {
  transactionId: string;
  type: TransactionType;
  direction: 'IN' | 'OUT';
  amount: number;
  expectedAfter: number;
  description: string;
  occurredAt: string;
  customerName: string | null;
  customerAccount: string | null;
  otherBackingAccount: BackingAccount | null;
  reference: TreasuryReference | null;
}

export interface BackingStatementResponse {
  account: BackingAccount;
  entries: BackingEntry[];
  next: string | null;
  currency: string;
}

export interface UncoveredDay {
  date: string;
  created: number;
  covered: number;
  uncoveredAtEnd: number;
}

export interface UncoveredDailyResponse {
  currency: string;
  days: UncoveredDay[];
}

export interface TreasuryTransferRequest {
  from: string;
  to: string;
  amount: number;
  description?: string;
  reference?: TreasuryReference;
}

export interface RegisterBackingAccountRequest {
  type: BackingType;
  name: string;
  externalRef?: string;
}

// ---------------------------------------------------------------- fee basket (wallet-fees' ADR-001)

export type CustomerType = 'PF' | 'PJ';
/** What a schedule prices per transaction. A monthly fee is a charge of operation MONTHLY. */
export type FeeOperation = 'PIX_OUT' | 'PIX_IN' | 'TRANSFER_OUT' | 'WITHDRAWAL';
export type ChargeStatus = 'PENDING' | 'CHARGED' | 'CANCELLED' | 'REFUNDED';

/** One operation of a schedule: free per month, then the price. Only PIX_IN has a price for a payer company. */
export interface OperationPrice {
  operation: FeeOperation;
  freePerMonth: number;
  price: number;
  priceFromCompany: number | null;
}

/** A package's prices from a date on (yyyy-MM-dd, in Brasília). */
export interface FeeSchedule {
  id: string;
  validFrom: string;
  monthlyFee: number;
  operations: OperationPrice[];
  createdBy: string;
  createdAt: string;
}

export interface FeePackage {
  id: string;
  code: string;
  name: string;
  customerType: CustomerType;
  /** The package every new account of its type starts in. */
  isDefault: boolean;
  /** In force today; null only for a package with no schedule yet. */
  current: FeeSchedule | null;
  /** Already registered to start on a later day. */
  next: FeeSchedule | null;
}

export interface CreateFeeScheduleRequest {
  validFrom: string;
  monthlyFee: number;
  operations: OperationPrice[];
  operator: string;
}

export interface FeeUsage {
  operation: FeeOperation;
  used: number;
  free: number;
}

export interface FeeCharge {
  id: string;
  /** A FeeOperation, or MONTHLY. */
  operation: string;
  amount: number;
  description: string;
  status: ChargeStatus;
  sourceTransactionId: string | null;
  /** The month (yyyy-MM) of a monthly fee. */
  reference: string | null;
  feeTransactionId: string | null;
  refundTransactionId: string | null;
  /** Why it is still pending, or why it was cancelled: a code. */
  lastError: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface PackageChange {
  fromPackageId: string | null;
  toPackageId: string;
  operator: string;
  clientId: string;
  changedAt: string;
}

/** `GET /v1/fees/accounts/{id}`: the account's package, this month's quota and its latest fees. */
export interface FeeAccount {
  accountId: string;
  customerType: CustomerType;
  status: 'ACTIVE' | 'CLOSED';
  packageId: string;
  packageCode: string;
  packageName: string;
  since: string;
  changes: PackageChange[];
  fees: {
    month: string;
    usage: FeeUsage[];
    /** The total not charged yet. */
    pending: number;
    charges: FeeCharge[];
  };
}

// ---------------------------------------------------------------- tenant revenue (ADR-014 and ADR-015 of the core)

export interface RevenueDestination {
  bank: string;
  branch: string;
  account: string;
}

/** Where a withdrawal of the revenue left from and went to. */
export interface RevenueWithdrawalInfo {
  backingAccountId: string;
  backingAccountName: string;
  destination: RevenueDestination;
  document: string;
  note: string | null;
  operator: string;
}

export interface RevenueEntry {
  transactionId: string;
  sequence: number;
  type: TransactionType;
  direction: 'CREDIT' | 'DEBIT';
  amount: number;
  balanceAfter: number;
  description: string;
  occurredAt: string;
  withdrawal: RevenueWithdrawalInfo | null;
}

/** `GET /v1/treasury/revenue`. `month` is absent in the first minute of a month. */
export interface RevenueResponse {
  balance: number;
  currency: string;
  month: { month: string; earned: number; withdrawn: number } | null;
  entries: RevenueEntry[];
  next: number | null;
}

export interface RevenueWithdrawalRequest {
  backingAccountId: string;
  amount: number;
  destination: RevenueDestination;
  document: string;
  note?: string;
  operator: string;
}
