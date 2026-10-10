import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/**
 * The M-Wall brand (ADR-001, decision 8): the mark - a rounded "M" over an orange tile whose top band is the
 * wallet's flap, and a coin - with the wordmark beside it. Same drawing as public/brand/m-wall-mark.svg.
 */
@Component({
  selector: 'mw-logo',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <svg [attr.width]="size()" [attr.height]="size()" viewBox="0 0 96 96" aria-hidden="true">
      <defs>
        <linearGradient [attr.id]="gradientId" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stop-color="#FF9A4D" />
          <stop offset="1" stop-color="#E0520B" />
        </linearGradient>
      </defs>
      <rect width="96" height="96" rx="26" [attr.fill]="'url(#' + gradientId + ')'" />
      <path
        d="M0 26A26 26 0 0 1 26 0h44a26 26 0 0 1 26 26v3c-13 7-30 10-48 10S13 36 0 29z"
        fill="#fff"
        opacity=".14"
      />
      <path
        d="M25 69V35l21 20 21-20v34"
        fill="none"
        stroke="#fff"
        stroke-width="10"
        stroke-linecap="round"
        stroke-linejoin="round"
      />
      <circle cx="79" cy="69" r="6" fill="#FFE2C7" />
    </svg>
    @if (wordmark()) {
      <span class="word" [style.font-size.px]="size() * 0.5">M<span class="dash">-</span>Wall</span>
    }
    <span class="sr-only">M-Wall</span>
  `,
  styles: `
    :host {
      display: inline-flex;
      align-items: center;
      gap: 10px;
    }
    .word {
      font-weight: 800;
      letter-spacing: -0.03em;
      line-height: 1;
      color: var(--mw-text);
    }
    :host(.on-brand) .word {
      color: #fff;
    }
    .dash {
      color: var(--mw-orange);
    }
    :host(.on-brand) .dash {
      color: #ffe2c7;
    }
  `,
})
export class Logo {
  readonly size = input(40);
  readonly wordmark = input(true);
  /** Unique per instance: two logos on one page must not share a gradient id. */
  protected readonly gradientId = `mw-g-${Math.random().toString(36).slice(2, 8)}`;
}
