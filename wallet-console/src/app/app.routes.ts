import { Routes } from '@angular/router';
import { authGuard } from './core/auth.guard';

export const routes: Routes = [
  { path: 'login', loadComponent: () => import('./pages/login/login').then((m) => m.Login) },
  {
    path: 'home',
    canActivate: [authGuard],
    loadComponent: () => import('./pages/home/home').then((m) => m.Home),
  },
  {
    path: 'onboard',
    canActivate: [authGuard],
    loadComponent: () => import('./pages/onboard/onboard').then((m) => m.Onboard),
  },
  {
    path: 'accounts/:id',
    canActivate: [authGuard],
    loadComponent: () => import('./pages/account/account').then((m) => m.AccountPage),
  },
  { path: '', pathMatch: 'full', redirectTo: 'home' },
  { path: '**', redirectTo: 'home' },
];
