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
import {
  ApiProblem, LmsGateway, MATERIAL_CLASSES, OrderDesk, OrderHeaderDraft, OrderLineDraft, OrderLineView,
  OrderRaiseDraft,
} from '../gateway/lms-gateway';
import { Session } from '../session';
import { EntityPicker } from './entity-picker';

/** The editable part of a stored line, as an edit restates it. */
function draftOf(line: OrderLineView): OrderLineDraft {
  return {
    lineNo: line.lineNo, materialCode: line.materialCode, materialDescription: line.materialDescription,
    materialClass: line.materialClass, hazmatUnCode: line.hazmatUnCode, quantity: line.quantity, uom: line.uom,
    deadWeightKg: line.deadWeightKg, lengthM: line.lengthM, widthM: line.widthM, heightM: line.heightM,
    volumetricDivisor: line.volumetricDivisor, consigneePartnerId: line.consigneePartnerId,
    destinationTerminalId: line.destinationTerminalId,
  };
}

/** A new line, numbered after the last and going where the last one went. */
function nextLine(lines: OrderLineDraft[]): OrderLineDraft {
  const last = lines[lines.length - 1];
  return {
    lineNo: lines.reduce((max, l) => Math.max(max, l.lineNo), 0) + 1, materialCode: '', materialDescription: '',
    materialClass: 'GENERAL', hazmatUnCode: '', quantity: '1', uom: 'EA', deadWeightKg: '', lengthM: '', widthM: '',
    heightM: '', volumetricDivisor: '', consigneePartnerId: last?.consigneePartnerId ?? '',
    destinationTerminalId: last?.destinationTerminalId ?? '',
  };
}

/** What the editor starts from: the stored lines, or one blank line for an order that has none. */
function editableLines(lines: OrderLineView[]): OrderLineDraft[] {
  return lines.length > 0 ? lines.map(draftOf) : [nextLine([])];
}

/** Once consignments are cut from an order, its lines belong to them. */
const PLANNED = ['PARTIALLY_PLANNED', 'FULLY_PLANNED'];

const BLANK_RAISE: OrderRaiseDraft = {
  orderNo: '', customerPartnerId: '', originTerminalId: '', requestedPickupAt: '', requestedDeliveryAt: '',
};

const BLANK_HEADER: OrderHeaderDraft = {
  customerPartnerId: '', originTerminalId: '', requestedPickupAt: '', requestedDeliveryAt: '',
  originHubTerminalId: '', destinationHubTerminalId: '',
};

/** A refusal as the desk shows it: the problem's stable name, then what it says. */
interface Refusal {
  code: string;
  message: string;
}

/**
 * The order desk: raise an order, edit it while it is a draft, and amend it once validated
 * until freight is cut from it. Every edit sends back the version the desk was read at, so
 * two people editing one order cannot overwrite each other unseen.
 *
 * Every input is bound to a signal with [value] and (input), not ngModel: ngModel connects
 * after the screen is shown and would wipe what was typed before it.
 */
