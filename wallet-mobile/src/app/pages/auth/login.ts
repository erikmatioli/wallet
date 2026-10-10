import { ChangeDetectionStrategy, Component, OnDestroy, inject } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { AppApi } from '../../core/api';
import { cpfMask } from '../../core/format';
import { Pwa } from '../../core/pwa';
import { SessionStore } from '../../core/session';
import { LoginFlow } from '../../flows/auth.flow';
import { Alert } from '../../ui/alert';
import { Icon } from '../../ui/icon';
import { Logo } from '../../ui/logo';
import { CodeStep } from './code-step';

/** Login (ADR-002 of wallet-app): the CPF, then the code sent to the account's email. No password. */
@Component({
  selector: 'mw-login',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, Logo, Icon, CodeStep, Alert],
  styleUrl: './auth.scss',
  template: `
    <header class="hero">
      <mw-logo class="on-brand" [size]="52" />
      <p class="tagline">Sua conta, no seu bolso.</p>
    </header>
    <main class="panel">
      @if (session.endedBecause(); as reason) {
        <div class="alert alert-info" role="status">
          <mw-icon name="clock" [size]="20" />{{ reason }}
        </div>
      }
      @if (!flow.step()) {
        <div class="title">
          <h1>Entrar</h1>
          <p class="muted">Sem senha: enviamos um código para o e-mail da sua conta.</p>
        </div>
        <form class="stack" (submit)="$event.preventDefault(); flow.sendCode()" novalidate>
          <div class="field">
            <label for="cpf">CPF</label>
            <input
              id="cpf"
              inputmode="numeric"
              autocomplete="username"
              placeholder="000.000.000-00"
              [value]="flow.cpf()"
              (input)="onCpf($event)"
              [attr.aria-describedby]="flow.error() ? 'login-error' : null"
              [attr.aria-invalid]="flow.error() ? 'true' : null"
            />
          </div>
          @if (flow.error(); as error) {
            <mw-alert [message]="error" alertId="login-error" />
          }
          <button type="submit" class="btn btn-primary" [disabled]="!flow.canSend()">
            @if (flow.busy()) {
              <span class="spinner" aria-hidden="true"></span><span>Enviando…</span>
            } @else {
              Receber código
            }
          </button>
        </form>
        <p class="footer">
          Ainda não tem conta? <a routerLink="/abrir-conta">Abrir minha conta</a>
        </p>
      } @else {
        <div class="title">
          <h1>Confira seu e-mail</h1>
        </div>
        <mw-code-step [flow]="flow" backLabel="Trocar CPF" (confirmed)="confirm()" />
      }
      @if (pwa.canInstall()) {
        <button type="button" class="btn btn-ghost install" (click)="pwa.install()">
          <mw-icon name="download" [size]="20" /> Instalar o M-Wall
        </button>
      }
      <p class="trust">
        <mw-icon name="shield" [size]="16" /> Sua sessão fica só neste aparelho e termina sozinha.
      </p>
    </main>
  `,
})
export class Login implements OnDestroy {
  protected readonly session = inject(SessionStore);
  protected readonly pwa = inject(Pwa);
  protected readonly flow = new LoginFlow(inject(AppApi), this.session);
  private readonly router = inject(Router);

  protected onCpf(event: Event): void {
    const el = event.target as HTMLInputElement;
    el.value = cpfMask(el.value);
    this.flow.setCpf(el.value);
  }

  protected async confirm(): Promise<void> {
    await this.flow.confirm();
    if (this.session.loggedIn()) await this.router.navigateByUrl('/inicio');
  }

  ngOnDestroy(): void {
    this.flow.dispose();
  }
}
