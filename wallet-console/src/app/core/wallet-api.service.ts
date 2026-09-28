import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import {
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

  getAccount(accountId: string): Observable<AccountResponse> {
    return this.http.get<AccountResponse>(`/v1/accounts/${accountId}`);
  }

  getBalance(accountId: string): Observable<BalanceResponse> {
    return this.http.get<BalanceResponse>(`/v1/accounts/${accountId}/balance`);
  }

  getStatement(accountId: string, before?: number, limit = 20): Observable<StatementResponse> {
    let params = new HttpParams().set('limit', limit);
    if (before != null) params = params.set('before', before);
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
}