@Component({
  selector: 'app-order-desk',
  imports: [FormsModule, EntityPicker],
  template: `
    <h1>Order desk</h1>
    <p class="muted">
      Raise an order, edit it while it is a draft, and amend it once validated, until consignments
      are cut from it.
    </p>
    <form class="inline" (ngSubmit)="openTyped()" data-testid="open-order">
      <app-entity-picker [kind]="'order'" placeholder="Order" ariaLabel="Order id" testId="order-id" [required]="true"
                         [(value)]="typed" />
      <button type="submit">Open</button>
    </form>
    @if (session.can('ORDER_CREATE') && session.orgUnitId()) {
      <form class="inline" (ngSubmit)="raise()" data-testid="raise-order">
        <input name="orderNo" placeholder="Order number" aria-label="Order number" [value]="raising().orderNo"
               (input)="setRaise('orderNo', $any($event.target).value)" required />
        <app-entity-picker [kind]="'customer'" placeholder="Customer" ariaLabel="New order customer id"
                           [value]="raising().customerPartnerId" [required]="true"
                           (valueChange)="setRaise('customerPartnerId', $event)" />
        <app-entity-picker [kind]="'terminal'" placeholder="Origin terminal" ariaLabel="New order origin terminal id"
                           [value]="raising().originTerminalId" [required]="true"
                           (valueChange)="setRaise('originTerminalId', $event)" />
        <input name="raisePickup" placeholder="Pickup (ISO time, optional)" aria-label="New order pickup"
               [value]="raising().requestedPickupAt"
               (input)="setRaise('requestedPickupAt', $any($event.target).value)" />
        <button type="submit">Raise order</button>
        @if (raiseError()) { <span class="error" role="alert"> {{ raiseError() }}</span> }
      </form>
    }
    @if (loadError()) { <p class="error" role="alert">{{ loadError() }}</p> }
    @if (desk(); as d) {
      <section data-testid="order-panel">
        <h2>{{ d.order['orderNo'] }}</h2>
        <dl>
          <div><dt>Status</dt><dd data-testid="order-status">{{ d.order['status'] }}</dd></div>
          <div><dt>Version</dt><dd data-testid="order-version">{{ d.version }}</dd></div>
          <div><dt>Customer</dt><dd data-testid="order-customer">{{ d.order['customerName'] }}</dd></div>
          <div><dt>Origin</dt><dd>{{ d.order['originTerminalCode'] }}</dd></div>
          <div><dt>Mode</dt><dd>{{ d.order['freightMode'] }}</dd></div>
          <div><dt>Lines</dt><dd data-testid="order-line-count">{{ d.order['lineCount'] }}</dd></div>
          <div><dt>Weight kg</dt><dd data-testid="order-weight">{{ d.order['totalDeadWeightKg'] }}</dd></div>
          <div><dt>Pickup</dt><dd>{{ d.order['requestedPickupAt'] || '—' }}</dd></div>
        </dl>
        <p class="actions">
          @if (isDraft() && session.can('ORDER_UPDATE')) {
            <button type="button" data-testid="validate-order"
                    (click)="act('Validated.', gateway.validateOrder(d.order['id']))">Validate</button>
          }
          @if (isValidated() && session.can('LOAD_CREATE')) {
            <button type="button" data-testid="generate-consignments"
                    (click)="act('Consignments cut.', gateway.generateConsignments(d.order['id']))">
              Generate consignments
            </button>
          }
          <button type="button" class="link" data-testid="print-order" (click)="slip.set(true)">Print order</button>
          <button type="button" class="link" data-testid="reload-order" (click)="reload()">Reload</button>
        </p>
        @if (done()) { <p class="ok" role="status" data-testid="order-done">{{ done() }}</p> }
        @if (refusal(); as r) {
          <p class="error" role="alert" data-testid="order-error">
            @if (r.code) { <code data-testid="order-refusal">{{ r.code }}</code> }
            {{ r.message }}
          </p>
        }

        <div class="table-scroll">
          <table data-testid="order-lines">
            <thead>
              <tr><th>#</th><th>Material</th><th>Class</th><th>Qty</th><th>Weight kg</th><th>L × W × H m</th><th>Consignee</th><th>Destination</th><th>Planned</th><th></th></tr>
            </thead>
            <tbody>
              @for (l of d.lines; track l.id) {
                <tr data-testid="order-line">
                  <td>{{ l.lineNo }}</td>
                  <td>{{ l.materialCode }} · {{ l.materialDescription }}</td>
                  <td>{{ l.materialClass }}@if (l.hazmatUnCode) { (UN {{ l.hazmatUnCode }})}</td>
                  <td>{{ l.quantity }} {{ l.uom }}</td>
                  <td>{{ l.deadWeightKg }}</td>
                  <td>{{ l.lengthM ? l.lengthM + ' × ' + l.widthM + ' × ' + l.heightM : '—' }}</td>
                  <td>{{ l.consigneeName }}</td>
                  <td>{{ l.destinationTerminalCode }}</td>
                  <td>{{ l.planned ? 'Yes' : '' }}</td>
                  <td>
                    @if (isDraft() && session.can('ORDER_UPDATE') && d.lines.length > 1) {
                      <button type="button" class="link" data-testid="remove-line"
                              (click)="act('Line ' + l.lineNo + ' removed.', gateway.removeOrderLine(d.order['id'], d.version, l.id))">
                        Remove
                      </button>
                    }
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>

        @if (isPlanned()) {
          <p class="muted" data-testid="not-amendable">
            Consignments have been cut from this order, so it can no longer be amended
            (<code>order-has-consignments</code>).
          </p>
        }
        @if (editable()) {
          <section [attr.data-testid]="isDraft() ? 'edit-draft' : 'amend-order'">
            <h3>{{ isDraft() ? 'Edit the draft' : 'Amend the order' }}</h3>
            @if (isValidated()) {
              <p class="muted">
                An amendment validates again and keeps the order validated. Once consignments are cut it is refused.
              </p>
            }
            <div class="inline" data-testid="header-editor">
              <app-entity-picker [kind]="'customer'" ariaLabel="Customer id" placeholder="Customer"
                                 [value]="header().customerPartnerId" (valueChange)="setHeader('customerPartnerId', $event)" />
              <app-entity-picker [kind]="'terminal'" ariaLabel="Origin terminal id" placeholder="Origin terminal"
                                 [value]="header().originTerminalId" (valueChange)="setHeader('originTerminalId', $event)" />
              <input aria-label="Pickup" placeholder="Pickup (ISO time)" [value]="header().requestedPickupAt"
                     (input)="setHeader('requestedPickupAt', $any($event.target).value)" />
              <input aria-label="Delivery" placeholder="Delivery (ISO time)" [value]="header().requestedDeliveryAt"
                     (input)="setHeader('requestedDeliveryAt', $any($event.target).value)" />
            </div>
            <div class="table-scroll">
              <table data-testid="lines-editor">
                <thead>
                  <tr><th>#</th><th>Code</th><th>Description</th><th>Class</th><th>Qty</th><th>UoM</th><th>Weight kg</th><th>L</th><th>W</th><th>H</th><th>Consignee id</th><th>Destination id</th><th></th></tr>
                </thead>
                <tbody>
                  @for (l of lines(); track $index; let i = $index) {
                    <tr data-testid="edit-line">
                      <td>{{ l.lineNo }}</td>
                      <td><input [attr.aria-label]="'Line ' + l.lineNo + ' material code'" [value]="l.materialCode"
                                 (input)="setLine(i, 'materialCode', $any($event.target).value)" /></td>
                      <td><input [attr.aria-label]="'Line ' + l.lineNo + ' description'" [value]="l.materialDescription"
                                 (input)="setLine(i, 'materialDescription', $any($event.target).value)" /></td>
                      <td>
                        <select [attr.aria-label]="'Line ' + l.lineNo + ' class'" [value]="l.materialClass"
                                (change)="setLine(i, 'materialClass', $any($event.target).value)">
                          @for (c of classes; track c) {
                            <option [value]="c" [selected]="c === l.materialClass">{{ c }}</option>
                          }
                        </select>
                      </td>
                      <td><input [attr.aria-label]="'Line ' + l.lineNo + ' quantity'" [value]="l.quantity" size="5"
                                 (input)="setLine(i, 'quantity', $any($event.target).value)" /></td>
                      <td><input [attr.aria-label]="'Line ' + l.lineNo + ' unit'" [value]="l.uom" size="4"
                                 (input)="setLine(i, 'uom', $any($event.target).value)" /></td>
                      <td><input [attr.aria-label]="'Line ' + l.lineNo + ' weight kg'" [value]="l.deadWeightKg" size="7"
                                 (input)="setLine(i, 'deadWeightKg', $any($event.target).value)" /></td>
                      <td><input [attr.aria-label]="'Line ' + l.lineNo + ' length m'" [value]="l.lengthM" size="4"
                                 (input)="setLine(i, 'lengthM', $any($event.target).value)" /></td>
                      <td><input [attr.aria-label]="'Line ' + l.lineNo + ' width m'" [value]="l.widthM" size="4"
                                 (input)="setLine(i, 'widthM', $any($event.target).value)" /></td>
                      <td><input [attr.aria-label]="'Line ' + l.lineNo + ' height m'" [value]="l.heightM" size="4"
                                 (input)="setLine(i, 'heightM', $any($event.target).value)" /></td>
                      <td><app-entity-picker [kind]="'partner'" [ariaLabel]="'Line ' + l.lineNo + ' consignee id'"
                                             [value]="l.consigneePartnerId" (valueChange)="setLine(i, 'consigneePartnerId', $event)" /></td>
                      <td><app-entity-picker [kind]="'terminal'" [ariaLabel]="'Line ' + l.lineNo + ' destination id'"
                                             [value]="l.destinationTerminalId" (valueChange)="setLine(i, 'destinationTerminalId', $event)" /></td>
                      <td>
                        @if (lines().length > 1) {
                          <button type="button" class="link" [attr.aria-label]="'Drop line ' + l.lineNo"
                                  (click)="dropLine(i)">Drop</button>
                        }
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
            <p class="actions">
              <button type="button" class="link" data-testid="add-line" (click)="addLine()">Add a line</button>
            </p>
            <p class="actions">
              @if (isDraft()) {
                <button type="button" data-testid="save-header"
                        (click)="act('Header saved.', gateway.reviseOrderHeader(d.order['id'], d.version, header()))">
                  Save header
                </button>
                <button type="button" data-testid="save-lines" [disabled]="lines().length === 0"
                        (click)="act('Lines saved.', gateway.replaceOrderLines(d.order['id'], d.version, lines()))">
                  Save lines
                </button>
              } @else {
                <button type="button" data-testid="amend"
                        (click)="act('Order amended.', gateway.amendOrder(d.order['id'], d.version, header(), lines()))">
                  Amend order
                </button>
              }
            </p>
            <form class="inline" data-testid="cancel-order"
                  (ngSubmit)="act('Order cancelled.', gateway.cancelOrder(d.order['id'], reason()))">
              <input name="reason" aria-label="Cancellation reason" placeholder="Why it is cancelled (optional)"
                     [value]="reason()" (input)="reason.set($any($event.target).value)" />
              <button type="submit">Cancel order</button>
            </form>
          </section>
        }

        @if (slip()) {
          <section class="slip" data-testid="order-slip" aria-label="Order">
            <h3>Order · {{ d.order['orderNo'] }}</h3>
            <p>
              {{ d.order['customerName'] }} · from {{ d.order['originTerminalCode'] }} · {{ d.order['status'] }} ·
              {{ d.order['totalDeadWeightKg'] }} kg
            </p>
            <table>
              <thead><tr><th>#</th><th>Material</th><th>Qty</th><th>Weight kg</th><th>Consignee</th><th>Destination</th></tr></thead>
              <tbody>
                @for (l of d.lines; track l.id) {
                  <tr data-testid="slip-line">
                    <td>{{ l.lineNo }}</td><td>{{ l.materialCode }} · {{ l.materialDescription }}</td>
                    <td>{{ l.quantity }} {{ l.uom }}</td><td>{{ l.deadWeightKg }}</td>
                    <td>{{ l.consigneeName }}</td><td>{{ l.destinationTerminalCode }}</td>
                  </tr>
                }
              </tbody>
            </table>
            <p class="actions no-print">
              <button data-testid="print-now" (click)="print()">Print</button>
              <button class="link" (click)="slip.set(false)">Close</button>
            </p>
          </section>
        }
      </section>
    }
  `,
})
export class OrderDeskPage {
  readonly gateway = inject(LmsGateway);
  readonly session = inject(Session);

