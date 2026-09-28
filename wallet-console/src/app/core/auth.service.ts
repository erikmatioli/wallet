import { HttpClient, HttpContext, HttpHeaders } from '@angular/common/http';
import { Injectable, computed, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { TokenResponse } from './models';
import { SKIP_AUTH } from './auth.interceptor';

const STORAGE_KEY = 'wallet-console.session';

/**
 * Session is kept in sessionStorage (not localStorage) on purpose: it survives a page refresh
 * within the same tab, but disappears when the tab closes and is never shared across tabs. For
 * an internal ops console handling money movements, that trade-off - re-login per session,
 * smaller XSS blast radius than a persistent token - is worth it. See wallet-console's README
 * for the fuller reasoning and what to reach for instead (BFF + httpOnly cookie) if this console
 * ever needs to hold a real customer-facing session.
 */
interface StoredSession {
  accessToken: string;
  clientId: string;
  scope: string;
  expiresAtEpochMs: number;
}

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly session = signal<StoredSession | null>(this.readStoredSession());

  readonly isAuthenticated = computed(() => {
    const s = this.session();
    return s !== null && s.expiresAtEpochMs > Date.now();
  });
  readonly clientId = computed(() => this.session()?.clientId ?? null);
  readonly scopes = computed(() => this.session()?.scope.split(' ').filter(Boolean) ?? []);

  constructor(private readonly http: HttpClient) {}

  async login(clientId: string, clientSecret: string): Promise<void> {
    const basic = btoa(`${clientId}:${clientSecret}`);
    const response = await firstValueFrom(
      this.http.post<TokenResponse>(
        '/v1/auth/token',
        null,
        {
          headers: new HttpHeaders({ Authorization: `Basic ${basic}` }),
          // The token endpoint authenticates via Basic; there is no Bearer token yet, so the
          // auth interceptor (which would otherwise attach one automatically) must skip this call.
          context: new HttpContext().set(SKIP_AUTH, true),
        },
      ),
    );
    const stored: StoredSession = {
      accessToken: response.access_token,
      clientId,
      scope: response.scope,
      // 30s safety margin so a request that starts right before expiry doesn't get rejected mid-flight.
      expiresAtEpochMs: Date.now() + (response.expires_in - 30) * 1000,
    };
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(stored));
    this.session.set(stored);
  }

  logout(): void {
    sessionStorage.removeItem(STORAGE_KEY);
    this.session.set(null);
  }

  currentToken(): string | null {
    const s = this.session();
    return s && s.expiresAtEpochMs > Date.now() ? s.accessToken : null;
  }

  hasScope(scope: string): boolean {
    return this.scopes().includes(scope);
  }

  private readStoredSession(): StoredSession | null {
    const raw = sessionStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    try {
      const parsed = JSON.parse(raw) as StoredSession;
      return parsed.expiresAtEpochMs > Date.now() ? parsed : null;
    } catch {
      return null;
    }
  }
}
