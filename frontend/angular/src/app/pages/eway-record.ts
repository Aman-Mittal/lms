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

import { Component, inject, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiProblem, LmsGateway } from '../gateway/lms-gateway';
import { EwayRecordDraft } from '../gateway/small-ops-types';
import { EntityPicker } from './entity-picker';
import { ErrorBanner } from './error-banner';

const BLANK: EwayRecordDraft = {
  consignmentId: '', billNo: '', generatedAt: '', validUntil: '', vehicleNo: '', distanceKm: '',
};

/**
 * Writes down a bill that was generated outside the platform (on the statutory portal, say) against its
 * consignment, with the validity it carries, so the desk watches it like any other.
 */
@Component({
  selector: 'app-eway-record',
  imports: [EntityPicker, ErrorBanner, FormsModule],
  template: `
    <h2>Record a bill issued elsewhere</h2>
    <form ngNativeValidate class="inline" data-testid="eway-record-form" (submit)="submit($event)">
      <app-entity-picker [kind]="'consignment'" placeholder="Consignment id" ariaLabel="Recorded bill consignment id"
                         testId="eway-record-consignment" [required]="true" [value]="draft().consignmentId"
                         (valueChange)="set('consignmentId', $event)" />
      <input placeholder="Bill number" aria-label="Recorded bill number" data-testid="eway-record-no" required
             [value]="draft().billNo" (input)="set('billNo', $any($event.target).value)" />
      <input type="datetime-local" aria-label="Recorded bill generated at" data-testid="eway-record-generated"
             [value]="draft().generatedAt" (input)="set('generatedAt', $any($event.target).value)" />
      <input type="datetime-local" aria-label="Recorded bill valid until" data-testid="eway-record-valid-until" required
             [value]="draft().validUntil" (input)="set('validUntil', $any($event.target).value)" />
      <input placeholder="Vehicle number (optional)" aria-label="Recorded bill vehicle number"
             data-testid="eway-record-vehicle" [value]="draft().vehicleNo"
             (input)="set('vehicleNo', $any($event.target).value)" />
      <input inputmode="numeric" placeholder="Distance (km, optional)" aria-label="Recorded bill distance in kilometres"
             data-testid="eway-record-distance" [value]="draft().distanceKm"
             (input)="set('distanceKm', $any($event.target).value)" />
      <button type="submit" data-testid="eway-record-submit">Record issued bill</button>
    </form>
    @if (done()) { <p class="ok" role="status" data-testid="eway-record-done">Bill {{ done() }} recorded.</p> }
    @if (error().message) { <app-error-banner [message]="error().message" [code]="error().code" testId="eway-record-error" codeTestId="eway-record-refusal" /> }
  `,
})
export class EwayRecord {
  private readonly gateway = inject(LmsGateway);

  /** A bill was recorded: the desk reads its lists again. */
  readonly recorded = output<void>();
  readonly draft = signal<EwayRecordDraft>(BLANK);
  readonly done = signal('');
  readonly error = signal({ message: '', code: '' });

  set<K extends keyof EwayRecordDraft>(key: K, value: EwayRecordDraft[K]): void {
    this.draft.update((d) => ({ ...d, [key]: value }));
  }

  async submit(event: Event): Promise<void> {
    event.preventDefault();
    this.done.set('');
    this.error.set({ message: '', code: '' });
    try {
      const billNo = this.draft().billNo.trim();
      await this.gateway.recordEwayBill(this.draft());
      this.draft.set(BLANK);
      this.done.set(billNo);
      this.recorded.emit();
    } catch (e) {
      this.error.set({ message: (e as Error).message, code: e instanceof ApiProblem ? e.code : '' });
    }
  }
}
