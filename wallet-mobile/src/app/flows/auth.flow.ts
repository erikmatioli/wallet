import { computed, signal } from '@angular/core';
import { AppApiError, WalletApi, message } from '../core/api';
import { CodeSent } from '../core/contract';
import { isCpf } from '../core/format';
import { SessionStore } from '../core/session';

/** The second step of login and signup (ADR-002 of wallet-app): the code that arrived by email. */
export interface CodeStep {
  challengeId: string;
  sentMessage: string;
}

/** Counts down the seconds until "Reenviar código" is offered again; a new countdown replaces the old. */
class Countdown {
  readonly left = signal(0);
  private timer: ReturnType<typeof setInterval> | null = null;

  start(seconds: number): void {
    this.stop();
    this.left.set(seconds);
    this.timer = setInterval(() => {
      this.left.update((s) => Math.max(0, s - 1));
      if (this.left() === 0) this.stop();
    }, 1_000);
  }

  stop(): void {
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
  }
}

/** What both flows share: the code step, the code typed, busy, error and the resend countdown. */
abstract class CodeFlow {
  readonly step = signal<CodeStep | null>(null);
  readonly code = signal('');
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  protected readonly countdown = new Countdown();
  readonly resendIn = this.countdown.left.asReadonly();
  readonly canConfirm = computed(() => !this.busy() && this.code().length === 6);

  constructor(
    protected readonly api: WalletApi,
    protected readonly session: SessionStore,
  ) {}

  /** Only digits, at most 6: what is pasted from the email may come with spaces. */
  setCode(value: string): void {
    this.code.set(value.replace(/\D/g, '').slice(0, 6));
    this.error.set(null);
  }

  /** Back to the first step, to fix something; the next code is a new one. */
  back(): void {
    this.countdown.stop();
    this.step.set(null);
    this.code.set('');
    this.error.set(null);
  }

  dispose(): void {
    this.countdown.stop();
  }

  protected showCode(sent: CodeSent): void {
    this.busy.set(false);
    this.code.set('');
    this.step.set({ challengeId: sent.challengeId, sentMessage: sent.message });
    this.countdown.start(sent.resendAfterSeconds ?? 60);
  }

  protected failed(e: unknown): void {
    this.busy.set(false);
    this.error.set(message(e));
    // A "wait N seconds" from app-api restarts the countdown, so the button matches the server.
    if (e instanceof AppApiError && e.retryAfterSeconds && this.step())
      this.countdown.start(e.retryAfterSeconds);
  }

  protected async finish(
    confirm: () => Promise<Parameters<SessionStore['start']>[0]>,
  ): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    try {
      this.session.start(await confirm());
      this.countdown.stop();
    } catch (e) {
      this.busy.set(false);
      this.code.set('');
      this.error.set(message(e));
    }
  }
}

/** Login: the CPF, then the code sent to the account's email. Success only starts the session. */
export class LoginFlow extends CodeFlow {
  readonly cpf = signal('');
  readonly canSend = computed(() => !this.busy() && isCpf(this.cpf()));

  setCpf(value: string): void {
    this.cpf.set(value);
    this.error.set(null);
  }

  /** Asks for a code: the first one, or again with "Reenviar código". */
  async sendCode(): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    try {
      this.showCode(await this.api.startLogin(this.cpf()));
    } catch (e) {
      this.failed(e);
    }
  }

  confirm(): Promise<void> {
    const step = this.step();
    if (!step || !this.canConfirm()) return Promise.resolve();
    return this.finish(() => this.api.confirmLogin(step.challengeId, this.cpf(), this.code()));
  }
}

/** Opening an account: name, CPF and email, then the code sent to that email - proof the customer owns it. */
export class SignupFlow extends CodeFlow {
  readonly cpf = signal('');
  readonly name = signal('');
  readonly email = signal('');
  readonly canSend = computed(
    () =>
      !this.busy() &&
      isCpf(this.cpf()) &&
      this.name().trim().length > 1 &&
      /.+@.+\..+/.test(this.email().trim()),
  );

  set(field: 'cpf' | 'name' | 'email', value: string): void {
    this[field].set(value);
    this.error.set(null);
  }

  async sendCode(): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    try {
      this.showCode(
        await this.api.startSignup(this.cpf(), this.name().trim(), this.email().trim()),
      );
    } catch (e) {
      this.failed(e);
    }
  }

  confirm(): Promise<void> {
    const step = this.step();
    if (!step || !this.canConfirm()) return Promise.resolve();
    return this.finish(() =>
      this.api.confirmSignup(
        step.challengeId,
        this.code(),
        this.cpf(),
        this.name().trim(),
        this.email().trim(),
      ),
    );
  }
}
