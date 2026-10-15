import { Component, computed, effect, input, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { AuthService } from '../../../core/auth.service';
import { WalletApiService } from '../../../core/wallet-api.service';
import { extractErrorMessage } from '../../../core/http-error.util';
import { FeeAccount, FeePackage, FeeUsage } from '../../../core/models';
import {
  CHARGE_STATUS_LABELS,
  CUSTOMER_TYPE_LABELS,
  changeLabel,
  chargeReason,
  chargeTone,
  monthLabel,
  operationLabel,
  packagesOf,
  scheduleLines,
} from '../../../core/fees';
import { rememberOperator, rememberedOperator } from '../../../core/account-control';
import { ProblemBanner } from '../../../shared/problem-banner/problem-banner';

/**
 * The Package and fees section of an account (wallet-fees' ADR-001): its package and the migration to another,
 * this month's free quota, and the latest fees with why a pending one waits. Reading needs fees:read; migrating,
 * fees:admin. The fees are charged by wallet-fees after the core's events, a moment after the transaction: the
 * section has its own refresh.
 */
@Component({
  selector: 'app-account-fees',
  imports: [FormsModule, DecimalPipe, DatePipe, ProblemBanner],
  templateUrl: './account-fees.html',
  styleUrl: './account-fees.scss',
})
export class AccountFees {
  readonly accountId = input.required<string>();

  readonly fees = signal<FeeAccount | null>(null);
  readonly packages = signal<FeePackage[]>([]);
  /** wallet-fees does not know the account: an internal one, or one whose opening it has not read yet. */
  readonly notInBasket = signal(false);
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  readonly notice = signal<string | null>(null);
  readonly changing = signal(false);

  readonly canAdmin: boolean;
  readonly currentPackage = computed(() => this.packages().find((p) => p.id === this.fees()?.packageId) ?? null);
  /** The packages the account may move to: its customer type's, but the one it has. */
  readonly options = computed(() => {
    const f = this.fees();
    return f ? packagesOf(this.packages(), f.customerType).filter((p) => p.id !== f.packageId) : [];
  });
  /** What each operation costs after the quota, in the package's schedule in force. */
  readonly prices = computed(() => {
    const current = this.currentPackage()?.current;
    return current ? scheduleLines(current) : [];
  });

  readonly statusLabels = CHARGE_STATUS_LABELS;
  readonly typeLabels = CUSTOMER_TYPE_LABELS;
  readonly operation = operationLabel;
  readonly month = monthLabel;
  readonly reason = chargeReason;
  readonly tone = chargeTone;

  targetPackageId = '';
  operator = rememberedOperator();

  constructor(
    private readonly api: WalletApiService,
    auth: AuthService,
  ) {
    this.canAdmin = auth.hasScope('fees:admin');
    // Navigating from one account to another reuses this component with a new accountId.
    effect(() => this.load(this.accountId()));
  }

  load(accountId: string = this.accountId()): void {
    this.error.set(null);
    this.api.feeAccount(accountId).subscribe({
      next: (f) => {
        this.notInBasket.set(false);
        this.fees.set(f);
      },
      error: (err) => {
        this.fees.set(null);
        if (err instanceof HttpErrorResponse && err.status === 404) {
          this.notInBasket.set(true);
        } else {
          this.error.set(extractErrorMessage(err));
        }
      },
    });
    if (this.packages().length === 0) {
      this.api.feePackages().subscribe({ next: (list) => this.packages.set(list), error: () => this.packages.set([]) });
    }
  }

  /** The price of an operation after the quota, as the usage table shows it. */
  priceOf(usage: FeeUsage): { price: number; priceFromCompany: number | null } | null {
    return this.prices().find((p) => p.operation === usage.operation) ?? null;
  }

  changeText(change: FeeAccount['changes'][number]): string {
    return changeLabel(change, this.packages());
  }

  startChange(): void {
    this.targetPackageId = this.options()[0]?.id ?? '';
    this.notice.set(null);
    this.changing.set(true);
  }

  submitChange(): void {
    const target = this.options().find((p) => p.id === this.targetPackageId);
    if (!target) return;
    this.busy.set(true);
    this.error.set(null);
    this.api.changeFeePackage(this.accountId(), target.id, this.operator).subscribe({
      next: (f) => {
        rememberOperator(this.operator);
        this.fees.set(f);
        this.busy.set(false);
        this.changing.set(false);
        this.notice.set(`Pacote alterado para ${target.name}.`);
      },
      error: (err) => {
        this.busy.set(false);
        this.error.set(extractErrorMessage(err));
      },
    });
  }
}
