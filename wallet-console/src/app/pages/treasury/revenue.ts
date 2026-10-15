import { Component, OnInit, computed, input, output, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { AuthService } from '../../core/auth.service';
import { WalletApiService } from '../../core/wallet-api.service';
import { extractErrorMessage } from '../../core/http-error.util';
import { BackingPosition, RevenueEntry, RevenueResponse } from '../../core/models';
import { transactionLabel } from '../../core/transaction-labels';
import { monthLabel } from '../../core/fees';
import { rememberOperator, rememberedOperator } from '../../core/account-control';
import { ProblemBanner } from '../../shared/problem-banner/problem-banner';

/**
 * The tenant's revenue (ADR-014 and ADR-015 of wallet-core): what the fees took in, net of the refunds, its
 * statement, and the withdrawal that takes it out of the platform - from a real backing account, to the tenant's own
 * account, with the receipt. Reading needs ledger:audit; withdrawing, treasury:write.
 */
@Component({
  selector: 'app-revenue',
  imports: [FormsModule, DecimalPipe, DatePipe, ProblemBanner],
  template: `
    <h2 class="section-title">Receitas do tenant</h2>
    <div class="card">
      <app-problem-banner [message]="error()" />
      @if (revenue(); as r) {
        <div class="figures">
          <div>
            <div class="kicker">Disponível</div>
            <div class="big num">R$ {{ r.balance | number: '1.2-2' }}</div>
          </div>
          @if (r.month; as m) {
            <div>
              <div class="kicker">Faturado em {{ month(m.month) }}</div>
              <div class="num value">R$ {{ m.earned | number: '1.2-2' }}</div>
              <p class="explain">tarifas menos estornos</p>
            </div>
            <div>
              <div class="kicker">Retirado em {{ month(m.month) }}</div>
              <div class="num value">R$ {{ m.withdrawn | number: '1.2-2' }}</div>
            </div>
          }
        </div>
        <p class="explain">
          A receita é dinheiro do tenant, não dos clientes, mas fica nas mesmas contas de lastro. Retirar é tirá-la da
          plataforma por uma delas: o saldo esperado ali cai.
        </p>

        @if (canWrite) {
          @if (!withdrawing()) {
            <button type="button" class="secondary" (click)="start()" [disabled]="r.balance <= 0">Retirar receita</button>
          } @else {
            <form class="withdraw" (ngSubmit)="submit()">
              <label>
                Valor (R$)
                <input name="amount" type="number" min="0.01" step="0.01" [max]="r.balance" [(ngModel)]="amount" required />
              </label>
              <label>
                Sai de
                <select name="from" [(ngModel)]="from" required>
                  <option value="" disabled>Escolha</option>
                  @for (p of sources(); track p.account.id) {
                    <option [value]="p.account.id">{{ p.account.name }} (R$ {{ p.expected | number: '1.2-2' }})</option>
                  }
                </select>
              </label>
              <label>
                Banco
                <input name="bank" [(ngModel)]="bank" maxlength="80" required placeholder="Itaú (341)" />
              </label>
              <label>
                Agência
                <input name="branch" [(ngModel)]="branch" maxlength="10" required placeholder="0001" />
              </label>
              <label>
                Conta
                <input name="account" [(ngModel)]="account" maxlength="30" required placeholder="12345-6" />
              </label>
              <label>
                Comprovante
                <input name="document" [(ngModel)]="document" maxlength="140" required placeholder="TED 000123" />
              </label>
              <label class="grow">
                Observação
                <input name="note" [(ngModel)]="note" maxlength="280" placeholder="receita de outubro" />
              </label>
              <label>
                Operador
                <input name="operator" [(ngModel)]="operator" maxlength="80" required />
              </label>
              @if (fromGoesNegative()) {
                <p class="warn">A conta de origem fica negativa: a tesouraria vai precisar recompô-la.</p>
              }
              <div class="actions">
                <button type="submit" [disabled]="busy()">{{ busy() ? 'Registrando...' : 'Registrar retirada' }}</button>
                <button type="button" class="secondary" (click)="withdrawing.set(false)">Fechar</button>
              </div>
            </form>
          }
        }

        <table>
          <thead>
            <tr>
              <th>Data</th>
              <th>Movimento</th>
              <th class="right">Valor</th>
              <th class="right">Saldo</th>
            </tr>
          </thead>
          <tbody>
            @for (e of entries(); track e.sequence) {
              <tr>
                <td class="num nowrap">{{ e.occurredAt | date: 'dd/MM/yy HH:mm' }}</td>
                <td>
                  {{ label(e.type) }}
                  @if (e.withdrawal; as w) {
                    <div class="sub">de {{ w.backingAccountName }} para {{ w.destination.bank }}, ag {{ w.destination.branch }}, conta {{ w.destination.account }}</div>
                    <div class="sub">{{ w.document }}{{ w.note ? ' · ' + w.note : '' }} · por {{ w.operator }}</div>
                  } @else {
                    <div class="sub">{{ e.description }}</div>
                  }
                </td>
                <td class="right num" [class.amount-credit]="e.direction === 'CREDIT'" [class.amount-debit]="e.direction === 'DEBIT'">
                  {{ e.direction === 'CREDIT' ? '+' : '−' }} {{ e.amount | number: '1.2-2' }}
                </td>
                <td class="right num">{{ e.balanceAfter | number: '1.2-2' }}</td>
              </tr>
            } @empty {
              <tr>
                <td colspan="4" class="hint">Nenhuma tarifa cobrada ainda.</td>
              </tr>
            }
          </tbody>
        </table>
        @if (next() !== null) {
          <button type="button" class="secondary" (click)="loadMore()">Carregar mais</button>
        }
      }
    </div>
  `,
  styleUrl: './revenue.scss',
})
export class Revenue implements OnInit {
  /** The backing accounts with their expected balance: where the money can leave from. */
  readonly positions = input.required<BackingPosition[]>();
  /** A withdrawal was recorded: the treasury reloads its backing accounts. */
  readonly changed = output<string>();

  readonly revenue = signal<RevenueResponse | null>(null);
  readonly entries = signal<RevenueEntry[]>([]);
  readonly next = signal<number | null>(null);
  readonly error = signal<string | null>(null);
  readonly busy = signal(false);
  readonly withdrawing = signal(false);

  /** Never "a regularizar": that is money a deposit created, not a place. */
  readonly sources = computed(() => this.positions().filter((p) => p.account.type !== 'UNASSIGNED'));
  readonly canWrite: boolean;
  readonly label = transactionLabel;
  readonly month = monthLabel;

  amount: number | null = null;
  from = '';
  bank = '';
  branch = '';
  account = '';
  document = '';
  note = '';
  operator = rememberedOperator();
  /** One key per withdrawal being registered: a retry after a network error does not withdraw twice. */
  private key = crypto.randomUUID();

  constructor(
    private readonly api: WalletApiService,
    auth: AuthService,
  ) {
    this.canWrite = auth.hasScope('treasury:write');
  }

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.api.revenue().subscribe({
      next: (r) => {
        this.revenue.set(r);
        this.entries.set(r.entries);
        this.next.set(r.next ?? null);
      },
      error: (err) => this.error.set(extractErrorMessage(err)),
    });
  }

  loadMore(): void {
    const before = this.next();
    if (before === null) return;
    this.api.revenue(before).subscribe({
      next: (r) => {
        this.entries.set([...this.entries(), ...r.entries]);
        this.next.set(r.next ?? null);
      },
      error: (err) => this.error.set(extractErrorMessage(err)),
    });
  }

  start(): void {
    this.error.set(null);
    this.amount = this.revenue()?.balance ?? null;
    this.withdrawing.set(true);
  }

  fromGoesNegative(): boolean {
    const p = this.sources().find((s) => s.account.id === this.from);
    return !!p && !!this.amount && p.expected - this.amount < 0;
  }

  submit(): void {
    if (!this.amount || !this.from) return;
    this.busy.set(true);
    this.error.set(null);
    this.api
      .withdrawRevenue(
        {
          backingAccountId: this.from,
          amount: this.amount,
          destination: { bank: this.bank, branch: this.branch, account: this.account },
          document: this.document,
          note: this.note || undefined,
          operator: this.operator,
        },
        this.key,
      )
      .subscribe({
        next: (r) => {
          rememberOperator(this.operator);
          this.busy.set(false);
          this.withdrawing.set(false);
          this.key = crypto.randomUUID();
          this.document = '';
          this.note = '';
          this.changed.emit(`Retirada de receita de R$ ${r.amount.toFixed(2)} registrada.`);
          this.load();
        },
        error: (err) => {
          this.busy.set(false);
          this.error.set(extractErrorMessage(err));
        },
      });
  }
}
