import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Icon } from '../../ui/icon';

/** "Pagar": the ways money leaves the account. */
@Component({
  selector: 'mw-pay',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, Icon],
  template: `
    <div class="page">
      <header class="page-head"><h1>Pagar</h1></header>
      <ul class="list">
        @for (o of options; track o.path) {
          <li>
            <a class="list-item" [routerLink]="o.path">
              <span class="avatar"><mw-icon [name]="o.icon" /></span>
              <span class="grow">
                <span class="title">{{ o.title }}</span>
                <span class="sub">{{ o.sub }}</span>
              </span>
              <mw-icon name="chevron" [size]="20" class="muted" />
            </a>
          </li>
        }
      </ul>
    </div>
  `,
  styles: `
    a.list-item {
      text-decoration: none;
      color: inherit;
      font-weight: inherit;
    }
  `,
})
export class Pay {
  protected readonly options = [
    { path: '/pagar/pix', icon: 'pix', title: 'Pix', sub: 'Para contas de outros bancos, na hora' },
    {
      path: '/pagar/transferencia',
      icon: 'transfer',
      title: 'Transferência',
      sub: 'Para outra conta M-Wall',
    },
    {
      path: '/agenda/novo',
      icon: 'calendar',
      title: 'Agendar pagamento',
      sub: 'Pix ou transferência em outro dia',
    },
  ];
}
