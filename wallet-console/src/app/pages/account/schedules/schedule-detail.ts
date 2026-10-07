import { Component, ElementRef, input, output, signal, viewChild } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { ScheduleResponse } from '../../../core/models';
import { attemptLabel, brasiliaTime, canCancel, scheduleTypeLabel, scheduleView } from '../../../core/schedule-labels';

/**
 * Everything the scheduler already returned about one schedule: destination, result, and every
 * attempt with its time and reason - so "why was it not paid?" is answered here, with no new request.
 */
@Component({
  selector: 'app-schedule-detail',
  imports: [DecimalPipe, DatePipe],
  templateUrl: './schedule-detail.html',
  styleUrl: './schedule-detail.scss',
})
export class ScheduleDetail {
  /** Disables the cancel button while a request is in flight. */
  readonly busy = input(false);
  /** The customer asked to cancel this schedule; the parent calls the API. */
  readonly cancelRequested = output<string>();

  readonly selected = signal<ScheduleResponse | null>(null);
  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dialog');

  readonly view = scheduleView;
  readonly typeLabel = scheduleTypeLabel;
  readonly attemptLabel = attemptLabel;
  readonly time = brasiliaTime;
  readonly canCancel = (s: ScheduleResponse) => canCancel(s);

  open(schedule: ScheduleResponse): void {
    this.selected.set(schedule);
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
}
