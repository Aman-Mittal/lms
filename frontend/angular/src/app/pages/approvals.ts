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

import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  APPROVAL_SCOPES, APPROVAL_STATUSES, ApprovalDetail, ApprovalScopeName, ApprovalStatusName, LmsGateway, Row,
} from '../gateway/lms-gateway';
import { RowTable } from './row-table';

const SCOPE_LABELS: Record<ApprovalScopeName, string> = {
  MINE_TO_DECIDE: 'Mine to decide', REQUESTED_BY_ME: 'Requested by me', ALL: 'Everything',
};

/**
 * The approvals inbox: every request waiting for a person to agree -- an
 * order's cancellation, a proof of delivery's reversal -- in one list. Open
 * one to see what approving will do, the policy it is decided under and its
 * trail, then approve, reject with a reason, or (as its requester) cancel it.
 * The server decides who may: the buttons are offered to whoever it says
 * could decide now.
 */
@Component({
  selector: 'app-approvals',
  imports: [FormsModule, RowTable],
  template: `
    <h1>Approvals</h1>
    <p class="muted">Requests waiting for somebody to agree before anything happens.</p>
    <form class="inline" (ngSubmit)="load()" data-testid="approvals-filter">
      <select name="scope" aria-label="Scope" data-testid="approvals-scope" [(ngModel)]="filter.scope">
        @for (s of scopes; track s) { <option [value]="s">{{ labels[s] }}</option> }
      </select>
      <select name="status" aria-label="Status" data-testid="approvals-status" [(ngModel)]="filter.status">
        <option value="">Any status</option>
        @for (s of statuses; track s) { <option [value]="s">{{ s }}</option> }
      </select>
      <input name="subjectType" placeholder="Subject type" aria-label="Subject type"
             data-testid="approvals-subject-type" [(ngModel)]="filter.subjectType" />
      <button type="submit">Filter</button>
    </form>
    @if (loading()) { <p class="muted">Loading…</p> }
    @if (error()) { <p class="error" role="alert">{{ error() }}</p> }
    @if (!loading() && !error() && rows().length === 0) {
      <p class="muted" data-testid="no-approvals">Nothing matches.</p>
    }
    @if (rows().length > 0) {
      <app-row-table testId="approvals" [rows]="rows()" [columns]="columns" [pickable]="true" [picked]="picked()"
                     (pick)="open($event['id'])" />
    }
    @if (detail(); as d) {
      <section data-testid="approval-detail">
        <h2>{{ d.summary['summary'] }}</h2>
        <dl class="facts">
          <div><dt>Subject</dt><dd>{{ d.summary['subjectType'] }} {{ d.summary['action'] }}</dd></div>
          <div><dt>Status</dt><dd data-testid="approval-status">{{ d.summary['status'] }}</dd></div>
          <div><dt>Approvals</dt><dd>{{ d.summary['approvals'] }}</dd></div>
          <div><dt>Requested</dt><dd>{{ d.summary['requestedAt'] }}</dd></div>
          <div><dt>Due</dt><dd>{{ d.summary['dueAt'] }}</dd></div>
          @if (d.summary['reason']) {
            <div><dt>Reason</dt><dd data-testid="approval-reason">{{ d.summary['reason'] }}</dd></div>
          }
        </dl>
        <h3>What approving will do</h3>
        <pre data-testid="approval-payload">{{ d.payload }}</pre>
        <p class="muted" data-testid="approval-policy">Policy: {{ d.policy }}</p>
        <h3>Trail</h3>
        @if (d.trail.length === 0) {
          <p class="muted" data-testid="approval-no-trail">No decision yet.</p>
        } @else {
          <app-row-table testId="approval-trail" [rows]="d.trail" [columns]="trailColumns" />
        }
        @if (outcome()) { <p data-testid="approval-outcome">{{ outcome() }}</p> }
        @if (actionError()) { <p class="error" role="alert">{{ actionError() }}</p> }
        @if (d.open && d.mineToDecide) {
          <form class="inline" (ngSubmit)="approve(d)" data-testid="approve-form">
            <input name="note" placeholder="Note (optional)" aria-label="Note" data-testid="approve-note"
                   [(ngModel)]="note" />
            <button type="submit" data-testid="approve">Approve</button>
          </form>
          <form class="inline" (ngSubmit)="reject(d)" data-testid="reject-form">
            <input name="reason" placeholder="Reason" aria-label="Reason to reject" data-testid="reject-reason"
                   [(ngModel)]="reason" required />
            <button type="submit" data-testid="reject">Reject</button>
          </form>
        }
        @if (d.open && d.requestedByMe) {
          <button type="button" data-testid="cancel-approval" (click)="cancel(d)">Cancel my request</button>
        }
      </section>
    }
  `,
})
export class Approvals implements OnInit {
  private readonly gateway = inject(LmsGateway);

  readonly scopes = APPROVAL_SCOPES;
  readonly statuses = APPROVAL_STATUSES;
  readonly labels = SCOPE_LABELS;
  readonly rows = signal<Row[]>([]);
  readonly loading = signal(true);
  readonly error = signal('');
  readonly picked = signal('');
  readonly detail = signal<ApprovalDetail | null>(null);
  readonly outcome = signal('');
  readonly actionError = signal('');
  readonly columns = [
    { header: 'Subject', field: 'subjectType' }, { header: 'Summary', field: 'summary' },
    { header: 'Status', field: 'status' }, { header: 'Approvals', field: 'approvals' },
    { header: 'Waiting for', field: 'waitingFor' }, { header: 'Requested', field: 'requestedAt' },
    { header: 'Due', field: 'dueAt' },
  ];
  readonly trailColumns = [
    { header: 'Decision', field: 'decision' }, { header: 'Step', field: 'step' },
    { header: 'Under', field: 'permission' }, { header: 'By', field: 'decidedBy' },
    { header: 'At', field: 'decidedAt' }, { header: 'Note', field: 'note' },
  ];

  filter = { scope: 'MINE_TO_DECIDE' as ApprovalScopeName, status: '' as ApprovalStatusName | '', subjectType: '' };
  note = '';
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
      this.rows.set(await this.gateway.approvals({
        scope: this.filter.scope, status: this.filter.status || undefined,
        subjectType: this.filter.subjectType.trim() || undefined,
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
      this.detail.set(await this.gateway.approval(id));
    } catch (e) {
      this.actionError.set((e as Error).message);
    }
  }

  approve(d: ApprovalDetail): Promise<void> {
    return this.act('Approved', d, () => this.gateway.approveApproval(d.summary['id'], d.version, this.note.trim()));
  }

  reject(d: ApprovalDetail): Promise<void> {
    return this.act('Rejected', d, () => this.gateway.rejectApproval(d.summary['id'], d.version, this.reason.trim()));
  }

  cancel(d: ApprovalDetail): Promise<void> {
    return this.act('Cancelled', d, () => this.gateway.cancelApproval(d.summary['id'], d.version, ''));
  }

  private async act(what: string, d: ApprovalDetail, call: () => Promise<void>): Promise<void> {
    this.actionError.set('');
    try {
      await call();
      this.outcome.set(what);
      this.detail.set(await this.gateway.approval(d.summary['id']));
    } catch (e) {
      this.actionError.set((e as Error).message);
    }
  }
}
