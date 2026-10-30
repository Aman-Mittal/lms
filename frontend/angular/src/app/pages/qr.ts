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
import { Component, effect, input, signal } from '@angular/core';
import { toDataURL } from 'qrcode';

/**
 * A QR code drawn in the browser. The code is made here, from text the page
 * already has, so the backend serves no image and stores none. The payload is
 * also left on the element, which is what a test (or a curious reader of the
 * page) can check without a scanner.
 */
@Component({
  selector: 'app-qr',
  template: `
    @if (failed()) {
      <p class="error" role="alert">Could not draw the QR code.</p>
    } @else if (image(); as src) {
      <img class="qr" [src]="src" [attr.width]="size()" [attr.height]="size()"
           [alt]="'QR code for ' + payload()" data-testid="qr" [attr.data-payload]="payload()" />
    } @else {
      <span class="muted">Preparing QR code…</span>
    }
  `,
})
export class Qr {
  readonly payload = input.required<string>();
  readonly size = input(160);

  readonly image = signal('');
  readonly failed = signal(false);

  constructor() {
    effect(() => {
      const payload = this.payload();
      const size = this.size();
      this.image.set('');
      this.failed.set(false);
      toDataURL(payload, { margin: 1, width: size })
        .then((url) => { if (payload === this.payload()) { this.image.set(url); } })
        .catch(() => this.failed.set(true));
    });
  }
}
