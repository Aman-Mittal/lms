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

import { Component, OnInit, WritableSignal, computed, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  ApiProblem, AsnLineDraft, Choice, LmsGateway, OutboundLineDraft, Row, StockMoveDraft, WarehouseDocument,
} from '../gateway/lms-gateway';
import { Session } from '../session';
import { Qr } from './qr';

type Tab = 'inbound' | 'stock' | 'outbound' | 'waves' | 'setup';
type Columns = Array<[string, string]>;

const ASN_COLUMNS: Columns = [['asnNo', 'ASN'], ['status', 'Status'], ['crossDock', 'Cross-dock'], ['receivedAt', 'Received']];
const ASN_LINE_COLUMNS: Columns = [
  ['lineNo', '#'], ['skuCode', 'SKU'], ['lotNo', 'Lot'], ['expectedQty', 'Expected'], ['receivedQty', 'Received'],
  ['damagedQty', 'Damaged'], ['shortQty', 'Short'], ['excessQty', 'Excess'],
];
const STOCK_COLUMNS: Columns = [
  ['locationType', 'Where'], ['locationCode', 'Location'], ['skuCode', 'SKU'], ['lotNo', 'Lot'], ['onHand', 'On hand'],
];
const MOVEMENT_COLUMNS: Columns = [
  ['occurredAt', 'When'], ['movementType', 'Movement'], ['locationType', 'Where'], ['lotNo', 'Lot'],
  ['quantity', 'Quantity'], ['reason', 'Reason'], ['actor', 'By'],
];
const ORDER_COLUMNS: Columns = [
  ['orderNo', 'Order'], ['status', 'Status'], ['shipTo', 'Ship to'], ['orderedUnits', 'Ordered'],
  ['pickedUnits', 'Picked'], ['backOrderedUnits', 'Back-ordered'],
];
const ORDER_LINE_COLUMNS: Columns = [
  ['lineNo', '#'], ['skuCode', 'SKU'], ['orderedQty', 'Ordered'], ['pickedQty', 'Picked'], ['shortQty', 'Short'],
];
const UNIT_COLUMNS: Columns = [
  ['huNo', 'Unit'], ['status', 'Status'], ['source', 'Source'], ['dispatchRef', 'Dispatch ref'], ['dispatchedAt', 'Dispatched'],
];
const CONTENT_COLUMNS: Columns = [['skuCode', 'SKU'], ['lotNo', 'Lot'], ['quantity', 'Quantity']];
const WAVE_COLUMNS: Columns = [['waveNo', 'Wave'], ['status', 'Status'], ['orderCount', 'Orders'], ['pickedAt', 'Picked']];
const WAVE_ORDER_COLUMNS: Columns = [
  ['seq', '#'], ['orderNo', 'Order'], ['status', 'Status'], ['orderedUnits', 'Ordered'], ['pickedUnits', 'Picked'],
  ['shortUnits', 'Short'],
];
const BIN_COLUMNS: Columns = [['code', 'Bin'], ['capacityUnits', 'Capacity'], ['occupiedUnits', 'Occupied']];
const SKU_COLUMNS: Columns = [['code', 'SKU'], ['description', 'Description'], ['uom', 'Unit']];

const blankMove = (): StockMoveDraft => ({ binId: '', skuId: '', lotNo: '', quantity: '', reason: '' });
const blankAsnLine = (): AsnLineDraft => ({ skuId: '', lotNo: '', quantity: '' });
const blankOutLine = (): OutboundLineDraft => ({ skuId: '', quantity: '' });

/** A table of rows, one column per field; rows open when the table is given somewhere to send them. */
@Component({
  selector: 'app-wh-table',
  template: `
    <div class="table-scroll">
      <table>
        <thead>
          <tr>@for (c of columns(); track c[0]) { <th>{{ c[1] }}</th> }</tr>
        </thead>
        <tbody>
          @for (row of rows(); track $index) {
            @if (openable()) {
              <tr [attr.data-testid]="rowTestId()" tabindex="0" [class.selected]="row['id'] === selected()"
                  (click)="opened.emit(row['id'])" (keydown.enter)="opened.emit(row['id'])">
                @for (c of columns(); track c[0]) { <td>{{ row[c[0]] }}</td> }
              </tr>
            } @else {
              <tr [attr.data-testid]="rowTestId()">
                @for (c of columns(); track c[0]) { <td>{{ row[c[0]] }}</td> }
              </tr>
            }
          }
        </tbody>
      </table>
    </div>
  `,
})
export class WhTable {
  readonly rows = input.required<Row[]>();
  readonly columns = input.required<Columns>();
  readonly rowTestId = input.required<string>();
  readonly openable = input(false);
  readonly selected = input<string | null>(null);
  readonly opened = output<string>();
}

/**
 * One warehouse's floor: goods in and put away, what is on hand and in quarantine,
 * orders picked, packed and dispatched, pick waves, and the bins and SKUs it all runs on.
 */
