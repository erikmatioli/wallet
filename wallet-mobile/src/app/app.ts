import { ChangeDetectionStrategy, Component, effect, inject } from '@angular/core';
import { Router, RouterOutlet } from '@angular/router';
import { InactivityWatch } from './core/inactivity';
import { Pwa } from './core/pwa';
import { SessionStore } from './core/session';
import { Icon } from './ui/icon';

/**
 * The root: the page, and the two notices that can show over any of them - no network, and a new version
 * of the app ready to apply.
 */
@Component({
  selector: 'mw-root',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterOutlet, Icon],
  template: `
    @if (!pwa.online()) {
      <div class="notice offline" role="status">
        <mw-icon name="offline" [size]="18" />
        Você está sem conexão. Nada será enviado até a rede voltar.
      </div>
    }
    @if (pwa.updateReady()) {
      <div class="notice update" role="status">
        <span>Nova versão do M-Wall disponível.</span>
        <button type="button" class="btn-link" (click)="pwa.update()">Atualizar</button>
      </div>
    }
    <router-outlet />
  `,
  styles: `
    .notice {
      position: sticky;
      top: 0;
      z-index: 20;
      display: flex;
      align-items: center;
      justify-content: center;
      gap: 8px;
      padding: 8px 16px;
      padding-top: calc(8px + env(safe-area-inset-top));
      font-size: 0.875rem;
      font-weight: 600;
      text-align: center;
    }
    .offline {
      background: var(--mw-text);
      color: var(--mw-bg);
    }
    .update {
      background: var(--mw-orange-soft);
      color: var(--mw-orange-strong);
    }
  `,
})
export class App {
  protected readonly pwa = inject(Pwa);

  constructor() {
    inject(InactivityWatch);
    const session = inject(SessionStore);
    const router = inject(Router);
    // The session can end by itself (expired token, inactivity): whatever is on screen goes away.
    effect(() => {
      if (!session.loggedIn() && session.endedBecause()) void router.navigateByUrl('/entrar');
    });
  }
}
