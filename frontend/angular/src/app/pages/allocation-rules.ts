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

import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  AllocationRuleView, AllocationSummary, Choice, LmsGateway, MATERIAL_CLASSES, RULE_ACTIONS, RuleAction,
  RuleDraft, RuleEvaluation,
} from '../gateway/lms-gateway';
import { Session } from '../session';

/** Who a load is offered to first: the rules that decide it, a dry run, and what decided an allocation. */
@Component({
  selector: 'app-allocation-rules',
  imports: [FormsModule],
  template: `
    <h1>Allocation rules</h1>
    @if (!read()) {
      <p class="muted" data-testid="no-rights">You do not have the right to read allocation rules.</p>
    } @else {
      <p class="muted">
        When a load is offered, the first active rule that matches it decides who is asked: PREFER asks its vendors
        first, in order; EXCLUDE never asks them. A rule is retired, never deleted.
      </p>
      @if (loadError()) { <p class="error" role="alert">{{ loadError() }}</p> }
      @if (error()) { <p class="error" role="alert">{{ error() }}</p> }
      @if (loading()) {
        <p class="muted">Loading…</p>
      } @else if (rules().length === 0) {
        <p class="muted" data-testid="no-rules">No rules yet: loads are offered by the routing guide alone.</p>
      } @else {
        <table data-testid="rules-table">
          <thead>
            <tr><th>Priority</th><th>Name</th><th>When</th><th>Action</th><th>Vendors</th><th>Status</th><th></th></tr>
          </thead>
          <tbody>
            @for (r of rules(); track r.id) {
              <tr data-testid="rule-row">
                <td>{{ r.priority }}</td>
                <td data-testid="rule-name">{{ r.name }}</td>
                <td>{{ conditions(r) }}</td>
                <td data-testid="rule-action">{{ r.action }}</td>
                <td data-testid="rule-vendors">{{ vendorList(r) }}</td>
                <td data-testid="rule-status">{{ r.active ? 'Active' : 'Retired' }}</td>
                <td>
                  @if (manage()) {
                    <button class="link" data-testid="rule-edit" (click)="edit(r)">Edit</button>
                    <button class="link" [attr.data-testid]="r.active ? 'rule-retire' : 'rule-reinstate'"
                            (click)="setActive(r, !r.active)">{{ r.active ? 'Retire' : 'Reinstate' }}</button>
                  }
                </td>
              </tr>
            }
          </tbody>
        </table>
      }

      @if (manage()) {
        <form class="card" (ngSubmit)="save()" data-testid="rule-form">
          <h2>{{ editing() ? 'Edit ' + editing()!.name : 'New rule' }}</h2>
          <label>Name <input name="name" data-testid="rule-name-input" [(ngModel)]="form.name" required /></label>
          <label>Priority (lowest first)
            <input name="priority" type="number" min="0" max="100000" data-testid="rule-priority"
                   [(ngModel)]="form.priority" required />
          </label>
          <label>From
            <select name="origin" data-testid="rule-origin" [(ngModel)]="form.origin">
              <option value="">Any origin</option>
              @for (t of terminals(); track t.id) { <option [value]="t.id">{{ t.label }}</option> }
            </select>
          </label>
          <label>To
            <select name="destination" data-testid="rule-destination" [(ngModel)]="form.destination">
              <option value="">Any destination</option>
              @for (t of terminals(); track t.id) { <option [value]="t.id">{{ t.label }}</option> }
            </select>
          </label>
          <label>Weight from (kg)
            <input name="minWeight" type="number" min="0" step="any" data-testid="rule-min-weight" [(ngModel)]="form.minWeight" />
          </label>
          <label>Weight to (kg)
            <input name="maxWeight" type="number" min="0" step="any" data-testid="rule-max-weight" [(ngModel)]="form.maxWeight" />
          </label>
          <label>Goods
            <select name="material" data-testid="rule-material" [(ngModel)]="form.material">
              <option value="">Any goods</option>
              @for (m of materials; track m) { <option [value]="m">{{ m }}</option> }
            </select>
          </label>
          <label>Action
            <select name="action" data-testid="rule-action-input" [(ngModel)]="form.action">
              @for (a of actions; track a) { <option [value]="a">{{ a }}</option> }
            </select>
          </label>
          <div>
            <span>Vendors{{ form.action === 'PREFER' ? ', in the order they are asked' : '' }}</span>
            <ol data-testid="rule-vendor-list">
              @for (c of chosen(); track c.id) {
                <li data-testid="rule-vendor">
                  {{ c.label }}
                  <button type="button" class="link" data-testid="rule-vendor-remove" (click)="remove(c.id)">Remove</button>
                </li>
              }
            </ol>
            <select name="pick" aria-label="Vendor to add" data-testid="rule-vendor-pick" [(ngModel)]="pick">
              <option value="">Choose a vendor…</option>
              @for (v of free(); track v.id) { <option [value]="v.id">{{ v.label }}</option> }
            </select>
            <button type="button" data-testid="rule-vendor-add" (click)="add()" [disabled]="!pick">Add</button>
          </div>
          @if (formError()) { <p class="error" role="alert" data-testid="rule-error">{{ formError() }}</p> }
          <p class="actions">
            <button type="submit" data-testid="rule-save">{{ editing() ? 'Save changes' : 'Save rule' }}</button>
            @if (editing()) { <button type="button" class="link" (click)="reset()">Cancel</button> }
          </p>
        </form>
      }

      <section data-testid="dry-run">
        <h2>Dry run</h2>
        <p class="muted">Which rule would fire for a load, and who would be asked first. Nothing is offered.</p>
        <form class="inline" (ngSubmit)="evaluate()">
          <input name="loadId" placeholder="Load id" aria-label="Load id" data-testid="dry-run-load"
                 [(ngModel)]="loadId" required />
          <button type="submit" data-testid="dry-run-go">Evaluate</button>
        </form>
        @if (dryRunError()) { <p class="error" role="alert">{{ dryRunError() }}</p> }
        @if (evaluation(); as e) {
          <dl data-testid="dry-run-result">
            <div><dt>Load</dt><dd>{{ e.loadNo }}</dd></div>
            <div><dt>Rule</dt><dd data-testid="dry-run-rule">
              {{ e.ruleName ? e.ruleName + ' (' + e.action + ')' : 'No rule fires' }}
            </dd></div>
            <div><dt>Asked first</dt><dd data-testid="dry-run-next">{{ e.nextVendor || 'Nobody' }}</dd></div>
            <div><dt>Order</dt><dd>
              <ol>
                @for (c of e.candidates; track c.id) { <li data-testid="dry-run-order">{{ c.label }}</li> }
              </ol>
            </dd></div>
          </dl>
        }
      </section>

      <section data-testid="allocation-lookup">
        <h2>What decided an allocation</h2>
        <form class="inline" (ngSubmit)="lookUp()">
          <input name="allocationId" placeholder="Allocation id" aria-label="Allocation id" data-testid="allocation-id"
                 [(ngModel)]="allocationId" required />
          <button type="submit" data-testid="allocation-go">Look up</button>
        </form>
        @if (lookUpError()) { <p class="error" role="alert">{{ lookUpError() }}</p> }
        @if (found(); as a) {
          <dl data-testid="allocation-result">
            <div><dt>Load</dt><dd>{{ a.loadNo }}</dd></div>
            <div><dt>Status</dt><dd data-testid="allocation-status">{{ a.status }}</dd></div>
            <div><dt>Rule</dt><dd data-testid="allocation-rule">
              {{ a.appliedRuleName ? a.appliedRuleName + ' (' + a.appliedRuleAction + ')' : 'No rule fired' }}
            </dd></div>
          </dl>
        }
      </section>
    }
  `,
})
export class AllocationRules implements OnInit {
  private readonly gateway = inject(LmsGateway);
  private readonly session = inject(Session);

