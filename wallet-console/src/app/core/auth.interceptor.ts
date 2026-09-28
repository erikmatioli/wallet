import { HttpContextToken, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { AuthService } from './auth.service';

/** Set on a request's HttpContext to opt out of automatic Bearer-token attachment (see AuthService.login). */
export const SKIP_AUTH = new HttpContextToken<boolean>(() => false);

/** Attaches "Authorization: Bearer <jwt>" to every outgoing request that has a live session. */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  if (req.context.get(SKIP_AUTH)) {
    return next(req);
  }
  const token = inject(AuthService).currentToken();
  if (!token) {
    return next(req);
  }
  return next(req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }));
};
