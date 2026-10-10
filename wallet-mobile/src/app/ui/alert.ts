import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  afterNextRender,
  inject,
  input,
} from '@angular/core';
import { Icon } from './icon';

/**
 * An error for the customer. On a phone the form's error shows near the button, often below the fold:
 * the alert scrolls itself into view when it appears, and `role="alert"` reads it out.
 */
@Component({
  selector: 'mw-alert',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon],
  template: `
    <div class="alert alert-error" role="alert" [attr.id]="alertId() || null">
      <mw-icon name="alert" [size]="20" /><span>{{ message() }}</span>
    </div>
  `,
  host: { style: 'display:block;scroll-margin:96px' },
})
export class Alert {
  readonly message = input.required<string>();
  readonly alertId = input('');

  constructor() {
    const host = inject(ElementRef<HTMLElement>);
    afterNextRender(() => {
      const reduce = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
      host.nativeElement.scrollIntoView?.({
        block: 'nearest',
        behavior: reduce ? 'auto' : 'smooth',
      });
    });
  }
}
