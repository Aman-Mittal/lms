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
import {
  ApiProblem, GATE_PURPOSES, GatePassPage, GatePassVerdict, GatePassView, GatePurpose, GateVisitPage, GateVisitView,
  LmsGateway,
} from '../gateway/lms-gateway';
import { Session } from '../session';
import { EntityPicker } from './entity-picker';

type Tab = 'desk' | 'register' | 'passes';

/** What an `(input)` or `(change)` event carries, read without a cast in the template. */
function valueOf(event: Event): string {
  return (event.target as HTMLInputElement).value;
}

/** A comma-separated list as the gateway takes it. */
function listOf(text: string): string[] {
  return text.split(',').map((s) => s.trim()).filter((s) => s !== '');
}

/**
 * The yard gate: who is inside now, recording a vehicle in and out, the register of past
 * visits, and gate passes. Recording needs GATE_REGISTER and issuing or cancelling a pass
 * needs GATE_PASS_MANAGE; the controls a person could not use are not drawn.
 *
 * Inputs bind through signals rather than `ngModel`, which without zone.js can write its
 * first value late and wipe what was typed.
 */
@Component({
  selector: 'app-gate-desk',
  imports: [EntityPicker],
  template: `
    <h1>Gate desk</h1>
    <p class="muted">Vehicles at the gate: who is inside, entries and exits, the register and gate passes.</p>
    @if (refusal(); as r) {
      <p class="error" role="alert" data-testid="gate-error">
        @if (r.code) { <code data-testid="gate-refusal">{{ r.code }}</code> }
        {{ r.message }}
      </p>
    }
    @if (message()) { <p class="ok" role="status" data-testid="gate-message">{{ message() }}</p> }

    <p class="actions" role="tablist" aria-label="Gate views">
      <button type="button" role="tab" [class.link]="tab() !== 'desk'" data-testid="gate-tab-desk"
              [attr.aria-selected]="tab() === 'desk'" (click)="show('desk')">Desk</button>
      <button type="button" role="tab" [class.link]="tab() !== 'register'" data-testid="gate-tab-register"
              [attr.aria-selected]="tab() === 'register'" (click)="show('register')">Register</button>
      <button type="button" role="tab" [class.link]="tab() !== 'passes'" data-testid="gate-tab-passes"
              [attr.aria-selected]="tab() === 'passes'" (click)="show('passes')">Passes</button>
    </p>

    @if (tab() === 'desk') {
      <section data-testid="gate-desk" aria-label="Gate desk">
        <h2>Inside now</h2>
        @if (inside().length === 0) {
          <p class="muted" data-testid="inside-empty">No vehicle is inside.</p>
        } @else {
          <table data-testid="inside">
            <thead>
              <tr><th>Vehicle</th><th>Site</th><th>Purpose</th><th>Driver</th><th>Trip</th><th>Pass</th><th>Entered</th>
                <th>Inside for</th><th></th></tr>
            </thead>
            <tbody>
              @for (v of inside(); track v.id) {
                <tr data-testid="inside-row" [attr.data-id]="v.id">
                  <td>{{ v.registrationNo }}</td><td>{{ v.terminalCode }}</td><td>{{ v.purpose }}</td>
                  <td>{{ v.driverName }}</td><td>{{ v.tripNo }}</td><td>{{ v.gatePassNo }}</td>
                  <td>{{ v.enteredAt }}</td><td>{{ v.dwell }}</td>
                  <td>
                    @if (session.can('GATE_REGISTER')) {
                      <button type="button" class="link" data-testid="visit-exit"
                              [attr.aria-label]="'Exit ' + v.registrationNo" (click)="startExit(v.id)">Exit</button>
                    }
                  </td>
                </tr>
                @if (exiting() === v.id) {
                  <tr data-testid="exit-row">
                    <td colspan="9">
                      <form class="inline" data-testid="exit-form" (submit)="exit($event, v.id)">
                        <input placeholder="Seal numbers, comma separated" aria-label="Exit seal numbers"
                               data-testid="exit-seals" [value]="exitSeals()" (input)="exitSeals.set(valueOf($event))" />
                        <input placeholder="Weight reference" aria-label="Exit weight reference"
                               data-testid="exit-weight" [value]="exitWeight()" (input)="exitWeight.set(valueOf($event))" />
                        <input placeholder="Remarks" aria-label="Exit remarks" data-testid="exit-remarks"
                               [value]="exitRemarks()" (input)="exitRemarks.set(valueOf($event))" />
                        <button type="submit" data-testid="exit-submit">Record exit</button>
                        <button type="button" class="link" (click)="exiting.set('')">Cancel</button>
                      </form>
                    </td>
                  </tr>
                }
              }
            </tbody>
          </table>
        }

        @if (session.can('GATE_REGISTER')) {
          <form data-testid="gate-entry" (submit)="enter($event)">
            <h2>Record entry</h2>
            <app-entity-picker [kind]="'terminal'" ariaLabel="Gate site" placeholder="Site" testId="entry-site"
                               [required]="true" [(value)]="entrySite" />
            <app-entity-picker [kind]="'vehicle'" ariaLabel="Registered vehicle" placeholder="Registered vehicle"
                               testId="entry-vehicle" [(value)]="entryVehicle" />
            <input placeholder="Or a visitor's registration number" aria-label="Visitor registration number"
                   data-testid="entry-registration" [value]="entryRegistration()"
                   (input)="entryRegistration.set(valueOf($event))" />
            <app-entity-picker [kind]="'driver'" ariaLabel="Driver" placeholder="Driver" testId="entry-driver"
                               [(value)]="entryDriver" />
            <input placeholder="Or a driver's name" aria-label="Driver name" data-testid="entry-driver-name"
                   [value]="entryDriverName()" (input)="entryDriverName.set(valueOf($event))" />
            <select aria-label="Purpose" data-testid="entry-purpose" (change)="entryPurpose.set($any(valueOf($event)))">
              @for (p of purposes; track p) { <option [value]="p" [selected]="p === entryPurpose()">{{ p }}</option> }
            </select>
            <app-entity-picker [kind]="'trip'" ariaLabel="Trip" placeholder="Trip (optional)" testId="entry-trip"
                               [(value)]="entryTrip" />
            <input placeholder="Gate pass number" aria-label="Gate pass number" data-testid="entry-pass"
                   [value]="entryPass()" (input)="entryPass.set(valueOf($event))" />
            <input placeholder="Seal numbers, comma separated" aria-label="Seal numbers" data-testid="entry-seals"
                   [value]="entrySeals()" (input)="entrySeals.set(valueOf($event))" />
            <input placeholder="Weight reference" aria-label="Weight reference" data-testid="entry-weight"
                   [value]="entryWeight()" (input)="entryWeight.set(valueOf($event))" />
            <input placeholder="Remarks" aria-label="Remarks" data-testid="entry-remarks"
                   [value]="entryRemarks()" (input)="entryRemarks.set(valueOf($event))" />
            <button type="submit" data-testid="entry-submit">Record entry</button>
          </form>

          <form class="inline" data-testid="pass-check" (submit)="check($event)">
            <h2>Check pass</h2>
            <app-entity-picker [kind]="'terminal'" ariaLabel="Check site" placeholder="Site" testId="check-site"
                               [required]="true" [(value)]="checkSite" />
            <app-entity-picker [kind]="'vehicle'" ariaLabel="Check vehicle" placeholder="Registered vehicle"
                               testId="check-vehicle" [(value)]="checkVehicle" />
            <input placeholder="Or a registration number" aria-label="Check registration number"
                   data-testid="check-registration" [value]="checkRegistration()"
                   (input)="checkRegistration.set(valueOf($event))" />
            <app-entity-picker [kind]="'trip'" ariaLabel="Check trip" placeholder="Trip (optional)"
                               testId="check-trip" [(value)]="checkTrip" />
            <input placeholder="Pass number" aria-label="Check pass number" data-testid="check-number"
                   [value]="checkNumber()" (input)="checkNumber.set(valueOf($event))" />
            <button type="submit" data-testid="check-submit">Check</button>
          </form>
          @if (verdict(); as v) {
            <p data-testid="check-verdict">
              @if (v.admits) {
                Admits{{ v.passNo ? ' on pass ' + v.passNo : '' }}{{ v.passRequired ? '' : ' (no pass is required here)' }}
              } @else {
                Refused: <code data-testid="check-code">{{ v.code }}</code> {{ v.message }}
              }
            </p>
          }
        }
      </section>
    }

    @if (tab() === 'register') {
      <section data-testid="gate-register" aria-label="Gate register">
        <h2>Register</h2>
        <form class="inline" (submit)="searchRegister($event)">
          <select aria-label="Visit status" data-testid="register-status" (change)="registerStatus.set(valueOf($event))">
            <option value="" [selected]="registerStatus() === ''">Every visit</option>
            <option value="OPEN" [selected]="registerStatus() === 'OPEN'">Inside</option>
            <option value="CLOSED" [selected]="registerStatus() === 'CLOSED'">Left</option>
          </select>
          <input placeholder="Registration number" aria-label="Search the register" data-testid="register-search"
                 [value]="registerQ()" (input)="registerQ.set(valueOf($event))" />
          <button type="submit" data-testid="register-search-submit">Search</button>
        </form>
        @if (visits().length === 0) {
          <p class="muted" data-testid="register-empty">No visit matches.</p>
        } @else {
          <table data-testid="register">
            <thead>
              <tr><th>Vehicle</th><th>Site</th><th>Purpose</th><th>Driver</th><th>Trip</th><th>Pass</th><th>Status</th>
                <th>Entered</th><th>Left</th><th>Inside for</th><th>Seals in</th><th>Seals out</th></tr>
            </thead>
            <tbody>
              @for (v of visits(); track v.id) {
                <tr data-testid="register-row" [attr.data-id]="v.id">
                  <td>{{ v.registrationNo }}</td><td>{{ v.terminalCode }}</td><td>{{ v.purpose }}</td>
                  <td>{{ v.driverName }}</td><td>{{ v.tripNo }}</td><td>{{ v.gatePassNo }}</td><td>{{ v.status }}</td>
                  <td>{{ v.enteredAt }}</td><td>{{ v.exitedAt }}</td><td>{{ v.dwell }}</td>
                  <td>{{ v.entrySeals }}</td><td>{{ v.exitSeals }}</td>
                </tr>
              }
            </tbody>
          </table>
        }
        @if (visitsNext()) {
          <button type="button" data-testid="register-more" (click)="moreVisits()">Load more</button>
        }
      </section>
    }

    @if (tab() === 'passes') {
      <section data-testid="gate-passes" aria-label="Gate passes">
        @if (session.can('GATE_PASS_MANAGE')) {
          <form data-testid="pass-issue" (submit)="issue($event)">
            <h2>Issue a pass</h2>
            <app-entity-picker [kind]="'terminal'" ariaLabel="Pass site" placeholder="Site" testId="pass-site"
                               [required]="true" [(value)]="passSite" />
            <app-entity-picker [kind]="'vehicle'" ariaLabel="Pass vehicle" placeholder="Registered vehicle"
                               testId="pass-vehicle" [(value)]="passVehicle" />
            <input placeholder="Or a visitor's registration number" aria-label="Pass registration number"
                   data-testid="pass-registration" [value]="passRegistration()"
                   (input)="passRegistration.set(valueOf($event))" />
            <select aria-label="Pass purpose" data-testid="pass-purpose" (change)="passPurpose.set($any(valueOf($event)))">
              @for (p of purposes; track p) { <option [value]="p" [selected]="p === passPurpose()">{{ p }}</option> }
            </select>
            <label>Valid from
              <input type="datetime-local" aria-label="Valid from" data-testid="pass-from" required
                     [value]="passFrom()" (input)="passFrom.set(valueOf($event))" />
            </label>
            <label>Valid until
              <input type="datetime-local" aria-label="Valid until" data-testid="pass-until" required
                     [value]="passUntil()" (input)="passUntil.set(valueOf($event))" />
            </label>
            <app-entity-picker [kind]="'trip'" ariaLabel="Pass trip" placeholder="Trip (optional)" testId="pass-trip"
                               [(value)]="passTrip" />
            <input placeholder="Pass number, where no series numbers it" aria-label="Pass number"
                   data-testid="pass-number" [value]="passNumber()" (input)="passNumber.set(valueOf($event))" />
            <button type="submit" data-testid="pass-submit">Issue pass</button>
          </form>
        }

        <h2>Passes</h2>
        <form class="inline" (submit)="searchPasses($event)">
          <select aria-label="Pass status" data-testid="pass-status-filter" (change)="passStatus.set(valueOf($event))">
            <option value="" [selected]="passStatus() === ''">Every pass</option>
            <option value="ISSUED" [selected]="passStatus() === 'ISSUED'">Issued</option>
            <option value="USED" [selected]="passStatus() === 'USED'">Used</option>
            <option value="CANCELLED" [selected]="passStatus() === 'CANCELLED'">Cancelled</option>
          </select>
          <input placeholder="Search" aria-label="Search passes" data-testid="pass-search" [value]="passQ()"
                 (input)="passQ.set(valueOf($event))" />
          <button type="submit" data-testid="pass-search-submit">Search</button>
        </form>
        @if (passes().length === 0) {
          <p class="muted" data-testid="passes-empty">No pass matches.</p>
        } @else {
          <table data-testid="passes">
            <thead>
              <tr><th>Pass</th><th>Site</th><th>Vehicle</th><th>Trip</th><th>Purpose</th><th>Valid from</th>
                <th>Valid until</th><th>Status</th><th></th></tr>
            </thead>
            <tbody>
              @for (p of passes(); track p.id) {
                <tr data-testid="pass-row" [attr.data-id]="p.id">
                  <td>{{ p.passNo }}</td><td>{{ p.terminalCode }}</td><td>{{ p.registrationNo }}</td>
                  <td>{{ p.tripNo }}</td><td>{{ p.purpose }}</td><td>{{ p.validFrom }}</td><td>{{ p.validUntil }}</td>
                  <td>
                    <span class="badge" data-testid="pass-status">{{ p.status }}</span>
                    @if (p.expired) { <span class="badge" data-testid="pass-expired">Expired</span> }
                    @if (p.cancelReason) { <span class="muted">{{ p.cancelReason }}</span> }
                  </td>
                  <td>
                    @if (p.status === 'ISSUED' && session.can('GATE_PASS_MANAGE')) {
                      <button type="button" class="link" data-testid="pass-cancel"
                              [attr.aria-label]="'Cancel pass ' + p.passNo" (click)="startCancel(p.id)">Cancel</button>
                    }
                  </td>
                </tr>
                @if (cancelling() === p.id) {
                  <tr data-testid="pass-cancel-row">
                    <td colspan="9">
                      <form class="inline" data-testid="pass-cancel-form" (submit)="cancel($event, p.id)">
                        <input placeholder="Why" aria-label="Cancellation reason" data-testid="pass-cancel-reason" required
                               [value]="cancelReason()" (input)="cancelReason.set(valueOf($event))" />
                        <button type="submit" data-testid="pass-cancel-confirm">Cancel pass</button>
                        <button type="button" class="link" (click)="cancelling.set('')">Keep</button>
                      </form>
                    </td>
                  </tr>
                }
              }
            </tbody>
          </table>
        }
        @if (passesNext()) {
          <button type="button" data-testid="passes-more" (click)="morePasses()">Load more</button>
        }
      </section>
    }
  `,
})
export class GateDesk implements OnInit {
  private readonly gateway = inject(LmsGateway);
  protected readonly session = inject(Session);

