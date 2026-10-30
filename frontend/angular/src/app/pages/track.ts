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
import { ActivatedRoute } from '@angular/router';
import { LmsGateway, PublicTrackingView } from '../gateway/lms-gateway';

/**
 * Where a customer lands from a tracking link. It needs no sign-in, shows the
 * small view the public route returns, and says the same thing whether the link
 * never existed, has expired or was revoked: the server does not tell them apart.
 */
@Component({
  selector: 'app-track',
  template: `
    <main class="public">
      <h1>Track your shipment</h1>
      @if (loading()) { <p class="muted">Loading…</p> }
      @if (error()) { <p class="error" role="alert">{{ error() }}</p> }
      @if (!loading() && !error() && view() === null) {
        <p data-testid="track-not-found" role="alert">
          This tracking link is not valid. It may have expired or been withdrawn.
        </p>
      }
      @if (view(); as v) {
        <section data-testid="track-view">
          <dl>
            <div><dt>Reference</dt><dd data-testid="track-reference">{{ v.reference }}</dd></div>
            <div><dt>Status</dt><dd data-testid="track-status">{{ v.status }}</dd></div>
            <div><dt>From</dt><dd data-testid="track-origin">{{ v.originName || '—' }}</dd></div>
            <div><dt>To</dt><dd data-testid="track-destination">{{ v.destinationName || '—' }}</dd></div>
            <div><dt>Requested delivery</dt><dd>{{ v.requestedDeliveryAt || '—' }}</dd></div>
            <div><dt>Delivered</dt><dd>{{ v.deliveredAt || '—' }}</dd></div>
            <div>
              <dt>Receipt confirmed</dt>
              <dd data-testid="track-proof">{{ v.proofRecorded ? 'Yes' : 'No' }}</dd>
            </div>
            @if (v.position; as p) {
              <div>
                <dt>Last seen</dt>
                <dd data-testid="track-position">{{ p.lat }}, {{ p.lon }} ({{ p.recordedAt }})</dd>
              </div>
            }
          </dl>
        </section>
      }
    </main>
  `,
})
export class Track {
  private readonly gateway = inject(LmsGateway);
  private readonly route = inject(ActivatedRoute);

  readonly view = signal<PublicTrackingView | null>(null);
  readonly loading = signal(true);
  readonly error = signal('');

  constructor() {
    const token = this.route.snapshot.paramMap.get('token') ?? '';
    this.gateway.publicTracking(token)
      .then((v) => this.view.set(v))
      .catch((e: Error) => this.error.set(e.message))
      .finally(() => this.loading.set(false));
  }
}
