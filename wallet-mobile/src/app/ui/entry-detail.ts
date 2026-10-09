import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { StatementEntry } from '../core/contract';
import { dateTime, money, signed, type } from '../core/format';
import { Sheet } from './sheet';

/** A statement entry's detail, in a bottom sheet: everything the page already brought, no new request. */
@Component({
  selector: 'mw-entry-detail',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Sheet],
  template: `
    <mw-sheet [open]="!!entry()" labelledBy="entry-title" (closed)="closed.emit()">
      @if (entry(); as e) {
        <div class="head">
          <p class="muted" id="entry-title">{{ type(e.type) }}</p>
          <p class="amount num" [class.credit]="e.credit">{{ signed(e) }}</p>
          <p class="muted">{{ dateTime(e.occurredAt) }}</p>
        </div>
        <dl class="details">
          @if (e.counterpartyName) {
            <dt>{{ e.credit ? 'De' : 'Para' }}</dt>
            <dd>{{ e.counterpartyName }}</dd>
          }
          @if (e.pix?.counterpartyTaxIdMasked) {
            <dt>CPF/CNPJ</dt>
            <dd>{{ e.pix?.counterpartyTaxIdMasked }}</dd>
          }
          @if (e.pix?.counterpartyInstitution) {
            <dt>Instituição</dt>
            <dd>{{ e.pix?.counterpartyInstitution }}</dd>
          }
          @if (e.description) {
            <dt>Descrição</dt>
            <dd>{{ e.description }}</dd>
          }
          <dt>Saldo depois</dt>
          <dd class="num">{{ money(e.balanceAfterCents) }}</dd>
          @if (e.pix?.reasonCode) {
            <dt>Motivo</dt>
            <dd>{{ e.pix?.reasonCode }}</dd>
          }
          @if (e.pix?.endToEndId) {
            <dt>ID do Pix</dt>
            <dd class="mono">{{ e.pix?.endToEndId }}</dd>
          }
          <dt>Transação</dt>
          <dd class="mono">{{ e.transactionId }}</dd>
        </dl>
        <button type="button" class="btn btn-secondary" (click)="closed.emit()">Fechar</button>
      }
    </mw-sheet>
  `,
  styles: `
    .head {
      text-align: center;
      display: flex;
      flex-direction: column;
      gap: 4px;
    }
    .amount {
      font-size: 2rem;
      font-weight: 800;
      letter-spacing: -0.02em;
    }
    .mono {
      font-family: ui-monospace, 'Cascadia Mono', Menlo, monospace;
      font-size: 0.8rem;
      font-weight: 500;
    }
  `,
})
export class EntryDetail {
  readonly entry = input<StatementEntry | null>(null);
  readonly closed = output<void>();
  protected readonly type = type;
  protected readonly signed = signed;
  protected readonly money = money;
  protected readonly dateTime = dateTime;
}