@Component({
  selector: 'app-warehouse-floor',
  imports: [FormsModule, WhTable, Qr],
  template: `
    <section data-testid="warehouse-desk">
      @if (error()) { <p class="error" role="alert">{{ error() }}</p> }
      <datalist id="bin-options">
        @for (b of bins(); track b['id']) { <option [value]="b['id']">{{ b['code'] }}</option> }
      </datalist>
      <datalist id="sku-options">
        @for (s of skus(); track s['id']) { <option [value]="s['id']">{{ s['code'] }} · {{ s['description'] }}</option> }
      </datalist>
      <nav class="inline" aria-label="Warehouse sections">
        @for (t of tabs; track t[0]) {
          <button [class.link]="tab() !== t[0]" [attr.aria-pressed]="tab() === t[0]"
                  [attr.data-testid]="'tab-' + t[0]" (click)="show(t[0])">{{ t[1] }}</button>
        }
      </nav>

      @switch (tab()) {
        @case ('inbound') {
          <h2>Advance shipping notices</h2>
          @if (loaded() && asns().length === 0) {
            <p class="muted" data-testid="no-asns">Nothing is expected at this warehouse.</p>
          } @else {
            <app-wh-table [rows]="asns()" [columns]="asnColumns" rowTestId="asn-row" [openable]="true"
                          [selected]="asnId()" (opened)="openAsn($event)" />
          }
          @if (asn(); as a) {
            <section data-testid="asn-panel" aria-label="ASN">
              <h3>{{ a.header['asnNo'] }} <span class="muted" data-testid="asn-status">{{ a.header['status'] }}</span></h3>
              <app-wh-table [rows]="a.lines" [columns]="asnLineColumns" rowTestId="asn-line" />
              @if (a.header['status'] === 'EXPECTED' && session.can('WAREHOUSE_OPERATE')) {
                <form (ngSubmit)="receive()" data-testid="receive-asn">
                  @for (l of a.lines; track l['lineNo']) {
                    <div class="inline">
                      <span>Line {{ l['lineNo'] }} · {{ l['skuCode'] }}</span>
                      <input [name]="'received' + l['lineNo']" [attr.aria-label]="'Line ' + l['lineNo'] + ' received'"
                             inputmode="numeric" [value]="countOf(l).received"
                             (input)="setCount(l, 'received', $any($event.target).value)" required />
                      <input [name]="'damaged' + l['lineNo']" [attr.aria-label]="'Line ' + l['lineNo'] + ' damaged'"
                             inputmode="numeric" [value]="countOf(l).damaged"
                             (input)="setCount(l, 'damaged', $any($event.target).value)" />
                    </div>
                  }
                  <label><input name="crossDock" type="checkbox" [checked]="crossDock()"
                                (change)="crossDock.set($any($event.target).checked)" /> Cross-dock</label>
                  <p><button type="submit">Receive</button></p>
                </form>
              }
            </section>
          }
          @if (session.can('WAREHOUSE_OPERATE')) {
            <h3>Expect a delivery</h3>
            <form (ngSubmit)="createAsn()" data-testid="create-asn">
              <div class="inline">
                <input name="asnNo" aria-label="ASN number" placeholder="ASN number"
                       [value]="asnNo()" (input)="asnNo.set($any($event.target).value)" required />
                <input name="supplier" aria-label="Supplier id" placeholder="Supplier id (optional)"
                       [value]="supplier()" (input)="supplier.set($any($event.target).value)" />
              </div>
              @for (l of asnLines(); track $index; let i = $index) {
                <div class="inline">
                  <input [name]="'asnSku' + i" [attr.aria-label]="'Line ' + (i + 1) + ' SKU'" placeholder="SKU id" list="sku-options"
                         [value]="l.skuId" (input)="setLine(asnLines, i, 'skuId', $any($event.target).value)" required />
                  <input [name]="'asnLot' + i" [attr.aria-label]="'Line ' + (i + 1) + ' lot'" placeholder="Lot (optional)"
                         [value]="l.lotNo" (input)="setLine(asnLines, i, 'lotNo', $any($event.target).value)" />
                  <input [name]="'asnQty' + i" [attr.aria-label]="'Line ' + (i + 1) + ' quantity'" placeholder="Quantity"
                         inputmode="numeric" [value]="l.quantity"
                         (input)="setLine(asnLines, i, 'quantity', $any($event.target).value)" required />
                </div>
              }
              <button type="button" class="link" (click)="asnLines.set([...asnLines(), blankAsnLine()])">Add line</button>
              <p><button type="submit">Create ASN</button></p>
            </form>
            <h3>Put away</h3>
            <form class="inline" (ngSubmit)="putaway()" data-testid="putaway">
              <input name="putBin" aria-label="Bin" placeholder="Bin id" list="bin-options"
                     [value]="put().binId" (input)="setMove(put, 'binId', $any($event.target).value)" required />
              <input name="putSku" aria-label="SKU" placeholder="SKU id" list="sku-options"
                     [value]="put().skuId" (input)="setMove(put, 'skuId', $any($event.target).value)" required />
              <input name="putLot" aria-label="Lot" placeholder="Lot (optional)"
                     [value]="put().lotNo" (input)="setMove(put, 'lotNo', $any($event.target).value)" />
              <input name="putQty" aria-label="Quantity" placeholder="Quantity" inputmode="numeric"
                     [value]="put().quantity" (input)="setMove(put, 'quantity', $any($event.target).value)" required />
              <button type="submit">Put away</button>
            </form>
          }
        }

        @case ('stock') {
          <h2>On hand</h2>
          @if (loaded() && stock().length === 0) {
            <p class="muted" data-testid="no-stock">Nothing is on hand here.</p>
          } @else {
            <app-wh-table [rows]="stock()" [columns]="stockColumns" rowTestId="stock-row" />
          }
          <h3>Movements</h3>
          <form class="inline" (ngSubmit)="showMovements()" data-testid="movements">
            <input name="movementSku" aria-label="SKU for movements" placeholder="SKU id" list="sku-options"
                   [value]="movementSku()" (input)="movementSku.set($any($event.target).value)" required />
            <button type="submit">Show movements</button>
          </form>
          @if (movementsShown()) {
            <app-wh-table [rows]="movements()" [columns]="movementColumns" rowTestId="movement-row" />
          }
          @if (session.can('INVENTORY_ADJUST')) {
            @for (m of moves; track m.kind) {
              <h3>{{ m.title }}</h3>
              <form class="inline" (ngSubmit)="stockMove(m.kind)" [attr.data-testid]="m.testId">
                @if (m.kind !== 'write-off') {
                  <input [name]="m.kind + 'Bin'" aria-label="Bin" placeholder="Bin id" list="bin-options"
                         [value]="m.draft().binId" (input)="setMove(m.draft, 'binId', $any($event.target).value)" required />
                }
                <input [name]="m.kind + 'Sku'" aria-label="SKU" placeholder="SKU id" list="sku-options"
                       [value]="m.draft().skuId" (input)="setMove(m.draft, 'skuId', $any($event.target).value)" required />
                <input [name]="m.kind + 'Lot'" aria-label="Lot" placeholder="Lot (optional)"
                       [value]="m.draft().lotNo" (input)="setMove(m.draft, 'lotNo', $any($event.target).value)" />
                <input [name]="m.kind + 'Qty'" aria-label="Quantity" placeholder="Quantity" inputmode="numeric"
                       [value]="m.draft().quantity" (input)="setMove(m.draft, 'quantity', $any($event.target).value)" required />
                <input [name]="m.kind + 'Reason'" aria-label="Reason" placeholder="Reason"
                       [value]="m.draft().reason" (input)="setMove(m.draft, 'reason', $any($event.target).value)" required />
                <button type="submit">{{ m.button }}</button>
              </form>
            }
          }
        }

        @case ('outbound') {
          <h2>Outbound orders</h2>
          @if (loaded() && orders().length === 0) {
            <p class="muted" data-testid="no-orders">No order is going out of this warehouse.</p>
          } @else {
            <app-wh-table [rows]="orders()" [columns]="orderColumns" rowTestId="outbound-row" [openable]="true"
                          [selected]="orderId()" (opened)="openOrder($event)" />
          }
          @if (order(); as o) {
            <section data-testid="outbound-panel" aria-label="Outbound order">
              <h3>{{ o.header['orderNo'] }} <span class="muted" data-testid="outbound-status">{{ o.header['status'] }}</span></h3>
              <app-wh-table [rows]="o.lines" [columns]="orderLineColumns" rowTestId="outbound-line" />
              <p class="actions">
                @if (session.can('WAREHOUSE_OPERATE') && (o.header['status'] === 'OPEN' || o.header['status'] === 'BACK_ORDERED')) {
                  <button data-testid="pick-order" (click)="pickOrder()">Pick</button>
                }
                @if (session.can('WAREHOUSE_OPERATE') && (o.header['status'] === 'PICKED' || o.header['status'] === 'SHORT_PICKED')) {
                  <button data-testid="pack-order" (click)="packOrder()">Pack</button>
                }
                @if (session.can('WAREHOUSE_OPERATE') && o.header['status'] === 'BACK_ORDERED') {
                  <button data-testid="cancel-back-order" (click)="cancelBackOrder()">Cancel back order</button>
                }
                <button class="link" data-testid="print-pick-list" (click)="pickList.set(true)">Print pick list</button>
              </p>
              @if (pickList()) {
                <section class="slip manifest" data-testid="pick-list" aria-label="Pick list to print">
                  <h3>Pick list {{ o.header['orderNo'] }}</h3>
                  <p>@if (o.header['shipTo']) { Ship to {{ o.header['shipTo'] }} · } raised {{ o.header['createdAt'] }}</p>
                  <table>
                    <thead><tr><th>#</th><th>SKU</th><th>Quantity</th><th>From bins</th><th>Picked</th></tr></thead>
                    <tbody>
                      @for (l of o.lines; track l['lineNo']) {
                        <tr data-testid="pick-list-row">
                          <td>{{ l['lineNo'] }}</td><td>{{ l['skuCode'] }}</td><td>{{ l['orderedQty'] }}</td>
                          <td>{{ binsHolding(l['skuId']) }}</td><td>&nbsp;</td>
                        </tr>
                      }
                    </tbody>
                  </table>
                  <p class="actions no-print">
                    <button data-testid="print-now" (click)="print()">Print</button>
                    <button class="link" (click)="pickList.set(false)">Close</button>
                  </p>
                </section>
              }
            </section>
          }
          @if (session.can('WAREHOUSE_OPERATE')) {
            <h3>Send goods out</h3>
            <form (ngSubmit)="createOutbound()" data-testid="create-outbound">
              <div class="inline">
                <input name="orderNo" aria-label="Order number" placeholder="Order number"
                       [value]="orderNo()" (input)="orderNo.set($any($event.target).value)" required />
                <input name="shipTo" aria-label="Ship to" placeholder="Ship to (optional)"
                       [value]="shipTo()" (input)="shipTo.set($any($event.target).value)" />
              </div>
              @for (l of outLines(); track $index; let i = $index) {
                <div class="inline">
                  <input [name]="'outSku' + i" [attr.aria-label]="'Line ' + (i + 1) + ' SKU'" placeholder="SKU id" list="sku-options"
                         [value]="l.skuId" (input)="setLine(outLines, i, 'skuId', $any($event.target).value)" required />
                  <input [name]="'outQty' + i" [attr.aria-label]="'Line ' + (i + 1) + ' quantity'" placeholder="Quantity"
                         inputmode="numeric" [value]="l.quantity"
                         (input)="setLine(outLines, i, 'quantity', $any($event.target).value)" required />
                </div>
              }
              <button type="button" class="link" (click)="outLines.set([...outLines(), blankOutLine()])">Add line</button>
              <p><button type="submit">Create order</button></p>
            </form>
          }

          <h2>Handling units</h2>
          @if (loaded() && units().length === 0) {
            <p class="muted" data-testid="no-units">Nothing has been packed here yet.</p>
          } @else {
            <app-wh-table [rows]="units()" [columns]="unitColumns" rowTestId="unit-row" [openable]="true"
                          [selected]="unitId()" (opened)="openUnit($event)" />
          }
          @if (unit(); as u) {
            <section data-testid="unit-panel" aria-label="Handling unit">
              <h3>{{ u.header['huNo'] }} <span class="muted" data-testid="unit-status">{{ u.header['status'] }}</span></h3>
              <app-wh-table [rows]="u.lines" [columns]="contentColumns" rowTestId="unit-content" />
              <p><button class="link" data-testid="print-unit-label" (click)="label.set(true)">Print label</button></p>
              @if (label()) {
                <section class="slip" data-testid="unit-label" aria-label="Label to print">
                  <div class="slip-body">
                    <dl>
                      <div><dt>Unit</dt><dd>{{ u.header['huNo'] }}</dd></div>
                      <div><dt>Source</dt><dd>{{ u.header['source'] }}</dd></div>
                      <div><dt>Contents</dt><dd>{{ contentsOf(u) }}</dd></div>
                      @if (u.header['grossWeightKg']) { <div><dt>Gross</dt><dd>{{ u.header['grossWeightKg'] }} kg</dd></div> }
                    </dl>
                    <app-qr [payload]="'LMS:HU:' + u.header['huNo']" />
                  </div>
                  <p class="actions no-print">
                    <button data-testid="print-now" (click)="print()">Print</button>
                    <button class="link" (click)="label.set(false)">Close</button>
                  </p>
                </section>
              }
              @if (u.header['status'] === 'PACKED' && session.can('WAREHOUSE_OPERATE')) {
                <form (ngSubmit)="dispatch()" data-testid="dispatch-unit">
                  <div class="inline">
                    <input name="reference" aria-label="Dispatch reference" placeholder="Dispatch reference"
                           [value]="dispatchRef()" (input)="dispatchRef.set($any($event.target).value)" />
                    <input name="destination" aria-label="Destination terminal" placeholder="Destination terminal (optional)"
                           list="terminal-options" [value]="destination()" (input)="destination.set($any($event.target).value)" />
                    <datalist id="terminal-options">
                      @for (t of terminals(); track t.id) { <option [value]="t.id">{{ t.label }}</option> }
                    </datalist>
                    <input name="consignee" aria-label="Consignee id" placeholder="Consignee id (optional)"
                           [value]="consignee()" (input)="consignee.set($any($event.target).value)" />
                    <input name="weight" aria-label="Gross weight kg" placeholder="Gross kg (optional)" inputmode="decimal"
                           [value]="weight()" (input)="weight.set($any($event.target).value)" />
                  </div>
                  <p class="muted">A destination, a consignee and a weight hand the unit to transport as a consignment.</p>
                  <p><button type="submit">Dispatch</button></p>
                </form>
              }
            </section>
          }
        }

        @case ('waves') {
          <h2>Pick waves</h2>
          @if (loaded() && waves().length === 0) {
            <p class="muted" data-testid="no-waves">No wave has been released here.</p>
          } @else {
            <app-wh-table [rows]="waves()" [columns]="waveColumns" rowTestId="wave-row" [openable]="true"
                          [selected]="waveId()" (opened)="openWave($event)" />
          }
          @if (wave(); as w) {
            <section data-testid="wave-panel" aria-label="Pick wave">
              <h3>{{ w.header['waveNo'] }} <span class="muted" data-testid="wave-status">{{ w.header['status'] }}</span></h3>
              <app-wh-table [rows]="w.lines" [columns]="waveOrderColumns" rowTestId="wave-order" />
              @if (w.header['status'] === 'RELEASED' && session.can('WAREHOUSE_OPERATE')) {
                <p><button data-testid="pick-wave" (click)="pickWave()">Pick wave</button></p>
              }
            </section>
          }
          @if (session.can('WAREHOUSE_OPERATE')) {
            <h3>Release a wave</h3>
            @if (openOrders().length === 0) {
              <p class="muted">No open order is waiting to be picked.</p>
            } @else {
              <form (ngSubmit)="releaseWave()" data-testid="release-wave">
                @for (o of openOrders(); track o['id']) {
                  <label class="inline">
                    <input type="checkbox" [name]="'wave' + o['id']" [attr.aria-label]="'Wave ' + o['orderNo']"
                           [checked]="chosen().includes(o['id'])" (change)="choose(o['id'], $any($event.target).checked)" />
                    {{ o['orderNo'] }} · {{ o['orderedUnits'] }} units
                  </label>
                }
                <p><button type="submit" [disabled]="chosen().length === 0">Release wave</button></p>
              </form>
            }
          }
        }

        @case ('setup') {
          <h2>Bins</h2>
          @if (loaded() && bins().length === 0) {
            <p class="muted" data-testid="no-bins">This warehouse has no bins yet.</p>
          } @else {
            <app-wh-table [rows]="bins()" [columns]="binColumns" rowTestId="bin-row" />
          }
          @if (session.can('WAREHOUSE_SETUP')) {
            <form class="inline" (ngSubmit)="createBin()" data-testid="create-bin">
              <input name="binCode" aria-label="Bin code" placeholder="Bin code"
                     [value]="binCode()" (input)="binCode.set($any($event.target).value)" required />
              <input name="capacity" aria-label="Capacity units" placeholder="Capacity units" inputmode="numeric"
                     [value]="capacity()" (input)="capacity.set($any($event.target).value)" required />
              <button type="submit">Create bin</button>
            </form>
          }
          <h2>SKUs</h2>
          <app-wh-table [rows]="skus()" [columns]="skuColumns" rowTestId="sku-row" />
          @if (session.can('WAREHOUSE_SETUP')) {
            <form class="inline" (ngSubmit)="createSku()" data-testid="create-sku">
              <input name="skuCode" aria-label="SKU code" placeholder="SKU code"
                     [value]="skuCode()" (input)="skuCode.set($any($event.target).value)" required />
              <input name="skuDescription" aria-label="SKU description" placeholder="Description"
                     [value]="skuDescription()" (input)="skuDescription.set($any($event.target).value)" required />
              <input name="skuOwner" aria-label="Owner id" placeholder="Owner client id (optional)"
                     [value]="skuOwner()" (input)="skuOwner.set($any($event.target).value)" />
              <input name="skuUom" aria-label="Unit of measure" placeholder="Unit (optional)"
                     [value]="skuUom()" (input)="skuUom.set($any($event.target).value)" />
              <button type="submit">Create SKU</button>
            </form>
          }
        }
      }

      @if (done()) { <p data-testid="wh-done">{{ done() }}</p> }
      @if (refusal(); as r) {
        <p class="error" role="alert">
          @if (r.code) { <code data-testid="wh-refusal">{{ r.code }}</code> }
          {{ r.message }}
        </p>
      }
    </section>
  `,
})
export class WarehouseFloor implements OnInit {
  private readonly gateway = inject(LmsGateway);
  readonly session = inject(Session);

