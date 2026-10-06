import { Component, ElementRef, input, signal, viewChild } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { EntryResponse } from '../../../core/models';
import { reasonLabel, transactionLabel } from '../../../core/transaction-labels';

/**
 * Popup with everything the statement already has about one entry (ids, description, Pix detail).
 * It never calls the API: the statement page loaded it all, and the list stays short because of it.
 */
@Component({
  selector: 'app-entry-detail',
  imports: [RouterLink, DecimalPipe, DatePipe],
  templateUrl: './entry-detail.html',
  styleUrl: './entry-detail.scss',
})
export class EntryDetail {
  /** The entries loaded so far, to jump from a refund or return to its original Pix. */
  readonly entries = input<EntryResponse[]>([]);

  readonly selected = signal<EntryResponse | null>(null);
  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dialog');

  open(entry: EntryResponse): void {
    this.selected.set(entry);
    const dialog = this.dialog().nativeElement;
    if (!dialog.open) dialog.showModal();
  }

  close(): void {
    this.dialog().nativeElement.close();
  }

  /** A click on the dialog element itself (not its content) is a click on the backdrop. */
  closeOnBackdrop(event: MouseEvent): void {
    if (event.target === this.dialog().nativeElement) this.close();
  }

  typeLabel(type: string): string {
    return transactionLabel(type);
  }

  reason(code: string): string | null {
    return reasonLabel(code);
  }

  /** This account's entry of the given transaction, when it is among the entries already loaded. */
  loadedEntry(transactionId: string): EntryResponse | undefined {
    return this.entries().find((e) => e.transactionId === transactionId);
  }
}
