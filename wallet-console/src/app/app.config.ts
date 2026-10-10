import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { DATE_PIPE_DEFAULT_OPTIONS } from '@angular/common';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter } from '@angular/router';

import { routes } from './app.routes';
import { authInterceptor } from './core/auth.interceptor';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    provideHttpClient(withInterceptors([authInterceptor])),
    // Every date the console shows is in Brasília, whatever the operator's browser: the statement's days
    // (and their closing balance, ADR-012 of the core) and the schedules' windows are Brasília's. Brazil has
    // had no daylight saving time since 2019, so a fixed offset is exact.
    { provide: DATE_PIPE_DEFAULT_OPTIONS, useValue: { timezone: '-0300' } },
  ],
};
