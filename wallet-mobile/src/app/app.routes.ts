import { inject } from '@angular/core';
import { CanActivateFn, Router, Routes } from '@angular/router';
import { SessionStore } from './core/session';

/** Without a session, everything goes to the login. */
const loggedIn: CanActivateFn = () =>
  inject(SessionStore).loggedIn() || inject(Router).parseUrl('/entrar');

/** With a session, the login and the signup go home. */
const loggedOut: CanActivateFn = () =>
  !inject(SessionStore).loggedIn() || inject(Router).parseUrl('/inicio');

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'inicio' },
  {
    path: 'entrar',
    canActivate: [loggedOut],
    title: 'Entrar · M-Wall',
    loadComponent: () => import('./pages/auth/login').then((m) => m.Login),
  },
  {
    path: 'abrir-conta',
    canActivate: [loggedOut],
    title: 'Abrir conta · M-Wall',
    loadComponent: () => import('./pages/auth/signup').then((m) => m.Signup),
  },
  {
    path: '',
    canActivate: [loggedIn],
    canActivateChild: [loggedIn],
    loadComponent: () => import('./pages/shell/shell').then((m) => m.Shell),
    children: [
      {
        path: 'inicio',
        title: 'Início · M-Wall',
        loadComponent: () => import('./pages/home/home').then((m) => m.Home),
      },
      {
        path: 'extrato',
        title: 'Extrato · M-Wall',
        loadComponent: () => import('./pages/statement/statement').then((m) => m.Statement),
      },
      {
        path: 'pagar',
        title: 'Pagar · M-Wall',
        loadComponent: () => import('./pages/pay/pay').then((m) => m.Pay),
      },
      {
        path: 'pagar/pix',
        title: 'Pix · M-Wall',
        loadComponent: () => import('./pages/pay/pix').then((m) => m.PixPage),
      },
      {
        path: 'pagar/transferencia',
        title: 'Transferência · M-Wall',
        loadComponent: () => import('./pages/pay/transfer').then((m) => m.TransferPage),
      },
      {
        path: 'agenda',
        title: 'Agendamentos · M-Wall',
        loadComponent: () => import('./pages/schedules/schedules').then((m) => m.Schedules),
      },
      {
        path: 'agenda/novo',
        title: 'Novo agendamento · M-Wall',
        loadComponent: () => import('./pages/schedules/new-schedule').then((m) => m.NewSchedule),
      },
    ],
  },
  { path: '**', redirectTo: 'inicio' },
];
