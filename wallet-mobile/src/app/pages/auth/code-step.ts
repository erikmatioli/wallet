import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  afterNextRender,
  input,
  output,
  viewChild,
} from '@angular/core';
import { LoginFlow, SignupFlow } from '../../flows/auth.flow';
import { Alert } from '../../ui/alert';
import { Icon } from '../../ui/icon';

/** The second step of login and signup: the 6-digit code from the email, resend, and going back. */
@Component({
  selector: 'mw-code-step',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon, Alert],
  template: `
    <div class="sent">
      <span class="mail"><mw-icon name="mail" /></span>
      <p>{{ flow().step()?.sentMessage }}</p>
    </div>
    <form class="stack" (submit)="$event.preventDefault(); confirmed.emit()" novalidate>
      <div class="field">
        <label for="code">Código de 6 dígitos</label>
        <input
          #code
          id="code"
          class="code-input num"
          inputmode="numeric"
          autocomplete="one-time-code"
          maxlength="6"
          placeholder="000000"
          [value]="flow().code()"
          (input)="
            flow().setCode($any($event.target).value); $any($event.target).value = flow().code()
          "
          [attr.aria-describedby]="flow().error() ? 'code-error' : 'code-hint'"
          [attr.aria-invalid]="flow().error() ? 'true' : null"
        />
        <span class="hint" id="code-hint">Vale por 5 minutos. Confira também a caixa de spam.</span>
      </div>
      @if (flow().error(); as error) {
        <mw-alert [message]="error" alertId="code-error" />
      }
      <button type="submit" class="btn btn-primary" [disabled]="!flow().canConfirm()">
        @if (flow().busy()) {
          <span class="spinner" aria-hidden="true"></span><span>Conferindo…</span>
        } @else {
          {{ confirmLabel() }}
        }
      </button>
    </form>
    <div class="links">
      <button type="button" class="btn-link" (click)="flow().back()">{{ backLabel() }}</button>
      <button
        type="button"
        class="btn-link"
        [disabled]="flow().resendIn() > 0 || flow().busy()"
        (click)="flow().sendCode()"
      >
        @if (flow().resendIn() > 0) {
          Reenviar em {{ flow().resendIn() }}s
        } @else {
          Reenviar código
        }
      </button>
    </div>
  `,
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      gap: 16px;
    }
    .sent {
      display: flex;
      gap: 12px;
      align-items: center;
      padding: 14px;
      border-radius: var(--mw-radius-sm);
      background: var(--mw-orange-soft);
      color: var(--mw-orange-strong);
      font-weight: 600;
    }
    .mail {
      flex: none;
      width: 40px;
      height: 40px;
      border-radius: 12px;
      display: grid;
      place-items: center;
      background: var(--mw-surface);
    }
    .links {
      display: flex;
      justify-content: space-between;
    }
  `,
})
export class CodeStep {
  readonly flow = input.required<LoginFlow | SignupFlow>();
  readonly confirmLabel = input('Entrar');
  readonly backLabel = input('Voltar');
  readonly confirmed = output<void>();
  private readonly codeInput = viewChild.required<ElementRef<HTMLInputElement>>('code');

  constructor() {
    afterNextRender(() => this.codeInput().nativeElement.focus());
  }
}
