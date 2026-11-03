/*
 * Copyright 2026 Aman Mittal
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { LmsGateway } from '../gateway/lms-gateway';
import { DEMO_TENANT } from '../config';
import { Session } from '../session';

@Component({
  selector: 'app-login',
  imports: [FormsModule],
  template: `
    <main class="card narrow">
      <h1>LMS demo console</h1>
      <p class="muted">Angular · signs in to the demo tenant on Render.</p>
      <form (ngSubmit)="submit()">
        <label>Tenant <input name="tenant" [(ngModel)]="tenantCode" required /></label>
        <label>Email <input name="email" type="email" [(ngModel)]="email" required /></label>
        <label>Password <input name="password" type="password" [(ngModel)]="password" required /></label>
        <button type="submit" [disabled]="busy()">{{ busy() ? 'Signing in…' : 'Sign in' }}</button>
      </form>
      @if (error()) { <p class="error" role="alert">{{ error() }}</p> }
    </main>
  `,
})
export class LoginPage {
  private readonly gateway = inject(LmsGateway);
  private readonly session = inject(Session);
  private readonly router = inject(Router);

  tenantCode = DEMO_TENANT;
  email = 'admin@demo.local';
  password = '';
  readonly busy = signal(false);
  readonly error = signal('');

  async submit(): Promise<void> {
    this.busy.set(true);
    this.error.set('');
    try {
      this.session.start(await this.gateway.signIn(
        { tenantCode: this.tenantCode, email: this.email, password: this.password }));
      await this.router.navigateByUrl('/');
    } catch (e) {
      this.error.set((e as Error).message);
      this.busy.set(false);
    }
  }
}
