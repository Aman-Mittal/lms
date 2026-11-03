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
import { IssuedTrackingLink, LmsGateway, TrackingLinkView } from '../gateway/lms-gateway';
import { Session } from '../session';
import { Qr } from './qr';
import { trackingUrl } from './tracking-url';

/** Issue, list, revoke and print the links a customer follows a consignment by. */
@Component({
  selector: 'app-tracking-links',
  imports: [FormsModule, Qr],
  template: `
    <h1>Tracking links</h1>
    <p class="muted">
      Give a customer a link to follow a consignment without signing in. A link's secret is shown once, when it is issued.
    </p>
    @if (!manage()) {
      <p class="muted" data-testid="no-rights">You do not have the right to manage tracking links.</p>
    } @else {
      <!-- A signal, not ngModel: ngModel connects after the screen is shown, and would wipe an id typed before it. -->
      <form class="inline" (ngSubmit)="lookUp()" data-testid="lookup">
        <input name="consignmentId" placeholder="Consignment id" aria-label="Consignment id"
               data-testid="consignment-id" [value]="typed()" (input)="typed.set($any($event.target).value)" required />
        <button type="submit">Look up</button>
      </form>
      @if (loadError()) { <p class="error" role="alert">{{ loadError() }}</p> }
      @if (consignmentId() && loaded()) {
        <section data-testid="links-panel">
          <form class="inline" (ngSubmit)="issue()" data-testid="issue-link-form">
            <label>
              Valid for (days)
              <input name="days" type="number" min="1" max="180" aria-label="Valid for (days)"
                     data-testid="link-days" [(ngModel)]="days" required />
            </label>
            <button type="submit" data-testid="issue-link">Issue a link</button>
          </form>
          @if (error()) { <p class="error" role="alert">{{ error() }}</p> }
          @if (issued(); as i) {
            <section data-testid="issued-link">
              <p>Link issued, valid until {{ i.expiresAt }}. Copy it now: the secret is not shown again.</p>
              <p>
                <input readonly aria-label="Tracking link" data-testid="link-url" [value]="url()" size="60" />
                <button type="button" data-testid="copy-link" (click)="copy()">{{ copied() ? 'Copied' : 'Copy' }}</button>
                <button type="button" class="link" data-testid="print-link" (click)="slip.set(true)">Print slip</button>
              </p>
              @if (slip()) {
                <section class="slip" data-testid="link-slip" aria-label="Tracking link slip">
                  <h3>Follow your shipment</h3>
                  <div class="slip-body">
                    <dl>
                      <div><dt>Open</dt><dd>{{ url() }}</dd></div>
                      <div><dt>Valid until</dt><dd>{{ i.expiresAt }}</dd></div>
                    </dl>
                    <app-qr [payload]="url()" />
                  </div>
                  <p class="actions no-print">
                    <button data-testid="print-now" (click)="print()">Print</button>
                  </p>
                </section>
              }
            </section>
          }
          @if (links().length === 0) {
            <p class="muted" data-testid="no-links">This consignment has no tracking links.</p>
          } @else {
            <table data-testid="links-table">
              <thead>
                <tr><th>Status</th><th>Created</th><th>Expires</th><th>Revoked</th><th></th></tr>
              </thead>
              <tbody>
                @for (l of links(); track l.id) {
                  <tr data-testid="link-row">
                    <td data-testid="link-status">{{ l.status }}</td>
                    <td>{{ l.createdAt }}</td>
                    <td>{{ l.expiresAt }}</td>
                    <td>{{ l.revokedAt || '—' }}</td>
                    <td>
                      @if (l.status === 'ACTIVE') {
                        <button class="link" data-testid="revoke-link" (click)="revoke(l.id)">Revoke</button>
                      }
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          }
        </section>
      }
    }
  `,
})
export class TrackingLinks {
  private readonly gateway = inject(LmsGateway);
  private readonly session = inject(Session);

  readonly manage = computed(() => this.session.can('TRACKING_LINK_MANAGE'));
  readonly consignmentId = signal('');
  readonly links = signal<TrackingLinkView[]>([]);
  readonly loaded = signal(false);
  readonly loadError = signal('');
  readonly error = signal('');
  readonly issued = signal<IssuedTrackingLink | null>(null);
  readonly slip = signal(false);
  readonly copied = signal(false);
  readonly url = computed(() => {
    const i = this.issued();
    return i ? trackingUrl(i.token) : '';
  });

  readonly typed = signal('');
  days = 30;

  async lookUp(): Promise<void> {
    this.consignmentId.set(this.typed().trim());
    this.issued.set(null);
    this.slip.set(false);
    await this.refresh();
  }

  async issue(): Promise<void> {
    this.error.set('');
    this.slip.set(false);
    this.copied.set(false);
    try {
      this.issued.set(await this.gateway.issueTrackingLink(this.consignmentId(), Number(this.days)));
      await this.refresh();
    } catch (e) {
      this.error.set((e as Error).message);
    }
  }

  async revoke(id: string): Promise<void> {
    this.error.set('');
    try {
      await this.gateway.revokeTrackingLink(id);
      await this.refresh();
    } catch (e) {
      this.error.set((e as Error).message);
    }
  }

  async copy(): Promise<void> {
    await navigator.clipboard?.writeText(this.url()).catch(() => undefined);
    this.copied.set(true);
  }

  print(): void {
    window.print();
  }

  private async refresh(): Promise<void> {
    this.loadError.set('');
    try {
      this.links.set(await this.gateway.trackingLinks(this.consignmentId()));
      this.loaded.set(true);
    } catch (e) {
      this.loaded.set(false);
      this.loadError.set((e as Error).message);
    }
  }
}
