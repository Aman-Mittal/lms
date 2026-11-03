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
import { FormsModule } from '@angular/forms';
import { LmsGateway, PARKED_STATUSES, ParkedEventDetail, ParkedStatus, Row } from '../gateway/lms-gateway';
import { Session } from '../session';
import { RowTable } from './row-table';

/**
 * Events whose listener failed on every retry and were parked. An operator
 * reads what failed, replays it once the cause is fixed, or discards it with
 * a reason. Only the caller's own company's events are listed.
 */
@Component({
  selector: 'app-parked-events',
  imports: [FormsModule, RowTable],
  template: `
    <h1>Parked events</h1>
    <p class="muted">Events whose listener kept failing after every retry. Replay one once its cause is fixed.</p>
    <form class="inline" (ngSubmit)="load()" data-testid="parked-filter">
      <select name="status" aria-label="Status" data-testid="parked-status" [(ngModel)]="filter.status">
        <option value="">Any status</option>
        @for (s of statuses; track s) { <option [value]="s">{{ s }}</option> }
      </select>
      <input name="eventType" placeholder="Event type" aria-label="Event type" data-testid="parked-type"
             [(ngModel)]="filter.eventType" />
      <input name="listener" placeholder="Listener" aria-label="Listener" data-testid="parked-listener"
             [(ngModel)]="filter.listenerId" />
      <button type="submit">Filter</button>
    </form>
    @if (loading()) { <p class="muted">Loading…</p> }
    @if (error()) { <p class="error" role="alert">{{ error() }}</p> }
    @if (!loading() && !error() && rows().length === 0) {
      <p class="muted" data-testid="no-parked">Nothing is parked that matches.</p>
    }
    @if (rows().length > 0) {
      <app-row-table testId="parked" [rows]="rows()" [columns]="columns" [pickable]="true" [picked]="picked()"
                     (pick)="open($event['id'])" />
    }
    @if (detail(); as d) {
      <section data-testid="parked-detail">
        <h2>Parked event {{ d.summary['parkedNo'] }}</h2>
        <dl class="facts">
          <div><dt>Event</dt><dd>{{ d.summary['eventType'] }}</dd></div>
          <div><dt>Listener</dt><dd>{{ d.summary['listenerId'] }}</dd></div>
          <div><dt>Status</dt><dd data-testid="parked-detail-status">{{ d.summary['status'] }}</dd></div>
          <div><dt>Attempts</dt><dd>{{ d.summary['attempts'] }}</dd></div>
          <div><dt>Last failed</dt><dd>{{ d.summary['lastFailedAt'] }}</dd></div>
        </dl>
        <p class="muted" data-testid="parked-error">{{ d.summary['lastError'] }}</p>
        <pre data-testid="parked-payload">{{ d.payload }}</pre>
        @if (outcome()) { <p data-testid="parked-outcome">{{ outcome() }}</p> }
        @if (actionError()) { <p class="error" role="alert">{{ actionError() }}</p> }
        @if (manage() && d.summary['status'] === 'PARKED') {
          <button type="button" data-testid="replay" (click)="replay(d.summary['id'])">Replay</button>
          <form class="inline" (ngSubmit)="discard(d.summary['id'])" data-testid="discard-form">
            <input name="reason" placeholder="Reason" aria-label="Reason to discard" data-testid="discard-reason"
                   [(ngModel)]="reason" required />
            <button type="submit" data-testid="discard">Discard</button>
          </form>
        }
      </section>
    }
  `,
})
export class ParkedEvents implements OnInit {
  private readonly gateway = inject(LmsGateway);
  private readonly session = inject(Session);

  readonly manage = computed(() => this.session.can('EVENT_DEAD_LETTER_MANAGE'));
  readonly statuses = PARKED_STATUSES;
  readonly rows = signal<Row[]>([]);
  readonly loading = signal(true);
  readonly error = signal('');
  readonly picked = signal('');
  readonly detail = signal<ParkedEventDetail | null>(null);
  readonly outcome = signal('');
  readonly actionError = signal('');
  readonly columns = [
    { header: '#', field: 'parkedNo' }, { header: 'Event', field: 'eventType' },
    { header: 'Listener', field: 'listenerId' }, { header: 'Status', field: 'status' },
    { header: 'Attempts', field: 'attempts' }, { header: 'Parked', field: 'parkedAt' },
  ];

  filter = { status: 'PARKED' as ParkedStatus | '', eventType: '', listenerId: '' };
  reason = '';

  ngOnInit(): Promise<void> {
    return this.load();
  }

  async load(): Promise<void> {
    this.loading.set(true);
    this.error.set('');
    this.picked.set('');
    this.detail.set(null);
    try {
      this.rows.set(await this.gateway.parkedEvents({
        status: this.filter.status || undefined, eventType: this.filter.eventType.trim(),
        listenerId: this.filter.listenerId.trim(),
      }));
    } catch (e) {
      this.error.set((e as Error).message);
    } finally {
      this.loading.set(false);
    }
  }

  async open(id: string): Promise<void> {
    this.picked.set(id);
    this.outcome.set('');
    this.actionError.set('');
    try {
      this.detail.set(await this.gateway.parkedEvent(id));
    } catch (e) {
      this.actionError.set((e as Error).message);
    }
  }

  async replay(id: string): Promise<void> {
    this.actionError.set('');
    try {
      const r = await this.gateway.replayParkedEvent(id);
      this.outcome.set(`${r.outcome} after ${r.attempts} attempts`);
      this.detail.set(await this.gateway.parkedEvent(id));
    } catch (e) {
      this.actionError.set((e as Error).message);
    }
  }

  async discard(id: string): Promise<void> {
    this.actionError.set('');
    try {
      await this.gateway.discardParkedEvent(id, this.reason.trim());
      this.outcome.set('DISCARDED');
      this.detail.set(await this.gateway.parkedEvent(id));
    } catch (e) {
      this.actionError.set((e as Error).message);
    }
  }
}