  readonly warehouseId = input.required<string>();

  readonly tabs: Array<[Tab, string]> = [
    ['inbound', 'Inbound'], ['stock', 'Stock'], ['outbound', 'Outbound'], ['waves', 'Pick waves'], ['setup', 'Setup'],
  ];
  readonly asnColumns = ASN_COLUMNS;
  readonly asnLineColumns = ASN_LINE_COLUMNS;
  readonly stockColumns = STOCK_COLUMNS;
  readonly movementColumns = MOVEMENT_COLUMNS;
  readonly orderColumns = ORDER_COLUMNS;
  readonly orderLineColumns = ORDER_LINE_COLUMNS;
  readonly unitColumns = UNIT_COLUMNS;
  readonly contentColumns = CONTENT_COLUMNS;
  readonly waveColumns = WAVE_COLUMNS;
  readonly waveOrderColumns = WAVE_ORDER_COLUMNS;
  readonly binColumns = BIN_COLUMNS;
  readonly skuColumns = SKU_COLUMNS;
  readonly blankAsnLine = blankAsnLine;
  readonly blankOutLine = blankOutLine;

  readonly tab = signal<Tab>('inbound');
  readonly loaded = signal(false);
  readonly error = signal('');
  readonly done = signal('');
  readonly refusal = signal<{ code: string; message: string } | null>(null);

