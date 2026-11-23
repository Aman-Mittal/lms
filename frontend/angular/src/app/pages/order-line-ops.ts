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
import { FormsModule } from '@angular/forms';
import { ApiProblem, LmsGateway, MATERIAL_CLASSES, OrderLineDraft, OrderLineView } from '../gateway/lms-gateway';
import { Session } from '../session';
import { EntityPicker } from './entity-picker';
import { ErrorBanner } from './error-banner';
import { ConfirmHost } from './confirm-dialog';
import { confirmWording, type PendingConfirm } from '../../../../shared/confirm';

/** Once consignments are cut from an order, a line can still be cancelled on its own. */
const CANCELLABLE = ['VALIDATED', 'PARTIALLY_PLANNED', 'FULLY_PLANNED'];

function blankLine(lines: OrderLineView[]): OrderLineDraft {
  const last = lines[lines.length - 1];
  return {
    lineNo: lines.reduce((max, l) => Math.max(max, l.lineNo), 0) + 1, materialCode: '', materialDescription: '',
    materialClass: 'GENERAL', hazmatUnCode: '', quantity: '1', uom: 'EA', deadWeightKg: '', lengthM: '', widthM: '',
    heightM: '', volumetricDivisor: '', consigneePartnerId: last?.consigneePartnerId ?? '',
    destinationTerminalId: last?.destinationTerminalId ?? '',
  };
}

/**
 * Two single-line operations on the order desk: appending one line to a draft without restating the others, and
 * cancelling one line of a validated order (with the consignment it is on) under the order's version.
 */
@Component({
  selector: 'app-order-line-ops',
  imports: [ConfirmHost, EntityPicker, ErrorBanner, FormsModule],
  template: `
    @if (canAppend()) {
      <form ngNativeValidate class="inline" data-testid="append-line" (submit)="append($event)">
        <h3>Append one line</h3>
        <input type="number" min="1" aria-label="Appended line number" data-testid="append-line-no" required
               [value]="draft().lineNo" (input)="set('lineNo', +$any($event.target).value)" />
        <input placeholder="Material code" aria-label="Appended material code" data-testid="append-material" required
               [value]="draft().materialCode" (input)="set('materialCode', $any($event.target).value)" />
        <input placeholder="Description" aria-label="Appended material description" data-testid="append-description" required
               [value]="draft().materialDescription" (input)="set('materialDescription', $any($event.target).value)" />
        <select aria-label="Appended material class" data-testid="append-class" (change)="set('materialClass', $any($event.target).value)">
          @for (c of classes; track c) { <option [value]="c" [selected]="c === draft().materialClass">{{ c }}</option> }
        </select>
        <input inputmode="decimal" placeholder="Quantity" aria-label="Appended quantity" data-testid="append-quantity" required
               [value]="draft().quantity" (input)="set('quantity', $any($event.target).value)" />
        <input placeholder="Unit" aria-label="Appended unit" data-testid="append-uom" required
               [value]="draft().uom" (input)="set('uom', $any($event.target).value)" />
        <input inputmode="decimal" placeholder="Weight kg" aria-label="Appended weight kg" data-testid="append-weight" required
               [value]="draft().deadWeightKg" (input)="set('deadWeightKg', $any($event.target).value)" />
        <app-entity-picker [kind]="'terminal'" placeholder="Destination terminal" ariaLabel="Appended destination terminal id"
                           testId="append-destination" [required]="true" [value]="draft().destinationTerminalId"
                           (valueChange)="set('destinationTerminalId', $event)" />
        <app-entity-picker [kind]="'partner'" placeholder="Consignee (optional)" ariaLabel="Appended consignee id"
                           testId="append-consignee" [value]="draft().consigneePartnerId"
                           (valueChange)="set('consigneePartnerId', $event)" />
        <button type="submit" data-testid="append-submit">Append line</button>
      </form>
    }
    @if (cancellable().length > 0) {
      <section data-testid="cancel-lines" aria-label="Cancel a line">
        <h3>Cancel a line</h3>
        <p class="muted">
          Cancels the line and the consignment it is on. The order's last live line cannot be cancelled: cancel or
          close the order instead.
        </p>
        <div class="inline">
          @for (l of cancellable(); track l.id) {
            <button type="button" data-testid="cancel-line" [attr.data-line]="l.lineNo" (click)="askCancel(l)">
              Cancel line {{ l.lineNo }}
            </button>
          }
        </div>
      </section>
    }
    <app-confirm-host [(pending)]="pending" />
    @if (done()) { <p class="ok" role="status" data-testid="line-op-done">{{ done() }}</p> }
    @if (error().message) { <app-error-banner [message]="error().message" [code]="error().code" testId="line-op-error" codeTestId="line-op-refusal" /> }
  `,
})
export class OrderLineOps {
  /** The destructive action waiting for its confirmation, shown by the confirm host in the template. */
  protected readonly pending = signal<PendingConfirm | null>(null);
  private readonly gateway = inject(LmsGateway);
  private readonly session = inject(Session);

  readonly orderId = input.required<string>();
  readonly version = input.required<string>();
  readonly status = input.required<string>();
  readonly lines = input.required<OrderLineView[]>();
  /** The order changed: the desk reads it again. */
  readonly changed = output<void>();

  readonly classes = MATERIAL_CLASSES;
  readonly done = signal('');
  readonly error = signal({ message: '', code: '' });
  private readonly typed = signal<OrderLineDraft | null>(null);
  readonly draft = computed(() => this.typed() ?? blankLine(this.lines()));
  readonly canAppend = computed(() => this.status() === 'DRAFT' && this.session.can('ORDER_UPDATE'));
  readonly cancellable = computed(() => this.session.can('ORDER_UPDATE') && CANCELLABLE.includes(this.status())
    ? this.lines().filter((l) => !l.cancelled && !l.closeKind) : []);

  set<K extends keyof OrderLineDraft>(key: K, value: OrderLineDraft[K]): void {
    this.typed.set({ ...this.draft(), [key]: value });
  }

  async append(event: Event): Promise<void> {
    event.preventDefault();
    this.begin();
    try {
      const line = this.draft();
      await this.gateway.addOrderLine(this.orderId(), line);
      this.typed.set(null);
      this.done.set(`Line ${line.lineNo} appended.`);
      this.changed.emit();
    } catch (e) {
      this.fail(e);
    }
  }

  askCancel(line: OrderLineView): void {
    this.pending.set({
      wording: confirmWording('Cancel line', String(line.lineNo), 'The consignment it is on is cancelled too.'),
      reason: { label: 'Why a line is cancelled', testId: 'cancel-line-reason', placeholder: 'Why (optional)' },
      run: (why) => this.cancelLine(line, why),
    });
  }

  private async cancelLine(line: OrderLineView, reason: string): Promise<void> {
    this.begin();
    try {
      const result = await this.gateway.cancelOrderLine(this.orderId(), this.version(), line.id, reason);
      this.done.set(`Line ${line.lineNo} cancelled; ${result.cancelledConsignments} consignment(s) cancelled.`);
      this.changed.emit();
    } catch (e) {
      this.fail(e);
    }
  }

  private begin(): void {
    this.done.set('');
    this.error.set({ message: '', code: '' });
  }

  private fail(e: unknown): void {
    this.error.set({ message: (e as Error).message, code: e instanceof ApiProblem ? e.code : '' });
  }
}
