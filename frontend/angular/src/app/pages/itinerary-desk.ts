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
import { ApiProblem, LmsGateway, MappedDetail, Row } from '../gateway/lms-gateway';
import { ItineraryLegDraft, ItineraryPlanDraft } from '../gateway/milk-itinerary-types';
import { Session } from '../session';
import { EntityPicker } from './entity-picker';
import { ErrorBanner } from './error-banner';
import { PageHeader } from './page-header';
import { StatusBadge } from './status-badge';

/** The rail milestone each status is waiting for, in the order a railway reports them. */
const RAIL_NEXT: Record<string, string | undefined> = {
  PLANNED: 'PLACED', PLACED: 'LOADED', LOADED: 'DEPARTED', DEPARTED: 'ARRIVED', ARRIVED: 'RELEASED',
};

interface Refusal {
  code: string;
  message: string;
}

const BLANK_LEG: ItineraryLegDraft = {
  mode: 'ROAD', fromTerminalId: '', toTerminalId: '', plannedDepartureAt: '', plannedArrivalAt: '', carrierPartnerId: '',
  tripId: '', unitType: '', unitCount: '', bookingRef: '', trainNo: '', costAmount: '',
};

function blankPlan(): ItineraryPlanDraft {
  return { shipmentRef: '', currency: '', legs: [{ ...BLANK_LEG }] };
}

/**
 * The itinerary desk: plan a multimodal itinerary leg by leg and record a rail leg's milestones. Road legs move with
 * their trips and have no button here. The server decides what each state allows; every refusal is shown by its code.
 */
