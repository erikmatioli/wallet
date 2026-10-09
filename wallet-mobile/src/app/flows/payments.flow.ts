import { WritableSignal, signal } from '@angular/core';
import { WalletApi, message } from '../core/api';
import { PixPayee, PixReceipt, TransferDestination, TransferReceipt } from '../core/contract';
import { TAX_ID_ERROR, isTaxId, uuid } from '../core/format';

/**
 * The three steps of every payment (ADR-001, decision 6): fill in, confirm, receipt. The Idempotency-Key
 * is born when the confirmation shows and stays while the customer is there: confirming again after a
 * timeout repeats the same payment instead of making a new one.
 */
export type Step<P, R> =
  | { kind: 'form' }
  | { kind: 'confirm'; preview: P; idempotencyKey: string }
  | { kind: 'done'; receipt: R };

export const AMOUNT_ERROR = 'Informe um valor maior que zero.';

/** What every payment flow has: the form, the step, busy and error. */
abstract class PaymentFlow<F, P, R> {
  readonly form: WritableSignal<F>;
  readonly step = signal<Step<P, R>>({ kind: 'form' });
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);

  protected constructor(private readonly empty: () => F) {
    this.form = signal(empty());
  }

  edit(change: Partial<F>): void {
    this.form.update((f) => ({ ...f, ...change }));
    this.error.set(null);
  }

  /** Back to the form to change something: the next confirmation gets a new key. */
  change(): void {
    this.step.set({ kind: 'form' });
    this.error.set(null);
  }

  /** Another payment, from a clean form. */
  reset(): void {
    this.form.set(this.empty());
    this.step.set({ kind: 'form' });
    this.error.set(null);
    this.busy.set(false);
  }

  protected confirmStep(preview: P): void {
    this.step.set({ kind: 'confirm', preview, idempotencyKey: uuid() });
    this.error.set(null);
  }
}

export interface TransferForm {
  branch: string;
  number: string;
  checkDigit: string;
  amountCents: number;
  description: string;
}

export interface TransferPreview {
  destination: TransferDestination;
  amountCents: number;
}

export class TransferFlow extends PaymentFlow<TransferForm, TransferPreview, TransferReceipt> {
  constructor(private readonly api: WalletApi) {
    super(() => ({ branch: '', number: '', checkDigit: '', amountCents: 0, description: '' }));
  }

  /** Checks the amount here and the destination at app-api, then shows the confirmation. Nothing moves yet. */
  async review(): Promise<void> {
    const f = this.form();
    if ([f.branch, f.number, f.checkDigit].some((v) => !v.trim())) {
      this.error.set('Informe agência, conta e dígito de quem recebe.');
      return;
    }
    if (f.amountCents <= 0) {
      this.error.set(AMOUNT_ERROR);
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    try {
      const destination = await this.api.transferDestination(
        f.branch.trim(),
        f.number.trim(),
        f.checkDigit.trim(),
      );
      this.confirmStep({ destination, amountCents: f.amountCents });
    } catch (e) {
      this.error.set(message(e));
    } finally {
      this.busy.set(false);
    }
  }

  async confirm(): Promise<void> {
    const step = this.step();
    if (step.kind !== 'confirm' || this.busy()) return;
    this.busy.set(true);
    this.error.set(null);
    try {
      const d = step.preview.destination;
      const receipt = await this.api.transfer(
        {
          branch: d.branch,
          number: d.number,
          checkDigit: d.checkDigit,
          amountCents: step.preview.amountCents,
          description: this.form().description.trim() || null,
        },
        step.idempotencyKey,
      );
      this.step.set({ kind: 'done', receipt });
    } catch (e) {
      // Stays on the confirmation with the same key: confirming again is a retry, not a second transfer.
      this.error.set(message(e));
    } finally {
      this.busy.set(false);
    }
  }
}

export interface PixForm {
  name: string;
  taxId: string;
  ispb: string;
  branch: string;
  account: string;
  amountCents: number;
  description: string;
}

export interface PixPreview {
  payee: PixPayee;
  amountCents: number;
}

export class PixFlow extends PaymentFlow<PixForm, PixPreview, PixReceipt> {
  constructor(
    private readonly api: WalletApi,
    private readonly pollEveryMs = 1_000,
    private readonly pollTimes = 10,
  ) {
    super(() => ({
      name: '',
      taxId: '',
      ispb: '',
      branch: '',
      account: '',
      amountCents: 0,
      description: '',
    }));
  }

  /** A Pix goes to another institution: nobody to look up, the confirmation repeats what was typed. */
  review(): void {
    const f = this.form();
    const error = [f.name, f.taxId, f.ispb, f.branch, f.account].some((v) => !v.trim())
      ? 'Preencha os dados do recebedor.'
      : !isTaxId(f.taxId)
        ? TAX_ID_ERROR
        : f.amountCents <= 0
          ? AMOUNT_ERROR
          : null;
    if (error) {
      this.error.set(error);
      return;
    }
    const payee: PixPayee = {
      ispb: f.ispb.trim(),
      branch: f.branch.trim(),
      accountNumber: f.account.trim(),
      taxId: f.taxId.trim(),
      name: f.name.trim(),
    };
    this.confirmStep({ payee, amountCents: f.amountCents });
  }

  /**
   * Sends, shows the receipt and follows the Pix for a few seconds: wallet-pix settles it right after
   * answering, so the receipt usually turns from "Enviado" into "Concluído" (or "Não concluído") by itself.
   */
  async confirm(): Promise<void> {
    const step = this.step();
    if (step.kind !== 'confirm' || this.busy()) return;
    this.busy.set(true);
    this.error.set(null);
    let receipt: PixReceipt;
    try {
      receipt = await this.api.sendPix(
        {
          payee: step.preview.payee,
          amountCents: step.preview.amountCents,
          description: this.form().description.trim() || null,
        },
        step.idempotencyKey,
      );
    } catch (e) {
      this.error.set(message(e));
      this.busy.set(false);
      return;
    }
    this.busy.set(false);
    this.step.set({ kind: 'done', receipt });
    for (let i = 0; i < this.pollTimes && receipt.status === 'SENT'; i++) {
      await new Promise((r) => setTimeout(r, this.pollEveryMs));
      const current = this.step();
      if (current.kind !== 'done' || current.receipt.endToEndId !== receipt.endToEndId) return; // left the receipt
      try {
        receipt = await this.api.pixStatus(receipt.endToEndId);
      } catch {
        continue; // the next poll tries again; the receipt keeps what it knows
      }
      this.step.set({ kind: 'done', receipt });
    }
  }
}
