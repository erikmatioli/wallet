import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { AuthService } from './auth.service';

describe('AuthService', () => {
  let service: AuthService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(AuthService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('starts unauthenticated with no stored session', () => {
    expect(service.isAuthenticated()).toBe(false);
    expect(service.currentToken()).toBeNull();
  });

  it('authenticates with HTTP Basic and stores the resulting JWT', async () => {
    const loginPromise = service.login('demo-tenant', 's3cret');

    const req = httpMock.expectOne('/v1/auth/token');
    expect(req.request.headers.get('Authorization')).toBe(`Basic ${btoa('demo-tenant:s3cret')}`);
    req.flush({ access_token: 'jwt-123', token_type: 'Bearer', expires_in: 600, scope: 'ledger:write' });

    await loginPromise;

    expect(service.isAuthenticated()).toBe(true);
    expect(service.currentToken()).toBe('jwt-123');
    expect(service.clientId()).toBe('demo-tenant');
    expect(service.hasScope('ledger:write')).toBe(true);
    expect(service.hasScope('ledger:audit')).toBe(false);
  });

  it('logout clears the session', async () => {
    const loginPromise = service.login('demo-tenant', 's3cret');
    httpMock.expectOne('/v1/auth/token').flush({ access_token: 'jwt-123', token_type: 'Bearer', expires_in: 600, scope: '' });
    await loginPromise;

    service.logout();

    expect(service.isAuthenticated()).toBe(false);
    expect(sessionStorage.getItem('wallet-console.session')).toBeNull();
  });
});
