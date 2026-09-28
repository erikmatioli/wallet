import { Component, OnInit, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { WalletApiService } from '../../core/wallet-api.service';
import { extractErrorMessage } from '../../core/http-error.util';
import { AccountResponse, AuditResponse, EntryResponse } from '../../core/models';
import { ProblemBanner } from '../../shared/problem-banner/problem-banner';

type TransferMethod = 'id' | 'number';

@Component({
  selector: 'app-account',
  imports: [FormsModule, RouterLink, DecimalPipe, DatePipe, ProblemBanner],
  templateUrl: './account.html',
  styleUrl: './account.scss',
})
export class AccountPage implements OnInit {
  private accountId = '';

  readonly account = signal<AccountResponse | null>(null);
  readonly entries = signal<EntryResponse[]>([]);
  readonly nextBefore = signal<number | null>(null);
  readonly audit = signal<AuditResponse | null>(null);

  readonly loading = signal(false);
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  readonly notice = signal<string | null>(null);

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
  ) {}

  ngOnInit(): void {
    this.accountId = this.route.snapshot.paramMap.get('id') ?? '';
    this.loadAccount();
    this.loadStatement(true);
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
    this.api.getStatement(this.accountId, before).subscribe({
      next: (page) => {
        this.entries.set(reset ? page.entries : [...this.entries(), ...page.entries]);
        this.nextBefore.set(page.nextBefore);
      },
      error: (err) => this.error.set(extractErrorMessage(err)),
    });
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
