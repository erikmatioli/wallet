import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AppApi } from '../../core/api';
import { money, pixStatus } from '../../core/format';
import { PixFlow } from '../../flows/payments.flow';
import { AmountField } from '../../ui/amount-field';
import { Alert } from '../../ui/alert';
import { Icon } from '../../ui/icon';

/** A Pix to another institution: form, confirmation, and a receipt that follows the Pix until it settles. */
@Component({
  selector: 'mw-pix',
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
        <h1 class="grow">Pix</h1>
      </header>
      <div class="steps" aria-hidden="true">
        <span class="on"></span>
        <span [class.on]="step.kind !== 'form'"></span>
        <span [class.on]="step.kind === 'done'"></span>
      </div>

      @if (step.kind === 'form') {
        @let f = flow.form();
        <form class="stack" (submit)="$event.preventDefault(); flow.review()" novalidate>
          <mw-amount-field
            [cents]="f.amountCents"
            (centsChange)="flow.edit({ amountCents: $event })"
          />
          <p class="muted">Para quem? Os dados da conta de quem recebe, em outra instituição.</p>
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
              placeholder="Só números (ou letras no CNPJ alfanumérico)"
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
              <label for="account">Conta com dígito</label>
              <input
                id="account"
                inputmode="numeric"
                placeholder="1234565"
                [value]="f.account"
                (input)="flow.edit({ account: $any($event.target).value })"
              />
            </div>
          </div>
          <div class="field">
            <label for="desc">Mensagem (opcional)</label>
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
        <section class="card confirm-card" aria-labelledby="confirm-title">
          <div class="big">
            <p class="muted" id="confirm-title">Você vai enviar um Pix de</p>
            <p class="value num">{{ money(step.preview.amountCents) }}</p>
          </div>
          <dl class="details">
            <dt>Para</dt>
            <dd>{{ step.preview.payee.name }}</dd>
            <dt>CPF/CNPJ</dt>
            <dd class="num">{{ step.preview.payee.taxId }}</dd>
            <dt>Instituição</dt>
            <dd class="num">ISPB {{ step.preview.payee.ispb }}</dd>
            <dt>Agência e conta</dt>
            <dd class="num">
              {{ step.preview.payee.branch }} / {{ step.preview.payee.accountNumber }}
            </dd>
            @if (flow.form().description.trim()) {
              <dt>Mensagem</dt>
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
              <span class="spinner" aria-hidden="true"></span><span>Enviando…</span>
            } @else {
              Confirmar Pix
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
        @let r = step.receipt;
        <section class="card receipt" aria-live="polite">
          @switch (r.status) {
            @case ('SENT') {
              <span class="badge wait"><span class="spinner" aria-hidden="true"></span></span>
              <h2>Pix enviado</h2>
            }
            @case ('COMPLETED') {
              <span class="badge"><mw-icon name="check" [size]="36" /></span>
              <h2>Pix concluído</h2>
            }
            @default {
              <span class="badge bad"><mw-icon name="x" [size]="36" /></span>
              <h2>Pix não concluído</h2>
            }
          }
          <p class="value num">{{ money(r.amountCents) }}</p>
          <p class="muted">para {{ r.payeeName }}</p>
          @if (r.reasonMessage) {
            <div class="alert alert-error" role="alert">{{ r.reasonMessage }}</div>
          }
          @if (r.status === 'REFUNDED') {
            <p class="muted">O valor voltou para a sua conta.</p>
          }
          <dl class="details">
            <dt>Situação</dt>
            <dd>{{ pixStatus(r.status) }}</dd>
            <dt>Instituição</dt>
            <dd class="num">ISPB {{ r.payeeIspb }}</dd>
            <dt>ID do Pix</dt>
            <dd class="mono">{{ r.endToEndId }}</dd>
          </dl>
        </section>
        <div class="actions">
          <a routerLink="/inicio" class="btn btn-primary">Voltar ao início</a>
          <button type="button" class="btn btn-ghost" (click)="flow.reset()">Novo Pix</button>
        </div>
      }
    </div>
  `,
})
export class PixPage {
  protected readonly flow = new PixFlow(inject(AppApi));
  protected readonly money = money;
  protected readonly pixStatus = pixStatus;
}
