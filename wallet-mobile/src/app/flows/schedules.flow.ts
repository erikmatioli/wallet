import { signal } from '@angular/core';
import { WalletApi, message } from '../core/api';
import { Schedule, ScheduleRequest } from '../core/contract';
import { TAX_ID_ERROR, isTaxId, tomorrow, uuid } from '../core/format';
import { AMOUNT_ERROR, Step } from './payments.flow';

/** The customer's schedules, the one whose detail is open, and cancelling it. */
export class SchedulesFlow {
  readonly schedules = signal<Schedule[]>([]);
  readonly loading = signal(false);
  readonly loaded = signal(false);
  readonly error = signal<string | null>(null);
  readonly selected = signal<Schedule | null>(null);
  readonly cancelling = signal(false);

  constructor(private readonly api: WalletApi) {}

  async load(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      this.schedules.set(await this.api.schedules());
      this.loaded.set(true);
    } catch (e) {
      this.error.set(message(e));
    } finally {
      this.loading.set(false);
    }
  }

  /** The detail shows what the list already brought: no new request. */
  open(s: Schedule): void {
    this.error.set(null);
    this.selected.set(s);
  }

  close(): void {
    this.selected.set(null);
  }

  /** Cancels and shows the result in the open detail; the list gets it too. */
  async cancel(): Promise<void> {
    const selected = this.selected();
    if (!selected || this.cancelling()) return;
    this.cancelling.set(true);
    this.error.set(null);
    try {
      const cancelled = await this.api.cancelSchedule(selected.id);
      this.selected.set(cancelled);
      this.schedules.update((all) => all.map((s) => (s.id === cancelled.id ? cancelled : s)));
    } catch (e) {
      this.error.set(message(e));
    } finally {
      this.cancelling.set(false);
    }
  }
}

export interface NewScheduleForm {
  pix: boolean;
  /** yyyy-MM-dd, from the date input. */
  date: string;
  amountCents: number;
  description: string;
  // transfer
  branch: string;
  number: string;
  checkDigit: string;
  // Pix
  name: string;
  taxId: string;
  ispb: string;
  pixBranch: string;
  account: string;
}

const emptyForm = (): NewScheduleForm => ({
  pix: false,
  date: '',
  amountCents: 0,
  description: '',
  branch: '',
  number: '',
  checkDigit: '',
  name: '',
  taxId: '',
  ispb: '',
  pixBranch: '',
  account: '',
});

/** A new schedule: a transfer or a Pix, a day, then confirm. Same three steps as the payments. */
export class NewScheduleFlow {
  readonly form = signal(emptyForm());
  readonly step = signal<Step<ScheduleRequest, Schedule>>({ kind: 'form' });
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);

  constructor(
    private readonly api: WalletApi,
    private readonly now: () => Date = () => new Date(),
  ) {}

  /** The first day the date input offers. */
  minDate(): string {
    return tomorrow(this.now());
  }

  edit(change: Partial<NewScheduleForm>): void {
    this.form.update((f) => ({ ...f, ...change }));
    this.error.set(null);
  }

  /** Checks the format here; whether the day and the destination are acceptable is app-api's to say. */
  review(): void {
    const f = this.form();
    const error = !/^\d{4}-\d{2}-\d{2}$/.test(f.date)
      ? 'Escolha o dia do pagamento.'
      : f.date < this.minDate()
        ? 'O agendamento precisa ser a partir de amanhã.'
        : f.amountCents <= 0
          ? AMOUNT_ERROR
          : !f.pix && [f.branch, f.number, f.checkDigit].some((v) => !v.trim())
            ? 'Informe agência, conta e dígito de quem recebe.'
            : f.pix && [f.name, f.taxId, f.ispb, f.pixBranch, f.account].some((v) => !v.trim())
              ? 'Preencha os dados do recebedor.'
              : f.pix && !isTaxId(f.taxId)
                ? TAX_ID_ERROR
                : null;
    if (error) {
      this.error.set(error);
      return;
    }
    const request: ScheduleRequest = {
      type: f.pix ? 'PIX' : 'TRANSFER',
      executeOn: f.date,
      amountCents: f.amountCents,
      description: f.description.trim() || null,
      transfer: f.pix
        ? null
        : { branch: f.branch.trim(), number: f.number.trim(), checkDigit: f.checkDigit.trim() },
      pix: f.pix
        ? {
            ispb: f.ispb.trim(),
            branch: f.pixBranch.trim(),
            accountNumber: f.account.trim(),
            taxId: f.taxId.trim(),
            name: f.name.trim(),
          }
        : null,
    };
    this.step.set({ kind: 'confirm', preview: request, idempotencyKey: uuid() });
    this.error.set(null);
  }

  async confirm(): Promise<void> {
    const step = this.step();
    if (step.kind !== 'confirm' || this.busy()) return;
    this.busy.set(true);
    this.error.set(null);
    try {
      const created = await this.api.createSchedule(step.preview, step.idempotencyKey);
      this.step.set({ kind: 'done', receipt: created });
    } catch (e) {
      // Same key on the next confirmation: a retry, never a second schedule.
      this.error.set(message(e));
    } finally {
      this.busy.set(false);
    }
  }

  change(): void {
    this.step.set({ kind: 'form' });
    this.error.set(null);
  }

  reset(): void {
    this.form.set(emptyForm());
    this.step.set({ kind: 'form' });
    this.error.set(null);
  }
}
