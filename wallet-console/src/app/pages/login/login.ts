import { Component, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { extractErrorMessage } from '../../core/http-error.util';
import { ProblemBanner } from '../../shared/problem-banner/problem-banner';

@Component({
  selector: 'app-login',
  imports: [FormsModule, ProblemBanner],
  templateUrl: './login.html',
  styleUrl: './login.scss',
})
export class Login {
  clientId = '';
  clientSecret = '';
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);

  constructor(
    private readonly auth: AuthService,
    private readonly router: Router,
    private readonly route: ActivatedRoute,
  ) {}

  async submit(): Promise<void> {
    this.error.set(null);
    this.loading.set(true);
    try {
      await this.auth.login(this.clientId.trim(), this.clientSecret);
      const returnUrl = this.route.snapshot.queryParamMap.get('returnUrl') ?? '/home';
      await this.router.navigateByUrl(returnUrl);
    } catch (err) {
      this.error.set(extractErrorMessage(err));
    } finally {
      this.loading.set(false);
    }
  }
}
