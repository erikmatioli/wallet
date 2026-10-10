import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  effect,
  input,
  output,
  viewChild,
} from '@angular/core';

/**
 * A bottom sheet on the native `<dialog>`: focus trap, Esc and the backdrop come from the browser.
 * Open while `open` is true; `closed` fires when the customer dismisses it (Esc, backdrop, close button).
 */
@Component({
  selector: 'mw-sheet',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <dialog
      #dialog
      class="sheet"
      [attr.aria-labelledby]="labelledBy() || null"
      (close)="closed.emit()"
      (click)="onClick($event)"
    >
      <div class="grabber" aria-hidden="true"></div>
      <div class="sheet-body">
        <ng-content />
      </div>
    </dialog>
  `,
})
export class Sheet {
  readonly open = input(false);
  readonly labelledBy = input('');
  readonly closed = output<void>();
  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dialog');

  constructor() {
    effect(() => {
      const d = this.dialog().nativeElement;
      if (this.open() && !d.open) d.showModal();
      if (!this.open() && d.open) d.close();
    });
  }

  /** A click on the backdrop lands on the dialog itself, outside its content box. */
  protected onClick(event: MouseEvent): void {
    const d = this.dialog().nativeElement;
    if (event.target !== d) return;
    const r = d.getBoundingClientRect();
    const inside =
      event.clientX >= r.left &&
      event.clientX <= r.right &&
      event.clientY >= r.top &&
      event.clientY <= r.bottom;
    if (!inside) d.close();
  }
}
