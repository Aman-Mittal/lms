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

import { Component, effect, inject, input, output, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  ApiProblem, ContractChargeDraft, ContractChecklistRow, ContractDetail, ContractDraft, ContractLaneDraft,
  LANE_RATE_BASES, LmsGateway, Row,
} from '../gateway/lms-gateway';
import { Session } from '../session';
import { EntityPicker } from './entity-picker';

/** Today as a `YYYY-MM-DD` day, in UTC like the API. */
function today(): string {
  return new Date().toISOString().slice(0, 10);
}

const FIELDS: Array<[keyof ContractDraft, string, string]> = [
  ['code', 'Contract number (blank for the next in the series)', 'text'],
  ['effectiveFrom', 'Starts', 'date'],
  ['validTill', 'Ends (optional)', 'date'],
  ['transportRatePerKg', 'Transport / kg', 'text'],
  ['transportMinimumCharge', 'Transport minimum', 'text'],
  ['storageRatePerPalletDay', 'Storage / pallet-day', 'text'],
  ['handlingInRatePerPallet', 'Handling in / pallet', 'text'],
  ['handlingOutRatePerPallet', 'Handling out / pallet', 'text'],
];

function blankLane(): ContractLaneDraft {
  return {
    laneCode: '', originTerminalId: '', originZone: '', destinationTerminalId: '', destinationZone: '',
    freightMode: '', loadType: '', rateBasis: 'PER_KG', rate: '', transitDays: '', slabs: '', consigneeIds: '',
  };
}

/** A lane's end for the table: a terminal, or a zone. */
function end(terminal: string, zone: string): string {
  return zone ? `zone ${zone}` : terminal;
}

function blankDraft(): ContractDraft {
  return {
    code: '', effectiveFrom: today(), validTill: '', transportRatePerKg: '', transportMinimumCharge: '',
    storageRatePerPalletDay: '', handlingInRatePerPallet: '', handlingOutRatePerPallet: '',
  };
}

function draftOf(d: ContractDetail): ContractDraft {
  return {
    code: d.code, effectiveFrom: d.effectiveFrom, validTill: d.validTill, transportRatePerKg: d.transportRatePerKg,
    transportMinimumCharge: d.transportMinimumCharge, storageRatePerPalletDay: d.storageRatePerPalletDay,
    handlingInRatePerPallet: d.handlingInRatePerPallet, handlingOutRatePerPallet: d.handlingOutRatePerPallet,
  };
}

/**
 * A client's contract lifecycle: draft a version, edit and delete it while it is a draft,
 * ask for it to be verified (a second person approves it in the approvals inbox), and on one
 * in force ask to extend its end date or add a charge from a day. Everything the server
 * refuses is shown with its code and, for the checklist, every failing rule.
 */
