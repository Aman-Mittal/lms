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

import { Component, OnInit, computed, inject, signal } from '@angular/core';
import {
  LmsGateway, Row, WEBHOOK_EVENT_TYPES, WebhookEventType, WebhookSecret, WebhookView,
} from '../gateway/lms-gateway';
import { Session } from '../session';
import { RowTable } from './row-table';

/** A subscription as a table row. */
function rowOf(w: WebhookView): Row {
  const state = !w.active ? (w.pausedReason ? 'Paused' : 'Inactive') : 'Active';
  return { id: w.id, name: w.name, url: w.url, eventTypes: w.eventTypes.join(', '), state };
}

/** What an `(input)` or `(change)` event carries, read without a cast in the template. */
function valueOf(event: Event): string {
  return (event.target as HTMLInputElement).value;
}

/**
 * Where a company's systems are told about orders, trips, deliveries and
 * claims. The signing secret is shown once, in the panel that issued it, and
 * nowhere else: the screens never read it back because the API never returns it.
 *
 * The text inputs bind through signals rather than `ngModel`: without zone.js
 * `ngModel` writes its first value on a later pass and can wipe what was typed
 * before it.
 */
@Component({
  selector: 'app-webhooks',
  imports: [RowTable],
  template: `
    <h1>Webhooks</h1>
    <p class="muted">Addresses your systems listen on, told about orders, trips, deliveries and claims as they happen.</p>
    @if (secret(); as s) {
      <section class="notice" data-testid="webhook-secret">
        <h2>Signing secret</h2>
        <p>Store it now: it cannot be shown again. If it is lost, rotate it.</p>
        <code data-testid="secret-value">{{ s.secret }}</code>
        <button type="button" data-testid="copy-secret" (click)="copy(s.secret)">Copy</button>
        <button type="button" data-testid="secret-done" (click)="secret.set(null)">I have stored it</button>
      </section>
    }
    @if (loading()) { <p class="muted">Loading…</p> }
    @if (error()) { <p class="error" role="alert">{{ error() }}</p> }
    @if (!loading() && !error() && hooks().length === 0) {
      <p class="muted" data-testid="no-webhooks">No subscriptions yet.</p>
    }
    @if (hooks().length > 0) {
      <app-row-table testId="webhooks" [rows]="rows()" [columns]="columns" [pickable]="true" [picked]="picked()"
                     (pick)="open($event['id'])" />
    }

    @if (manage() && !current()) {
      <form (submit)="subscribe($event)" data-testid="subscribe-form">
        <h2>Subscribe an address</h2>
        <input placeholder="Name" aria-label="Name" data-testid="webhook-name" required
               [value]="name()" (input)="name.set(valueOf($event))" />
        <input placeholder="https://…" aria-label="Address" data-testid="webhook-url" required
               [value]="url()" (input)="url.set(valueOf($event))" />
        <fieldset data-testid="event-types">
          <legend>Events</legend>
          @for (t of eventTypes; track t) {
            <label>
              <input type="checkbox" [attr.data-testid]="'type-' + t" [checked]="types().includes(t)"
                     (change)="toggle(t, $any($event.target).checked)" />
              {{ t }}
            </label>
          }
        </fieldset>
        @if (formError()) { <p class="error" role="alert">{{ formError() }}</p> }
        <button type="submit" data-testid="subscribe">Subscribe</button>
      </form>
    }

    @if (current(); as w) {
      <section data-testid="webhook-detail">
        <h2>{{ w.name }}</h2>
        <button type="button" class="link" (click)="close()">Back to subscribing</button>
        @if (w.pausedReason) { <p class="error" data-testid="paused-reason">Paused: {{ w.pausedReason }}</p> }
        @if (manage()) {
          <form (submit)="save($event, w.id)" data-testid="revise-form">
            <input aria-label="Name" data-testid="edit-name" required [value]="name()" (input)="name.set(valueOf($event))" />
            <input aria-label="Address" data-testid="edit-url" required [value]="url()" (input)="url.set(valueOf($event))" />
            <fieldset data-testid="event-types">
              <legend>Events</legend>
              @for (t of eventTypes; track t) {
                <label>
                  <input type="checkbox" [attr.data-testid]="'type-' + t" [checked]="types().includes(t)"
                         (change)="toggle(t, $any($event.target).checked)" />
                  {{ t }}
                </label>
              }
            </fieldset>
            <label>
              <input type="checkbox" data-testid="edit-active" [checked]="active()"
                     (change)="active.set($any($event.target).checked)" />
              Active
            </label>
            <button type="submit" data-testid="save-webhook">Save</button>
            <button type="button" data-testid="rotate-secret" (click)="rotate(w.id)">Rotate secret</button>
            <button type="button" data-testid="ping" (click)="ping(w.id)">Send test ping</button>
          </form>
        } @else {
          <dl class="facts">
            <div><dt>Address</dt><dd>{{ w.url }}</dd></div>
            <div><dt>Events</dt><dd>{{ w.eventTypes.join(', ') }}</dd></div>
            <div><dt>Active</dt><dd>{{ w.active ? 'Yes' : 'No' }}</dd></div>
          </dl>
        }
        @if (message()) { <p data-testid="webhook-message">{{ message() }}</p> }
        @if (formError()) { <p class="error" role="alert">{{ formError() }}</p> }

        <section data-testid="webhook-deliveries">
          <h3>Deliveries</h3>
          <button type="button" data-testid="refresh-deliveries" (click)="loadDeliveries(w.id)">Refresh</button>
          @if (deliveryError()) { <p class="error" role="alert">{{ deliveryError() }}</p> }
          @if (deliveriesLoaded() && deliveries().length === 0) {
            <p class="muted" data-testid="no-deliveries">Nothing sent yet.</p>
          }
          @if (deliveries().length > 0) {
            <app-row-table testId="deliveries" [rows]="deliveries()" [columns]="deliveryColumns" [pickable]="true"
                           [picked]="pickedDelivery()" (pick)="pickedDelivery.set($event['id'])" />
          }
          @if (cursor()) { <button type="button" data-testid="more-deliveries" (click)="more(w.id)">Load more</button> }
          @if (manage() && pickedDelivery()) {
            <button type="button" data-testid="redeliver" (click)="redeliver(w.id)">Redeliver</button>
          }
        </section>
      </section>
    }

    <details data-testid="signature-help">
      <summary>Verifying a delivery</summary>
      <p>Every delivery is a POST of a JSON envelope with these headers:</p>
      <ul>
        <li><code>X-Lms-Event</code>: the event type.</li>
        <li><code>X-Lms-Delivery</code>: the delivery id, the same on every retry. Use it to ignore duplicates.</li>
        <li><code>X-Lms-Timestamp</code>: seconds since the epoch. Reject one more than a few minutes from your clock.</li>
        <li><code>X-Lms-Signature</code>: <code>v1=</code> and the hex HMAC-SHA256 of <code>timestamp.body</code>, keyed with the secret.</li>
      </ul>
      <p>Compute the HMAC over the raw body exactly as received, and compare in constant time. Answer with any 2xx; anything else is retried.</p>
    </details>
  `,
})
export class Webhooks implements OnInit {
  private readonly gateway = inject(LmsGateway);
  private readonly session = inject(Session);