  readonly valueOf = valueOf;
  readonly purposes = GATE_PURPOSES;

  readonly tab = signal<Tab>('desk');
  readonly refusal = signal<{ code: string; message: string } | null>(null);
  readonly message = signal('');

  // inside now and the exit form
  readonly inside = signal<GateVisitView[]>([]);
  readonly exiting = signal('');
  readonly exitSeals = signal('');
  readonly exitWeight = signal('');
  readonly exitRemarks = signal('');

  // the entry form
  readonly entrySite = signal('');
  readonly entryVehicle = signal('');
  readonly entryRegistration = signal('');
  readonly entryDriver = signal('');
  readonly entryDriverName = signal('');
  readonly entryPurpose = signal<GatePurpose>('TRIP');
  readonly entryTrip = signal('');
  readonly entryPass = signal('');
  readonly entrySeals = signal('');
  readonly entryWeight = signal('');
  readonly entryRemarks = signal('');

  // checking a pass
  readonly checkSite = signal('');
  readonly checkVehicle = signal('');
  readonly checkRegistration = signal('');
  readonly checkTrip = signal('');
  readonly checkNumber = signal('');
  readonly verdict = signal<GatePassVerdict | null>(null);

  // the register
  readonly registerStatus = signal('');
  readonly registerQ = signal('');
  readonly visits = signal<GateVisitView[]>([]);
  readonly visitsNext = signal<string | null>(null);