  readonly classes = MATERIAL_CLASSES;
  readonly typed = signal('');
  readonly orderId = signal('');
  readonly desk = signal<OrderDesk | null>(null);
  readonly loadError = signal('');
  readonly raising = signal<OrderRaiseDraft>({ ...BLANK_RAISE });
  readonly raiseError = signal('');
  readonly header = signal<OrderHeaderDraft>({ ...BLANK_HEADER });
  readonly lines = signal<OrderLineDraft[]>([]);
  readonly refusal = signal<Refusal | null>(null);
  readonly done = signal('');
  readonly reason = signal('');
  readonly slip = signal(false);

  readonly isDraft = computed(() => this.desk()?.order['status'] === 'DRAFT');
  readonly isValidated = computed(() => this.desk()?.order['status'] === 'VALIDATED');
  readonly isPlanned = computed(() => PLANNED.includes(this.desk()?.order['status'] ?? ''));
  readonly editable = computed(() => this.session.can('ORDER_UPDATE') && (this.isDraft() || this.isValidated()));

  async openTyped(): Promise<void> {
    await this.open(this.typed());
  }

  async open(id: string): Promise<void> {
    const next = id.trim().toLowerCase();
    if (next !== this.orderId()) {
      this.desk.set(null);
      this.slip.set(false);
      this.refusal.set(null);
      this.done.set('');
    }
    this.typed.set(id);
    this.orderId.set(next);
    await this.read();
  }

