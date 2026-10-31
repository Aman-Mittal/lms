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
import { DemoResetOutcome, LmsGateway } from '../gateway/lms-gateway';
import { Session } from '../session';

/** Puts the demo back to its seeded state. Two clicks on purpose: the first only asks. */
@Component({
  selector: 'app-demo-settings',
  imports: [FormsModule],
  template: `
    @if (!session.can('DEMO_RESET')) {
      <p class="muted" data-testid="demo-reset-forbidden">You cannot reset the demo.</p>
    } @else {
      <h1>Demo data</h1>
      <p class="muted">
        Deletes everything in the demo -- orders, trips, partners, custom fields, users -- and seeds it
        again: one administrator with the same password. Anything you built here is lost.
      </p>
      @if (error()) { <p class="error" role="alert">{{ error() }}</p> }
      @if (step() === 'idle') {
        <p><button data-testid="demo-reset" (click)="step.set('confirming')">
          Reset demo data
        </button></p>
      }
      @if (step() === 'confirming' || step() === 'working') {
        <section data-testid="demo-reset-confirm" aria-label="Confirm the reset">
          <p><strong>Reset the whole demo?</strong> This cannot be undone. Type the demo's company
            code to confirm.</p>
          <p class="actions">
            <input name="code" aria-label="Company code" placeholder="demo" data-testid="demo-reset-code"
                   [(ngModel)]="typed" />
            <button data-testid="demo-reset-yes" [disabled]="step() === 'working' || !typed.trim()"
                    (click)="reset()">Yes, reset everything</button>
            <button class="link" data-testid="demo-reset-cancel" [disabled]="step() === 'working'"
                    (click)="step.set('idle'); typed = ''">Cancel</button>
          </p>
        </section>
      }
      @if (step() === 'done' && outcome(); as o) {
        <section data-testid="demo-reset-done">
          <p>The demo is back to its seeded state: {{ o.rowsDeleted }} rows deleted.</p>
          @if (o.signInAgain) {
            <p>
              Your user was recreated, so sign in again with the same password.
              <button data-testid="demo-reset-sign-in" (click)="signInAgain()">Sign in again</button>
            </p>
          }
        </section>
      }
    }
  `,
})
export class DemoSettings {
  private readonly gateway = inject(LmsGateway);
  private readonly router = inject(Router);
  protected readonly session = inject(Session);

  readonly step = signal<'idle' | 'confirming' | 'working' | 'done'>('idle');
  readonly outcome = signal<DemoResetOutcome | null>(null);
  readonly error = signal('');
  typed = '';

  async reset(): Promise<void> {
    this.step.set('working');
    this.error.set('');
    try {
      this.outcome.set(await this.gateway.resetDemoData(this.typed.trim()));
      this.step.set('done');
    } catch (e) {
      this.error.set((e as Error).message);
      this.step.set('idle');
    }
  }

  signInAgain(): void {
    this.session.end();
    this.router.navigateByUrl('/login');
  }
}