  // the passes
  readonly passSite = signal('');
  readonly passVehicle = signal('');
  readonly passRegistration = signal('');
  readonly passPurpose = signal<GatePurpose>('VISITOR');
  readonly passFrom = signal('');
  readonly passUntil = signal('');
  readonly passTrip = signal('');
  readonly passNumber = signal('');
  readonly passStatus = signal('');
  readonly passQ = signal('');
  readonly passes = signal<GatePassView[]>([]);
  readonly passesNext = signal<string | null>(null);
  readonly cancelling = signal('');
  readonly cancelReason = signal('');

  async ngOnInit(): Promise<void> {
    await this.attempt(() => this.loadInside());
  }

  async show(tab: Tab): Promise<void> {
    this.tab.set(tab);
    this.refusal.set(null);
    this.message.set('');
    await this.attempt(async () => {
      if (tab === 'desk') {
        await this.loadInside();
      } else if (tab === 'register') {
        await this.loadVisits(false);
      } else {
        await this.loadPasses(false);
      }
    });
  }

  // ---------------------------------------------------------------- entries and exits

  async enter(event: Event): Promise<void> {
    event.preventDefault();
    await this.attempt(async () => {
      await this.gateway.recordGateEntry({
        terminalId: this.entrySite(), vehicleId: this.entryVehicle(), registrationNo: this.entryRegistration(),
        driverId: this.entryDriver(), driverName: this.entryDriverName(), purpose: this.entryPurpose(),
        tripId: this.entryTrip(), gatePassNo: this.entryPass(), sealNos: listOf(this.entrySeals()),
        weightRef: this.entryWeight(), remarks: this.entryRemarks(),
      });
      // Clear first: a re-read below must not race what is typed next.
      this.entryRegistration.set('');
      this.entryDriverName.set('');
      this.entryVehicle.set('');
      this.entryDriver.set('');
      this.entryTrip.set('');
      this.entryPass.set('');
      this.entrySeals.set('');
      this.entryWeight.set('');
      this.entryRemarks.set('');
      await this.loadInside();
      return 'Entry recorded.';
    });
  }