  readonly read = computed(() => this.session.can('ALLOCATION_RULE_READ'));
  readonly manage = computed(() => this.session.can('ALLOCATION_RULE_MANAGE'));
  readonly materials = MATERIAL_CLASSES;
  readonly actions = RULE_ACTIONS;

  readonly rules = signal<AllocationRuleView[]>([]);
  readonly loading = signal(true);
  readonly loadError = signal('');
  readonly error = signal('');
  readonly terminals = signal<Choice[]>([]);
  readonly vendors = signal<Choice[]>([]);

  readonly editing = signal<AllocationRuleView | null>(null);
  readonly chosen = signal<Choice[]>([]);
  // Derived, not stored: the vendors still free to add.
  readonly free = computed(() => this.vendors().filter((v) => !this.chosen().some((c) => c.id === v.id)));
  readonly formError = signal('');
  form = this.blank();
  pick = '';

  loadId = '';
  readonly evaluation = signal<RuleEvaluation | null>(null);
  readonly dryRunError = signal('');

  allocationId = '';
  readonly found = signal<AllocationSummary | null>(null);
  readonly lookUpError = signal('');

  async ngOnInit(): Promise<void> {
    if (!this.read()) {
      return;
    }
    await this.refresh();
    if (this.manage()) {
      try {
        const [terminals, vendors] = await Promise.all([this.gateway.terminalChoices(), this.gateway.activeVendors()]);
        this.terminals.set(terminals);
        this.vendors.set(vendors);
      } catch (e) {
        this.loadError.set((e as Error).message);
      }
    }
  }