@Component({
  selector: 'app-contract-lifecycle',
  imports: [FormsModule, EntityPicker],
  template: `
    <section data-testid="contract-lifecycle" aria-label="Contract lifecycle">
      <h3>Contract versions</h3>
      <table>
        <thead><tr><th>Contract</th><th>State</th><th>From</th><th>To</th><th>Ends</th><th></th></tr></thead>
        <tbody>
          @for (c of contracts(); track c['id']) {
            <tr data-testid="version-row">
              <td>{{ c['code'] }}</td><td data-testid="version-state">{{ c['state'] }}</td>
              <td>{{ c['effectiveFrom'] }}</td><td>{{ c['effectiveTo'] }}</td><td>{{ c['validTill'] }}</td>
              <td><button class="link" data-testid="open-version" (click)="open(c['id'])">Open</button></td>
            </tr>
          }
        </tbody>
      </table>

      @if (session.can('CLIENT_CONTRACT_MANAGE')) {
        <h3>Draft a contract</h3>
        <form class="inline" (ngSubmit)="create()" data-testid="new-contract">
          @for (f of fields; track f[0]) {
            <label>{{ f[1] }}
              <input [name]="'new-' + f[0]" [type]="f[2]" [attr.data-testid]="'new-contract-' + f[0]"
                     [value]="form()[f[0]]" (input)="setNew(f[0], $any($event.target).value)" />
            </label>
          }
          <button type="submit" data-testid="create-draft">Create draft</button>
        </form>
      }
      @if (error()) {
        <p class="error" role="alert" data-testid="contract-error">
          @if (code()) { <code data-testid="contract-refusal">{{ code() }}</code> }
          {{ error() }}
        </p>
        @if (refused().length) {
          <ul data-testid="contract-refused-rules">
            @for (r of refused(); track r[0]) { <li [attr.data-testid]="'refused-' + r[0]">{{ r[0] }}: {{ r[1] }}</li> }
          </ul>
        }
      }
      @if (message()) { <p data-testid="contract-message">{{ message() }}</p> }

      @if (detail(); as d) {
        <section data-testid="contract-panel" aria-label="Contract version">
          <header>
            <h3>{{ d.code }}</h3>
            <button class="link" (click)="close()" aria-label="Close contract">Close</button>
          </header>
          <dl>
            <div><dt>State</dt><dd data-testid="contract-status">{{ d.state }}</dd></div>
            <div><dt>Starts</dt><dd>{{ d.effectiveFrom }}</dd></div>
            <div><dt>Ends</dt><dd data-testid="contract-valid-till">{{ d.validTill }}</dd></div>
            <div><dt>Services</dt><dd>@for (s of d.services; track s.serviceCode) { {{ s.serviceCode }} {{ s.rate }}/{{ s.unit }}; }</dd></div>
            <div><dt>Direct orders</dt><dd data-testid="contract-direct">{{ d.directOrdersAllowed ? 'Allowed' : 'Not allowed' }}</dd></div>
            <div><dt>Load types</dt><dd data-testid="contract-load-types">{{ d.allowedLoadTypes.length ? d.allowedLoadTypes.join(', ') : 'Any' }}</dd></div>
          </dl>
          <h4>Lanes</h4>
          @if (d.lanes.length === 0) { <p class="muted" data-testid="no-lanes">No lanes: orders are priced by the per-kilo rate.</p> }
          @if (d.lanes.length > 0) {
            <table data-testid="contract-lanes">
              <thead><tr><th>Lane</th><th>From</th><th>To</th><th>Mode</th><th>Load</th><th>Basis</th><th>Rate</th><th>Days</th><th>Slabs</th><th></th></tr></thead>
              <tbody>
                @for (l of d.lanes; track l.laneCode) {
                  <tr data-testid="lane-row">
                    <td data-testid="lane-code">{{ l.laneCode }}</td>
                    <td>{{ end(l.originTerminalId, l.originZone) }}</td><td>{{ end(l.destinationTerminalId, l.destinationZone) }}</td>
                    <td>{{ l.freightMode || 'Any' }}</td><td>{{ l.loadType || 'Any' }}</td>
                    <td>{{ l.rateBasis }}</td><td>{{ l.rate || '—' }}</td><td>{{ l.transitDays || '—' }}</td>
                    <td>{{ l.slabs || '—' }}</td>
                    <td>
                      @if (d.status === 'DRAFT' && session.can('CLIENT_CONTRACT_MANAGE')) {
                        <button class="link" data-testid="remove-lane" (click)="removeLane(l.laneCode)">Remove</button>
                      }
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          }
          @if (d.rejectionReason) {
            <p class="error" data-testid="contract-rejection">Verification refused: {{ d.rejectionReason }}</p>
          }

          @if (d.status === 'DRAFT' && session.can('CLIENT_CONTRACT_MANAGE')) {
            <ul data-testid="contract-checklist">
              @for (c of checklist(); track c.code) {
                <li [attr.data-testid]="'check-' + c.code" [class.error]="!c.passed">
                  {{ c.code }}: {{ c.passed ? 'passes' : c.message }}{{ c.required ? '' : ' (not required)' }}
                </li>
              }
            </ul>
            <form class="inline" (ngSubmit)="saveAllowances()" data-testid="edit-allowances">
              <label>Direct orders allowed
                <input type="checkbox" name="direct" data-testid="allow-direct" [checked]="direct()"
                       (change)="direct.set($any($event.target).checked)" />
              </label>
              <input name="loadTypes" aria-label="Allowed load types" placeholder="Allowed load types (comma separated)"
                     data-testid="allowed-load-types" [value]="loadTypes()" (input)="loadTypes.set($any($event.target).value)" />
              <button type="submit" data-testid="save-allowances">Save allowances</button>
            </form>
            <form class="inline" (ngSubmit)="addLane()" data-testid="add-lane">
              <input name="laneCode" aria-label="Lane code" placeholder="Lane code" data-testid="lane-form-code"
                     [value]="lane().laneCode" (input)="setLane('laneCode', $any($event.target).value)" required />
              <app-entity-picker [kind]="'terminal'" ariaLabel="Lane origin terminal id" placeholder="Origin terminal"
                                 testId="lane-form-origin" [value]="lane().originTerminalId"
                                 (valueChange)="setLane('originTerminalId', $event)" />
              <input name="originZone" aria-label="Lane origin zone" placeholder="or origin zone" data-testid="lane-form-origin-zone"
                     [value]="lane().originZone" (input)="setLane('originZone', $any($event.target).value)" />
              <app-entity-picker [kind]="'terminal'" ariaLabel="Lane destination terminal id" placeholder="Destination terminal"
                                 testId="lane-form-destination" [value]="lane().destinationTerminalId"
                                 (valueChange)="setLane('destinationTerminalId', $event)" />
              <input name="destinationZone" aria-label="Lane destination zone" placeholder="or destination zone"
                     data-testid="lane-form-destination-zone" [value]="lane().destinationZone"
                     (input)="setLane('destinationZone', $any($event.target).value)" />
              <select name="freightMode" aria-label="Lane freight mode" data-testid="lane-form-mode"
                      (change)="setLane('freightMode', $any($event.target).value)">
                <option value="" [selected]="!lane().freightMode">Any mode</option>
                <option value="FTL" [selected]="lane().freightMode === 'FTL'">FTL</option>
                <option value="LTL" [selected]="lane().freightMode === 'LTL'">LTL</option>
              </select>
              <input name="laneLoadType" aria-label="Lane load type" placeholder="Load type (any)" data-testid="lane-form-load-type"
                     [value]="lane().loadType" (input)="setLane('loadType', $any($event.target).value)" />
              <select name="rateBasis" aria-label="Lane rate basis" data-testid="lane-form-basis"
                      (change)="setLane('rateBasis', $any($event.target).value)">
                @for (b of rateBases; track b) { <option [value]="b" [selected]="lane().rateBasis === b">{{ b }}</option> }
              </select>
              <input name="laneRate" aria-label="Lane rate" placeholder="Rate" inputmode="decimal" data-testid="lane-form-rate"
                     [value]="lane().rate" (input)="setLane('rate', $any($event.target).value)" />
              <input name="laneDays" aria-label="Lane transit days" placeholder="Transit days" inputmode="numeric"
                     data-testid="lane-form-days" [value]="lane().transitDays"
                     (input)="setLane('transitDays', $any($event.target).value)" />
              <input name="laneSlabs" aria-label="Lane slabs" placeholder="Slabs: 0-1000:PER_KG:12; 1000-:PER_TONNE:9000"
                     data-testid="lane-form-slabs" [value]="lane().slabs" (input)="setLane('slabs', $any($event.target).value)" />
              <input name="laneConsignees" aria-label="Lane consignee ids" placeholder="Consignee ids (comma separated)"
                     data-testid="lane-form-consignees" [value]="lane().consigneeIds"
                     (input)="setLane('consigneeIds', $any($event.target).value)" />
              <button type="submit" data-testid="add-lane-button">Add lane</button>
            </form>
            <form class="inline" (ngSubmit)="save()" data-testid="edit-contract">
              @for (f of fields; track f[0]) {
                <label>{{ f[1] }}
                  <input [name]="'edit-' + f[0]" [type]="f[2]" [attr.data-testid]="'edit-contract-' + f[0]"
                         [value]="edit()[f[0]]" (input)="setEdit(f[0], $any($event.target).value)" />
                </label>
              }
              <button type="submit" data-testid="save-draft">Save draft</button>
            </form>
            <p class="actions">
              <button data-testid="request-verification" (click)="verify()">Request verification</button>
              <button class="link" data-testid="delete-draft" (click)="remove()">Delete draft</button>
            </p>
          }
          @if (d.status === 'PENDING_VERIFICATION') {
            <p class="muted" data-testid="contract-pending">Awaiting verification in the approvals inbox.</p>
          }

          @if (d.status === 'ACTIVE' && !d.effectiveTo && session.can('CLIENT_CONTRACT_MANAGE')) {
            @if (d.validTill) {
              <h4>Extend</h4>
              <form class="inline" (ngSubmit)="extend()" data-testid="extend-contract">
                <input name="extendTill" type="date" aria-label="New end date" data-testid="extend-till"
                       [value]="extendTill()" (input)="extendTill.set($any($event.target).value)" required />
                <input name="extendReason" aria-label="Reason for the extension" placeholder="Reason"
                       data-testid="extend-reason" [value]="extendReason()"
                       (input)="extendReason.set($any($event.target).value)" required />
                <button type="submit" data-testid="request-extension">Request extension</button>
              </form>
            }
            <h4>Add a charge</h4>
            <form class="inline" (ngSubmit)="addCharge()" data-testid="add-charge">
              <input name="chargeCode" aria-label="Service code" placeholder="Service code" data-testid="charge-code"
                     [value]="charge().serviceCode" (input)="setCharge('serviceCode', $any($event.target).value)" required />
              <input name="chargeUnit" aria-label="Unit" placeholder="Unit" data-testid="charge-unit"
                     [value]="charge().unit" (input)="setCharge('unit', $any($event.target).value)" required />
              <input name="chargeRate" aria-label="Rate" placeholder="Rate" inputmode="decimal" data-testid="charge-rate"
                     [value]="charge().rate" (input)="setCharge('rate', $any($event.target).value)" required />
              <input name="chargeFrom" type="date" aria-label="Applies from" data-testid="charge-from"
                     [value]="charge().effectiveFrom" (input)="setCharge('effectiveFrom', $any($event.target).value)" required />
              <input name="chargeReason" aria-label="Reason for the charge" placeholder="Reason" data-testid="charge-reason"
                     [value]="charge().reason" (input)="setCharge('reason', $any($event.target).value)" required />
              <button type="submit" data-testid="request-charge">Request charge</button>
            </form>
          }
        </section>
      }
    </section>
  `,
})
export class ContractLifecycle {
  private readonly gateway = inject(LmsGateway);
  readonly session = inject(Session);

