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
import { Component, ElementRef, effect, inject, input, output, signal, viewChild } from '@angular/core';
import type { ConfigAction, ConfigKind } from '../gateway/config-types';
import { ApiProblem, LmsGateway } from '../gateway/lms-gateway';
import { Session } from '../session';
import { STALE_MESSAGE } from './master-data-form';
import { ErrorBanner } from './error-banner';

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
  imports: [ErrorBanner],
  selector: 'app-config-actions',
  template: `
    @if (!pending()) {
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
    } @else {
      <div class="confirm" data-testid="confirm-panel" role="group" [attr.aria-label]="question()">
        <p #asked tabindex="-1"><strong>{{ question() }}</strong> {{ line() }} Say why; the reason is kept in the audit trail.</p>
        <label>Reason
          <textarea rows="2" data-testid="reason-input" [value]="reason()" (input)="reason.set($any($event.target).value)"></textarea>
        </label>
        @if (reasonError()) { <p class="error" role="alert" data-testid="reason-error">A reason is required.</p> }
        @if (failure()) { <app-error-banner [message]="failure()" testId="action-error" /> }
        <div class="actions">
          <button type="button" data-testid="confirm-action" [disabled]="busy()" (click)="confirm()">{{ verb() }}</button>
          <button type="button" class="link" data-testid="cancel-action" (click)="cancel()">Cancel</button>
        </div>
      </div>
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
  protected readonly reason = signal('');
  protected readonly reasonError = signal(false);
  protected readonly failure = signal('');
  protected readonly busy = signal(false);
  /** A stale refusal is shown in place; the list is read again when the confirmation is left. */
  private stale = false;
  private readonly questionRef = viewChild<ElementRef<HTMLElement>>('asked');

  constructor() {
    // Focus moves to the question when the confirmation opens.
    effect(() => this.questionRef()?.nativeElement.focus());
  }

  protected allowed(): boolean {
    return this.session.can(MANAGE[this.kind()]);
  }

  protected question(): string {
    const action = this.pending();
    return action ? `${WORDS[action].verb} ${this.label()}?` : '';
  }

  protected line(): string {
    const action = this.pending();
    return action ? WORDS[action].line : '';
  }

  protected verb(): string {
    const action = this.pending();
    return action ? WORDS[action].verb : '';
  }

  protected ask(action: ConfigAction): void {
    this.reason.set('');
    this.reasonError.set(false);
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

  protected async confirm(): Promise<void> {
    const action = this.pending();
    if (!action) {
      return;
    }
    if (!this.reason().trim()) {
      this.reasonError.set(true);
      return;
    }
    this.reasonError.set(false);
    this.failure.set('');
    this.busy.set(true);
    try {
      await this.gateway.switchConfigRecord(this.kind(), this.recordId(), action, this.reason(), Number(this.version()),
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
