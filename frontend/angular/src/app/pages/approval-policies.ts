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
import { ApiProblem, LmsGateway, Row } from '../gateway/lms-gateway';
import { APPROVAL_EXPIRIES, ApprovalPolicyDraft, ApprovalPolicyRow } from '../gateway/small-ops-types';
import { Session } from '../session';
import { ErrorBanner } from './error-banner';
import { RowColumn, RowTable } from './row-table';

const COLUMNS: RowColumn[] = [
  { header: 'Subject', field: 'subjectType' }, { header: 'Approval asked', field: 'required' },
  { header: 'Decided by', field: 'steps' }, { header: 'In order', field: 'ordered' },
  { header: 'Self-approval', field: 'self' }, { header: 'Open for (min)', field: 'due' },
  { header: 'On expiry', field: 'onExpiry' },
];

function draftOf(p: ApprovalPolicyRow): ApprovalPolicyDraft {
  return {
    required: p.required, decidePermissions: p.decidePermissions.join(', '), ordered: p.ordered,
    allowSelfApproval: p.allowSelfApproval, dueMinutes: p.dueMinutes, onExpiry: p.onExpiry,
  };
}

/**
 * The tenant's approval policies under the inbox: what each kind of request needs before it happens. Whoever may
 * manage them picks a subject, restates its policy and sets it, or resets it to the platform default.
 */
@Component({
  selector: 'app-approval-policies',
  imports: [ErrorBanner, FormsModule, RowTable],
  template: `
    <section data-testid="approval-policies">
      <h2>Approval policies</h2>
      <p class="muted">What each kind of request needs before it goes ahead, for the whole organisation.</p>
      @if (error().message) { <app-error-banner [message]="error().message" [code]="error().code" testId="apol-error" /> }
      @if (done()) { <p class="ok" role="status" data-testid="apol-done">{{ done() }}</p> }
      @if (rows().length > 0) {
        <app-row-table testId="apol" [rows]="table()" [columns]="columns" [pickable]="canManage()" [picked]="picked()"
                       (pick)="pick($event['id'])" />
      }
      @if (current(); as p) {
        @if (canManage()) {
          <form ngNativeValidate data-testid="apol-form" (submit)="set($event, p)">
            <h3>{{ p.subjectType }}</h3>
            <label>
              <input type="checkbox" data-testid="apol-required" [checked]="draft().required" [disabled]="p.mandatory"
                     (change)="change('required', $any($event.target).checked)" />
              Ask for approval{{ p.mandatory ? ' (always: this cannot be switched off)' : '' }}
            </label>
            <input placeholder="Permissions that decide, in order" aria-label="Deciding permissions"
                   data-testid="apol-steps" [value]="draft().decidePermissions"
                   (input)="change('decidePermissions', $any($event.target).value)" />
            <label>
              <input type="checkbox" data-testid="apol-ordered" [checked]="draft().ordered"
                     (change)="change('ordered', $any($event.target).checked)" />
              Decide in that order
            </label>
            <label>
              <input type="checkbox" data-testid="apol-self" [checked]="draft().allowSelfApproval"
                     (change)="change('allowSelfApproval', $any($event.target).checked)" />
              The requester may approve their own request
            </label>
            <input type="number" min="1" placeholder="Minutes a request stays open" aria-label="Minutes open"
                   data-testid="apol-due" [value]="draft().dueMinutes"
                   (input)="change('dueMinutes', $any($event.target).value)" />
            <select aria-label="When it lapses" data-testid="apol-expiry"
                    (change)="change('onExpiry', $any($event.target).value)">
              @for (e of expiries; track e) {
                <option [value]="e" [selected]="e === draft().onExpiry">{{ e === 'EXPIRE' ? 'Lapses unapproved' : 'Approved automatically' }}</option>
              }
            </select>
            <button type="submit" data-testid="apol-set">Set policy</button>
            <button type="button" class="link" data-testid="apol-reset" (click)="reset(p)">Reset to default</button>
          </form>
        }
      }
    </section>
  `,
})
export class ApprovalPolicies implements OnInit {
  private readonly gateway = inject(LmsGateway);
  private readonly session = inject(Session);

  readonly columns = COLUMNS;
  readonly expiries = APPROVAL_EXPIRIES;
  readonly rows = signal<ApprovalPolicyRow[]>([]);
  readonly picked = signal('');
  readonly draft = signal<ApprovalPolicyDraft>({
    required: false, decidePermissions: '', ordered: false, allowSelfApproval: false, dueMinutes: '', onExpiry: 'EXPIRE',
  });
  readonly done = signal('');
  readonly error = signal({ message: '', code: '' });
  readonly canManage = computed(() => this.session.can('APPROVAL_POLICY_MANAGE'));
  readonly current = computed(() => this.rows().find((r) => r.subjectType === this.picked()) ?? null);
  readonly table = computed<Row[]>(() => this.rows().map((p) => ({
    id: p.subjectType, subjectType: p.subjectType, required: p.required ? 'Yes' : 'No',
    steps: p.decidePermissions.join(' → '), ordered: p.ordered ? 'Yes' : 'No', self: p.allowSelfApproval ? 'Yes' : 'No',
    due: p.dueMinutes || '—', onExpiry: p.onExpiry,
  })));

  ngOnInit(): Promise<void> {
    return this.run(async () => { this.rows.set(await this.gateway.approvalPolicies()); });
  }

  pick(subjectType: string): void {
    this.picked.set(subjectType);
    this.done.set('');
    const p = this.rows().find((r) => r.subjectType === subjectType);
    if (p) {
      this.draft.set(draftOf(p));
    }
  }

  change<K extends keyof ApprovalPolicyDraft>(key: K, value: ApprovalPolicyDraft[K]): void {
    this.draft.update((d) => ({ ...d, [key]: value }));
  }

  set(event: Event, p: ApprovalPolicyRow): Promise<void> {
    event.preventDefault();
    return this.run(async () => {
      this.replace(await this.gateway.setApprovalPolicy(p.subjectType, this.draft()));
      this.done.set(`Policy for ${p.subjectType} set.`);
    });
  }

  reset(p: ApprovalPolicyRow): Promise<void> {
    return this.run(async () => {
      this.replace(await this.gateway.resetApprovalPolicy(p.subjectType));
      this.done.set(`Policy for ${p.subjectType} reset to the default.`);
    });
  }

  private replace(policy: ApprovalPolicyRow): void {
    this.rows.update((all) => all.map((r) => r.subjectType === policy.subjectType ? policy : r));
    this.draft.set(draftOf(policy));
  }

  private async run(action: () => Promise<void>): Promise<void> {
    this.error.set({ message: '', code: '' });
    this.done.set('');
    try {
      await action();
    } catch (e) {
      this.error.set({ message: (e as Error).message, code: e instanceof ApiProblem ? e.code : '' });
    }
  }
}
