import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AppApi } from '../../core/api';
import { account, dateTime, money } from '../../core/format';
import { TransferFlow } from '../../flows/payments.flow';
import { AmountField } from '../../ui/amount-field';
import { Alert } from '../../ui/alert';
import { Icon } from '../../ui/icon';

/** A transfer to another account of this institution: form, confirmation with who receives, receipt. */
@Component({
  selector: 'mw-transfer',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, Icon, AmountField, Alert],
  styleUrl: './pay.scss',
  template: `
    <div class="page">
      @let step = flow.step();
      <header class="page-head">
        @if (step.kind === 'form') {
          <a routerLink="/pagar" class="icon-btn" aria-label="Voltar"><mw-icon name="back" /></a>
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
        <h1 class="grow">Transferência</h1>
      </header>
      <div class="steps" aria-hidden="true">
        <span class="on"></span>
        <span [class.on]="step.kind !== 'form'"></span>
        <span [class.on]="step.kind === 'done'"></span>
      </div>

      @if (step.kind === 'form') {
        <form class="stack" (submit)="$event.preventDefault(); flow.review()" novalidate>
          <mw-amount-field
            [cents]="flow.form().amountCents"
            (centsChange)="flow.edit({ amountCents: $event })"
          />
          <p class="muted">Para quem? Os dados da conta M-Wall de quem recebe.</p>
          <div class="row">
            <div class="field">
              <label for="branch">Agência</label>
              <input
                id="branch"
                inputmode="numeric"
                maxlength="4"
                placeholder="0001"
                [value]="flow.form().branch"
                (input)="flow.edit({ branch: $any($event.target).value })"
              />
            </div>
            <div class="field" style="flex: 2">
              <label for="number">Conta</label>
              <input
                id="number"
                inputmode="numeric"
                placeholder="00100123"
                [value]="flow.form().number"
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
                [value]="flow.form().checkDigit"
                (input)="flow.edit({ checkDigit: $any($event.target).value })"
              />
            </div>
          </div>
          <div class="field">
            <label for="desc">Descrição (opcional)</label>
            <input
              id="desc"
              maxlength="140"
              [value]="flow.form().description"
              (input)="flow.edit({ description: $any($event.target).value })"
            />
          </div>
          @if (flow.error(); as error) {
            <mw-alert [message]="error" />
          }
          <button type="submit" class="btn btn-primary" [disabled]="flow.busy()">
            @if (flow.busy()) {
              <span class="spinner" aria-hidden="true"></span><span>Buscando conta…</span>
            } @else {
              Continuar
            }
          </button>
        </form>
      } @else if (step.kind === 'confirm') {
        <section class="card confirm-card" aria-labelledby="confirm-title">
          <div class="big">
            <p class="muted" id="confirm-title">Você vai transferir</p>
            <p class="value num">{{ money(step.preview.amountCents) }}</p>
          </div>
          <dl class="details">
            <dt>Para</dt>
            <dd>{{ step.preview.destination.holderName }}</dd>
            <dt>Conta</dt>
            <dd class="num">
              {{
                account(
                  step.preview.destination.branch,
                  step.preview.destination.number,
                  step.preview.destination.checkDigit
                )
              }}
            </dd>
            @if (flow.form().description.trim()) {
              <dt>Descrição</dt>
              <dd>{{ flow.form().description }}</dd>
            }
          </dl>
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
              <span class="spinner" aria-hidden="true"></span><span>Transferindo…</span>
            } @else {
              Confirmar transferência
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
        <section class="card receipt" aria-live="polite">
          <span class="badge"><mw-icon name="check" [size]="36" /></span>
          <h2>Transferência feita</h2>
          <p class="value num">{{ money(step.receipt.amountCents) }}</p>
          <p class="muted">para {{ step.receipt.destination.holderName }}</p>
          <dl class="details">
            <dt>Conta</dt>
            <dd class="num">
              {{
                account(
                  step.receipt.destination.branch,
                  step.receipt.destination.number,
                  step.receipt.destination.checkDigit
                )
              }}
            </dd>
            <dt>Quando</dt>
            <dd>{{ dateTime(step.receipt.occurredAt) }}</dd>
            @if (step.receipt.description) {
              <dt>Descrição</dt>
              <dd>{{ step.receipt.description }}</dd>
            }
            <dt>Transação</dt>
            <dd class="mono">{{ step.receipt.transactionId }}</dd>
          </dl>
        </section>
        <div class="actions">
          <a routerLink="/inicio" class="btn btn-primary">Voltar ao início</a>
          <button type="button" class="btn btn-ghost" (click)="flow.reset()">
            Nova transferência
          </button>
        </div>
      }
    </div>
  `,
})
export class TransferPage {
  protected readonly flow = new TransferFlow(inject(AppApi));
  protected readonly money = money;
  protected readonly account = account;
  protected readonly dateTime = dateTime;
}