  readonly bins = signal<Row[]>([]);
  readonly skus = signal<Row[]>([]);
  readonly stock = signal<Row[]>([]);
  readonly asns = signal<Row[]>([]);
  readonly orders = signal<Row[]>([]);
  readonly units = signal<Row[]>([]);
  readonly waves = signal<Row[]>([]);
  readonly terminals = signal<Choice[]>([]);
  readonly openOrders = computed(() => this.orders().filter((o) => o['status'] === 'OPEN'));

  readonly asnId = signal<string | null>(null);
  readonly asn = signal<WarehouseDocument | null>(null);
  readonly counts = signal<Record<string, { received: string; damaged: string }>>({});
  readonly crossDock = signal(false);
  readonly asnNo = signal('');
  readonly supplier = signal('');
  readonly asnLines = signal<AsnLineDraft[]>([blankAsnLine()]);
  readonly put = signal<StockMoveDraft>(blankMove());

  readonly movementSku = signal('');
  readonly movementsShown = signal(false);
  readonly movements = signal<Row[]>([]);
  readonly moves: Array<{
    kind: 'adjust' | 'release' | 'write-off'; title: string; testId: string; button: string;
    draft: WritableSignal<StockMoveDraft>;
  }> = [
    { kind: 'adjust', title: 'Adjust a bin', testId: 'adjust-stock', button: 'Adjust', draft: signal(blankMove()) },
    { kind: 'release', title: 'Release from quarantine', testId: 'release-quarantine', button: 'Release', draft: signal(blankMove()) },
    { kind: 'write-off', title: 'Write off quarantine', testId: 'write-off-quarantine', button: 'Write off', draft: signal(blankMove()) },
  ];