  startExit(id: string): void {
    this.exiting.set(id);
    this.exitSeals.set('');
    this.exitWeight.set('');
    this.exitRemarks.set('');
  }

  async exit(event: Event, id: string): Promise<void> {
    event.preventDefault();
    await this.attempt(async () => {
      await this.gateway.recordGateExit(id, {
        sealNos: listOf(this.exitSeals()), weightRef: this.exitWeight(), remarks: this.exitRemarks(),
      });
      this.exiting.set('');
      await this.loadInside();
      return 'Exit recorded.';
    });
  }

  async check(event: Event): Promise<void> {
    event.preventDefault();
    this.verdict.set(null);
    await this.attempt(async () => this.verdict.set(await this.gateway.checkGatePass({
      terminalId: this.checkSite(), vehicleId: this.checkVehicle(), registrationNo: this.checkRegistration(),
      tripId: this.checkTrip(), passNo: this.checkNumber(),
    })));
  }

  // ---------------------------------------------------------------- the register

  async searchRegister(event: Event): Promise<void> {
    event.preventDefault();
    await this.attempt(() => this.loadVisits(false));
  }

  async moreVisits(): Promise<void> {
    await this.attempt(() => this.loadVisits(true));
  }

  // ---------------------------------------------------------------- passes

  async issue(event: Event): Promise<void> {
    event.preventDefault();
    await this.attempt(async () => {
      await this.gateway.issueGatePass({
        terminalId: this.passSite(), vehicleId: this.passVehicle(), registrationNo: this.passRegistration(),
        tripId: this.passTrip(), purpose: this.passPurpose(), validFrom: this.passFrom(), validUntil: this.passUntil(),
        passNo: this.passNumber(),
      });
      this.passRegistration.set('');
      this.passVehicle.set('');
      this.passTrip.set('');
      this.passNumber.set('');
      await this.loadPasses(false);
      return 'Pass issued.';
    });
  }

