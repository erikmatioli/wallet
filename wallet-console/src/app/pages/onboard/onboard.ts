import { Component, signal } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { WalletApiService } from '../../core/wallet-api.service';
import { extractErrorMessage } from '../../core/http-error.util';
import { OnboardCustomerResponse } from '../../core/models';
import { ProblemBanner } from '../../shared/problem-banner/problem-banner';

@Component({
  selector: 'app-onboard',
  imports: [FormsModule, DecimalPipe, ProblemBanner],
  templateUrl: './onboard.html',
  styleUrl: './onboard.scss',
})
export class Onboard {
  name = '';
  taxId = '';
  externalRef = '';
  email = '';

  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly result = signal<OnboardCustomerResponse | null>(null);

  constructor(
    private readonly api: WalletApiService,
    private readonly router: Router,
  ) {}

  submit(): void {
    this.error.set(null);
    this.loading.set(true);
    this.result.set(null);
    this.api
      .onboardCustomer({
        name: this.name.trim(),
        taxId: this.taxId.trim(),
        externalRef: this.externalRef.trim() || undefined,
        email: this.email.trim() || undefined,
      })
      .subscribe({
        next: (res) => {
          this.result.set(res);
          this.loading.set(false);
          this.name = '';
          this.taxId = '';
          this.externalRef = '';
          this.email = '';
        },
        error: (err) => {
          this.error.set(extractErrorMessage(err));
          this.loading.set(false);
        },
      });
  }

  openAccount(accountId: string): void {
    this.router.navigate(['/accounts', accountId]);
  }
}
