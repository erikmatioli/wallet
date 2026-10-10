import { AppApiError } from '../core/api';
import { SessionStore } from '../core/session';
import { fakeApi } from '../testing/fake-api';
import { LoginFlow, SignupFlow } from './auth.flow';

const sent = {
  challengeId: 'ch-1',
  message: 'Enviamos um código para m***@example.com.',
  resendAfterSeconds: 60,
};
const session = { token: 'tok', expiresInSeconds: 1800, customerName: 'Maria Silva' };

describe('LoginFlow', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('asks for the code, then the code starts the session and nothing typed stays', async () => {
    const api = fakeApi();
    api.startLogin.mockResolvedValue(sent);
    api.confirmLogin.mockResolvedValue(session);
    const store = new SessionStore();
    const flow = new LoginFlow(api, store);

    flow.setCpf('529.982.247-25');
    expect(flow.canSend()).toBe(true);
    await flow.sendCode();
    expect(flow.step()?.sentMessage).toContain('m***@example.com');
    expect(flow.resendIn()).toBe(60);

    flow.setCode(' 12 34 56 ');
    expect(flow.code()).toBe('123456');
    await flow.confirm();

    expect(api.confirmLogin).toHaveBeenCalledWith('ch-1', '529.982.247-25', '123456');
    expect(store.loggedIn()).toBe(true);
    expect(store.token).toBe('tok');
    flow.dispose();
  });

  it('a wrong code shows app-api message, clears the code and stays on the code step', async () => {
    const api = fakeApi();
    api.startLogin.mockResolvedValue(sent);
    api.confirmLogin.mockRejectedValue(
      new AppApiError('INVALID_CODE', 'Código incorreto. Restam 4 tentativas.'),
    );
    const store = new SessionStore();
    const flow = new LoginFlow(api, store);
    flow.setCpf('52998224725');
    await flow.sendCode();
    flow.setCode('000000');

    await flow.confirm();

    expect(flow.error()).toBe('Código incorreto. Restam 4 tentativas.');
    expect(flow.code()).toBe('');
    expect(flow.step()).not.toBeNull();
    expect(store.loggedIn()).toBe(false);
    flow.dispose();
  });

  it('resend is offered after the countdown, and a wait from the server restarts it', async () => {
    const api = fakeApi();
    api.startLogin
      .mockResolvedValueOnce({ ...sent, resendAfterSeconds: 3 })
      .mockRejectedValueOnce(
        new AppApiError('TOO_MANY_REQUESTS', 'Aguarde para pedir outro código.', 40),
      );
    const flow = new LoginFlow(api, new SessionStore());
    flow.setCpf('52998224725');
    await flow.sendCode();

    vi.advanceTimersByTime(3_000);
    expect(flow.resendIn()).toBe(0);

    await flow.sendCode();
    expect(flow.error()).toBe('Aguarde para pedir outro código.');
    expect(flow.resendIn()).toBe(40);
    flow.dispose();
  });

  it('without resendAfterSeconds (left out at its default) the wait is 60 seconds', async () => {
    const api = fakeApi();
    api.startLogin.mockResolvedValue({ challengeId: 'ch-1', message: 'Enviamos um código.' });
    const flow = new LoginFlow(api, new SessionStore());
    flow.setCpf('52998224725');
    await flow.sendCode();
    expect(flow.resendIn()).toBe(60);
    flow.dispose();
  });

  it('cannot ask for a code before the CPF has 11 digits', () => {
    const flow = new LoginFlow(fakeApi(), new SessionStore());
    flow.setCpf('529.982.247');
    expect(flow.canSend()).toBe(false);
  });
});

describe('SignupFlow', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('sends the code to the email typed, then opens the account with the same data', async () => {
    const api = fakeApi();
    api.startSignup.mockResolvedValue(sent);
    api.confirmSignup.mockResolvedValue(session);
    const store = new SessionStore();
    const flow = new SignupFlow(api, store);
    flow.set('name', ' Maria Silva ');
    flow.set('cpf', '529.982.247-25');
    flow.set('email', 'maria@example.com');
    expect(flow.canSend()).toBe(true);

    await flow.sendCode();
    flow.setCode('654321');
    await flow.confirm();

    expect(api.startSignup).toHaveBeenCalledWith(
      '529.982.247-25',
      'Maria Silva',
      'maria@example.com',
    );
    expect(api.confirmSignup).toHaveBeenCalledWith(
      'ch-1',
      '654321',
      '529.982.247-25',
      'Maria Silva',
      'maria@example.com',
    );
    expect(store.customerName()).toBe('Maria Silva');
    flow.dispose();
  });

  it('a network failure becomes the generic message', async () => {
    const api = fakeApi();
    api.startSignup.mockRejectedValue(new TypeError('Failed to fetch'));
    const flow = new SignupFlow(api, new SessionStore());
    flow.set('name', 'Maria');
    flow.set('cpf', '52998224725');
    flow.set('email', 'maria@example.com');

    await flow.sendCode();

    expect(flow.error()).toContain('Não foi possível falar com o servidor');
    expect(flow.step()).toBeNull();
  });
});
