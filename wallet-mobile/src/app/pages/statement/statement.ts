import { ChangeDetectionStrategy, Component, OnInit, inject } from '@angular/core';
import { AppApi } from '../../core/api';
import { dayTitle } from '../../core/format';
import { StatementFlow } from '../../flows/account.flow';
import { EntryDetail } from '../../ui/entry-detail';
import { EntryItem } from '../../ui/entry-item';
import { Alert } from '../../ui/alert';
import { Icon } from '../../ui/icon';

/** The statement: newest first, grouped by day, page by page; tapping a line opens its detail. */
@Component({
  selector: 'mw-statement',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon, EntryItem, EntryDetail, Alert],
  template: `
    <div class="page">
      <header class="page-head">
        <h1 class="grow">Extrato</h1>
        <button
          type="button"
          class="icon-btn"
          (click)="flow.refresh()"
          aria-label="Atualizar extrato"
          [disabled]="flow.loading()"
        >
          <mw-icon name="refresh" />
        </button>
      </header>

      @for (d of flow.days(); track d.day) {
        <section class="stack day" [attr.aria-label]="dayTitle(d.day)">
          <h2 class="day-title">{{ dayTitle(d.day) }}</h2>
          <ul class="list">
            @for (e of d.entries; track e.transactionId + e.sequence) {
              <li><mw-entry-item [entry]="e" (pick)="flow.open($event)" /></li>
            }
          </ul>
        </section>
      }

      @if (flow.empty()) {
        <div class="card empty">
          <span class="avatar"><mw-icon name="statement" /></span>
          <p><strong>Seu extrato está vazio.</strong></p>
          <p class="muted">Depósitos, Pix e transferências aparecem aqui.</p>
        </div>
      }

      @if (flow.error(); as error) {
        <mw-alert [message]="error" />
      }

      @if (flow.loading()) {
        <div class="loading" role="status">
          <span class="spinner" aria-hidden="true"></span> Carregando…
        </div>
      } @else if (flow.hasMore() || flow.error()) {
        <button type="button" class="btn btn-ghost" (click)="flow.loadMore()">Carregar mais</button>
      }
    </div>
    <mw-entry-detail [entry]="flow.selected()" (closed)="flow.close()" />
  `,
  styles: `
    .grow {
      flex: 1;
    }
    .day {
      gap: 8px;
    }
    .day-title {
      font-size: 0.85rem;
      font-weight: 700;
      color: var(--mw-text-2);
      text-transform: none;
      padding-left: 4px;
    }
    .empty {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 6px;
      text-align: center;
      padding: 32px 16px;
    }
    .loading {
      display: flex;
      gap: 8px;
      justify-content: center;
      align-items: center;
      color: var(--mw-text-2);
      padding: 12px;
    }
  `,
})
export class Statement implements OnInit {
  protected readonly flow = new StatementFlow(inject(AppApi));
  protected readonly dayTitle = (day: string) => dayTitle(day);

  ngOnInit(): void {
    void this.flow.loadMore();
  }
}
