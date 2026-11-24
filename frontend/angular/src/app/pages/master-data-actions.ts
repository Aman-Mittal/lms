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

import { Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { ApiProblem, LmsGateway } from '../gateway/lms-gateway';
import type { MasterAction, MasterKind } from '../gateway/master-data-types';
import { Session } from '../session';
import { STALE_MESSAGE } from './master-data-form';
import { ErrorBanner } from './error-banner';
import { ConfirmDialog } from './confirm-dialog';
import { confirmWording, type ConfirmWording, type ReasonField } from '../../../../shared/confirm';

const UPDATE: Record<MasterKind, string> = {
  partners: 'PARTNER_UPDATE', vehicles: 'VEHICLE_UPDATE', drivers: 'DRIVER_UPDATE', terminals: 'TERMINAL_UPDATE',
};

const WORDS: Record<MasterAction, { verb: string; done: string }> = {
  deactivate: { verb: 'Deactivate', done: 'deactivated' },
  reactivate: { verb: 'Reactivate', done: 'reactivated' },
  blacklist: { verb: 'Blacklist', done: 'blacklisted' },
};

/**
 * The buttons that edit a record or switch it off and on, and the confirmation each one asks for. A reason is
 * always required; a refusal from the server is shown in place and the confirmation stays open.
 */
@Component({
  imports: [ConfirmDialog, ErrorBanner],
  selector: 'app-master-data-actions',
  template: `
    <div class="actions">
      @if (canUpdate()) { <button type="button" data-testid="edit-record" (click)="edit.emit()">Edit</button> }
      @if (canDeactivate()) { <button type="button" data-testid="deactivate-record" (click)="ask('deactivate')">Deactivate</button> }
      @if (canReactivate()) { <button type="button" data-testid="reactivate-record" (click)="ask('reactivate')">Reactivate</button> }
      @if (canBlacklist()) { <button type="button" class="danger" data-testid="blacklist-record" (click)="ask('blacklist')">Blacklist</button> }
    </div>
    @if (pending()) {
      <app-confirm-dialog [wording]="wording()" [reason]="reasonField" [tone]="pending() === 'reactivate' ? 'neutral' : 'danger'"
                          [busy]="busy()" (confirmed)="confirm($event)" (dismissed)="cancel()">
        @if (failure()) { <app-error-banner [message]="failure()" testId="action-error" /> }
      </app-confirm-dialog>
    }
  `,
})
export class MasterDataActions {
  private readonly gateway = inject(LmsGateway);
  private readonly session = inject(Session);

  readonly kind = input.required<MasterKind>();
  readonly recordId = input.required<string>();
  readonly status = input.required<string>();
  /** What the record is called in the question: its code, registration, name. */
  readonly label = input.required<string>();
  readonly edit = output<void>();
  /** A switch went through; the list and the record should be read again. */
  readonly changed = output<void>();

  protected readonly pending = signal<MasterAction | null>(null);
  protected readonly failure = signal('');
  protected readonly busy = signal(false);
  private read: Promise<number> = Promise.resolve(0);
  protected readonly reasonField: ReasonField = { label: 'Reason', testId: 'reason-input', required: true, multiline: true };

  constructor() {
    // A different record starts clean.
    effect(() => { this.recordId(); this.cancel(); }, { allowSignalWrites: true });
  }

  private partners(): boolean {
    return this.kind() === 'partners';
  }

  protected canUpdate(): boolean {
    return this.session.can(UPDATE[this.kind()]);
  }

  protected canDeactivate(): boolean {
    const s = this.status();
    const allowed = this.partners() ? s === 'ACTIVE' : s !== 'INACTIVE';
    return allowed && this.session.can(this.kind() === 'terminals' ? 'TERMINAL_DELETE' : UPDATE[this.kind()]);
  }

  protected canReactivate(): boolean {
    const s = this.status();
    return (this.partners() ? s === 'SUSPENDED' : s === 'INACTIVE') && this.canUpdate();
  }

  protected canBlacklist(): boolean {
    return this.partners() && this.status() !== 'BLACKLISTED' && this.canUpdate();
  }

  protected readonly wording = computed<ConfirmWording>(() => {
    const action = this.pending();
    if (!action) {
      return confirmWording('', '');
    }
    const thing = this.kind() === 'partners' ? 'partner' : this.kind().replace(/s$/, '');
    const line = action === 'blacklist' ? 'A blacklisted partner cannot be reinstated.'
      : action === 'reactivate' ? `The ${thing} will be available again.`
        : `The ${thing} is kept, but can no longer be used until it is reactivated.`;
    return confirmWording(WORDS[action].verb, this.label(), `${line} The reason is kept in the audit trail.`);
  });

  protected ask(action: MasterAction): void {
    this.failure.set('');
    this.pending.set(action);
    // The version the person is looking at when asked, which is what the change is conditional on.
    this.read = this.gateway.masterRecord(this.kind(), this.recordId()).then((r) => r.version);
    this.read.catch(() => undefined);
  }

  protected cancel(): void {
    this.pending.set(null);
  }

  protected async confirm(reason: string): Promise<void> {
    const action = this.pending();
    if (!action) {
      return;
    }
    this.failure.set('');
    this.busy.set(true);
    try {
      await this.gateway.switchMasterRecord(this.kind(), this.recordId(), action, reason, await this.read);
      this.pending.set(null);
      this.changed.emit();
    } catch (e) {
      this.failure.set(e instanceof ApiProblem && e.code === 'concurrent-modification' ? STALE_MESSAGE : (e as Error).message);
    } finally {
      this.busy.set(false);
    }
  }
}
