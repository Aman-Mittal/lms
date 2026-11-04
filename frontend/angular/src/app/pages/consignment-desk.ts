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
import { ApiProblem, ConsignmentCard, ConsignmentParticularsDraft, LmsGateway } from '../gateway/lms-gateway';
import { Session } from '../session';
import { Qr } from './qr';
import { compareWithWhole, PlannedPart, splitByCapacity } from './split-plan';

/**
 * The consignment desk: find a consignment by its reference, see where it is,
 * print its lorry receipt, split one too big for a single vehicle, correct what
 * is said about it while it waits for a load, and cancel it on its own.
 *
 * Every input is a signal bound with [value] and (input), not ngModel: with no
 * zone, ngModel connects after the screen is shown and would wipe what was
 * typed before it.
 */
@Component({
  selector: 'app-consignment-desk',
  imports: [FormsModule, Qr],
  template: `
    <h1>Consignments</h1>
    <p class="muted">Look a consignment up by its lorry receipt or docket number.</p>
    <form class="inline" (ngSubmit)="lookUp()" data-testid="consignment-lookup">
      <input name="reference" placeholder="Reference" aria-label="Reference" data-testid="consignment-ref"
             [value]="typed()" (input)="typed.set($any($event.target).value)" required />
      <button type="submit">Look up</button>
    </form>
    @if (loadError()) { <p class="error" role="alert">{{ loadError() }}</p> }
    @if (card(); as c) {
      <section data-testid="consignment">
        <h2>{{ c.trackingRef }}</h2>
        <dl>
          <div><dt>Status</dt><dd data-testid="consignment-status">{{ c.status }}</dd></div>
          <div><dt>Proof of delivery</dt><dd data-testid="consignment-proof">{{ c.proofStatus === 'CONFIRMED' ? 'Confirmed, ' + c.proofCondition : c.proofStatus ? 'Code issued' : 'None yet' }}</dd></div>
          @if (c.parentId) {
            <div><dt>Part of</dt><dd>
              <button class="link" data-testid="consignment-parent" (click)="open(c.parentId)">{{ c.parentTrackingRef }}</button>
            </dd></div>
          }
          <div><dt>Order</dt><dd>{{ c.orderNo || '—' }}</dd></div>
          <div><dt>Consignee</dt><dd>{{ c.consigneeName }}</dd></div>
          <div><dt>Destination</dt><dd>{{ c.destinationTerminalCode }}</dd></div>
          <div><dt>Weight kg</dt><dd data-testid="consignment-weight">{{ c.deadWeightKg }}</dd></div>
          <div><dt>Volume m³</dt><dd>{{ c.volumeM3 }}</dd></div>
          <div><dt>Chargeable kg</dt><dd>{{ c.chargeableWeightKg }}</dd></div>
          <div><dt>Load</dt><dd>{{ c.loadNo || '—' }}</dd></div>
          <div><dt>Client reference</dt><dd data-testid="consignment-client-ref">{{ c.clientReference || '—' }}</dd></div>
          <div><dt>Consignee contact</dt><dd data-testid="consignment-contact">{{ c.contactName || '—' }}{{ c.contactPhone ? ', ' + c.contactPhone : '' }}</dd></div>
          <div><dt>Consignee reference</dt><dd>{{ c.consigneeReference || '—' }}</dd></div>
          <div><dt>Delivery window</dt><dd data-testid="consignment-window">{{ c.deliveryWindowFrom || '…' }} to {{ c.deliveryWindowTo || '…' }}</dd></div>
          <div><dt>Remarks</dt><dd data-testid="consignment-remarks">{{ c.remarks || '—' }}</dd></div>
        </dl>

        @if (amendable()) {
          <section data-testid="particulars-form">
            <h3>Particulars</h3>
            <p class="muted">What it carries is fixed; who to call, the references, the delivery window and remarks can be corrected until it is on a load. Times are ISO-8601, such as 2026-12-01T06:00:00Z.</p>
            <form (ngSubmit)="saveParticulars()">
              @for (f of particularFields; track f.key) {
                <label>{{ f.label }}
                  <input [name]="f.key" [attr.data-testid]="'particular-' + f.key" [attr.aria-label]="f.label"
                         [value]="draft()[f.key]" (input)="setParticular(f.key, $any($event.target).value)" />
                </label>
              }
              <button type="submit" data-testid="save-particulars" [disabled]="busy()">Save particulars</button>
            </form>
            @if (particularsRefusal(); as r) {
              <p class="error" role="alert" data-testid="particulars-error">
                @if (r.code) { <code data-testid="particulars-refusal">{{ r.code }}</code> } {{ r.message }}
              </p>
            }
          </section>
        }

        @if (cancellable()) {
          <section data-testid="cancel-form">
            <h3>Cancel this consignment</h3>
            <p class="muted">Takes it off its load first; its order lines go back to be planned again.</p>
            <form class="inline" (ngSubmit)="cancelConsignment()">
              <input name="reason" placeholder="Reason" aria-label="Reason for cancelling" data-testid="cancel-reason"
                     [value]="reason()" (input)="reason.set($any($event.target).value)" />
              <button type="submit" data-testid="cancel-consignment" [disabled]="busy()">Cancel consignment</button>
            </form>
          </section>
        }
        @if (cancelRefusal(); as r) {
          <p class="error" role="alert" data-testid="cancel-error">
            @if (r.code) { <code data-testid="cancel-refusal">{{ r.code }}</code> } {{ r.message }}
          </p>
        }

        @if (c.status === 'SPLIT' && c.partCount !== null) {
          <div data-testid="split-progress">
            <p>
              Split across <strong data-testid="part-count">{{ c.partCount }}</strong> vehicles:
              <strong data-testid="parts-delivered">{{ c.partsDelivered ?? 0 }}</strong> of {{ c.partCount }} delivered@if (c.partsCancelled) {, {{ c.partsCancelled }} cancelled}.
            </p>
            <progress [max]="c.partCount" [value]="c.partsDelivered ?? 0" aria-label="Parts delivered"></progress>
          </div>
          @if (partsError()) { <p class="error" role="alert">{{ partsError() }}</p> }
          @if (parts().length) {
            <table data-testid="parts">
              <thead><tr><th>Part</th><th>Status</th><th>Weight kg</th><th>Load</th></tr></thead>
              <tbody>
                @for (p of parts(); track p.id) {
                  <tr data-testid="part-row">
                    <td><button class="link" (click)="open(p.id)">{{ p.trackingRef }}</button></td>
                    <td>{{ p.status }}</td><td>{{ p.deadWeightKg }}</td><td>{{ p.loadNo || '—' }}</td>
                  </tr>
                }
              </tbody>
            </table>
          }
        }

        @if (splittable()) {
          <section data-testid="split-form">
            <h3>Split across vehicles</h3>
            <p class="muted">Too big for one vehicle? Plan equal parts that each fit one, adjust them if you need to, then split.</p>
            <form class="inline" (ngSubmit)="plan()">
              <input name="capacity" placeholder="Vehicle capacity kg" aria-label="Vehicle capacity kg"
                     data-testid="split-capacity" inputmode="decimal" required
                     [value]="capacity()" (input)="capacity.set($any($event.target).value)" />
              <button type="submit" data-testid="plan-parts">Plan parts</button>
            </form>
            @if (splitError()) { <p class="error" role="alert">{{ splitError() }}</p> }
            @if (planned().length) {
              <table data-testid="planned-parts">
                <thead><tr><th>Part</th><th>Weight kg</th><th>Volume m³</th></tr></thead>
                <tbody>
                  @for (p of planned(); track $index) {
                    <tr>
                      <td>{{ $index + 1 }}</td>
                      <td><input [attr.aria-label]="'Part ' + ($index + 1) + ' weight kg'"
                                 [attr.data-testid]="'part-weight-' + ($index + 1)" inputmode="decimal"
                                 [value]="p.deadWeightKg"
                                 (input)="edit($index, { deadWeightKg: $any($event.target).value })" /></td>
                      <td><input [attr.aria-label]="'Part ' + ($index + 1) + ' volume m³'"
                                 [attr.data-testid]="'part-volume-' + ($index + 1)" inputmode="decimal"
                                 [value]="p.volumeM3"
                                 (input)="edit($index, { volumeM3: $any($event.target).value })" /></td>
                    </tr>
                  }
                </tbody>
              </table>
              <p data-testid="split-totals" [class]="totals().ready ? 'muted' : 'error'">
                Parts total {{ totals().weightKg }} kg and {{ totals().volumeM3 }} m³, against
                {{ c.deadWeightKg }} kg and {{ c.volumeM3 }} m³{{ totals().ready ? ' — they match.' : ' — they must match exactly.' }}
              </p>
              <p>
                <button data-testid="split-submit" [disabled]="!totals().ready || busy()" (click)="submitSplit()">
                  Split into {{ planned().length }} parts
                </button>
              </p>
            }
          </section>
        }

        @if (c.status !== 'SPLIT') {
          <p><button class="link" data-testid="print-lr" (click)="slip.set(true)">Print lorry receipt</button></p>
          @if (slip()) {
            <section class="slip" data-testid="lr-slip" aria-label="Lorry receipt">
              <h3>Lorry receipt {{ c.trackingRef }}</h3>
              <div class="slip-body">
                <dl>
                  @if (c.parentId) { <div><dt>Part of</dt><dd>{{ c.parentTrackingRef }}</dd></div> }
                  <div><dt>Order</dt><dd>{{ c.orderNo || '—' }}</dd></div>
                  <div><dt>Consignee</dt><dd>{{ c.consigneeName }}</dd></div>
                  <div><dt>Destination</dt><dd>{{ c.destinationTerminalCode }}</dd></div>
                  <div><dt>Weight kg</dt><dd>{{ c.deadWeightKg }}</dd></div>
                  <div><dt>Load</dt><dd>{{ c.loadNo || '—' }}</dd></div>
                </dl>
                <app-qr [payload]="'LMS:CONSIGNMENT:' + c.id" />
              </div>
              <p class="actions no-print">
                <button data-testid="print-now" (click)="print()">Print</button>
              </p>
            </section>
          }
        }
      </section>
    }
  `,
})
export class ConsignmentDesk {
  private readonly gateway = inject(LmsGateway);
  private readonly session = inject(Session);

