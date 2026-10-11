import { Component, OnInit, computed, input, output, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { WalletApiService } from '../../core/wallet-api.service';
import { extractErrorMessage } from '../../core/http-error.util';
import { BackingPosition, PendingTransit } from '../../core/models';
import { rememberOperator, rememberedOperator } from '../../core/account-control';
import { ProblemBanner } from '../../shared/problem-banner/problem-banner';

/**
 * "Depósitos judiciais a transferir" (ADR-013 of wallet-core): the judicial transfers to a court deposit in another
 * bank whose TED the treasury still has to send. The core has no TED: the treasury sends it outside and records it
 * here, from the backing account it left.
 */
@Component({
  selector: 'app-judicial-transit',
  imports: [FormsModule, DecimalPipe, DatePipe, RouterLink, ProblemBanner],
  template: `
    @if (pending().length > 0 || error()) {
      <h2 class="section-title">Depósitos judiciais a transferir</h2>
      <div class="card">
        <app-problem-banner [message]="error()" />
        <p class="hint">Transferências por ordem judicial a um depósito em outro banco: registre aqui cada TED enviada.</p>
        <table>
          <thead>
            <tr>
              <th>Data</th>
              <th>Processo</th>
              <th class="num">Ordem</th>
              <th class="num">Falta enviar</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            @for (p of pending(); track p.transferId) {
              <tr>
                <td class="nowrap">{{ p.occurredAt | date: 'dd/MM HH:mm' }}</td>
                <td>
                  {{ p.process }}
                  <a class="hint" [routerLink]="['/accounts', p.accountId]">ver conta</a>
                </td>
                <td class="num">{{ p.amount | number: '1.2-2' }}</td>
                <td class="num">{{ p.remaining | number: '1.2-2' }}</td>
                <td>
                  @if (canWrite) {
                    <button type="button" class="secondary" (click)="start(p)">Registrar TED</button>
                  }
                </td>
              </tr>
            }
          </tbody>
        </table>
        @if (current(); as p) {
          <form class="ted" (ngSubmit)="submit(p)">
            <p class="hint">TED do processo {{ p.process }}: até {{ p.remaining | number: '1.2-2' }}.</p>
            <label>
              Saiu de
              <select name="from" [(ngModel)]="from" required>
                <option value="" disabled>Escolha</option>
                @for (b of sources(); track b.account.id) {
                  <option [value]="b.account.id">{{ b.account.name }}</option>
                }
              </select>
            </label>
            <label>
              Valor
              <input type="number" step="0.01" min="0.01" [max]="p.remaining" name="amount" [(ngModel)]="amount" required />
            </label>
            <label>
              Comprovante
              <input name="document" [(ngModel)]="document" maxlength="140" required placeholder="TED 000987 / ID do depósito" />
            </label>
            <label>
              Operador
              <input name="operator" [(ngModel)]="operator" maxlength="80" required />
            </label>
            <button type="submit" [disabled]="busy()">Registrar</button>
          </form>
        }
      </div>
    }
  `,
  styleUrl: './judicial-transit.scss',
})
export class JudicialTransit implements OnInit {
  readonly positions = input.required<BackingPosition[]>();
  readonly changed = output<string>();

  readonly pending = signal<PendingTransit[]>([]);
  readonly current = signal<PendingTransit | null>(null);
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  /** A TED leaves from a real place: never from "a regularizar". */
  readonly sources = computed(() => this.positions().filter((p) => p.account.type !== 'UNASSIGNED'));
  readonly canWrite: boolean;

  from = '';
  amount = 0;
  document = '';
  operator = rememberedOperator();
  private key = crypto.randomUUID();

  constructor(
    private readonly api: WalletApiService,
    auth: AuthService,
  ) {
    this.canWrite = auth.hasScope('accounts:judicial');
  }

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.api.pendingJudicialTransit().subscribe({
      next: (r) => this.pending.set(r.items),
      error: (err) => this.error.set(extractErrorMessage(err)),
    });
  }

  start(p: PendingTransit): void {
    this.error.set(null);
    this.from = this.sources().find((s) => s.account.type === 'RESERVES')?.account.id ?? '';
    this.amount = p.remaining;
    this.document = '';
    this.key = crypto.randomUUID();
    this.current.set(this.current()?.transferId === p.transferId ? null : p);
  }

  submit(p: PendingTransit): void {
    if (!this.operator.trim()) {
      this.error.set('Informe o nome do operador.');
      return;
    }
    rememberOperator(this.operator);
    this.busy.set(true);
    this.error.set(null);
    this.api
      .judicialTed(
        p.transferId,
        { backingAccountId: this.from, amount: this.amount, document: this.document.trim(), operator: this.operator },
        this.key,
      )
      .subscribe({
        next: () => {
          this.busy.set(false);
          this.current.set(null);
          this.load();
          this.changed.emit(`TED do processo ${p.process} registrada.`);
        },
        error: (err) => {
          this.error.set(extractErrorMessage(err));
          this.busy.set(false);
        },
      });
  }
}