  async searchPasses(event: Event): Promise<void> {
    event.preventDefault();
    await this.attempt(() => this.loadPasses(false));
  }

  async morePasses(): Promise<void> {
    await this.attempt(() => this.loadPasses(true));
  }

  startCancel(id: string): void {
    this.cancelling.set(id);
    this.cancelReason.set('');
  }

  async cancel(event: Event, id: string): Promise<void> {
    event.preventDefault();
    await this.attempt(async () => {
      await this.gateway.cancelGatePass(id, this.cancelReason());
      this.cancelling.set('');
      await this.loadPasses(false);
      return 'Pass cancelled.';
    });
  }

  // ---------------------------------------------------------------- helpers

  private async loadInside(): Promise<void> {
    this.inside.set(await this.gateway.openGateVisits());
  }

  private async loadVisits(append: boolean): Promise<void> {
    const page: GateVisitPage = await this.gateway.gateVisits(
      { status: this.registerStatus(), q: this.registerQ() }, append ? this.visitsNext() ?? undefined : undefined);
    this.visits.set(append ? [...this.visits(), ...page.items] : page.items);
    this.visitsNext.set(page.nextCursor);
  }

  private async loadPasses(append: boolean): Promise<void> {
    const page: GatePassPage = await this.gateway.gatePasses(
      { status: this.passStatus(), q: this.passQ() }, append ? this.passesNext() ?? undefined : undefined);
    this.passes.set(append ? [...this.passes(), ...page.items] : page.items);
    this.passesNext.set(page.nextCursor);
  }

  private async attempt(action: () => Promise<string | void>): Promise<void> {
    this.refusal.set(null);
    this.message.set('');
    try {
      const said = await action();
      if (said) {
        this.message.set(said);
      }
    } catch (e) {
      this.refusal.set({ code: e instanceof ApiProblem ? e.code : '', message: (e as Error).message });
    }
  }
}