@Component({
  selector: 'app-itinerary-desk',
  imports: [EntityPicker, ErrorBanner, FormsModule, PageHeader, StatusBadge],
  template: `
    <app-page-header title="Itinerary desk" />
    <p class="muted">Plan a road, rail and road movement leg by leg, and report each rail milestone as the railway does.</p>

    @if (session.can('MULTIMODAL_PLAN')) {
      <form ngNativeValidate data-testid="it-plan-form" (ngSubmit)="plan()">
        <h2>Plan an itinerary</h2>
        <div class="inline">
          <input name="shipmentRef" placeholder="Shipment reference" aria-label="Shipment reference" data-testid="it-shipment" required
                 [value]="draft().shipmentRef" (input)="setHead('shipmentRef', $any($event.target).value)" />
          <input name="currency" placeholder="Currency (optional)" aria-label="Itinerary currency" data-testid="it-currency"
                 [value]="draft().currency" (input)="setHead('currency', $any($event.target).value)" />
        </div>
        @for (l of draft().legs; track $index; let i = $index) {
          <fieldset data-testid="it-leg-row">
            <legend>Leg {{ i + 1 }}</legend>
            <div class="inline">
              <select [attr.aria-label]="'Mode of leg ' + (i + 1)" data-testid="it-leg-mode" [value]="l.mode"
                      (change)="setLeg(i, 'mode', $any($event.target).value)">
                <option value="ROAD" [selected]="l.mode === 'ROAD'">ROAD</option>
                <option value="RAIL" [selected]="l.mode === 'RAIL'">RAIL</option>
              </select>
              <app-entity-picker [kind]="'terminal'" placeholder="From terminal" [ariaLabel]="'From terminal of leg ' + (i + 1)"
                                 testId="it-leg-from" [required]="true" [value]="l.fromTerminalId"
                                 (valueChange)="setLeg(i, 'fromTerminalId', $event)" />
              <app-entity-picker [kind]="'terminal'" placeholder="To terminal" [ariaLabel]="'To terminal of leg ' + (i + 1)"
                                 testId="it-leg-to" [required]="true" [value]="l.toTerminalId"
                                 (valueChange)="setLeg(i, 'toTerminalId', $event)" />
              <app-entity-picker [kind]="'partner'" placeholder="Carrier" [ariaLabel]="'Carrier of leg ' + (i + 1)"
                                 testId="it-leg-carrier" [required]="true" [value]="l.carrierPartnerId"
                                 (valueChange)="setLeg(i, 'carrierPartnerId', $event)" />
            </div>
            <div class="inline">
              <input type="datetime-local" [attr.aria-label]="'Planned departure of leg ' + (i + 1)" data-testid="it-leg-departs"
                     required [value]="l.plannedDepartureAt" (input)="setLeg(i, 'plannedDepartureAt', $any($event.target).value)" />
              <input type="datetime-local" [attr.aria-label]="'Planned arrival of leg ' + (i + 1)" data-testid="it-leg-arrives"
                     required [value]="l.plannedArrivalAt" (input)="setLeg(i, 'plannedArrivalAt', $any($event.target).value)" />
              <input type="number" min="0" step="any" placeholder="Cost (optional)" [attr.aria-label]="'Cost of leg ' + (i + 1)"
                     data-testid="it-leg-cost" [value]="l.costAmount" (input)="setLeg(i, 'costAmount', $any($event.target).value)" />
              @if (l.mode === 'ROAD') {
                <app-entity-picker [kind]="'trip'" placeholder="Trip (optional)" [ariaLabel]="'Trip of leg ' + (i + 1)"
                                   testId="it-leg-trip" [value]="l.tripId" (valueChange)="setLeg(i, 'tripId', $event)" />
              } @else {
                <select [attr.aria-label]="'Unit type of leg ' + (i + 1)" data-testid="it-leg-unit-type" required [value]="l.unitType"
                        (change)="setLeg(i, 'unitType', $any($event.target).value)">
                  <option value="" [selected]="l.unitType === ''">Unit type</option>
                  <option value="WAGON" [selected]="l.unitType === 'WAGON'">WAGON</option>
                  <option value="CONTAINER" [selected]="l.unitType === 'CONTAINER'">CONTAINER</option>
                </select>
                <input type="number" min="1" placeholder="Units" [attr.aria-label]="'Unit count of leg ' + (i + 1)"
                       data-testid="it-leg-unit-count" required [value]="l.unitCount"
                       (input)="setLeg(i, 'unitCount', $any($event.target).value)" />
                <input placeholder="Booking reference" [attr.aria-label]="'Booking reference of leg ' + (i + 1)"
                       data-testid="it-leg-booking" required [value]="l.bookingRef"
                       (input)="setLeg(i, 'bookingRef', $any($event.target).value)" />
                <input placeholder="Train number (optional)" [attr.aria-label]="'Train number of leg ' + (i + 1)"
                       data-testid="it-leg-train" [value]="l.trainNo" (input)="setLeg(i, 'trainNo', $any($event.target).value)" />
              }
              @if (draft().legs.length > 1) {
                <button type="button" class="link" data-testid="it-leg-remove" [attr.aria-label]="'Remove leg ' + (i + 1)"
                        (click)="removeLeg(i)">Remove</button>
              }
            </div>
          </fieldset>
        }
        <p class="actions">
          <button type="button" data-testid="it-leg-add" (click)="addLeg()">Add leg</button>
          <button type="submit" data-testid="it-plan">Plan itinerary</button>
        </p>
      </form>
      @if (planError(); as r) {
        <app-error-banner [message]="r.message" [code]="r.code" testId="it-plan-error" codeTestId="it-plan-refusal" />
      }
    }

    <h2>Itineraries</h2>
    @if (listError()) { <app-error-banner [message]="listError()" testId="it-list-error" /> }
    @if (list().length === 0) {
      <p class="muted" data-testid="it-list-empty">No itineraries yet.</p>
    } @else {
      <div class="table-wrap"><table data-testid="it-list">
        <thead><tr><th>Itinerary</th><th>Shipment</th><th>Status</th><th>Legs</th><th></th></tr></thead>
        <tbody>
          @for (r of list(); track r['id']) {
            <tr data-testid="it-item" [attr.data-id]="r['id']">
              <td>{{ r['itineraryNo'] }}</td><td>{{ r['shipmentRef'] }}</td>
              <td><app-status-badge [value]="r['status']" /></td><td>{{ r['legCount'] }}</td>
              <td><button type="button" class="link" data-testid="it-select"
                          [attr.aria-label]="'Select itinerary ' + r['itineraryNo']" (click)="select(r['id'])">Select</button></td>
            </tr>
          }
        </tbody>
      </table></div>
    }

    @if (detail(); as d) {
      <section data-testid="it-panel" [attr.data-id]="d.header['id']">
        <h2>Itinerary <span data-testid="it-no">{{ d.header['itineraryNo'] }}</span></h2>
        <dl class="facts">
          <div><dt>Status</dt><dd><app-status-badge data-testid="it-status" [value]="d.header['status']" /></dd></div>
          <div><dt>Shipment</dt><dd data-testid="it-shipment-ref">{{ d.header['shipmentRef'] }}</dd></div>
          <div><dt>Total cost</dt><dd data-testid="it-cost">{{ d.header['totalCost'] || '—' }}</dd></div>
        </dl>
        @if (done()) { <p class="ok" role="status" data-testid="it-done">{{ done() }}</p> }
        @if (refusal(); as f) {
          <app-error-banner [message]="f.message" [code]="f.code" testId="it-error" codeTestId="it-refusal" />
        }
        <div class="table-wrap"><table data-testid="it-legs">
          <thead><tr><th>#</th><th>Mode</th><th>From</th><th>To</th><th>Status</th><th>Reference</th><th></th></tr></thead>
          <tbody>
            @for (p of d.parts; track p['id']) {
              <tr data-testid="it-leg" [attr.data-seq]="p['seq']">
                <td>{{ p['seq'] }}</td><td>{{ p['mode'] }}</td><td>{{ p['from'] }}</td><td>{{ p['to'] }}</td>
                <td><app-status-badge data-testid="it-leg-status" [value]="p['status']" /></td>
                <td>{{ p['reference'] || '—' }}</td>
                <td>
                  @if (session.can('MULTIMODAL_TRACK') && p['mode'] === 'RAIL' && next(p['status']); as n) {
                    <button type="button" class="link" data-testid="it-leg-next"
                            [attr.aria-label]="'Record ' + n + ' on leg ' + p['seq']" (click)="milestone(d, p, n)">{{ n }}</button>
                  }
                </td>
              </tr>
            }
          </tbody>
        </table></div>
      </section>
    }
  `,
})
export class ItineraryDesk implements OnInit {
  private readonly gateway = inject(LmsGateway);
  protected readonly session = inject(Session);

