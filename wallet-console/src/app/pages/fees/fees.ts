import { Component, OnInit, computed, signal } from '@angular/core';
import { DatePipe, DecimalPipe, NgTemplateOutlet } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { WalletApiService } from '../../core/wallet-api.service';
import { extractErrorMessage } from '../../core/http-error.util';
import { CustomerType, FeePackage, FeeSchedule } from '../../core/models';
import {
  CUSTOMER_TYPE_LABELS,
  ScheduleDraft,
  draftFrom,
  operationLabel,
  packagesOf,
  scheduleLines,
  scheduleRequest,
} from '../../core/fees';
import { brasiliaToday } from '../../core/schedule-labels';
import { rememberOperator, rememberedOperator } from '../../core/account-control';
import { ProblemBanner } from '../../shared/problem-banner/problem-banner';

/**
 * The fee basket (wallet-fees' ADR-001): the six packages, PF and PJ, with the prices in force and the ones
 * scheduled. A price changes by a new schedule from a date on, never backwards: what was charged keeps the schedule
 * of its day. Reading needs fees:read; a new schedule, fees:admin.
 */
@Component({
  selector: 'app-fees',
  imports: [FormsModule, RouterLink, DecimalPipe, DatePipe, NgTemplateOutlet, ProblemBanner],
  templateUrl: './fees.html',
  styleUrl: './fees.scss',
})
export class FeesPage implements OnInit {
  readonly packages = signal<FeePackage[]>([]);
  readonly loading = signal(false);
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  readonly success = signal<string | null>(null);
  /** The package whose history is open, and its schedules, newest first. */
  readonly historyOf = signal<string | null>(null);
  readonly history = signal<FeeSchedule[]>([]);
  /** The package the new-schedule form is for. */
  readonly editing = signal<FeePackage | null>(null);

  readonly canAdmin = computed(() => this.auth.hasScope('fees:admin'));
  readonly groups = computed(() =>
    (['PF', 'PJ'] as CustomerType[]).map((type) => ({
      type,
      label: CUSTOMER_TYPE_LABELS[type],
      packages: packagesOf(this.packages(), type),
    })),
  );

  readonly lines = scheduleLines;
  readonly operation = operationLabel;
  readonly today = brasiliaToday();

  draft: ScheduleDraft | null = null;
  operator = rememberedOperator();

  constructor(
    private readonly api: WalletApiService,
    private readonly auth: AuthService,
  ) {}

  ngOnInit(): void {
    this.reload();
  }

  reload(): void {
    this.loading.set(true);
    this.error.set(null);
    this.api.feePackages().subscribe({
      next: (list) => {
        this.packages.set(list);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(extractErrorMessage(err));
        this.loading.set(false);
      },
    });
  }

  toggleHistory(pkg: FeePackage): void {
    if (this.historyOf() === pkg.id) {
      this.historyOf.set(null);
      return;
    }
    this.historyOf.set(pkg.id);
    this.history.set([]);
    this.api.feeSchedules(pkg.id).subscribe({
      next: (list) => this.history.set([...list].sort((a, b) => b.validFrom.localeCompare(a.validFrom))),
      error: (err) => this.error.set(extractErrorMessage(err)),
    });
  }

  /** The form starts tomorrow, from the prices the package will have then. */
  startSchedule(pkg: FeePackage): void {
    this.success.set(null);
    this.error.set(null);
    this.draft = draftFrom(pkg, addDays(this.today, 1));
    this.editing.set(pkg);
  }

  closeForm(): void {
    this.editing.set(null);
    this.draft = null;
  }

  submit(): void {
    const pkg = this.editing();
    if (!pkg || !this.draft) return;
    this.busy.set(true);
    this.error.set(null);
    this.api.createFeeSchedule(pkg.id, scheduleRequest(this.draft, this.operator)).subscribe({
      next: (created) => {
        rememberOperator(this.operator);
        this.busy.set(false);
        this.closeForm();
        this.success.set(`Nova tabela do ${pkg.name} a partir de ${created.validFrom.split('-').reverse().join('/')}.`);
        if (this.historyOf() === pkg.id) this.historyOf.set(null);
        this.reload();
      },
      error: (err) => {
        this.busy.set(false);
        this.error.set(extractErrorMessage(err));
      },
    });
  }
}

function addDays(isoDate: string, days: number): string {
  const d = new Date(`${isoDate}T12:00:00Z`);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}
