import { ChangeDetectionStrategy, Component, OnInit, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AppApi } from '../../core/api';
import { date, dateTime, money, scheduleStatus, scheduleTone } from '../../core/format';
import { SchedulesFlow } from '../../flows/schedules.flow';
import { Alert } from '../../ui/alert';
import { Icon } from '../../ui/icon';
import { Sheet } from '../../ui/sheet';

/** The customer's schedules; the detail opens in a sheet, where a schedule can be cancelled. */
@Component({
  selector: 'mw-schedules',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, Icon, Sheet, Alert],
  template: `
    <div class="page">
      <header class="page-head">
        <h1 class="grow">Agendamentos</h1>
        <a routerLink="/agenda/novo" class="icon-btn add" aria-label="Novo agendamento"
          ><mw-icon name="plus"
        /></a>
      </header>

      @if (flow.schedules().length) {
        <ul class="list">
          @for (s of flow.schedules(); track s.id) {
            <li>
              <button type="button" class="list-item" (click)="flow.open(s)">
                <span class="avatar"
                  ><mw-icon [name]="s.type === 'PIX' ? 'pix' : 'transfer'" [size]="20"
                /></span>
                <span class="grow">
                  <span class="title">{{ s.payee }}</span>
                  <span class="sub"
                    >{{ date(s.executeOn) }} ·
                    {{ s.type === 'PIX' ? 'Pix' : 'Transferência' }}</span
                  >
                </span>
                <span class="end">
                  <span class="num">{{ money(s.amountCents) }}</span>
                  <span class="chip" [class]="'chip ' + tone(s)">{{ status(s) }}</span>
                </span>
              </button>
            </li>
          }
        </ul>
      } @else if (flow.loaded()) {
        <div class="card empty">
          <span class="avatar"><mw-icon name="calendar" /></span>
          <p><strong>Nenhum agendamento.</strong></p>
          <p class="muted">Agende um Pix ou uma transferência para outro dia.</p>
          <a routerLink="/agenda/novo" class="btn btn-primary">Agendar pagamento</a>
        </div>
      }

      @if (flow.loading()) {
        <div class="loading" role="status">
          <span class="spinner" aria-hidden="true"></span> Carregando…
        </div>
      }
      @if (flow.error() && !flow.selected()) {
        <div class="alert alert-error" role="alert">
          <mw-icon name="alert" [size]="20" /><span class="grow">{{ flow.error() }}</span>
          <button type="button" class="btn-link" (click)="flow.load()">Tentar de novo</button>
        </div>
      }
    </div>

    <mw-sheet [open]="!!flow.selected()" labelledBy="schedule-title" (closed)="flow.close()">
      @if (flow.selected(); as s) {
        <div class="head">
          <span class="chip" [class]="'chip ' + tone(s)">{{ status(s) }}</span>
          <p class="amount num">{{ money(s.amountCents) }}</p>
          <h2 id="schedule-title">
            {{ s.type === 'PIX' ? 'Pix' : 'Transferência' }} para {{ s.payee }}
          </h2>
        </div>
        <dl class="details">
          <dt>Dia</dt>
          <dd>{{ date(s.executeOn) }}</dd>
          <dt>Destino</dt>
          <dd class="num">{{ s.payeeDetail }}</dd>
          @if (s.description) {
            <dt>Descrição</dt>
            <dd>{{ s.description }}</dd>
          }
          <dt>Criado em</dt>
          <dd>{{ dateTime(s.createdAt) }}</dd>
          @if (s.execution.failureMessage) {
            <dt>Motivo</dt>
            <dd>{{ s.execution.failureMessage }}</dd>
          }
        </dl>
        @if (s.attempts?.length) {
          <div class="stack">
            <h3 class="attempts-title">Tentativas</h3>
            <ul class="attempts">
              @for (a of s.attempts ?? []; track a.startedAt) {
                <li>
                  <span
                    class="dot"
                    [class]="
                      'dot ' +
                      (a.outcome === 'EXECUTED' ? 'ok' : a.outcome === 'REFUSED' ? 'bad' : 'wait')
                    "
                  ></span>
                  <span class="grow">
                    {{ dateTime(a.startedAt) }}
                    <span class="muted"
                      >·
                      {{
                        a.outcome === 'EXECUTED'
                          ? 'pago'
                          : a.outcome === 'REFUSED'
                            ? 'recusado'
                            : 'em andamento'
                      }}</span
                    >
                    @if (a.reasonMessage) {
                      <br /><span class="muted">{{ a.reasonMessage }}</span>
                    }
                  </span>
                </li>
              }
            </ul>
          </div>
        }
        @if (flow.error(); as error) {
          <mw-alert [message]="error" />
        }
        @if (s.canCancel) {
          <button
            type="button"
            class="btn btn-danger"
            [disabled]="flow.cancelling()"
            (click)="flow.cancel()"
          >
            @if (flow.cancelling()) {
              <span class="spinner" aria-hidden="true"></span><span>Cancelando…</span>
            } @else {
              Cancelar agendamento
            }
          </button>
        }
        <button type="button" class="btn btn-secondary" (click)="flow.close()">Fechar</button>
      }
    </mw-sheet>
  `,
  styles: `
    .grow {
      flex: 1;
    }
    .add {
      background: var(--mw-action);
      color: var(--mw-on-action);
    }
    .add:hover {
      background: var(--mw-action-hover);
    }
    .end {
      display: flex;
      flex-direction: column;
      align-items: flex-end;
      gap: 4px;
    }
    .empty {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 6px;
      text-align: center;
      padding: 32px 16px;
    }
    .empty .btn {
      margin-top: 10px;
    }
    .loading {
      display: flex;
      gap: 8px;
      justify-content: center;
      align-items: center;
      color: var(--mw-text-2);
      padding: 12px;
    }
    .head {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 6px;
      text-align: center;
    }
    .amount {
      font-size: 2rem;
      font-weight: 800;
      letter-spacing: -0.02em;
    }
    .attempts-title {
      font-size: 0.9rem;
      color: var(--mw-text-2);
    }
    .attempts {
      list-style: none;
      margin: 0;
      padding: 0;
      display: flex;
      flex-direction: column;
      gap: 10px;
      font-size: 0.9rem;
    }
    .attempts li {
      display: flex;
      gap: 10px;
      align-items: flex-start;
    }
    .dot {
      width: 10px;
      height: 10px;
      border-radius: 50%;
      margin-top: 6px;
      flex: none;
    }
    .dot.ok {
      background: var(--mw-ok);
    }
    .dot.bad {
      background: var(--mw-bad);
    }
    .dot.wait {
      background: var(--mw-wait);
    }
  `,
})
export class Schedules implements OnInit {
  protected readonly flow = new SchedulesFlow(inject(AppApi));
  protected readonly money = money;
  protected readonly date = date;
  protected readonly dateTime = dateTime;
  protected readonly status = scheduleStatus;
  protected readonly tone = scheduleTone;

  ngOnInit(): void {
    void this.flow.load();
  }
}
