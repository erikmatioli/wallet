import { vi } from 'vitest';
import { WalletApi } from '../core/api';
import { PixReceipt, Schedule, StatementEntry } from '../core/contract';

/** A WalletApi whose every method is a vi.fn: each test says what the calls answer. */
export function fakeApi(): { [K in keyof WalletApi]: ReturnType<typeof vi.fn> } & WalletApi {
  const methods: (keyof WalletApi)[] = [
    'startSignup',
    'confirmSignup',
    'startLogin',
    'confirmLogin',
    'me',
    'statement',
    'transferDestination',
    'transfer',
    'sendPix',
    'pixStatus',
    'schedules',
    'createSchedule',
    'cancelSchedule',
  ];
  const api: Record<string, ReturnType<typeof vi.fn>> = {};
  for (const m of methods) api[m] = vi.fn(() => Promise.reject(new Error(`unexpected call: ${m}`)));
  return api as never;
}

export function entry(over: Partial<StatementEntry> = {}): StatementEntry {
  return {
    transactionId: 'tx-1',
    sequence: 1,
    type: 'PIX_IN',
    credit: true,
    amountCents: 1000,
    balanceAfterCents: 5000,
    occurredAt: '2026-10-09T15:00:00Z',
    ...over,
  };
}

export function pix(status: string, over: Partial<PixReceipt> = {}): PixReceipt {
  return {
    endToEndId: 'E123',
    status,
    amountCents: 1500,
    payeeName: 'Fulano',
    payeeIspb: '99999999',
    ...over,
  };
}

export function schedule(over: Partial<Schedule> = {}): Schedule {
  return {
    id: 's-1',
    type: 'TRANSFER',
    status: 'ACTIVE',
    executeOn: '2026-10-20',
    amountCents: 1000,
    payee: 'João',
    payeeDetail: '0001 / 00100161-0',
    createdAt: '2026-10-09T15:00:00Z',
    canCancel: true,
    execution: { status: 'PENDING', attemptCount: 0 },
    ...over,
  };
}