  readonly valueOf = valueOf;
  readonly eventTypes = WEBHOOK_EVENT_TYPES;
  readonly manage = computed(() => this.session.can('WEBHOOK_MANAGE'));
  readonly hooks = signal<WebhookView[]>([]);
  readonly rows = computed(() => this.hooks().map(rowOf));
  readonly loading = signal(true);
  readonly error = signal('');
  readonly picked = signal('');
  readonly current = computed(() => this.hooks().find((w) => w.id === this.picked()) ?? null);
  readonly secret = signal<WebhookSecret | null>(null);

  readonly name = signal('');
  readonly url = signal('');
  readonly types = signal<WebhookEventType[]>([]);
  readonly active = signal(true);
  readonly message = signal('');
  readonly formError = signal('');

  readonly deliveries = signal<Row[]>([]);
  readonly deliveriesLoaded = signal(false);
  readonly cursor = signal<string | null>(null);
  readonly pickedDelivery = signal('');
  readonly deliveryError = signal('');
  readonly columns = [
    { header: 'Name', field: 'name' }, { header: 'Address', field: 'url' },
    { header: 'Events', field: 'eventTypes' }, { header: 'State', field: 'state' },
  ];
  readonly deliveryColumns = [
    { header: 'Event', field: 'eventType' }, { header: 'Status', field: 'status' },
    { header: 'Attempts', field: 'attempts' }, { header: 'Last code', field: 'lastStatusCode' },
    { header: 'Last error', field: 'lastError' }, { header: 'Next attempt', field: 'nextAttemptAt' },
    { header: 'Delivered', field: 'deliveredAt' }, { header: 'Created', field: 'createdAt' },
  ];