  readonly clientId = input.required<string>();
  readonly contracts = input.required<Row[]>();
  /** The list of versions should be read again. */
  readonly changed = output<void>();

  readonly fields = FIELDS;
  readonly rateBases = LANE_RATE_BASES;
  readonly end = end;
  readonly lane = signal<ContractLaneDraft>(blankLane());
  readonly direct = signal(true);
  readonly loadTypes = signal('');
  readonly form = signal<ContractDraft>(blankDraft());
  readonly edit = signal<ContractDraft>(blankDraft());
  readonly detail = signal<ContractDetail | null>(null);
  readonly checklist = signal<ContractChecklistRow[]>([]);
  readonly error = signal('');
  readonly code = signal('');
  readonly refused = signal<Array<[string, string]>>([]);
  readonly message = signal('');
  readonly extendTill = signal('');
  readonly extendReason = signal('');
  readonly charge = signal<ContractChargeDraft>({
    serviceCode: '', unit: 'UNIT', rate: '', effectiveFrom: today(), reason: '',
  });

  constructor() {
    // A version opened earlier follows the list when it is read again.
    effect(() => {
      this.contracts();
      const open = untracked(() => this.detail());
      if (open) {
        void this.open(open.id);
      }
    });
  }

  setNew(field: keyof ContractDraft, value: string): void {
    this.form.update((f) => ({ ...f, [field]: value }));
  }