  readonly orderId = signal<string | null>(null);
  readonly order = signal<WarehouseDocument | null>(null);
  readonly pickList = signal(false);
  readonly orderNo = signal('');
  readonly shipTo = signal('');
  readonly outLines = signal<OutboundLineDraft[]>([blankOutLine()]);
  readonly unitId = signal<string | null>(null);
  readonly unit = signal<WarehouseDocument | null>(null);
  readonly label = signal(false);
  readonly dispatchRef = signal('');
  readonly destination = signal('');
  readonly consignee = signal('');
  readonly weight = signal('');

  readonly waveId = signal<string | null>(null);
  readonly wave = signal<WarehouseDocument | null>(null);
  readonly chosen = signal<string[]>([]);

  readonly binCode = signal('');
  readonly capacity = signal('');
  readonly skuCode = signal('');
  readonly skuDescription = signal('');
  readonly skuOwner = signal('');
  readonly skuUom = signal('');

  ngOnInit(): void {
    void this.refresh();
    this.gateway.terminalChoices().then((t) => this.terminals.set(t), () => undefined);
  }

  /** Reads the warehouse again, and whatever record is open. */
  async refresh(): Promise<void> {
    const id = this.warehouseId();
    this.error.set('');
    try {
      const [bins, skus, stock, asns, orders, units, waves] = await Promise.all([
        this.gateway.warehouseBins(id), this.gateway.skus(), this.gateway.stock(id), this.gateway.asns(id),
        this.gateway.outboundOrders(id), this.gateway.handlingUnits(id), this.gateway.pickWaves(id),
      ]);
      this.bins.set(bins);
      this.skus.set(skus);
      this.stock.set(stock);
      this.asns.set(asns);
      this.orders.set(orders);
      this.units.set(units);
      this.waves.set(waves);
      this.loaded.set(true);
      await this.reopen();
    } catch (e) {
      this.error.set((e as Error).message);
    }
  }

