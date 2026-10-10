import { ChangeDetectionStrategy, Component, OnDestroy, inject } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { AppApi } from '../../core/api';
import { cpfMask } from '../../core/format';
import { SessionStore } from '../../core/session';
import { SignupFlow } from '../../flows/auth.flow';
import { Alert } from '../../ui/alert';
import { Logo } from '../../ui/logo';
import { CodeStep } from './code-step';

/** Opening an account: name, CPF and email, then the code that proves the email is the customer's. */
@Component({
  selector: 'mw-signup',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, Logo, CodeStep, Alert],
  styleUrl: './auth.scss',
  template: `
    <header class="hero">
      <mw-logo class="on-brand" [size]="44" />
      <p class="tagline">Abra sua conta em um minuto.</p>
    </header>
    <main class="panel">
      @if (!flow.step()) {
        <div class="title">
          <h1>Abrir conta</h1>
          <p class="muted">
            Vamos confirmar seu e-mail com um código. É ele que você usa para entrar.
          </p>
        </div>
        <form class="stack" (submit)="$event.preventDefault(); flow.sendCode()" novalidate>
          <div class="field">
            <label for="name">Nome completo</label>
            <input
              id="name"
              autocomplete="name"
              [value]="flow.name()"
              (input)="flow.set('name', $any($event.target).value)"
            />
          </div>
          <div class="field">
            <label for="cpf">CPF</label>
            <input
              id="cpf"
              inputmode="numeric"
              autocomplete="off"
              placeholder="000.000.000-00"
              [value]="flow.cpf()"
              (input)="onCpf($event)"
            />
          </div>
          <div class="field">
            <label for="email">E-mail</label>
            <input
              id="email"
              type="email"
              inputmode="email"
              autocomplete="email"
              placeholder="voce@exemplo.com"
              [value]="flow.email()"
              (input)="flow.set('email', $any($event.target).value)"
              aria-describedby="email-hint"
            />
            <span class="hint" id="email-hint"
              >O mesmo e-mail pode ser usado em mais de uma conta.</span
            >
          </div>
          @if (flow.error(); as error) {
            <mw-alert [message]="error" />
          }
          <button type="submit" class="btn btn-primary" [disabled]="!flow.canSend()">
            @if (flow.busy()) {
              <span class="spinner" aria-hidden="true"></span><span>Enviando…</span>
            } @else {
              Receber código
            }
          </button>
        </form>
        <p class="footer">Já tem conta? <a routerLink="/entrar">Entrar</a></p>
      } @else {
        <div class="title">
          <h1>Confirme seu e-mail</h1>
        </div>
        <mw-code-step
          [flow]="flow"
          confirmLabel="Abrir minha conta"
          backLabel="Corrigir dados"
          (confirmed)="confirm()"
        />
      }
    </main>
  `,
})
export class Signup implements OnDestroy {
  private readonly session = inject(SessionStore);
  private readonly router = inject(Router);
  protected readonly flow = new SignupFlow(inject(AppApi), this.session);

  protected onCpf(event: Event): void {
    const el = event.target as HTMLInputElement;
    el.value = cpfMask(el.value);
    this.flow.set('cpf', el.value);
  }

  protected async confirm(): Promise<void> {
    await this.flow.confirm();
    if (this.session.loggedIn()) await this.router.navigateByUrl('/inicio');
  }

  ngOnDestroy(): void {
    this.flow.dispose();
  }
}
