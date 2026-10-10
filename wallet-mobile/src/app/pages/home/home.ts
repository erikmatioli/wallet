import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { AppApi } from '../../core/api';
import { StatementEntry } from '../../core/contract';
import { account, money } from '../../core/format';
import { SessionStore } from '../../core/session';
import { HomeFlow } from '../../flows/account.flow';
import { EntryDetail } from '../../ui/entry-detail';
import { EntryItem } from '../../ui/entry-item';
import { Icon } from '../../ui/icon';
import { Logo } from '../../ui/logo';

/** Home: greeting, balance card (can be hidden), shortcuts and the last entries. */
@Component({
  selector: 'mw-home',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, Icon, Logo, EntryItem, EntryDetail],
  template: `
    <div class="page">
      <header class="page-head">
        <mw-logo [size]="34" [wordmark]="false" />
        <div class="hello">
          <span class="muted">Olá,</span>
          <strong>{{ firstName() }}</strong>
        </div>
        <button type="button" class="icon-btn" (click)="logout()" aria-label="Sair">
          <mw-icon name="logout" />
        </button>
      </header>

      <section class="balance" aria-labelledby="balance-label">
        <div class="balance-top">
          <span id="balance-label">Saldo disponível</span>
          <button
            type="button"
            class="icon-btn eye"
            (click)="hidden.set(!hidden())"
            [attr.aria-label]="hidden() ? 'Mostrar saldo' : 'Esconder saldo'"
            [attr.aria-pressed]="hidden()"
          >
            <mw-icon [name]="hidden() ? 'eye-off' : 'eye'" [size]="20" />
          </button>
        </div>
        @if (flow.me(); as me) {
          <p class="amount num" aria-live="polite">
            @if (hidden()) {
              <span aria-hidden="true">R$ ••••••</span><span class="sr-only">Saldo escondido</span>
            } @else {
              {{ money(me.balanceCents) }}
            }
          </p>
          <p class="acct num">
            Ag {{ me.branch }} · Conta {{ me.accountNumber }}-{{ me.checkDigit }}
          </p>
          @if (me.status !== 'ACTIVE') {
            <p class="status">Conta {{ me.status === 'BLOCKED' ? 'bloqueada' : 'inativa' }}</p>
          }
        } @else if (flow.loading()) {
          <div class="skeleton bar-lg" aria-hidden="true"></div>
          <div class="skeleton bar-sm" aria-hidden="true"></div>
        }
      </section>

      @if (flow.error(); as error) {
        <div class="alert alert-error" role="alert">
          <mw-icon name="alert" [size]="20" />
          <span class="grow">{{ error }}</span>
          <button type="button" class="btn-link" (click)="flow.load()">Tentar de novo</button>
        </div>
      }

      <nav class="actions" aria-label="Atalhos">
        @for (a of actions; track a.path) {
          <a [routerLink]="a.path" class="action">
            <span class="action-icon"><mw-icon [name]="a.icon" /></span>
            <span>{{ a.label }}</span>
          </a>
        }
      </nav>

      <section class="stack" aria-labelledby="recent-title">
        <div class="section-head">
          <h2 id="recent-title">Últimos lançamentos</h2>
          <a routerLink="/extrato">Ver extrato</a>
        </div>
        @if (flow.recent().length) {
          <ul class="list">
            @for (e of flow.recent(); track e.transactionId + e.sequence) {
              <li><mw-entry-item [entry]="e" (pick)="selected.set($event)" /></li>
            }
          </ul>
        } @else if (flow.loading()) {
          <div class="list">
            @for (i of [1, 2, 3]; track i) {
              <div class="list-item">
                <div class="skeleton" style="height: 18px; flex: 1"></div>
              </div>
            }
          </div>
        } @else if (flow.me()) {
          <div class="card empty">
            <p><strong>Nada por aqui ainda.</strong></p>
            <p class="muted">Quando o dinheiro entrar ou sair, aparece aqui.</p>
          </div>
        }
      </section>
    </div>
    <mw-entry-detail [entry]="selected()" (closed)="selected.set(null)" />
  `,
  styles: `
    .hello {
      flex: 1;
      display: flex;
      flex-direction: column;
      line-height: 1.2;
      margin-left: 4px;
      font-size: 1.05rem;
    }
    .hello .muted {
      font-size: 0.85rem;
    }
    .balance {
      background: var(--mw-card);
      color: #fff;
      border-radius: 24px;
      padding: 18px 20px 22px;
      box-shadow: 0 12px 30px var(--mw-orange-glow);
      display: flex;
      flex-direction: column;
      gap: 6px;
      min-height: 150px;
    }
    .balance-top {
      display: flex;
      align-items: center;
      justify-content: space-between;
      font-weight: 600;
    }
    .eye {
      color: #fff;
      margin-right: -10px;
    }
    .eye:hover {
      background: rgba(255, 255, 255, 0.14);
    }
    .amount {
      font-size: 2.3rem;
      font-weight: 800;
      letter-spacing: -0.03em;
      line-height: 1.1;
    }
    .acct {
      font-size: 0.9rem;
      font-weight: 600;
      opacity: 0.92;
    }
    .status {
      align-self: flex-start;
      background: rgba(0, 0, 0, 0.25);
      border-radius: 999px;
      padding: 2px 10px;
      font-size: 0.8rem;
      font-weight: 700;
    }
    .bar-lg {
      height: 38px;
      width: 60%;
      opacity: 0.4;
    }
    .bar-sm {
      height: 16px;
      width: 45%;
      opacity: 0.4;
    }
    .actions {
      display: grid;
      grid-template-columns: repeat(4, 1fr);
      gap: 8px;
    }
    .action {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 6px;
      text-decoration: none;
      color: var(--mw-text);
      font-size: 0.8rem;
      font-weight: 700;
      padding: 6px 0;
      border-radius: 16px;
    }
    .action-icon {
      width: 56px;
      height: 56px;
      border-radius: 18px;
      display: grid;
      place-items: center;
      background: var(--mw-surface);
      color: var(--mw-orange-strong);
      box-shadow: var(--mw-shadow);
    }
    .section-head {
      display: flex;
      align-items: baseline;
      justify-content: space-between;
    }
    .empty {
      text-align: center;
      display: flex;
      flex-direction: column;
      gap: 4px;
      padding: 24px;
    }
    .grow {
      flex: 1;
    }
  `,
})
export class Home implements OnInit {
  private readonly session = inject(SessionStore);
  private readonly router = inject(Router);
  protected readonly flow = new HomeFlow(inject(AppApi));
  protected readonly selected = signal<StatementEntry | null>(null);
  /** Only for this screen, only in memory: someone looking over the shoulder. */
  protected readonly hidden = signal(false);
  protected readonly firstName = computed(
    () => this.session.customerName().split(' ')[0] || 'cliente',
  );
  protected readonly money = money;
  protected readonly account = account;
  protected readonly actions = [
    { path: '/pagar/pix', label: 'Pix', icon: 'pix' },
    { path: '/pagar/transferencia', label: 'Transferir', icon: 'transfer' },
    { path: '/agenda/novo', label: 'Agendar', icon: 'calendar' },
    { path: '/extrato', label: 'Extrato', icon: 'statement' },
  ];

  ngOnInit(): void {
    void this.flow.load();
  }

  protected logout(): void {
    this.session.logout();
    void this.router.navigateByUrl('/entrar');
  }
}