  private async reopen(): Promise<void> {
    const asn = this.asnId();
    const order = this.orderId();
    const unit = this.unitId();
    const wave = this.waveId();
    const [a, o, u, w] = await Promise.all([
      asn ? this.gateway.asn(asn) : null, order ? this.gateway.outboundOrder(order) : null,
      unit ? this.gateway.handlingUnit(unit) : null, wave ? this.gateway.pickWave(wave) : null,
    ]);
    this.asn.set(a);
    this.order.set(o);
    this.unit.set(u);
    this.wave.set(w);
    if (this.movementsShown()) {
      this.movements.set(await this.gateway.stockMovements(this.movementSku().trim()));
    }
  }

  show(tab: Tab): void {
    this.tab.set(tab);
    this.done.set('');
    this.refusal.set(null);
  }

  /** Runs an action, says what happened (or names the refusal), and reads everything again. */
  private async act(action: () => Promise<unknown>, confirmation: string): Promise<boolean> {
    this.done.set('');
    this.refusal.set(null);
    try {
      await action();
      this.done.set(confirmation);
      await this.refresh();
      return true;
    } catch (e) {
      this.refusal.set({ code: e instanceof ApiProblem ? e.code : '', message: (e as Error).message });
      return false;
    }
  }

  setMove(draft: WritableSignal<StockMoveDraft>, field: keyof StockMoveDraft, value: string): void {
    draft.set({ ...draft(), [field]: value });
  }

