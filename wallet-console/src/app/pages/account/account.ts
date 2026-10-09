import { Component, DestroyRef, OnInit, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DatePipe, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { WalletApiService } from '../../core/wallet-api.service';
import { extractErrorMessage } from '../../core/http-error.util';
import { AccountDetailResponse, AuditResponse, EntryResponse } from '../../core/models';
import { counterpartyName, transactionLabel } from '../../core/transaction-labels';
import { EntryDetail } from './entry-detail/entry-detail';
import { AccountSchedules } from './schedules/account-schedules';
import { ProblemBanner } from '../../shared/problem-banner/problem-banner';

type TransferMethod = 'id' | 'number';

@Component({
  selector: 'app-account',
  imports: [FormsModule, RouterLink, DecimalPipe, DatePipe, ProblemBanner, EntryDetail, AccountSchedules],
  templateUrl: './account.html',
  styleUrl: './account.scss',
})
export class AccountPage implements OnInit {
  private accountId = '';

  readonly account = signal<AccountDetailResponse | null>(null);
  readonly entries = signal<EntryResponse[]>([]);
  readonly nextBefore = signal<number | null>(null);
  /** Statement restricted to Pix entries (every PIX_* type). */
  readonly pixOnly = signal(false);
  readonly audit = signal<AuditResponse | null>(null);

  readonly loading = signal(false);
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  readonly notice = signal<string | null>(null);

  // Customer's email (wallet-app ADR-003): where the app sends the login codes.
  readonly editingEmail = signal(false);
  emailDraft = '';

  // Deposit / withdraw
  depositAmount = 0;
  depositDescription = '';
  withdrawAmount = 0;
  withdrawDescription = '';

  // Transfer
  transferMethod: TransferMethod = 'id';
  transferDestinationId = '';
  transferBranch = '';
  transferNumber = '';
  transferCheckDigit = '';
  transferAmount = 0;
  transferDescription = '';

  constructor(
    private readonly route: ActivatedRoute,
    private readonly api: WalletApiService,
    private readonly destroyRef: DestroyRef,
  ) {}

  ngOnInit(): void {
    // Subscribe instead of reading the snapshot: navigating /accounts/A -> /accounts/B (e.g. via a
    // statement's counterparty link) reuses this component, so ngOnInit does not run again.
    this.route.paramMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
      this.accountId = params.get('id') ?? '';
      this.account.set(null);
      this.entries.set([]);
      this.nextBefore.set(null);
      this.pixOnly.set(false);
      this.audit.set(null);
      this.error.set(null);
      this.notice.set(null);
      this.loadAccount();
      this.loadStatement(true);
    });
  }

  loadAccount(): void {
    this.loading.set(true);
    this.api.getAccount(this.accountId).subscribe({
      next: (acc) => {
        this.account.set(acc);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(extractErrorMessage(err));
        this.loading.set(false);
      },
    });
  }

  loadStatement(reset: boolean): void {
    const before = reset ? undefined : (this.nextBefore() ?? undefined);
    this.api.getStatement(this.accountId, before, undefined, this.pixOnly() ? 'PIX' : undefined).subscribe({
      next: (page) => {
        this.entries.set(reset ? page.entries : [...this.entries(), ...page.entries]);
        this.nextBefore.set(page.nextBefore ?? null);
      },
      error: (err) => this.error.set(extractErrorMessage(err)),
    });
  }

  togglePixOnly(): void {
    this.pixOnly.update((v) => !v);
    this.loadStatement(true);
  }

  typeLabel(type: string): string {
    return transactionLabel(type);
  }

  counterparty(entry: EntryResponse): string | null {
    return counterpartyName(entry);
  }


  private runAction(action: () => void): void {
    this.error.set(null);
    this.notice.set(null);
    this.busy.set(true);
    action();
  }

  private afterMutation(message: string): void {
    this.notice.set(message);
    this.busy.set(false);
    this.loadAccount();
    this.loadStatement(true);
  }

  submitDeposit(): void {
    this.runAction(() => {
      this.api
        .deposit(this.accountId, { amount: this.depositAmount, description: this.depositDescription || undefined }, crypto.randomUUID())
        .subscribe({
          next: () => {
            this.depositAmount = 0;
            this.depositDescription = '';
            this.afterMutation('Depósito realizado.');
          },
          error: (err) => {
            this.error.set(extractErrorMessage(err));
            this.busy.set(false);
          },
        });
    });
  }

  submitWithdraw(): void {
    this.runAction(() => {
      this.api
        .withdraw(this.accountId, { amount: this.withdrawAmount, description: this.withdrawDescription || undefined }, crypto.randomUUID())
        .subscribe({
          next: () => {
            this.withdrawAmount = 0;
            this.withdrawDescription = '';
            this.afterMutation('Saque realizado.');
          },
          error: (err) => {
            this.error.set(extractErrorMessage(err));
            this.busy.set(false);
          },
        });
    });
  }

  submitTransfer(): void {
    this.runAction(() => {
      this.api
        .transfer(
          {
            sourceAccountId: this.accountId,
            amount: this.transferAmount,
            description: this.transferDescription || undefined,
            ...(this.transferMethod === 'id'
              ? { destinationAccountId: this.transferDestinationId }
              : {
                  destination: {
                    branch: this.transferBranch,
                    number: this.transferNumber,
                    checkDigit: this.transferCheckDigit,
                  },
                }),
          },
          crypto.randomUUID(),
        )
        .subscribe({
          next: () => {
            this.transferDestinationId = '';
            this.transferBranch = '';
            this.transferNumber = '';
            this.transferCheckDigit = '';
            this.transferAmount = 0;
            this.transferDescription = '';
            this.afterMutation('Transferência realizada.');
          },
          error: (err) => {
            this.error.set(extractErrorMessage(err));
            this.busy.set(false);
          },
        });
    });
  }

  editEmail(current: string | null | undefined): void {
    this.emailDraft = current ?? '';
    this.editingEmail.set(true);
  }

  saveEmail(): void {
    const acc = this.account();
    if (!acc) return;
    this.runAction(() => {
      this.api.changeCustomerEmail(acc.customerId, this.emailDraft.trim()).subscribe({
        next: () => {
          this.editingEmail.set(false);
          this.afterMutation(this.emailDraft.trim() ? 'E-mail salvo.' : 'E-mail removido.');
        },
        error: (err) => {
          this.error.set(extractErrorMessage(err));
          this.busy.set(false);
        },
      });
    });
  }

  runAudit(): void {
    this.runAction(() => {
      this.api.audit(this.accountId).subscribe({
        next: (report) => {
          this.audit.set(report);
          this.busy.set(false);
        },
        error: (err) => {
          this.error.set(extractErrorMessage(err));
          this.busy.set(false);
        },
      });
    });
  }
}
