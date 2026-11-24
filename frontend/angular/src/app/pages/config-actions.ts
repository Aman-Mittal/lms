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
import { Component, computed, inject, input, output, signal } from '@angular/core';
import type { ConfigAction, ConfigKind } from '../gateway/config-types';
import { ApiProblem, LmsGateway } from '../gateway/lms-gateway';
import { Session } from '../session';
import { STALE_MESSAGE } from './master-data-form';
import { ErrorBanner } from './error-banner';
import { ConfirmDialog } from './confirm-dialog';
import { confirmWording, type ConfirmWording, type ReasonField } from '../../../../shared/confirm';

const MANAGE: Record<ConfigKind, string> = {
  allocationRules: 'ALLOCATION_RULE_MANAGE', orgUnits: 'ORG_UNIT_MANAGE', chargeCatalogue: 'CHARGE_CATALOGUE_MANAGE',
  webhooks: 'WEBHOOK_MANAGE', customFields: 'CUSTOM_FIELD_MANAGE', bins: 'WAREHOUSE_SETUP', skus: 'WAREHOUSE_SETUP',
  guideVendors: 'ROUTING_GUIDE_MANAGE', vendorInvoices: 'PAYABLES_MANAGE',
};

const WORDS: Record<ConfigAction, { verb: string; line: string }> = {
  deactivate: { verb: 'Deactivate', line: 'It is kept, but can no longer be used until it is reactivated.' },
  reactivate: { verb: 'Reactivate', line: 'It will be available again.' },
  void: { verb: 'Void', line: 'The invoice stays on record as void; its bills and advances are released.' },
};

/**
 * The Deactivate and Reactivate buttons of a row (Void, for a vendor invoice) and the confirmation each asks for: the
 * same shape as the master-data panel's. A reason is always required; a refusal from the server is shown in place and
 * the confirmation stays open. The version is the one the list read, so a row somebody else changed says so.
 */
@Component({
  imports: [ConfirmDialog, ErrorBanner],
  selector: 'app-config-actions',
  template: `
    @if (allowed()) {
      <span class="actions">
        @if (kind() === 'vendorInvoices') {
          @if (active()) { <button type="button" data-testid="void-record" (click)="ask('void')">Void</button> }
        } @else if (active()) {
          <button type="button" class="link" data-testid="deactivate-record" (click)="ask('deactivate')">Deactivate</button>
        } @else {
          <button type="button" class="link" data-testid="reactivate-record" (click)="ask('reactivate')">Reactivate</button>
        }
      </span>
    }
    @if (pending()) {
      <app-confirm-dialog [wording]="wording()" [reason]="reasonField" [tone]="pending() === 'reactivate' ? 'neutral' : 'danger'"
                          [busy]="busy()" (confirmed)="confirm($event)" (dismissed)="cancel()">
        @if (failure()) { <app-error-banner [message]="failure()" testId="action-error" /> }
      </app-confirm-dialog>
    }
  `,
})
export class ConfigActions {
  private readonly gateway = inject(LmsGateway);
  private readonly session = inject(Session);

  readonly kind = input.required<ConfigKind>();
  readonly recordId = input.required<string>();
  /** The warehouse of a bin, the guide of a vendor. */
  readonly parentId = input<string>();
  /** Whether the record is on now; for a vendor invoice, whether it can still be voided. */
  readonly active = input.required<boolean>();
  /** What the record is called in the question. */
  readonly label = input.required<string>();
  readonly version = input.required<number | string>();
  /** A switch went through, or a stale one was dismissed; the list should be read again so the row is current. */
  readonly changed = output<void>();

  protected readonly pending = signal<ConfigAction | null>(null);
  protected readonly failure = signal('');
  protected readonly busy = signal(false);
  /** A stale refusal is shown in place; the list is read again when the confirmation is left. */
  private stale = false;
  protected readonly reasonField: ReasonField = { label: 'Reason', testId: 'reason-input', required: true, multiline: true };

  protected allowed(): boolean {
    return this.session.can(MANAGE[this.kind()]);
  }

  protected readonly wording = computed<ConfirmWording>(() => {
    const action = this.pending();
    return action
      ? confirmWording(WORDS[action].verb, this.label(), `${WORDS[action].line} The reason is kept in the audit trail.`)
      : confirmWording('', '');
  });

  protected ask(action: ConfigAction): void {
    this.failure.set('');
    this.stale = false;
    this.pending.set(action);
  }

  protected cancel(): void {
    this.pending.set(null);
    if (this.stale) {
      this.changed.emit();
    }
  }

  protected async confirm(reason: string): Promise<void> {
    const action = this.pending();
    if (!action) {
      return;
    }
    this.failure.set('');
    this.busy.set(true);
    try {
      await this.gateway.switchConfigRecord(this.kind(), this.recordId(), action, reason, Number(this.version()),
        this.parentId());
      this.pending.set(null);
      this.changed.emit();
    } catch (e) {
      const stale = e instanceof ApiProblem && e.code === 'concurrent-modification';
      this.failure.set(stale ? STALE_MESSAGE : (e as Error).message);
      this.stale = stale;
    } finally {
      this.busy.set(false);
    }
  }
}
