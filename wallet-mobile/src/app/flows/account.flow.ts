import { computed, signal } from '@angular/core';
import { WalletApi, message } from '../core/api';
import { Me, StatementEntry } from '../core/contract';
import { dayKey } from '../core/format';

/** The home screen: the account and its last few entries, loaded together. */
export class HomeFlow {
  readonly me = signal<Me | null>(null);
  readonly recent = signal<StatementEntry[]>([]);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);

  constructor(private readonly api: WalletApi) {}

  async load(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      const [me, page] = await Promise.all([this.api.me(), this.api.statement(null, 5)]);
      this.me.set(me);
      this.recent.set(page.entries);
    } catch (e) {
      this.error.set(message(e));
    } finally {
      this.loading.set(false);
    }
  }
}

export interface StatementDay {
  day: string;
  entries: StatementEntry[];
}

/** The statement, page by page (newest first), grouped by day in Brasília; the detail needs no request. */
export class StatementFlow {
  readonly entries = signal<StatementEntry[]>([]);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly selected = signal<StatementEntry | null>(null);
  private readonly next = signal<number | null>(null);
  private readonly loadedOnce = signal(false);

  readonly hasMore = computed(() => this.next() !== null);
  readonly empty = computed(() => this.loadedOnce() && this.entries().length === 0);
  readonly days = computed<StatementDay[]>(() => {
    const days: StatementDay[] = [];
    for (const entry of this.entries()) {
      const day = dayKey(entry.occurredAt);
      const last = days.at(-1);
      if (last?.day === day) last.entries.push(entry);
      else days.push({ day, entries: [entry] });
    }
    return days;
  });

  constructor(
    private readonly api: WalletApi,
    private readonly pageSize = 20,
  ) {}

  /** The first page, or the next one after what is loaded. */
  async loadMore(): Promise<void> {
    if (this.loading() || (this.loadedOnce() && !this.hasMore())) return;
    this.loading.set(true);
    this.error.set(null);
    try {
      const page = await this.api.statement(this.next(), this.pageSize);
      this.entries.update((all) => [...all, ...page.entries]);
      this.next.set(page.nextBefore ?? null);
      this.loadedOnce.set(true);
    } catch (e) {
      this.error.set(message(e));
    } finally {
      this.loading.set(false);
    }
  }

  /** From the newest again: whatever happened since shows up. */
  refresh(): Promise<void> {
    this.entries.set([]);
    this.next.set(null);
    this.loadedOnce.set(false);
    return this.loadMore();
  }

  open(entry: StatementEntry): void {
    this.selected.set(entry);
  }

  close(): void {
    this.selected.set(null);
  }
}
