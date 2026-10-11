import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { DayBalance } from '../core/contract';
import { date, money } from '../core/format';

const W = 320;
const H = 96;
const PAD = 4;

/**
 * How the balance moved day by day (wallet-core's ADR-012): a line over the closing balances, drawn in SVG
 * with no library. Screen readers get the same thing in one sentence.
 */
@Component({
  selector: 'mw-balance-chart',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (days().length > 1) {
      <figure class="chart">
        <svg
          [attr.viewBox]="'0 0 ' + W + ' ' + H"
          preserveAspectRatio="none"
          role="img"
          [attr.aria-label]="summary()"
        >
          <path class="area" [attr.d]="area()" />
          <path class="line" [attr.d]="line()" />
          <circle class="dot" [attr.cx]="lastPoint().x" [attr.cy]="lastPoint().y" r="3.5" />
        </svg>
        <figcaption class="axis num" aria-hidden="true">
          <span>{{ date(days()[0].date) }}</span>
          <span>{{ date(days()[days().length - 1].date) }}</span>
        </figcaption>
      </figure>
    }
  `,
  styles: `
    .chart {
      margin: 0;
      display: flex;
      flex-direction: column;
      gap: 6px;
    }
    svg {
      width: 100%;
      height: 96px;
      overflow: visible;
    }
    .line {
      fill: none;
      stroke: var(--mw-orange);
      stroke-width: 2.5;
      stroke-linejoin: round;
      stroke-linecap: round;
      vector-effect: non-scaling-stroke;
    }
    .area {
      fill: var(--mw-orange-glow);
      stroke: none;
    }
    .dot {
      fill: var(--mw-orange);
      stroke: var(--mw-surface);
      stroke-width: 2;
      vector-effect: non-scaling-stroke;
    }
    .axis {
      display: flex;
      justify-content: space-between;
      font-size: 0.75rem;
      color: var(--mw-text-2);
    }
  `,
})
export class BalanceChart {
  readonly days = input.required<DayBalance[]>();
  protected readonly W = W;
  protected readonly H = H;
  protected readonly date = date;

  /** The points, scaled to the box. A flat period sits in the middle instead of on the floor. */
  private readonly points = computed(() => {
    const days = this.days();
    const values = days.map((d) => d.balanceCents);
    const min = Math.min(...values);
    const max = Math.max(...values);
    const span = max - min;
    return days.map((d, i) => ({
      x: PAD + (i * (W - 2 * PAD)) / Math.max(1, days.length - 1),
      y: span === 0 ? H / 2 : PAD + ((max - d.balanceCents) * (H - 2 * PAD)) / span,
    }));
  });

  protected readonly line = computed(() =>
    this.points()
      .map((p, i) => `${i === 0 ? 'M' : 'L'}${p.x.toFixed(1)} ${p.y.toFixed(1)}`)
      .join(' '),
  );

  protected readonly area = computed(() => {
    const pts = this.points();
    return `${this.line()} L${pts[pts.length - 1].x.toFixed(1)} ${H} L${pts[0].x.toFixed(1)} ${H} Z`;
  });

  protected readonly lastPoint = computed(() => this.points()[this.points().length - 1]);

  protected readonly summary = computed(() => {
    const d = this.days();
    const first = d[0];
    const last = d[d.length - 1];
    const max = d.reduce((a, b) => (b.balanceCents > a.balanceCents ? b : a));
    return (
      `Saldo no fim de cada dia: ${money(first.balanceCents)} em ${date(first.date)}, ` +
      `${money(last.balanceCents)} em ${date(last.date)}; o maior foi ${money(max.balanceCents)} em ${date(max.date)}.`
    );
  });
}
