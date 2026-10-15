import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import {
  AccountBlock,
  AccountDetailResponse,
  AccountListResponse,
  AccountResponse,
  AuditResponse,
  BackingAccount,
  BackingStatementResponse,
  BalanceResponse,
  BlockRequest,
  CloseReason,
  CreateFeeScheduleRequest,
  CreateScheduleRequest,
  FeeAccount,
  FeePackage,
  FeeSchedule,
  JudicialTedRequest,
  JudicialTransferRequest,
  MoneyMovementRequest,
  OnboardCustomerRequest,
  OnboardCustomerResponse,
  PendingTransit,
  PositionsResponse,
  RegisterBackingAccountRequest,
  RevenueResponse,
  RevenueWithdrawalRequest,
  ScheduleResponse,
  StatementResponse,
  TimelineItem,
  TransactionResponse,
  TransferRequest,
  TreasuryTransferRequest,
  UncoveredDailyResponse,
} from './models';

/**
 * Thin, typed wrapper around wallet-core's REST API. Every call is a relative path (no base
 * URL baked in): in dev, proxy.conf.json forwards /v1/** to the backend; in the Docker image,
 * nginx does the same reverse-proxying. The app never needs to know the backend's real address,
 * and there is no CORS configuration to keep in sync on the backend because of it.
 *
 * /v1/schedules is wallet-scheduler's API, not wallet-core's, and /v1/fees is wallet-fees': the proxy
 * and nginx route them there, and they accept the same token.
 */
@Injectable({ providedIn: 'root' })
export class WalletApiService {
  constructor(private readonly http: HttpClient) {}

  onboardCustomer(request: OnboardCustomerRequest): Observable<OnboardCustomerResponse> {
    return this.http.post<OnboardCustomerResponse>('/v1/customers', request);
  }

  /** Sets, changes or (with an empty string) removes the customer's email - where the app sends login codes. */
  changeCustomerEmail(customerId: string, email: string): Observable<void> {
    return this.http.put<void>(`/v1/customers/${customerId}/email`, { email });
  }

  getAccount(accountId: string): Observable<AccountDetailResponse> {
    return this.http.get<AccountDetailResponse>(`/v1/accounts/${accountId}`);
  }

  getBalance(accountId: string): Observable<BalanceResponse> {
    return this.http.get<BalanceResponse>(`/v1/accounts/${accountId}/balance`);
  }

  /** {@code product: 'PIX'} keeps only the Pix entries (every PIX_* type). */
  getStatement(accountId: string, before?: number, limit = 20, product?: 'PIX'): Observable<StatementResponse> {
    let params = new HttpParams().set('limit', limit);
    if (before != null) params = params.set('before', before);
    if (product) params = params.set('product', product);
    return this.http.get<StatementResponse>(`/v1/accounts/${accountId}/statement`, { params });
  }

  deposit(accountId: string, request: MoneyMovementRequest, idempotencyKey: string): Observable<TransactionResponse> {
    return this.http.post<TransactionResponse>(`/v1/accounts/${accountId}/deposits`, request, {
      headers: { 'Idempotency-Key': idempotencyKey },
    });
  }

  withdraw(accountId: string, request: MoneyMovementRequest, idempotencyKey: string): Observable<TransactionResponse> {
    return this.http.post<TransactionResponse>(`/v1/accounts/${accountId}/withdrawals`, request, {
      headers: { 'Idempotency-Key': idempotencyKey },
    });
  }

  transfer(request: TransferRequest, idempotencyKey: string): Observable<TransactionResponse> {
    return this.http.post<TransactionResponse>('/v1/transfers', request, {
      headers: { 'Idempotency-Key': idempotencyKey },
    });
  }

  audit(accountId: string): Observable<AuditResponse> {
    return this.http.get<AuditResponse>(`/v1/accounts/${accountId}/audit`);
  }

  listAccounts(cursor?: string, limit = 20): Observable<AccountListResponse> {
    let params = new HttpParams().set('limit', limit);
    if (cursor) params = params.set('cursor', cursor);
    return this.http.get<AccountListResponse>('/v1/accounts', { params });
  }

  /** Looks up one account by its bank-style number. 404 (via extractErrorMessage) if not found. */
  lookupAccountByNumber(branch: string, number: string, checkDigit: string): Observable<AccountResponse> {
    const params = new HttpParams().set('branch', branch).set('number', number).set('checkDigit', checkDigit);
    return this.http.get<AccountResponse>('/v1/accounts/lookup', { params });
  }

  /** Looks up one account by owner taxId. 404 (via extractErrorMessage) if not found. */
  lookupAccountByTaxId(taxId: string): Observable<AccountResponse> {
    const params = new HttpParams().set('taxId', taxId);
    return this.http.get<AccountResponse>('/v1/accounts/findByTaxId', { params });
  }

  /** A payer account's schedules, newest payment date first, each with its execution and attempts. */
  listSchedules(payerAccountId: string): Observable<ScheduleResponse[]> {
    return this.http.get<ScheduleResponse[]>('/v1/schedules', { params: new HttpParams().set('payerAccountId', payerAccountId) });
  }

  createSchedule(request: CreateScheduleRequest, idempotencyKey: string): Observable<ScheduleResponse> {
    return this.http.post<ScheduleResponse>('/v1/schedules', request, {
      headers: { 'Idempotency-Key': idempotencyKey },
    });
  }