  readonly draft = signal<ItineraryPlanDraft>(blankPlan());
  readonly planError = signal<Refusal | null>(null);
  readonly list = signal<Row[]>([]);
  readonly listError = signal('');
  readonly detail = signal<MappedDetail | null>(null);
  readonly done = signal('');
  readonly refusal = signal<Refusal | null>(null);

  ngOnInit(): void {
    void this.loadList();
  }

  next(status: string): string | undefined {
    return RAIL_NEXT[status];
  }

  setHead(key: 'shipmentRef' | 'currency', value: string): void {
    this.draft.update((d) => ({ ...d, [key]: value }));
  }

  setLeg(i: number, key: keyof ItineraryLegDraft, value: string): void {
    this.draft.update((d) => ({ ...d, legs: d.legs.map((l, at) => (at === i ? { ...l, [key]: value } : l)) }));
  }

  addLeg(): void {
    this.draft.update((d) => ({ ...d, legs: [...d.legs, { ...BLANK_LEG }] }));
  }

  removeLeg(i: number): void {
    this.draft.update((d) => ({ ...d, legs: d.legs.filter((_, at) => at !== i) }));
  }

  private problem(e: unknown): Refusal {
    return { code: e instanceof ApiProblem ? e.code : '', message: (e as Error).message };
  }

  async plan(): Promise<void> {
    this.planError.set(null);
    try {
      const id = await this.gateway.planItinerary(this.draft());
      this.draft.set(blankPlan());
      await this.loadList();
      await this.select(id);
      this.done.set('The itinerary is planned.');
    } catch (e) {
      this.planError.set(this.problem(e));
    }
  }

  async loadList(): Promise<void> {
    this.listError.set('');
    try {
      this.list.set(await this.gateway.itineraries());
    } catch (e) {
      this.listError.set((e as Error).message);
    }
  }

  async select(id: string): Promise<void> {
    if (id !== this.detail()?.header['id']) {
      this.detail.set(null);
      this.done.set('');
      this.refusal.set(null);
    }
    await this.refresh(id);
  }

  async milestone(d: MappedDetail, leg: Row, milestone: string): Promise<void> {
    const id = d.header['id'];
    this.done.set('');
    this.refusal.set(null);
    try {
      await this.gateway.recordRailMilestone(id, Number(leg['seq']), milestone);
      this.done.set(`Leg ${leg['seq']} is ${milestone.toLowerCase()}.`);
    } catch (e) {
      this.refusal.set(this.problem(e));
    }
    await this.refresh(id);
    await this.loadList();
  }

  private async refresh(id: string): Promise<void> {
    try {
      this.detail.set(await this.gateway.itinerary(id));
    } catch (e) {
      this.detail.set(null);
      this.listError.set((e as Error).message);
    }
  }
}
