import { Component, input } from '@angular/core';

@Component({
  selector: 'app-problem-banner',
  templateUrl: './problem-banner.html',
  styleUrl: './problem-banner.scss',
})
export class ProblemBanner {
  readonly message = input<string | null>(null);
}
