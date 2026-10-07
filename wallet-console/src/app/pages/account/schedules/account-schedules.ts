import { Component, effect, input, signal, viewChild } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { WalletApiService } from '../../../core/wallet-api.service';
import { extractErrorMessage } from '../../../core/http-error.util';
import { CreateScheduleRequest, ScheduleResponse, ScheduleType } from '../../../core/models';
import { brasiliaToday, payeeName, scheduleTypeLabel, scheduleView } from '../../../core/schedule-labels';
import { ProblemBanner } from '../../../shared/problem-banner/problem-banner';
import { ScheduleDetail } from './schedule-detail';

/**
 * The Schedules section of an account (wallet-scheduler's ADR-001): what this account will pay and
 * on which day, what happened on the day, and a form to schedule a transfer or a Pix.
 */
@Component({
  selector: 'app-account-schedules',
  imports: [FormsModule, DecimalPipe, DatePipe, ProblemBanner, ScheduleDetail],
  templateUrl: './account-schedules.html',
  styleUrl: './account-schedules.scss',
})
export class AccountSchedules {
  readonly accountId = input.required<string>();

  readonly schedules = signal<ScheduleResponse[]>([]);
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  readonly notice = signal<string | null>(null);
  readonly showForm = signal(false);
  private readonly detail = viewChild.required(ScheduleDetail);

  readonly view = scheduleView;
  readonly payee = payeeName;
  readonly typeLabel = scheduleTypeLabel;
  /** The earliest date the scheduler accepts. */
  readonly tomorrow = addDays(brasiliaToday(), 1);

  // Form. The Idempotency-Key lives as long as the form: a retry after a timeout cannot schedule twice.
  type: ScheduleType = 'TRANSFER';
  executeOn = this.tomorrow;
  amount = 0;
  description = '';
  branch = '';
  number = '';
  checkDigit = '';
  payerTaxId = '';
  payeeIspb = '';
  payeeBranch = '';
  payeeAccount = '';
  payeeTaxId = '';
  payeeName = '';
  private idempotencyKey = crypto.randomUUID();

  constructor(private readonly api: WalletApiService) {
    // Navigating from one account to another reuses this component with a new accountId.
    effect(() => this.load(this.accountId()));
  }

  load(accountId: string = this.accountId()): void {
    this.api.listSchedules(accountId).subscribe({
      next: (list) => this.schedules.set(list),
      error: (err) => this.error.set(extractErrorMessage(err)),
    });
  }

  openDetail(s: ScheduleResponse): void {
    this.detail().open(s);
  }

  submit(): void {
    this.error.set(null);
    this.notice.set(null);
    this.busy.set(true);
    this.api.createSchedule(this.request(), this.idempotencyKey).subscribe({
      next: (created) => {
        this.busy.set(false);
        this.resetForm();
        this.notice.set(`Agendado para ${created.executeOn.split('-').reverse().join('/')}.`);
        this.load();
      },
      error: (err) => {
        this.busy.set(false);
        this.error.set(extractErrorMessage(err));
      },
    });
  }

  cancel(id: string): void {
    this.busy.set(true);
    this.api.cancelSchedule(id).subscribe({
      next: (cancelled) => {
        this.busy.set(false);
        this.detail().open(cancelled);
        this.notice.set('Agendamento cancelado.');
        this.load();
      },
      error: (err) => {
        this.busy.set(false);
        this.detail().close();
        this.error.set(extractErrorMessage(err));
      },
    });
  }

  private request(): CreateScheduleRequest {
    const common = {
      type: this.type,
      payerAccountId: this.accountId(),
      executeOn: this.executeOn,
      amount: this.amount,
      description: this.description || undefined,
    };
    return this.type === 'PIX'
      ? {
          ...common,
          pix: {
            payerTaxId: this.payerTaxId,
            payee: {
              ispb: this.payeeIspb,
              branch: this.payeeBranch,
              accountNumber: this.payeeAccount,
              taxId: this.payeeTaxId,
              name: this.payeeName,
            },
          },
        }
      : { ...common, destination: { branch: this.branch, number: this.number, checkDigit: this.checkDigit } };
  }

  private resetForm(): void {
    this.amount = 0;
    this.description = '';
    this.branch = this.number = this.checkDigit = '';
    this.payeeIspb = this.payeeBranch = this.payeeAccount = this.payeeTaxId = this.payeeName = '';
    this.idempotencyKey = crypto.randomUUID();
    this.showForm.set(false);
  }
}

function addDays(isoDate: string, days: number): string {
  const d = new Date(`${isoDate}T12:00:00Z`);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}
