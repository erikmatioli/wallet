import { Injectable, computed, signal } from '@angular/core';
import { Session } from './contract';

/**
 * The customer's session, only in memory (ADR-001, decision 5): never localStorage, cookies or IndexedDB.
 * Closing or reloading the app asks for a new code. `endedBecause` is what the login screen shows after the
 * session ends by itself (expired token, inactivity).
 */
@Injectable({ providedIn: 'root' })
export class SessionStore {
  private readonly current = signal<{ token: string; customerName: string } | null>(null);
  private readonly reason = signal<string | null>(null);

  readonly loggedIn = computed(() => this.current() !== null);
  readonly customerName = computed(() => this.current()?.customerName ?? '');
  readonly endedBecause = this.reason.asReadonly();

  get token(): string | null {
    return this.current()?.token ?? null;
  }

  start(session: Session): void {
    this.reason.set(null);
    this.current.set({ token: session.token, customerName: session.customerName });
  }

  /** The customer tapped "Sair". */
  logout(): void {
    this.current.set(null);
    this.reason.set(null);
  }

  /** Ended by itself: the login screen says why. */
  expire(reason: string): void {
    if (!this.current()) return;
    this.current.set(null);
    this.reason.set(reason);
  }
}
