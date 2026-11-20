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

import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiProblem, LmsGateway } from '../gateway/lms-gateway';
import { OrderCloseSettingsView } from '../gateway/small-ops-types';
import { Session } from '../session';
import { ErrorBanner } from './error-banner';

/**
 * The organisation's order close policy, folded away on the order desk until it is wanted: how far short of its
 * quantity an order may be and still close by itself, and whether fulfilled orders close automatically. Changing it
 * belongs to whoever may force-close an order.
 */
@Component({
  selector: 'app-order-close-settings',
  imports: [ErrorBanner, FormsModule],
  template: `
    <details data-testid="close-settings" (toggle)="toggled($event)">
      <summary>Order close policy</summary>
      @if (settings(); as s) {
        <p class="muted" data-testid="close-settings-state">
          Tolerance {{ s.tolerancePercent }}%, fulfilled orders {{ s.autoFulfil ? 'close by themselves' : 'stay open until closed' }}
          ({{ s.customised ? 'set by this organisation' : 'platform default' }}).
        </p>
        @if (canChange()) {
          <form ngNativeValidate class="inline" data-testid="close-settings-form" (submit)="apply($event)">
            <input type="number" min="0" max="100" step="0.01" aria-label="Close tolerance percent"
                   data-testid="close-tolerance" required [value]="tolerance()"
                   (input)="tolerance.set($any($event.target).value)" />
            <label>
              <input type="checkbox" data-testid="close-auto-fulfil" [checked]="autoFulfil()"
                     (change)="autoFulfil.set($any($event.target).checked)" />
              Close fulfilled orders automatically
            </label>
            <button type="submit" data-testid="close-settings-apply">Apply close policy</button>
          </form>
        }
      }
      @if (done()) { <p class="ok" role="status" data-testid="close-settings-done">Close policy updated.</p> }
      @if (error().message) {
        <app-error-banner [message]="error().message" [code]="error().code" testId="close-settings-error" codeTestId="close-settings-refusal" />
      }
    </details>
  `,
})
export class OrderCloseSettings {
  private readonly gateway = inject(LmsGateway);
  private readonly session = inject(Session);

  readonly settings = signal<OrderCloseSettingsView | null>(null);
  readonly tolerance = signal('');
  readonly autoFulfil = signal(false);
  readonly done = signal(false);
  readonly error = signal({ message: '', code: '' });
  readonly canChange = computed(() => this.session.can('ORDER_FORCE_CLOSE'));

  async toggled(event: Event): Promise<void> {
    if ((event.target as HTMLDetailsElement).open && !this.settings()) {
      await this.run(async () => this.show(await this.gateway.orderCloseSettings()));
    }
  }

  async apply(event: Event): Promise<void> {
    event.preventDefault();
    await this.run(async () => {
      this.show(await this.gateway.setOrderCloseSettings(this.tolerance(), this.autoFulfil()));
      this.done.set(true);
    });
  }

  private show(s: OrderCloseSettingsView): void {
    this.settings.set(s);
    this.tolerance.set(s.tolerancePercent);
    this.autoFulfil.set(s.autoFulfil);
  }

  private async run(action: () => Promise<void>): Promise<void> {
    this.done.set(false);
    this.error.set({ message: '', code: '' });
    try {
      await action();
    } catch (e) {
      this.error.set({ message: (e as Error).message, code: e instanceof ApiProblem ? e.code : '' });
    }
  }
}
