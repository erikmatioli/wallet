import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import {
  AccountDetailResponse,
  AccountListResponse,
  AccountResponse,
  AuditResponse,
  BalanceResponse,
  MoneyMovementRequest,
  OnboardCustomerRequest,
  OnboardCustomerResponse,
  StatementResponse,
  TransactionResponse,
  TransferRequest,
} from './models';

/**
 * Thin, typed wrapper around wallet-core's REST API. Every call is a relative path (no base
 * URL baked in): in dev, proxy.conf.json forwards /v1/** to the backend; in the Docker image,
 * nginx does the same reverse-proxying. The app never needs to know the backend's real address,
 * and there is no CORS configuration to keep in sync on the backend because of it.
 */
@Injectable({ providedIn: 'root' })
export class WalletApiService {
  constructor(private readonly http: HttpClient) {}

  onboardCustomer(request: OnboardCustomerRequest): Observable<OnboardCustomerResponse> {
    return this.http.post<OnboardCustomerResponse>('/v1/customers', request);
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
}