  ngOnInit(): Promise<void> {
    return this.load();
  }

  async load(): Promise<void> {
    this.loading.set(true);
    this.error.set('');
    try {
      this.hooks.set(await this.gateway.webhooks());
    } catch (e) {
      this.error.set((e as Error).message);
    } finally {
      this.loading.set(false);
    }
  }

  toggle(type: WebhookEventType, on: boolean): void {
    this.types.update((t) => (on ? [...t, type] : t.filter((x) => x !== type)));
  }

  copy(secret: string): void {
    navigator.clipboard?.writeText(secret).catch(() => undefined);
  }

  async subscribe(event: Event): Promise<void> {
    event.preventDefault();
    this.formError.set('');
    try {
      const issued = await this.gateway.subscribeWebhook({
        name: this.name().trim(), url: this.url().trim(), eventTypes: this.types(),
      });
      this.secret.set(issued);
      await this.load();
      await this.open(issued.id);
    } catch (e) {
      this.formError.set((e as Error).message);
    }
  }

  async open(id: string): Promise<void> {
    this.picked.set(id);
    const w = this.current();
    this.name.set(w?.name ?? '');
    this.url.set(w?.url ?? '');
    this.types.set((w?.eventTypes ?? []) as WebhookEventType[]);
    this.active.set(w?.active ?? true);
    this.message.set('');
    this.formError.set('');
    await this.loadDeliveries(id);
  }

  close(): void {
    this.picked.set('');
    this.name.set('');
    this.url.set('');
    this.types.set([]);
    this.formError.set('');
  }

  async save(event: Event, id: string): Promise<void> {
    event.preventDefault();
    await this.act(async () => {
      await this.gateway.reviseWebhook(id, {
        name: this.name().trim(), url: this.url().trim(), eventTypes: this.types(), active: this.active(),
      });
      await this.load();
      return 'Saved.';
    });
  }

  async rotate(id: string): Promise<void> {
    await this.act(async () => {
      this.secret.set(await this.gateway.rotateWebhookSecret(id));
    });
  }

  async ping(id: string): Promise<void> {
    await this.act(async () => {
      await this.gateway.pingWebhook(id);
      await this.loadDeliveries(id);
      return 'Test ping queued.';
    });
  }

  async loadDeliveries(id: string): Promise<void> {
    this.deliveryError.set('');
    this.pickedDelivery.set('');
    try {
      const page = await this.gateway.webhookDeliveries(id);
      this.deliveries.set(page.rows);
      this.cursor.set(page.nextCursor);
      this.deliveriesLoaded.set(true);
    } catch (e) {
      this.deliveryError.set((e as Error).message);
    }
  }

  async more(id: string): Promise<void> {
    const cursor = this.cursor();
    if (!cursor) {
      return;
    }
    try {
      const page = await this.gateway.webhookDeliveries(id, cursor);
      this.deliveries.update((r) => [...r, ...page.rows]);
      this.cursor.set(page.nextCursor);
    } catch (e) {
      this.deliveryError.set((e as Error).message);
    }
  }

  async redeliver(id: string): Promise<void> {
    this.deliveryError.set('');
    try {
      await this.gateway.redeliverWebhook(this.pickedDelivery());
      await this.loadDeliveries(id);
    } catch (e) {
      this.deliveryError.set((e as Error).message);
    }
  }

  private async act(action: () => Promise<string | void>): Promise<void> {
    this.formError.set('');
    this.message.set('');
    try {
      const said = await action();
      if (said) {
        this.message.set(said);
      }
    } catch (e) {
      this.formError.set((e as Error).message);
    }
  }
}
