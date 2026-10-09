import { DestroyRef, Injectable, effect, inject } from '@angular/core';
import { SessionStore } from './session';

export const INACTIVITY_REASON = 'Sua sessão foi encerrada por inatividade. Entre de novo.';

/**
 * When the customer last did something (ADR-001, decision 5). Pure: the clock is passed in, so it is
 * tested without waiting.
 */
export class InactivityMonitor {
  private last: number;

  constructor(
    readonly timeoutMs = 5 * 60_000,
    private readonly now: () => number = () => Date.now(),
  ) {
    this.last = now();
  }

  touch(): void {
    this.last = this.now();
  }

  expired(): boolean {
    return this.now() - this.last >= this.timeoutMs;
  }
}

/**
 * Ends the session after 5 minutes without a touch or a key. Also checked when the app comes back from
 * the background: a phone's timers sleep there, so the check on `visibilitychange` is the one that counts.
 */
@Injectable({ providedIn: 'root' })
export class InactivityWatch {
  private readonly session = inject(SessionStore);
  private readonly monitor = new InactivityMonitor();

  constructor() {
    const touch = () => this.monitor.touch();
    const check = () => {
      if (this.session.loggedIn() && this.monitor.expired()) this.session.expire(INACTIVITY_REASON);
    };
    const onVisible = () => {
      if (document.visibilityState === 'visible') check();
    };
    const events = ['pointerdown', 'keydown', 'wheel', 'touchstart'] as const;
    events.forEach((e) => document.addEventListener(e, touch, { passive: true, capture: true }));
    document.addEventListener('visibilitychange', onVisible);
    const timer = setInterval(check, 5_000);

    // A new session starts "active".
    effect(() => {
      if (this.session.loggedIn()) this.monitor.touch();
    });

    inject(DestroyRef).onDestroy(() => {
      events.forEach((e) => document.removeEventListener(e, touch, { capture: true }));
      document.removeEventListener('visibilitychange', onVisible);
      clearInterval(timer);
    });
  }
}
