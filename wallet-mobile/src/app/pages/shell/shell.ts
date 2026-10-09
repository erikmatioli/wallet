import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { Icon } from '../../ui/icon';

/** The logged-in app: the page, and the bottom navigation (ADR-001, decision 7). */
@Component({
  selector: 'mw-shell',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterOutlet, RouterLink, RouterLinkActive, Icon],
  template: `
    <router-outlet />
    <nav class="tabs" aria-label="Navegação principal">
      @for (tab of tabs; track tab.path) {
        <a
          [routerLink]="tab.path"
          routerLinkActive="active"
          [routerLinkActiveOptions]="{ exact: tab.exact }"
          ariaCurrentWhenActive="page"
        >
          <span class="pill"><mw-icon [name]="tab.icon" [size]="22" /></span>
          <span class="label">{{ tab.label }}</span>
        </a>
      }
    </nav>
  `,
  styles: `
    :host {
      display: block;
      min-height: 100dvh;
    }
    .tabs {
      position: fixed;
      z-index: 10;
      left: 0;
      right: 0;
      bottom: 0;
      display: grid;
      grid-template-columns: repeat(4, 1fr);
      max-width: var(--mw-max);
      margin: 0 auto;
      padding: 6px 8px calc(6px + env(safe-area-inset-bottom));
      background: color-mix(in srgb, var(--mw-surface) 88%, transparent);
      backdrop-filter: blur(16px);
      -webkit-backdrop-filter: blur(16px);
      border-top: 1px solid var(--mw-line);
    }
    @media (min-width: 640px) {
      .tabs {
        bottom: 16px;
        border: 1px solid var(--mw-line);
        border-radius: 24px;
        box-shadow: var(--mw-shadow);
      }
    }
    a {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 2px;
      min-height: 56px;
      justify-content: center;
      text-decoration: none;
      color: var(--mw-text-2);
      font-size: 0.72rem;
      font-weight: 700;
      border-radius: 16px;
    }
    .pill {
      width: 56px;
      height: 32px;
      border-radius: 999px;
      display: grid;
      place-items: center;
      transition: background-color 0.2s ease;
    }
    a.active {
      color: var(--mw-orange-strong);
    }
    a.active .pill {
      background: var(--mw-orange-soft);
    }
  `,
})
export class Shell {
  protected readonly tabs = [
    { path: '/inicio', label: 'Início', icon: 'home', exact: true },
    { path: '/extrato', label: 'Extrato', icon: 'statement', exact: true },
    { path: '/pagar', label: 'Pagar', icon: 'pay', exact: false },
    { path: '/agenda', label: 'Agenda', icon: 'calendar', exact: false },
  ];
}
