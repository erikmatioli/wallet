import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { StatementEntry } from '../core/contract';
import { signed, time, type } from '../core/format';
import { Icon } from './icon';

/** One statement line: what, who, when and how much. Tapping it opens the detail. */
@Component({
  selector: 'mw-entry-item',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon],
  template: `
    <button type="button" class="list-item" (click)="pick.emit(entry())">
      <span class="avatar" [class.in]="entry().credit">
        <mw-icon [name]="icon()" [size]="20" />
      </span>
      <span class="grow">
        <span class="title">{{ title() }}</span>
        <span class="sub">{{ subtitle() }}</span>
      </span>
      <span class="end num" [class.credit]="entry().credit">
        <span class="sr-only">{{ entry().credit ? 'Entrada de' : 'Saída de' }}</span>
        {{ amount() }}
      </span>
    </button>
  `,
})
export class EntryItem {
  readonly entry = input.required<StatementEntry>();
  readonly pick = output<StatementEntry>();

  protected icon(): string {
    const t = this.entry().type;
    if (t.startsWith('PIX')) return 'pix';
    if (t === 'TRANSFER') return 'transfer';
    return this.entry().credit ? 'in' : 'out';
  }

  protected title(): string {
    return this.entry().counterpartyName || type(this.entry().type);
  }

  protected subtitle(): string {
    const e = this.entry();
    const what = e.counterpartyName ? type(e.type) : e.description || '';
    return [what, time(e.occurredAt)].filter(Boolean).join(' · ');
  }

  protected amount(): string {
    return signed(this.entry());
  }
}
