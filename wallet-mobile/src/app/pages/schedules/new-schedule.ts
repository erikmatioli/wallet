import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AppApi } from '../../core/api';
import { date, money, scheduleStatus } from '../../core/format';
import { NewScheduleFlow } from '../../flows/schedules.flow';
import { AmountField } from '../../ui/amount-field';
import { Alert } from '../../ui/alert';
import { Icon } from '../../ui/icon';

/** A new schedule: transfer or Pix, a day from tomorrow on, the destination; then confirm. */
@Component({
  selector: 'mw-new-schedule',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, Icon, AmountField, Alert],
  styleUrl: '../pay/pay.scss',
  template: `
    <div class="page">
      @let step = flow.step();
      <header class="page-head">
        @if (step.kind === 'form') {
          <a routerLink="/agenda" class="icon-btn" aria-label="Voltar"><mw-icon name="back" /></a>
        } @else if (step.kind === 'confirm') {
          <button
            type="button"
            class="icon-btn"
            (click)="flow.change()"
            aria-label="Voltar e alterar"
          >
            <mw-icon name="back" />
          </button>
        }
        <h1 class="grow">Agendar</h1>
      </header>
      <div class="steps" aria-hidden="true">
        <span class="on"></span>
        <span [class.on]="step.kind !== 'form'"></span>
        <span [class.on]="step.kind === 'done'"></span>
      </div>

      @if (step.kind === 'form') {
        @let f = flow.form();
        <form class="stack" (submit)="$event.preventDefault(); flow.review()" novalidate>
          <div class="segmented" role="group" aria-label="Tipo de pagamento">
            <button type="button" [attr.aria-pressed]="!f.pix" (click)="flow.edit({ pix: false })">
              Transferência
            </button>
            <button type="button" [attr.aria-pressed]="f.pix" (click)="flow.edit({ pix: true })">
              Pix
            </button>
          </div>
          <mw-amount-field
            [cents]="f.amountCents"
            (centsChange)="flow.edit({ amountCents: $event })"
          />
          <div class="field">
            <label for="date">Dia do pagamento</label>
            <input
              id="date"
              type="date"
              [min]="flow.minDate()"
              [value]="f.date"
              (input)="flow.edit({ date: $any($event.target).value })"
              aria-describedby="date-hint"
            />
            <span class="hint" id="date-hint">A partir de amanhã. Cancele até o dia anterior.</span>
          </div>
          @if (!f.pix) {
            <div class="row">
              <div class="field">
                <label for="branch">Agência</label>
                <input
                  id="branch"
                  inputmode="numeric"
                  maxlength="4"
                  placeholder="0001"
                  [value]="f.branch"
                  (input)="flow.edit({ branch: $any($event.target).value })"
                />
              </div>
              <div class="field" style="flex: 2">
                <label for="number">Conta</label>
                <input
                  id="number"
                  inputmode="numeric"
                  placeholder="00100123"
                  [value]="f.number"
                  (input)="flow.edit({ number: $any($event.target).value })"
                />
              </div>
              <div class="field" style="flex: 0.8">
                <label for="digit">Dígito</label>
                <input
                  id="digit"
                  inputmode="numeric"
                  maxlength="1"
                  placeholder="0"
                  [value]="f.checkDigit"
                  (input)="flow.edit({ checkDigit: $any($event.target).value })"
                />
              </div>
            </div>
          } @else {
            <div class="field">
              <label for="name">Nome de quem recebe</label>
              <input
                id="name"
                autocomplete="off"
                [value]="f.name"
                (input)="flow.edit({ name: $any($event.target).value })"
              />
            </div>
            <div class="field">
              <label for="taxId">CPF ou CNPJ</label>
              <input
                id="taxId"
                autocomplete="off"
                autocapitalize="characters"
                [value]="f.taxId"
                (input)="flow.edit({ taxId: $any($event.target).value })"
              />
            </div>
            <div class="field">
              <label for="ispb">Instituição (ISPB)</label>
              <input
                id="ispb"
                inputmode="numeric"
                maxlength="8"
                placeholder="8 dígitos"
                [value]="f.ispb"
                (input)="flow.edit({ ispb: $any($event.target).value })"
              />
            </div>
            <div class="row">
              <div class="field">
                <label for="pixBranch">Agência</label>
                <input
                  id="pixBranch"
                  inputmode="numeric"
                  maxlength="4"
                  placeholder="0001"
                  [value]="f.pixBranch"
                  (input)="flow.edit({ pixBranch: $any($event.target).value })"
                />
              </div>
              <div class="field" style="flex: 2">
                <label for="account">Conta com dígito</label>
                <input
                  id="account"
                  inputmode="numeric"
                  [value]="f.account"
                  (input)="flow.edit({ account: $any($event.target).value })"
                />
              </div>
            </div>
          }
          <div class="field">
            <label for="desc">Descrição (opcional)</label>
            <input
              id="desc"
              maxlength="140"
              [value]="f.description"
              (input)="flow.edit({ description: $any($event.target).value })"
            />
          </div>
          @if (flow.error(); as error) {
            <mw-alert [message]="error" />
          }
          <button type="submit" class="btn btn-primary">Continuar</button>
        </form>
      } @else if (step.kind === 'confirm') {
        @let r = step.preview;
        <section class="card confirm-card" aria-labelledby="confirm-title">
          <div class="big">
            <p class="muted" id="confirm-title">
              {{ r.type === 'PIX' ? 'Pix' : 'Transferência' }} agendado para {{ date(r.executeOn) }}
            </p>
            <p class="value num">{{ money(r.amountCents) }}</p>
          </div>
          <dl class="details">
            @if (r.pix; as p) {
              <dt>Para</dt>
              <dd>{{ p.name }}</dd>
              <dt>CPF/CNPJ</dt>
              <dd class="num">{{ p.taxId }}</dd>
              <dt>Destino</dt>
              <dd class="num">ISPB {{ p.ispb }} · {{ p.branch }} / {{ p.accountNumber }}</dd>
            }
            @if (r.transfer; as t) {
              <dt>Conta</dt>
              <dd class="num">{{ t.branch }} / {{ t.number }}-{{ t.checkDigit }}</dd>
            }
            @if (r.description) {
              <dt>Descrição</dt>
              <dd>{{ r.description }}</dd>
            }
          </dl>
          <p class="muted note">
            O saldo é conferido no dia. Se faltar, tentamos de novo mais tarde no mesmo dia.
          </p>
        </section>
        @if (flow.error(); as error) {
          <mw-alert [message]="error" />
        }
        <div class="actions">
          <button
            type="button"
            class="btn btn-primary"
            [disabled]="flow.busy()"
            (click)="flow.confirm()"
          >
            @if (flow.busy()) {
              <span class="spinner" aria-hidden="true"></span><span>Agendando…</span>
            } @else {
              Confirmar agendamento
            }
          </button>
          <button
            type="button"
            class="btn btn-ghost"
            [disabled]="flow.busy()"
            (click)="flow.change()"
          >
            Alterar
          </button>
        </div>
      } @else {
        @let s = step.receipt;
        <section class="card receipt" aria-live="polite">
          <span class="badge"><mw-icon name="calendar" [size]="34" /></span>
          <h2>Pagamento agendado</h2>
          <p class="value num">{{ money(s.amountCents) }}</p>
          <p class="muted">para {{ s.payee }}, em {{ date(s.executeOn) }}</p>
          <dl class="details">
            <dt>Situação</dt>
            <dd>{{ status(s) }}</dd>
            <dt>Destino</dt>
            <dd class="num">{{ s.payeeDetail }}</dd>
          </dl>
        </section>
        <div class="actions">
          <a routerLink="/agenda" class="btn btn-primary">Ver agendamentos</a>
          <button type="button" class="btn btn-ghost" (click)="flow.reset()">Agendar outro</button>
        </div>
      }
    </div>
  `,
  styles: `
    .note {
      font-size: 0.85rem;
      text-align: center;
    }
  `,
})
export class NewSchedule {
  protected readonly flow = new NewScheduleFlow(inject(AppApi));
  protected readonly money = money;
  protected readonly date = date;
  protected readonly status = scheduleStatus;
}