  setLine<T extends AsnLineDraft | OutboundLineDraft>(lines: WritableSignal<T[]>, i: number, field: string, value: string): void {
    lines.set(lines().map((l, j) => (j === i ? { ...l, [field]: value } : l)));
  }

  // ---------------------------------------------------------------- inbound

  async openAsn(id: string): Promise<void> {
    this.asnId.set(id);
    this.counts.set({});
    this.crossDock.set(false);
    await this.reopen();
  }

  countOf(line: Row): { received: string; damaged: string } {
    return this.counts()[line['lineNo']] ?? { received: line['expectedQty'], damaged: '0' };
  }

  setCount(line: Row, field: 'received' | 'damaged', value: string): void {
    this.counts.set({ ...this.counts(), [line['lineNo']]: { ...this.countOf(line), [field]: value } });
  }

  async receive(): Promise<void> {
    const id = this.asnId();
    const a = this.asn();
    if (!id || !a) {
      return;
    }
    await this.act(() => this.gateway.receiveAsn(id, a.lines.map((l) => ({
      lineNo: Number(l['lineNo']), receivedQty: this.countOf(l).received, damagedQty: this.countOf(l).damaged,
    })), this.crossDock()), `ASN ${a.header['asnNo']} received.`);
  }

  async createAsn(): Promise<void> {
    const asnNo = this.asnNo();
    let id = '';
    const ok = await this.act(async () => {
      id = await this.gateway.createAsn({
        warehouseId: this.warehouseId(), asnNo, supplierPartnerId: this.supplier(), lines: this.asnLines(),
      });
      this.asnId.set(id);
    }, `ASN ${asnNo} is expected.`);
    if (ok) {
      this.asnNo.set('');
      this.supplier.set('');
      this.asnLines.set([blankAsnLine()]);
    }
  }

  async putaway(): Promise<void> {
    const move = this.put();
    if (await this.act(() => this.gateway.putaway(move), `Put away ${move.quantity}.`)) {
      this.put.set(blankMove());
    }
  }

  // ---------------------------------------------------------------- stock

  async showMovements(): Promise<void> {
    try {
      this.movements.set(await this.gateway.stockMovements(this.movementSku().trim()));
      this.movementsShown.set(true);
    } catch (e) {
      this.error.set((e as Error).message);
    }
  }

  async stockMove(kind: 'adjust' | 'release' | 'write-off'): Promise<void> {
    const m = this.moves.find((x) => x.kind === kind);
    if (!m) {
      return;
    }
    const draft = m.draft();
    const action = kind === 'adjust' ? () => this.gateway.adjustStock(draft)
      : kind === 'release' ? () => this.gateway.releaseQuarantine(draft)
        : () => this.gateway.writeOffQuarantine(this.warehouseId(), draft);
    if (await this.act(action, `${m.button}: ${draft.quantity} recorded.`)) {
      m.draft.set(blankMove());
    }
  }

  // ---------------------------------------------------------------- outbound

  async openOrder(id: string): Promise<void> {
    this.orderId.set(id);
    this.pickList.set(false);
    await this.reopen();
  }

  /** The bins a SKU can be picked from, with what each holds. */
  binsHolding(skuId: string): string {
    return this.stock().filter((s) => s['locationType'] === 'BIN' && s['skuId'] === skuId)
      .map((s) => `${s['locationCode']} (${s['onHand']})`).join(', ');
  }

  async pickOrder(): Promise<void> {
    const id = this.orderId();
    if (id) {
      await this.act(() => this.gateway.pickOutboundOrder(id), `Order ${this.order()?.header['orderNo']} picked.`);
    }
  }

  async packOrder(): Promise<void> {
    const id = this.orderId();
    if (id) {
      await this.act(async () => { this.unitId.set(await this.gateway.packOutboundOrder(id)); },
        `Order ${this.order()?.header['orderNo']} packed.`);
    }
  }