  readonly typed = signal('');
  readonly card = signal<ConsignmentCard | null>(null);
  readonly loadError = signal('');
  readonly parts = signal<ConsignmentCard[]>([]);
  readonly partsError = signal('');
  readonly slip = signal(false);

  readonly capacity = signal('');
  readonly planned = signal<PlannedPart[]>([]);
  readonly splitError = signal('');
  readonly busy = signal(false);

  readonly particularFields: { key: keyof ConsignmentParticularsDraft; label: string }[] = [
    { key: 'contactName', label: 'Contact name' },
    { key: 'contactPhone', label: 'Contact phone' },
    { key: 'consigneeReference', label: 'Consignee reference' },
    { key: 'clientReference', label: 'Client reference' },
    { key: 'deliveryWindowFrom', label: 'Deliver from' },
    { key: 'deliveryWindowTo', label: 'Deliver by' },
    { key: 'remarks', label: 'Remarks' },
  ];
  readonly draft = signal<ConsignmentParticularsDraft>(emptyDraft());
  readonly particularsRefusal = signal<{ code: string; message: string } | null>(null);
  readonly reason = signal('');
  readonly cancelRefusal = signal<{ code: string; message: string } | null>(null);

  readonly amendable = computed(() => {
    const c = this.card();
    return !!c && c.status === 'PLANNED' && this.session.can('LOAD_CREATE');
  });
  readonly cancellable = computed(() => {
    const c = this.card();
    return !!c && (c.status === 'PLANNED' || c.status === 'ASSIGNED_TO_LOAD') && !c.handlingUnitNo
      && !c.parentId && this.session.can('LOAD_CREATE');
  });
  readonly splittable = computed(() => {
    const c = this.card();
    return !!c && c.status === 'PLANNED' && c.freightMode === 'FTL' && !c.parentId
      && this.session.can('LOAD_CREATE');
  });
  readonly totals = computed(() => {
    const c = this.card();
    return compareWithWhole(this.planned(), c?.deadWeightKg ?? '0', c?.volumeM3 ?? '0');
  });