  setEdit(field: keyof ContractDraft, value: string): void {
    this.edit.update((f) => ({ ...f, [field]: value }));
  }

  setLane(field: keyof ContractLaneDraft, value: string): void {
    this.lane.update((l) => ({ ...l, [field]: value }));
  }

  setCharge(field: keyof ContractChargeDraft, value: string): void {
    this.charge.update((c) => ({ ...c, [field]: value }));
  }

  async open(id: string): Promise<void> {
    try {
      const d = await this.gateway.contractDetail(id);
      this.detail.set(d);
      this.edit.set(draftOf(d));
      this.direct.set(d.directOrdersAllowed);
      this.loadTypes.set(d.allowedLoadTypes.join(', '));
      this.checklist.set(d.status === 'DRAFT' ? await this.gateway.contractChecklist(id) : []);
    } catch (e) {
      this.fail(e);
    }
  }

  close(): void {
    this.detail.set(null);
  }

  async create(): Promise<void> {
    await this.act(async () => {
      const id = await this.gateway.createContractDraft(this.session.orgUnitId() ?? '', this.clientId(), this.form());
      this.message.set('Draft created.');
      this.form.set(blankDraft());
      await this.open(id);
    });
  }

  async save(): Promise<void> {
    const d = this.detail();
    if (!d) {
      return;
    }
    await this.act(async () => {
      await this.gateway.reviseContractDraft(d, this.edit());
      this.message.set('Draft saved.');
      await this.open(d.id);
    });
  }