  cancelSchedule(id: string): Observable<ScheduleResponse> {
    return this.http.post<ScheduleResponse>(`/v1/schedules/${id}/cancel`, null);
  }

  // ---------------------------------------------------------------- treasury (ADR-011)

  /** Every backing account with its expected balance now and at the end of yesterday. */
  treasuryPositions(): Observable<PositionsResponse> {
    return this.http.get<PositionsResponse>('/v1/treasury/backing-accounts');
  }

  registerBackingAccount(request: RegisterBackingAccountRequest): Observable<BackingAccount> {
    return this.http.post<BackingAccount>('/v1/treasury/backing-accounts', request);
  }

  /** A backing account's movements, newest first; {@code before} is the previous page's {@code next}. */
  backingEntries(id: string, before?: string, limit = 20): Observable<BackingStatementResponse> {
    let params = new HttpParams().set('limit', limit);
    if (before) params = params.set('before', before);
    return this.http.get<BackingStatementResponse>(`/v1/treasury/backing-accounts/${id}/entries`, { params });
  }

  /** Per closed day: created by deposits, covered by the treasury, uncovered at the end of the day. */
  uncoveredDaily(from: string, to: string): Observable<UncoveredDailyResponse> {
    const params = new HttpParams().set('from', from).set('to', to);
    return this.http.get<UncoveredDailyResponse>('/v1/treasury/unassigned/daily', { params });
  }

  /** The tenant's revenue: balance, this month's totals and the statement, newest first. */
  revenue(before?: number, limit = 20): Observable<RevenueResponse> {
    let params = new HttpParams().set('limit', limit);
    if (before != null) params = params.set('before', before);
    return this.http.get<RevenueResponse>('/v1/treasury/revenue', { params });
  }

  /** Takes the revenue out of the platform, from a backing account (ADR-015 of the core). */
  withdrawRevenue(request: RevenueWithdrawalRequest, idempotencyKey: string): Observable<TransactionResponse> {
    return this.http.post<TransactionResponse>('/v1/treasury/revenue/withdrawals', request, {
      headers: { 'Idempotency-Key': idempotencyKey },
    });
  }

  treasuryTransfer(request: TreasuryTransferRequest, idempotencyKey: string): Observable<TransactionResponse> {
    return this.http.post<TransactionResponse>('/v1/treasury/transfers', request, {
      headers: { 'Idempotency-Key': idempotencyKey },
    });
  }

  // ---------------------------------------------------------------- blocks and closing (ADR-013 of the core)

  blockAccount(accountId: string, request: BlockRequest): Observable<AccountBlock> {
    return this.http.post<AccountBlock>(`/v1/accounts/${accountId}/blocks`, request);
  }

  releaseBlock(accountId: string, blockId: string, operator: string, note: string): Observable<AccountBlock> {
    return this.http.post<AccountBlock>(`/v1/accounts/${accountId}/blocks/${blockId}/release`, { operator, note });
  }

  accountTimeline(accountId: string): Observable<{ items: TimelineItem[] }> {
    return this.http.get<{ items: TimelineItem[] }>(`/v1/accounts/${accountId}/timeline`);
  }

  judicialTransfer(accountId: string, request: JudicialTransferRequest, idempotencyKey: string): Observable<TransactionResponse> {
    return this.http.post<TransactionResponse>(`/v1/accounts/${accountId}/judicial-transfers`, request, {
      headers: { 'Idempotency-Key': idempotencyKey },
    });
  }

  closeAccount(accountId: string, reason: CloseReason, operator: string, note?: string): Observable<AccountDetailResponse> {
    return this.http.post<AccountDetailResponse>(`/v1/accounts/${accountId}/close`, { reason, operator, note });
  }

  pendingJudicialTransit(): Observable<{ items: PendingTransit[] }> {
    return this.http.get<{ items: PendingTransit[] }>('/v1/judicial-transit/pending');
  }

  judicialTed(transferId: string, request: JudicialTedRequest, idempotencyKey: string): Observable<TransactionResponse> {
    return this.http.post<TransactionResponse>(`/v1/judicial-transit/${transferId}/teds`, request, {
      headers: { 'Idempotency-Key': idempotencyKey },
    });
  }

  // ---------------------------------------------------------------- fee basket (wallet-fees, fees:read / fees:admin)

  /** The tenant's six packages, each with the schedule in force and the next one. */
  feePackages(): Observable<FeePackage[]> {
    return this.http.get<FeePackage[]>('/v1/fees/packages');
  }

  /** Every schedule of a package, the past ones included: they explain what was charged. */
  feeSchedules(packageId: string): Observable<FeeSchedule[]> {
    return this.http.get<FeeSchedule[]>(`/v1/fees/packages/${packageId}/schedules`);
  }

  /** A new schedule from a date on; one of the same date not in force yet is replaced. */
  createFeeSchedule(packageId: string, request: CreateFeeScheduleRequest): Observable<FeeSchedule> {
    return this.http.post<FeeSchedule>(`/v1/fees/packages/${packageId}/schedules`, request);
  }

  feeAccount(accountId: string): Observable<FeeAccount> {
    return this.http.get<FeeAccount>(`/v1/fees/accounts/${accountId}`);
  }

  changeFeePackage(accountId: string, packageId: string, operator: string): Observable<FeeAccount> {
    return this.http.put<FeeAccount>(`/v1/fees/accounts/${accountId}/package`, { packageId, operator });
  }
}