  /** Clears what the last action said, then reads the order again. */
  async reload(): Promise<void> {
    this.refusal.set(null);
    this.done.set('');
    await this.read();
  }

  /** A fresh read replaces what was being edited; a refused edit leaves it, to be tried again. */
  private async read(): Promise<void> {
    this.loadError.set('');
    try {
      const desk = await this.gateway.orderDesk(this.orderId());
      this.desk.set(desk);
      this.header.set({ ...desk.header });
      this.lines.set(editableLines(desk.lines));
    } catch (e) {
      this.desk.set(null);
      this.loadError.set((e as Error).message);
    }
  }

  setRaise(field: keyof OrderRaiseDraft, value: string): void {
    this.raising.update((d) => ({ ...d, [field]: value }));
  }

  async raise(): Promise<void> {
    const orgUnitId = this.session.orgUnitId();
    if (!orgUnitId) {
      return;
    }
    this.raiseError.set('');
    try {
      const id = await this.gateway.raiseOrder(orgUnitId, this.raising());
      this.raising.set({ ...BLANK_RAISE });
      await this.open(id);
    } catch (e) {
      this.raiseError.set((e as Error).message);
    }
  }

  setHeader(field: keyof OrderHeaderDraft, value: string): void {
    this.header.update((h) => ({ ...h, [field]: value }));
  }

  setLine(index: number, field: keyof OrderLineDraft, value: string): void {
    this.lines.update((now) => now.map((l, i) => (i === index ? { ...l, [field]: value } : l)));
  }

  addLine(): void {
    this.lines.update((now) => [...now, nextLine(now)]);
  }

  dropLine(index: number): void {
    this.lines.update((now) => now.filter((_, i) => i !== index));
  }

  async act(outcome: string, action: Promise<unknown>): Promise<void> {
    this.refusal.set(null);
    this.done.set('');
    try {
      await action;
      this.done.set(outcome);
      await this.read();
    } catch (e) {
      this.refusal.set({ code: e instanceof ApiProblem ? e.code : '', message: (e as Error).message });
    }
  }

  print(): void {
    window.print();
  }
}