  async lookUp(): Promise<void> {
    await this.show(() => this.gateway.consignmentByRef(this.typed().trim()));
  }

  async open(id: string): Promise<void> {
    await this.show(() => this.gateway.consignment(id));
  }

  plan(): void {
    const c = this.card();
    this.splitError.set('');
    if (!c) {
      return;
    }
    try {
      this.planned.set(splitByCapacity(c.deadWeightKg, c.volumeM3, this.capacity()));
    } catch (e) {
      this.planned.set([]);
      this.splitError.set((e as Error).message);
    }
  }

  edit(index: number, change: Partial<PlannedPart>): void {
    this.planned.update((current) => current.map((p, i) => (i === index ? { ...p, ...change } : p)));
  }

  async submitSplit(): Promise<void> {
    const c = this.card();
    if (!c) {
      return;
    }
    this.splitError.set('');
    this.busy.set(true);
    try {
      await this.gateway.splitConsignment(c.id, this.planned());
      await this.show(() => this.gateway.consignment(c.id));
    } catch (e) {
      this.splitError.set((e as Error).message);
    } finally {
      this.busy.set(false);
    }
  }

  print(): void {
    window.print();
  }

  setParticular(key: keyof ConsignmentParticularsDraft, value: string): void {
    this.draft.update((d) => ({ ...d, [key]: value }));
  }

