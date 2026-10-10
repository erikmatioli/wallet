import { DestroyRef, Injectable, inject, signal } from '@angular/core';
import { SwUpdate, VersionReadyEvent } from '@angular/service-worker';
import { filter } from 'rxjs';

/** The install prompt Chrome and Edge fire; not in TypeScript's DOM types. */
interface BeforeInstallPromptEvent extends Event {
  prompt(): Promise<void>;
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>;
}

/**
 * What the PWA knows about itself (ADR-001, decision 4): whether there is network, whether a new version is
 * ready (applied only when the customer taps "Atualizar"), and whether the browser offers to install it.
 */
@Injectable({ providedIn: 'root' })
export class Pwa {
  private readonly updates = inject(SwUpdate);
  private installEvent: BeforeInstallPromptEvent | null = null;

  readonly online = signal(typeof navigator === 'undefined' ? true : navigator.onLine);
  readonly updateReady = signal(false);
  readonly canInstall = signal(false);

  constructor() {
    const onOnline = () => this.online.set(true);
    const onOffline = () => this.online.set(false);
    const onInstall = (e: Event) => {
      e.preventDefault(); // our own button, on the login screen, instead of the browser's mini bar
      this.installEvent = e as BeforeInstallPromptEvent;
      this.canInstall.set(true);
    };
    const onInstalled = () => this.canInstall.set(false);
    window.addEventListener('online', onOnline);
    window.addEventListener('offline', onOffline);
    window.addEventListener('beforeinstallprompt', onInstall);
    window.addEventListener('appinstalled', onInstalled);

    const sub = this.updates.isEnabled
      ? this.updates.versionUpdates
          .pipe(filter((e): e is VersionReadyEvent => e.type === 'VERSION_READY'))
          .subscribe(() => this.updateReady.set(true))
      : null;

    inject(DestroyRef).onDestroy(() => {
      window.removeEventListener('online', onOnline);
      window.removeEventListener('offline', onOffline);
      window.removeEventListener('beforeinstallprompt', onInstall);
      window.removeEventListener('appinstalled', onInstalled);
      sub?.unsubscribe();
    });
  }

  async install(): Promise<void> {
    const e = this.installEvent;
    if (!e) return;
    await e.prompt();
    await e.userChoice;
    this.installEvent = null;
    this.canInstall.set(false);
  }

  /** Reloads into the new version. The session is in memory, so the customer enters again. */
  async update(): Promise<void> {
    await this.updates.activateUpdate();
    document.location.reload();
  }
}
