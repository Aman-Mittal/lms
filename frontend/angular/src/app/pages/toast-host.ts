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

import { Component, OnDestroy, signal } from '@angular/core';
import { currentToasts, dismissToast, subscribeToasts, type ToastMessage } from '../../../../shared/feedback';

/** The success toasts, bottom right; a polite live region, so a screen reader hears each without being interrupted. */
@Component({
  selector: 'app-toast-host',
  template: `
    <div class="toasts" aria-live="polite" aria-atomic="false" data-testid="toasts">
      @for (t of toasts(); track t.id) {
        <div class="toast" data-testid="toast">
          <span>{{ t.text }}</span>
          <button type="button" aria-label="Dismiss" (click)="dismiss(t.id)">&times;</button>
        </div>
      }
    </div>
  `,
})
export class ToastHost implements OnDestroy {
  protected readonly toasts = signal<readonly ToastMessage[]>(currentToasts());
  private readonly stop = subscribeToasts(() => this.toasts.set(currentToasts()));

  protected dismiss(id: number): void {
    dismissToast(id);
  }

  ngOnDestroy(): void {
    this.stop();
  }
}