  async saveParticulars(): Promise<void> {
    const c = this.card();
    if (!c) {
      return;
    }
    this.particularsRefusal.set(null);
    this.busy.set(true);
    try {
      await this.gateway.amendConsignment(c.id, c.version, this.draft());
      await this.show(() => this.gateway.consignment(c.id));
    } catch (e) {
      this.particularsRefusal.set(refusalOf(e));
    } finally {
      this.busy.set(false);
    }
  }

  async cancelConsignment(): Promise<void> {
    const c = this.card();
    if (!c) {
      return;
    }
    this.cancelRefusal.set(null);
    this.busy.set(true);
    try {
      await this.gateway.cancelConsignment(c.id, this.reason());
      // Cleared before the re-read, so nothing typed survives a success.
      this.reason.set('');
      await this.show(() => this.gateway.consignment(c.id));
    } catch (e) {
      this.cancelRefusal.set(refusalOf(e));
    } finally {
      this.busy.set(false);
    }
  }

  private async show(read: () => Promise<ConsignmentCard>): Promise<void> {
    this.loadError.set('');
    this.slip.set(false);
    this.cancelRefusal.set(null);
    this.planned.set([]);
    this.capacity.set('');
    this.splitError.set('');
    this.parts.set([]);
    this.partsError.set('');
    this.particularsRefusal.set(null);
    try {
      const c = await read();
      this.card.set(c);
      this.draft.set({
        contactName: c.contactName, contactPhone: c.contactPhone, consigneeReference: c.consigneeReference,
        clientReference: c.clientReference, deliveryWindowFrom: c.deliveryWindowFrom,
        deliveryWindowTo: c.deliveryWindowTo, remarks: c.remarks,
      });
      if (c.status === 'SPLIT') {
        try {
          this.parts.set(await this.gateway.consignmentParts(c.id));
        } catch (e) {
          this.partsError.set((e as Error).message);
        }
      }
    } catch (e) {
      this.card.set(null);
      this.loadError.set((e as Error).message);
    }
  }
}

function emptyDraft(): ConsignmentParticularsDraft {
  return {
    contactName: '', contactPhone: '', consigneeReference: '', clientReference: '',
    deliveryWindowFrom: '', deliveryWindowTo: '', remarks: '',
  };
}

/** A refusal as the desk shows it: the problem's code, when there is one, and its message. */
function refusalOf(e: unknown): { code: string; message: string } {
  return { code: e instanceof ApiProblem ? e.code : '', message: (e as Error).message };
}
