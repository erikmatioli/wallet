import { ChangeDetectionStrategy, Component, input, model } from '@angular/core';
import { amountField, centsFromTyping } from '../core/format';

/**
 * The amount, the way bank apps take it: digits fill the cents from the right, on the numeric keypad.
 * Bound in cents (`[(cents)]`), never a floating point number.
 */
@Component({
  selector: 'mw-amount-field',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="field">
      <label [attr.for]="id()">{{ label() }}</label>
      <input
        [id]="id()"
        class="amount-input num"
        inputmode="numeric"
        autocomplete="off"
        placeholder="R$ 0,00"
        [value]="text()"
        (input)="onInput($event)"
        [attr.aria-describedby]="describedBy() || null"
      />
    </div>
  `,
})
export class AmountField {
  readonly id = input('amount');
  readonly label = input('Valor');
  readonly describedBy = input('');
  readonly cents = model(0);

  protected text(): string {
    return amountField(this.cents());
  }

  protected onInput(event: Event): void {
    const el = event.target as HTMLInputElement;
    const cents = centsFromTyping(el.value);
    this.cents.set(cents);
    // Rewrites what was typed in the money format, caret at the end (where the next digit goes).
    el.value = amountField(cents);
    el.setSelectionRange(el.value.length, el.value.length);
  }
}