  /** Adds the lane typed to the draft: the lanes are restated whole, so a clash of codes is refused by name. */
  async addLane(): Promise<void> {
    const d = this.detail();
    if (!d) {
      return;
    }
    await this.act(async () => {
      await this.gateway.reviseContractLanes(d, [...d.lanes, this.lane()], d.directOrdersAllowed, d.allowedLoadTypes);
      this.message.set('Lane added.');
      this.lane.set(blankLane());
      await this.open(d.id);
    });
  }

  async removeLane(laneCode: string): Promise<void> {
    const d = this.detail();
    if (!d) {
      return;
    }
    await this.act(async () => {
      await this.gateway.reviseContractLanes(d, d.lanes.filter((l) => l.laneCode !== laneCode), d.directOrdersAllowed,
        d.allowedLoadTypes);
      this.message.set('Lane removed.');
      await this.open(d.id);
    });
  }

  /** Whether orders outside the lanes are accepted, and which load types the contract covers. */
  async saveAllowances(): Promise<void> {
    const d = this.detail();
    if (!d) {
      return;
    }
    const types = this.loadTypes().split(',').map((t) => t.trim()).filter(Boolean);
    await this.act(async () => {
      await this.gateway.reviseContractLanes(d, d.lanes, this.direct(), types);
      this.message.set('Allowances saved.');
      await this.open(d.id);
    });
  }

  async remove(): Promise<void> {
    const d = this.detail();
    if (!d) {
      return;
    }
    await this.act(async () => {
      await this.gateway.deleteContractDraft(d.id, d.version);
      this.detail.set(null);
      this.message.set('Draft deleted.');
    });
  }

  async verify(): Promise<void> {
    const d = this.detail();
    if (!d) {
      return;
    }
    await this.act(async () => {
      const ticket = await this.gateway.requestContractVerification(d.id, d.version);
      this.message.set(ticket ? 'Verification requested: awaiting approval.' : 'The contract is in force.');
      await this.open(d.id);
    });
  }

  async extend(): Promise<void> {
    const d = this.detail();
    if (!d) {
      return;
    }
    await this.act(async () => {
      const ticket = await this.gateway.requestContractExtension(d.id, this.extendTill(), this.extendReason());
      this.message.set(ticket ? 'Extension requested: awaiting approval.' : 'The end date has moved.');
      await this.open(d.id);
    });
  }

  async addCharge(): Promise<void> {
    const d = this.detail();
    if (!d) {
      return;
    }
    await this.act(async () => {
      const ticket = await this.gateway.requestContractChargeAddition(d.id, this.charge());
      this.message.set(ticket ? 'Charge requested: awaiting approval.' : 'A successor version carries the charge.');
      await this.open(d.id);
    });
  }

  /** Runs one action, shows what the server refused it with, and reads the versions again. */
  private async act(action: () => Promise<void>): Promise<void> {
    this.error.set('');
    this.code.set('');
    this.refused.set([]);
    this.message.set('');
    try {
      await action();
      this.changed.emit();
    } catch (e) {
      this.fail(e);
    }
  }

  private fail(e: unknown): void {
    this.error.set((e as Error).message);
    if (e instanceof ApiProblem) {
      this.code.set(e.code);
      this.refused.set(Object.entries(e.errors));
    }
  }
}
