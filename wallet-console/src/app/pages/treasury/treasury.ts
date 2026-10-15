import { Component, OnInit, computed, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { WalletApiService } from '../../core/wallet-api.service';
import { extractErrorMessage } from '../../core/http-error.util';
import { BackingEntry, BackingPosition, BackingType, UncoveredDay } from '../../core/models';
import { BACKING_TYPE_LABELS, REGISTRABLE_TYPES, dayLabelBr, daysBefore, lastClosedDay } from '../../core/treasury';
import { transactionLabel } from '../../core/transaction-labels';
import { ProblemBanner } from '../../shared/problem-banner/problem-banner';
import { JudicialTransit } from './judicial-transit';
import { Revenue } from './revenue';

/**
 * The treasury (ADR-011 of wallet-core): where the money that backs the customers should be, how much a deposit
 * created and nobody covered yet, and the movements that resolve it. Reads need ledger:audit; registering a
 * coverage, a rebalancing or a backing account needs treasury:write.
 */
@Component({
  selector: 'app-treasury',
  imports: [FormsModule, RouterLink, DecimalPipe, DatePipe, ProblemBanner, JudicialTransit, Revenue],
  templateUrl: './treasury.html',
  styleUrl: './treasury.scss',
})
export class TreasuryPage implements OnInit {
  readonly positions = signal<BackingPosition[]>([]);
  readonly total = signal<number | null>(null);
  readonly days = signal<UncoveredDay[]>([]);
  readonly selectedId = signal<string | null>(null);
  readonly entries = signal<BackingEntry[]>([]);
  readonly next = signal<string | null>(null);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly success = signal<string | null>(null);
  readonly busy = signal(false);

  readonly canWrite = computed(() => this.auth.hasScope('treasury:write'));
  readonly unassigned = computed(() => this.positions().find((p) => p.account.type === 'UNASSIGNED') ?? null);
  readonly selected = computed(() => this.positions().find((p) => p.account.id === this.selectedId()) ?? null);
  /** Every place except "a regularizar": where a coverage or a rebalancing can go. */
  readonly destinations = computed(() => this.positions().filter((p) => p.account.type !== 'UNASSIGNED'));

  readonly typeLabel = (t: BackingType) => BACKING_TYPE_LABELS[t];
  readonly label = transactionLabel;
  readonly day = dayLabelBr;
  readonly registrable = REGISTRABLE_TYPES;

  mode: 'coverage' | 'rebalance' = 'coverage';
  moveFrom = '';
  moveTo = '';
  moveAmount: number | null = null;
  moveDocument = '';
  moveNote = '';
  /** One key per movement being registered: a retry after a network error does not move twice. */
  private moveKey = crypto.randomUUID();

  newType: BackingType = 'RESERVES';
  newName = '';
  newRef = '';

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
    this.api.treasuryPositions().subscribe({
      next: (r) => {
        this.positions.set(r.accounts);
        this.total.set(r.total);
        this.loading.set(false);
        const keep = this.selectedId() && r.accounts.some((p) => p.account.id === this.selectedId());
        this.select(keep ? this.selectedId()! : (this.unassigned()?.account.id ?? r.accounts[0]?.account.id));
      },
      error: (err) => {
        this.error.set(extractErrorMessage(err));
        this.loading.set(false);
      },
    });
    const to = lastClosedDay();
    this.api.uncoveredDaily(daysBefore(to, 13), to).subscribe({
      next: (r) => this.days.set([...r.days].reverse()),
      error: (err) => this.error.set(extractErrorMessage(err)),
    });
  }

  select(id: string | undefined): void {
    if (!id) return;
    this.selectedId.set(id);
    this.entries.set([]);
    this.next.set(null);
    this.loadEntries();
  }

  loadEntries(): void {
    const id = this.selectedId();
    if (!id) return;
    this.api.backingEntries(id, this.next() ?? undefined).subscribe({
      next: (page) => {
        this.entries.set([...this.entries(), ...page.entries]);
        this.next.set(page.next);
      },
      error: (err) => this.error.set(extractErrorMessage(err)),
    });
  }

  /** Who or what is on the other side of a movement of this place. */
  other(e: BackingEntry): string {
    if (e.type === 'JUDICIAL_TRANSIT_OUT') return 'Depósito judicial (TED)';
    if (e.type === 'REVENUE_WITHDRAWAL') return 'Receita do tenant (TED)';
    if (e.customerName) return e.customerAccount ? `${e.customerName} · ${e.customerAccount}` : e.customerName;
    if (e.otherBackingAccount) return `${e.direction === 'IN' ? 'de' : 'para'} ${e.otherBackingAccount.name}`;
    return '—';
  }

  /** A withdrawal of the revenue was recorded: the place it left from changed. */
  revenueWithdrawn(message: string): void {
    this.success.set(message);
    this.reload();
  }

  /** A TED of a judicial deposit was recorded: the place it left from changed. */
  judicialTedRecorded(message: string): void {
    this.success.set(message);
    this.reload();
  }

  setMode(mode: 'coverage' | 'rebalance'): void {
    this.mode = mode;
    this.moveFrom = '';
    this.moveTo = '';
  }

  move(): void {
    const from = this.mode === 'coverage' ? this.unassigned()?.account.id : this.moveFrom;
    if (!from || !this.moveTo || !this.moveAmount) return;
    this.busy.set(true);
    this.error.set(null);
    this.success.set(null);
    const reference =
      this.moveDocument || this.moveNote
        ? { document: this.moveDocument || null, note: this.moveNote || null }
        : undefined;
    this.api.treasuryTransfer({ from, to: this.moveTo, amount: this.moveAmount, reference }, this.moveKey).subscribe({
      next: (r) => {
        this.busy.set(false);
        this.success.set(`${r.description}: R$ ${r.amount.toFixed(2)} registrado.`);
        this.moveKey = crypto.randomUUID();
        this.moveAmount = null;
        this.moveDocument = '';
        this.moveNote = '';
        this.reload();
      },
      error: (err) => {
        this.busy.set(false);
        this.error.set(extractErrorMessage(err));
      },
    });
  }

  register(): void {
    if (!this.newName.trim()) return;
    this.busy.set(true);
    this.error.set(null);
    this.success.set(null);
    this.api
      .registerBackingAccount({ type: this.newType, name: this.newName.trim(), externalRef: this.newRef.trim() || undefined })
      .subscribe({
        next: (b) => {
          this.busy.set(false);
          this.success.set(`Conta de lastro "${b.name}" cadastrada.`);
          this.newName = '';
          this.newRef = '';
          this.reload();
        },
        error: (err) => {
          this.busy.set(false);
          this.error.set(extractErrorMessage(err));
        },
      });
  }
}
