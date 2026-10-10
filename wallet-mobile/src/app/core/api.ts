import { HttpClient, HttpErrorResponse, HttpHeaders, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, firstValueFrom, timeout } from 'rxjs';
import {
  AppError,
  AppInfo,
  CodeSent,
  Me,
  PixReceipt,
  PixRequest,
  Schedule,
  ScheduleRequest,
  Session,
  StatementPage,
  TransferDestination,
  TransferReceipt,
  TransferRequest,
} from './contract';
import { SessionStore } from './session';

/** An answer of app-api that is not a success: its code and a message made to be shown as it is. */
export class AppApiError extends Error {
  constructor(
    readonly code: string,
    message: string,
    readonly retryAfterSeconds: number | null = null,
  ) {
    super(message);
  }
}

export const NETWORK_MESSAGE =
  'Não foi possível falar com o servidor. Verifique a conexão e tente de novo.';
export const OFFLINE_MESSAGE = 'Você está sem conexão. Conecte-se e tente de novo.';

/** app-api's messages are written for the customer; anything else (network) gets a generic one. */
export function message(e: unknown): string {
  if (e instanceof AppApiError) return e.message;
  if (typeof navigator !== 'undefined' && navigator.onLine === false) return OFFLINE_MESSAGE;
  return NETWORK_MESSAGE;
}

/** What the flows need from app-api: the class below, or a fake in the tests. */
export type WalletApi = Pick<
  AppApi,
  | 'startSignup'
  | 'confirmSignup'
  | 'startLogin'
  | 'confirmLogin'
  | 'me'
  | 'statement'
  | 'transferDestination'
  | 'transfer'
  | 'sendPix'
  | 'pixStatus'
  | 'schedules'
  | 'createSchedule'
  | 'cancelSchedule'
>;

/**
 * The PWA's only door to the outside: app-api's `/app/v1`, on the same origin (nginx forwards it, ADR-001,
 * decision 3). Every POST that moves money takes the Idempotency-Key the flow created on its confirmation.
 * The flows depend on this class's shape only, so their tests pass a fake.
 */
@Injectable({ providedIn: 'root' })
export class AppApi {
  private readonly http = inject(HttpClient);
  private readonly session = inject(SessionStore);

  info(): Promise<AppInfo> {
    return this.call(this.http.get<AppInfo>('/app/v1/info'));
  }

  startSignup(cpf: string, name: string, email: string): Promise<CodeSent> {
    return this.call(this.http.post<CodeSent>('/app/v1/signup/start', { cpf, name, email }));
  }

  confirmSignup(
    challengeId: string,
    code: string,
    cpf: string,
    name: string,
    email: string,
  ): Promise<Session> {
    return this.call(
      this.http.post<Session>('/app/v1/signup/confirm', { challengeId, code, cpf, name, email }),
    );
  }

  startLogin(cpf: string): Promise<CodeSent> {
    return this.call(this.http.post<CodeSent>('/app/v1/login/start', { cpf }));
  }

  confirmLogin(challengeId: string, cpf: string, code: string): Promise<Session> {
    return this.call(this.http.post<Session>('/app/v1/login/confirm', { challengeId, cpf, code }));
  }

  me(): Promise<Me> {
    return this.call(this.http.get<Me>('/app/v1/me', { headers: this.auth() }));
  }

  statement(before: number | null = null, limit = 20): Promise<StatementPage> {
    let params = new HttpParams().set('limit', limit);
    if (before !== null) params = params.set('before', before);
    return this.call(
      this.http.get<StatementPage>('/app/v1/statement', { headers: this.auth(), params }),
    );
  }

  transferDestination(
    branch: string,
    number: string,
    checkDigit: string,
  ): Promise<TransferDestination> {
    const params = new HttpParams()
      .set('branch', branch)
      .set('number', number)
      .set('checkDigit', checkDigit);
    return this.call(
      this.http.get<TransferDestination>('/app/v1/transfers/destination', {
        headers: this.auth(),
        params,
      }),
    );
  }

  transfer(request: TransferRequest, idempotencyKey: string): Promise<TransferReceipt> {
    return this.call(
      this.http.post<TransferReceipt>('/app/v1/transfers', request, {
        headers: this.auth(idempotencyKey),
      }),
    );
  }

  sendPix(request: PixRequest, idempotencyKey: string): Promise<PixReceipt> {
    return this.call(
      this.http.post<PixReceipt>('/app/v1/pix', request, { headers: this.auth(idempotencyKey) }),
    );
  }

  pixStatus(endToEndId: string): Promise<PixReceipt> {
    return this.call(
      this.http.get<PixReceipt>(`/app/v1/pix/${encodeURIComponent(endToEndId)}`, {
        headers: this.auth(),
      }),
    );
  }

  schedules(): Promise<Schedule[]> {
    return this.call(this.http.get<Schedule[]>('/app/v1/schedules', { headers: this.auth() }));
  }

  createSchedule(request: ScheduleRequest, idempotencyKey: string): Promise<Schedule> {
    return this.call(
      this.http.post<Schedule>('/app/v1/schedules', request, {
        headers: this.auth(idempotencyKey),
      }),
    );
  }

  cancelSchedule(id: string): Promise<Schedule> {
    return this.call(
      this.http.post<Schedule>(`/app/v1/schedules/${encodeURIComponent(id)}/cancel`, null, {
        headers: this.auth(),
      }),
    );
  }

  private auth(idempotencyKey?: string): HttpHeaders {
    const token = this.session.token;
    if (!token) throw new AppApiError('SESSION_EXPIRED', 'Sua sessão expirou. Entre de novo.');
    let headers = new HttpHeaders({ Authorization: `Bearer ${token}` });
    if (idempotencyKey) headers = headers.set('Idempotency-Key', idempotencyKey);
    return headers;
  }

  /**
   * The body on success; otherwise the contract's AppError as an AppApiError. An expired session also ends
   * the SessionStore, and the guard takes the customer back to the login with app-api's message.
   */
  private async call<T>(request: Observable<T>): Promise<T> {
    try {
      return await firstValueFrom(request.pipe(timeout(15_000)));
    } catch (e) {
      const error = readError(e);
      if (!error) throw e;
      if (error.code === 'SESSION_EXPIRED') this.session.expire(error.message);
      throw new AppApiError(error.code, error.message, error.retryAfterSeconds ?? null);
    }
  }
}

function readError(e: unknown): AppError | null {
  if (!(e instanceof HttpErrorResponse) || e.status === 0) return null;
  const body = e.error as Partial<AppError> | null;
  if (body && typeof body.code === 'string' && typeof body.message === 'string')
    return body as AppError;
  return {
    code: `HTTP_${e.status}`,
    message: `Não foi possível falar com o servidor (${e.status}).`,
  };
}