  conditions(rule: AllocationRuleView): string {
    const name = (id: string) => this.terminals().find((t) => t.id === id)?.label ?? id;
    const parts: string[] = [];
    if (rule.originTerminalId) parts.push(`from ${name(rule.originTerminalId)}`);
    if (rule.destinationTerminalId) parts.push(`to ${name(rule.destinationTerminalId)}`);
    if (rule.minWeightKg || rule.maxWeightKg) parts.push(`${rule.minWeightKg || '0'}–${rule.maxWeightKg || '∞'} kg`);
    if (rule.materialClass) parts.push(rule.materialClass);
    return parts.length ? parts.join(', ') : 'Every load';
  }

  vendorList(rule: AllocationRuleView): string {
    return rule.vendors.map((v) => v.label).join(rule.action === 'PREFER' ? ' → ' : ', ');
  }

  edit(rule: AllocationRuleView): void {
    this.editing.set(rule);
    this.form = {
      name: rule.name, priority: rule.priority, origin: rule.originTerminalId, destination: rule.destinationTerminalId,
      minWeight: rule.minWeightKg, maxWeight: rule.maxWeightKg, material: rule.materialClass, action: rule.action,
    };
    this.chosen.set([...rule.vendors]);
    this.pick = '';
    this.formError.set('');
  }

  reset(): void {
    this.editing.set(null);
    this.form = this.blank();
    this.chosen.set([]);
    this.pick = '';
    this.formError.set('');
  }

  add(): void {
    const vendor = this.vendors().find((v) => v.id === this.pick);
    if (vendor) {
      this.chosen.set([...this.chosen(), vendor]);
      this.pick = '';
    }
  }

  remove(id: string): void {
    this.chosen.set(this.chosen().filter((c) => c.id !== id));
  }

  async save(): Promise<void> {
    this.formError.set('');
    const editing = this.editing();
    const draft: RuleDraft = {
      name: String(this.form.name).trim(), priority: Number(this.form.priority), active: editing?.active ?? true,
      originTerminalId: this.form.origin || undefined, destinationTerminalId: this.form.destination || undefined,
      minWeightKg: weight(this.form.minWeight), maxWeightKg: weight(this.form.maxWeight),
      materialClass: this.form.material || undefined, action: this.form.action as RuleAction,
      vendorIds: this.chosen().map((c) => c.id),
    };
    try {
      if (editing) {
        await this.gateway.reviseAllocationRule(editing.id, draft);
      } else {
        await this.gateway.createAllocationRule(draft);
      }
      this.reset();
      await this.refresh();
    } catch (e) {
      this.formError.set((e as Error).message);
    }
  }

  async setActive(rule: AllocationRuleView, active: boolean): Promise<void> {
    this.error.set('');
    try {
      await this.gateway.reviseAllocationRule(rule.id, { ...draftOf(rule), active });
      await this.refresh();
    } catch (e) {
      this.error.set((e as Error).message);
    }
  }

  async evaluate(): Promise<void> {
    this.dryRunError.set('');
    this.evaluation.set(null);
    try {
      this.evaluation.set(await this.gateway.evaluateAllocation(this.loadId.trim()));
    } catch (e) {
      this.dryRunError.set((e as Error).message);
    }
  }

  async lookUp(): Promise<void> {
    this.lookUpError.set('');
    this.found.set(null);
    try {
      this.found.set(await this.gateway.allocation(this.allocationId.trim()));
    } catch (e) {
      this.lookUpError.set((e as Error).message);
    }
  }

  private blank() {
    return {
      name: '', priority: 100 as number | string, origin: '', destination: '',
      minWeight: '' as number | string, maxWeight: '' as number | string, material: '', action: 'PREFER' as string,
    };
  }

  private async refresh(): Promise<void> {
    this.loadError.set('');
    try {
      this.rules.set(await this.gateway.allocationRules());
    } catch (e) {
      this.loadError.set((e as Error).message);
    } finally {
      this.loading.set(false);
    }
  }
}

/** An empty input means "not tested"; ngModel hands a number input back as a number or null. */
function weight(value: number | string | null): number | undefined {
  return value === '' || value === null ? undefined : Number(value);
}

function draftOf(rule: AllocationRuleView): RuleDraft {
  return {
    name: rule.name, priority: rule.priority, active: rule.active,
    originTerminalId: rule.originTerminalId || undefined,
    destinationTerminalId: rule.destinationTerminalId || undefined,
    minWeightKg: rule.minWeightKg ? Number(rule.minWeightKg) : undefined,
    maxWeightKg: rule.maxWeightKg ? Number(rule.maxWeightKg) : undefined,
    materialClass: rule.materialClass || undefined,
    action: rule.action, vendorIds: rule.vendors.map((v) => v.id),
  };
}
