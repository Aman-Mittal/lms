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

import { Component, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiProblem, LmsGateway } from '../gateway/lms-gateway';
import { LoadOpenDraft } from '../gateway/trip-desk-types';
import { Session } from '../session';
import { EntityPicker } from './entity-picker';
import { ErrorBanner } from './error-banner';

function refusal(e: unknown): { message: string; code: string } {
  return { message: (e as Error).message, code: e instanceof ApiProblem ? e.code : '' };
}

/** Opens a new load for building: a number, where it starts, and the vehicle that will carry it. */
@Component({
  selector: 'app-open-load-form',
  imports: [EntityPicker, ErrorBanner, FormsModule],
  template: `
    @if (session.can('LOAD_CREATE') && session.orgUnitId()) {
      <section data-testid="new-load">
        <h2>Open a new load</h2>
        <form ngNativeValidate class="inline" (ngSubmit)="submit()" data-testid="new-load-form">
          <input name="loadNo" placeholder="Load number" aria-label="New load number" data-testid="new-load-no" required
                 [value]="draft().loadNo" (input)="set('loadNo', $any($event.target).value)" />
          <app-entity-picker [kind]="'terminal'" placeholder="Origin terminal" ariaLabel="Origin terminal id"
                             testId="new-load-origin" [required]="true" [value]="draft().originTerminalId"
                             (valueChange)="set('originTerminalId', $event)" />
          <app-entity-picker [kind]="'vehicle'" placeholder="Vehicle" ariaLabel="Vehicle id" testId="new-load-vehicle"
                             [required]="true" [value]="draft().vehicleId" (valueChange)="set('vehicleId', $event)" />
          <select aria-label="Freight mode" data-testid="new-load-mode" [value]="draft().freightMode"
                  (change)="set('freightMode', $any($event.target).value)">
            <option value="" [selected]="!draft().freightMode">Full truckload</option>
            <option value="LTL" [selected]="draft().freightMode === 'LTL'">Part load</option>
          </select>
          <button type="submit" data-testid="new-load-open">Start load</button>
        </form>
        @if (error()) { <app-error-banner [message]="error().message" [code]="error().code" testId="new-load-error" /> }
      </section>
    }
  `,
})
export class OpenLoadForm {
  private readonly gateway = inject(LmsGateway);
  protected readonly session = inject(Session);

  /** The id of the load just opened; the screen shows it. */
  readonly opened = output<string>();
  readonly draft = signal<LoadOpenDraft>({ loadNo: '', originTerminalId: '', vehicleId: '', freightMode: '' });
  readonly error = signal({ message: '', code: '' });

  set(key: keyof LoadOpenDraft, value: string): void {
    this.draft.update((d) => ({ ...d, [key]: value }));
  }

  async submit(): Promise<void> {
    const unit = this.session.orgUnitId();
    if (!unit) {
      return;
    }
    this.error.set({ message: '', code: '' });
    try {
      const id = await this.gateway.openLoad(unit, this.draft());
      this.draft.set({ loadNo: '', originTerminalId: '', vehicleId: '', freightMode: '' });
      this.opened.emit(id);
    } catch (e) {
      this.error.set(refusal(e));
    }
  }
}

/** Puts one named consignment on the load, for the single one a planner already knows by reference. */
@Component({
  selector: 'app-assign-one',
  imports: [EntityPicker, ErrorBanner, FormsModule],
  template: `
    <form ngNativeValidate class="inline" (ngSubmit)="submit()" data-testid="assign-one-form">
      <app-entity-picker [kind]="'unassigned'" placeholder="Waiting consignment" ariaLabel="Consignment id"
                         testId="assign-one-id" [required]="true" [(value)]="consignmentId" />
      <button type="submit" data-testid="assign-one">Add to load</button>
    </form>
    @if (done()) { <p class="ok" role="status" data-testid="assign-one-done">Consignment added to the load.</p> }
    @if (error().message) { <app-error-banner [message]="error().message" [code]="error().code" testId="assign-one-error" /> }
  `,
})
export class AssignOne {
  private readonly gateway = inject(LmsGateway);

  readonly loadId = input.required<string>();
  readonly boarded = output<void>();
  readonly consignmentId = signal('');
  readonly done = signal(false);
  readonly error = signal({ message: '', code: '' });

  async submit(): Promise<void> {
    this.done.set(false);
    this.error.set({ message: '', code: '' });
    try {
      await this.gateway.assignConsignments(this.loadId(), [this.consignmentId().trim()]);
      this.consignmentId.set('');
      this.done.set(true);
      this.boarded.emit();
    } catch (e) {
      this.error.set(refusal(e));
    }
  }
}
