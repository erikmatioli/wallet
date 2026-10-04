import { Component, OnInit, signal } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { WalletApiService } from '../../core/wallet-api.service';
import { extractErrorMessage } from '../../core/http-error.util';
import { AccountListItem } from '../../core/models';
import { ProblemBanner } from '../../shared/problem-banner/problem-banner';

@Component({
  selector: 'app-home',
  imports: [FormsModule, RouterLink, DecimalPipe, ProblemBanner],
  templateUrl: './home.html',
  styleUrl: './home.scss',
})
export class Home implements OnInit {
  readonly items = signal<AccountListItem[]>([]);
  readonly cursor = signal<string | null>(null);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);

  searchMethod: 'number' | 'taxId' = 'number';


  // Search by account number
  searchTaxId = '';
  searchBranch = '';
  searchNumber = '';
  searchCheckDigit = '';
  readonly searching = signal(false);

  constructor(
    private readonly api: WalletApiService,
    private readonly router: Router,
  ) {}

  ngOnInit(): void {
    this.loadPage(true);
  }

  loadPage(reset: boolean): void {
    this.loading.set(true);
    this.error.set(null);
    this.api.listAccounts(reset ? undefined : (this.cursor() ?? undefined)).subscribe({
      next: (page) => {
        this.items.set(reset ? page.items : [...this.items(), ...page.items]);
        this.cursor.set(page.nextCursor);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(extractErrorMessage(err));
        this.loading.set(false);
      },
    });
  }

  search(): void {
    this.error.set(null);
    this.searching.set(true);
    // this.api.lookupAccountByNumber(this.searchBranch.trim(), this.searchNumber.trim(), this.searchCheckDigit.trim())
    const lookup = this.searchMethod === 'number'
      ? this.api.lookupAccountByNumber(this.searchBranch.trim(), this.searchNumber.trim(), this.searchCheckDigit.trim())
      : this.api.lookupAccountByTaxId(this.searchTaxId.trim());

    lookup.subscribe({
      next: (acc) => {
        this.searching.set(false);
        this.router.navigate(['/accounts', acc.id]);
      },
      error: (err) => {
        this.error.set(extractErrorMessage(err));
        this.searching.set(false);
      },
    });
  }

  openAccount(accountId: string): void {
    this.router.navigate(['/accounts', accountId]);
  }
}