  async cancelBackOrder(): Promise<void> {
    const id = this.orderId();
    if (id) {
      await this.act(() => this.gateway.cancelBackOrder(id), 'Back order cancelled.');
    }
  }

  async createOutbound(): Promise<void> {
    const orderNo = this.orderNo();
    const ok = await this.act(async () => {
      this.orderId.set(await this.gateway.createOutboundOrder({
        warehouseId: this.warehouseId(), orderNo, shipTo: this.shipTo(), lines: this.outLines(),
      }));
    }, `Order ${orderNo} is open.`);
    if (ok) {
      this.orderNo.set('');
      this.shipTo.set('');
      this.outLines.set([blankOutLine()]);
    }
  }

  async openUnit(id: string): Promise<void> {
    this.unitId.set(id);
    this.label.set(false);
    await this.reopen();
  }

  contentsOf(unit: WarehouseDocument): string {
    return unit.lines.map((l) => `${l['skuCode']} × ${l['quantity']}`).join(', ');
  }

  async dispatch(): Promise<void> {
    const id = this.unitId();
    if (id) {
      await this.act(() => this.gateway.dispatchHandlingUnit(id, {
        reference: this.dispatchRef(), destinationTerminalId: this.destination(), consigneePartnerId: this.consignee(),
        grossWeightKg: this.weight(),
      }), `Unit ${this.unit()?.header['huNo']} dispatched.`);
    }
  }

  // ---------------------------------------------------------------- waves

  async openWave(id: string): Promise<void> {
    this.waveId.set(id);
    await this.reopen();
  }

  choose(orderId: string, on: boolean): void {
    this.chosen.set(on ? [...this.chosen(), orderId] : this.chosen().filter((c) => c !== orderId));
  }

  async releaseWave(): Promise<void> {
    const orderIds = this.chosen();
    if (await this.act(async () => {
      this.waveId.set(await this.gateway.releasePickWave(this.warehouseId(), orderIds));
    }, `Wave released with ${orderIds.length} orders.`)) {
      this.chosen.set([]);
    }
  }

  async pickWave(): Promise<void> {
    const id = this.waveId();
    if (id) {
      await this.act(() => this.gateway.pickPickWave(id), `Wave ${this.wave()?.header['waveNo']} picked.`);
    }
  }

  // ---------------------------------------------------------------- setup

  async createBin(): Promise<void> {
    const code = this.binCode();
    if (await this.act(() => this.gateway.createBin(this.warehouseId(), code, Number(this.capacity())), `Bin ${code} created.`)) {
      this.binCode.set('');
      this.capacity.set('');
    }
  }

  async createSku(): Promise<void> {
    const code = this.skuCode();
    if (await this.act(() => this.gateway.createSku({
      code, description: this.skuDescription(), ownerPartnerId: this.skuOwner(), uom: this.skuUom(),
    }), `SKU ${code} created.`)) {
      this.skuCode.set('');
      this.skuDescription.set('');
      this.skuOwner.set('');
      this.skuUom.set('');
    }
  }

  print(): void {
    window.print();
  }
}

/** Opens a warehouse's floor by its terminal id. */
@Component({
  selector: 'app-warehouse-desk',
  imports: [FormsModule, WarehouseFloor],
  template: `
    <h1>Warehouse operations</h1>
    <p class="muted">Open a warehouse to receive, store, pick, pack and dispatch its goods.</p>
    <!-- A signal, not ngModel: ngModel connects after the screen is shown, and would wipe an id typed before it. -->
    <form class="inline" (ngSubmit)="open()" data-testid="open-warehouse">
      <input name="warehouseId" placeholder="Warehouse id" aria-label="Warehouse id" data-testid="warehouse-id"
             list="warehouse-options" [value]="typed()" (input)="typed.set($any($event.target).value)" required />
      <datalist id="warehouse-options">
        @for (c of choices(); track c.id) { <option [value]="c.id">{{ c.label }}</option> }
      </datalist>
      <button type="submit">Open</button>
    </form>
    @for (id of opened(); track id) {
      <app-warehouse-floor [warehouseId]="id" />
    }
  `,
})
export class WarehouseDesk implements OnInit {
  private readonly gateway = inject(LmsGateway);

  readonly typed = signal('');
  readonly warehouseId = signal('');
  /** The open warehouse as a one-item list, so a new warehouse gets a fresh floor. */
  readonly opened = computed(() => (this.warehouseId() ? [this.warehouseId()] : []));
  readonly choices = signal<Choice[]>([]);

  async ngOnInit(): Promise<void> {
    try {
      this.choices.set(await this.gateway.warehouseChoices());
    } catch {
      // The picker is a convenience; an id can still be typed.
    }
  }

  open(): void {
    this.warehouseId.set(this.typed().trim().toLowerCase());
  }
}
