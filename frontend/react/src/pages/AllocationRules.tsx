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

import { useState, type FormEvent } from 'react';
import {
  MATERIAL_CLASSES, RULE_ACTIONS, type AllocationRuleView, type AllocationSummary, type Choice,
  type RuleAction, type RuleDraft, type RuleEvaluation,
} from '../gateway/lmsGateway';
import { useSession } from '../session';
import { useLoad } from '../useLoad';

/** Who a load is offered to first: the rules that decide it, a dry run, and what decided an allocation. */
export function AllocationRules() {
  const { gateway, can } = useSession();
  const [reload, setReload] = useState(0);
  const [editing, setEditing] = useState<AllocationRuleView | null>(null);
  // Bumped on every save, so the form starts empty again even when it was not editing.
  const [saved, setSaved] = useState(0);
  const [error, setError] = useState('');

  const rules = useLoad(() => gateway.allocationRules(), reload);
  // The pickers' choices change rarely; they are loaded once for the screen.
  const choices = useLoad(
    () => (can('ALLOCATION_RULE_MANAGE')
      ? Promise.all([gateway.terminalChoices(), gateway.activeVendors()])
      : Promise.resolve([[], []] as Choice[][])),
    'once',
  );
  const terminals = choices.data?.[0] ?? [];
  const vendors = choices.data?.[1] ?? [];

  async function setActive(rule: AllocationRuleView, active: boolean) {
    setError('');
    try {
      await gateway.reviseAllocationRule(rule.id, { ...draftOf(rule), active });
      setReload((n) => n + 1);
    } catch (e) {
      setError((e as Error).message);
    }
  }

  if (!can('ALLOCATION_RULE_READ')) {
    return (
      <>
        <h1>Allocation rules</h1>
        <p className="muted" data-testid="no-rights">You do not have the right to read allocation rules.</p>
      </>
    );
  }

  return (
    <>
      <h1>Allocation rules</h1>
      <p className="muted">
        When a load is offered, the first active rule that matches it decides who is asked: PREFER asks its vendors
        first, in order; EXCLUDE never asks them. A rule is retired, never deleted.
      </p>
      {rules.error && <p className="error" role="alert">{rules.error}</p>}
      {error && <p className="error" role="alert">{error}</p>}
      {rules.loading ? <p className="muted">Loading…</p> : (rules.data ?? []).length === 0 ? (
        <p className="muted" data-testid="no-rules">No rules yet: loads are offered by the routing guide alone.</p>
      ) : (
        <table data-testid="rules-table">
          <thead>
            <tr><th>Priority</th><th>Name</th><th>When</th><th>Action</th><th>Vendors</th><th>Status</th><th /></tr>
          </thead>
          <tbody>
            {(rules.data ?? []).map((r) => (
              <tr key={r.id} data-testid="rule-row">
                <td>{r.priority}</td>
                <td data-testid="rule-name">{r.name}</td>
                <td>{conditions(r, terminals)}</td>
                <td data-testid="rule-action">{r.action}</td>
                <td data-testid="rule-vendors">{r.vendors.map((v) => v.label).join(r.action === 'PREFER' ? ' → ' : ', ')}</td>
                <td data-testid="rule-status">{r.active ? 'Active' : 'Retired'}</td>
                <td>
                  {can('ALLOCATION_RULE_MANAGE') && (
                    <>
                      <button className="link" data-testid="rule-edit" onClick={() => setEditing(r)}>Edit</button>{' '}
                      <button className="link" data-testid={r.active ? 'rule-retire' : 'rule-reinstate'}
                        onClick={() => setActive(r, !r.active)}>{r.active ? 'Retire' : 'Reinstate'}</button>
                    </>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {can('ALLOCATION_RULE_MANAGE') && (
        // A key per rule being edited: a fresh form, with its own state, each time.
        <RuleForm key={`${editing?.id ?? 'new'}:${saved}`} editing={editing} terminals={terminals} vendors={vendors}
          onSaved={() => { setEditing(null); setSaved((n) => n + 1); setReload((n) => n + 1); }}
          onCancel={() => setEditing(null)} />
      )}
      <DryRun />
      <AllocationLookUp />
    </>
  );
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

/** The rule's conditions in words; "Every load" when it has none. */
function conditions(rule: AllocationRuleView, terminals: Choice[]): string {
  const name = (id: string) => terminals.find((t) => t.id === id)?.label ?? id;
  const parts: string[] = [];
  if (rule.originTerminalId) parts.push(`from ${name(rule.originTerminalId)}`);
  if (rule.destinationTerminalId) parts.push(`to ${name(rule.destinationTerminalId)}`);
  if (rule.minWeightKg || rule.maxWeightKg) parts.push(`${rule.minWeightKg || '0'}–${rule.maxWeightKg || '∞'} kg`);
  if (rule.materialClass) parts.push(rule.materialClass);
  return parts.length ? parts.join(', ') : 'Every load';
}

interface RuleFormProps {
  editing: AllocationRuleView | null;
  terminals: Choice[];
  vendors: Choice[];
  onSaved: () => void;
  onCancel: () => void;
}

function RuleForm({ editing, terminals, vendors, onSaved, onCancel }: RuleFormProps) {
  const { gateway } = useSession();
  const [name, setName] = useState(editing?.name ?? '');
  const [priority, setPriority] = useState(String(editing?.priority ?? 100));
  const [origin, setOrigin] = useState(editing?.originTerminalId ?? '');
  const [destination, setDestination] = useState(editing?.destinationTerminalId ?? '');
  const [minWeight, setMinWeight] = useState(editing?.minWeightKg ?? '');
  const [maxWeight, setMaxWeight] = useState(editing?.maxWeightKg ?? '');
  const [material, setMaterial] = useState(editing?.materialClass ?? '');
  const [action, setAction] = useState<RuleAction>(editing?.action ?? 'PREFER');
  const [chosen, setChosen] = useState<Choice[]>(editing?.vendors ?? []);
  const [pick, setPick] = useState('');
  const [error, setError] = useState('');

  // Derived, not stored: the vendors still free to add.
  const free = vendors.filter((v) => !chosen.some((c) => c.id === v.id));

  function add() {
    const vendor = vendors.find((v) => v.id === pick);
    if (vendor) {
      setChosen([...chosen, vendor]);
      setPick('');
    }
  }

  async function save(event: FormEvent) {
    event.preventDefault();
    setError('');
    const draft: RuleDraft = {
      name: name.trim(), priority: Number(priority), active: editing?.active ?? true,
      originTerminalId: origin || undefined, destinationTerminalId: destination || undefined,
      minWeightKg: minWeight === '' ? undefined : Number(minWeight),
      maxWeightKg: maxWeight === '' ? undefined : Number(maxWeight),
      materialClass: material || undefined, action, vendorIds: chosen.map((c) => c.id),
    };
    try {
      if (editing) {
        await gateway.reviseAllocationRule(editing.id, draft);
      } else {
        await gateway.createAllocationRule(draft);
      }
      onSaved();
    } catch (e) {
      setError((e as Error).message);
    }
  }

  return (
    <form className="card" onSubmit={save} data-testid="rule-form">
      <h2>{editing ? `Edit ${editing.name}` : 'New rule'}</h2>
      <label>Name <input data-testid="rule-name-input" value={name} onChange={(e) => setName(e.target.value)} required /></label>
      <label>Priority (lowest first)
        <input type="number" min={0} max={100000} data-testid="rule-priority" value={priority}
          onChange={(e) => setPriority(e.target.value)} required />
      </label>
      <label>From
        <select data-testid="rule-origin" value={origin} onChange={(e) => setOrigin(e.target.value)}>
          <option value="">Any origin</option>
          {terminals.map((t) => <option key={t.id} value={t.id}>{t.label}</option>)}
        </select>
      </label>
      <label>To
        <select data-testid="rule-destination" value={destination} onChange={(e) => setDestination(e.target.value)}>
          <option value="">Any destination</option>
          {terminals.map((t) => <option key={t.id} value={t.id}>{t.label}</option>)}
        </select>
      </label>
      <label>Weight from (kg)
        <input type="number" min={0} step="any" data-testid="rule-min-weight" value={minWeight}
          onChange={(e) => setMinWeight(e.target.value)} />
      </label>
      <label>Weight to (kg)
        <input type="number" min={0} step="any" data-testid="rule-max-weight" value={maxWeight}
          onChange={(e) => setMaxWeight(e.target.value)} />
      </label>
      <label>Goods
        <select data-testid="rule-material" value={material} onChange={(e) => setMaterial(e.target.value)}>
          <option value="">Any goods</option>
          {MATERIAL_CLASSES.map((m) => <option key={m} value={m}>{m}</option>)}
        </select>
      </label>
      <label>Action
        <select data-testid="rule-action-input" value={action} onChange={(e) => setAction(e.target.value as RuleAction)}>
          {RULE_ACTIONS.map((a) => <option key={a} value={a}>{a}</option>)}
        </select>
      </label>
      <div>
        <span>Vendors{action === 'PREFER' ? ', in the order they are asked' : ''}</span>
        <ol data-testid="rule-vendor-list">
          {chosen.map((c) => (
            <li key={c.id} data-testid="rule-vendor">
              {c.label}{' '}
              <button type="button" className="link" data-testid="rule-vendor-remove"
                onClick={() => setChosen(chosen.filter((x) => x.id !== c.id))}>Remove</button>
            </li>
          ))}
        </ol>
        <select aria-label="Vendor to add" data-testid="rule-vendor-pick" value={pick} onChange={(e) => setPick(e.target.value)}>
          <option value="">Choose a vendor…</option>
          {free.map((v) => <option key={v.id} value={v.id}>{v.label}</option>)}
        </select>{' '}
        <button type="button" data-testid="rule-vendor-add" onClick={add} disabled={!pick}>Add</button>
      </div>
      {error && <p className="error" role="alert" data-testid="rule-error">{error}</p>}
      <p className="actions">
        <button type="submit" data-testid="rule-save">{editing ? 'Save changes' : 'Save rule'}</button>
        {editing && <button type="button" className="link" onClick={onCancel}>Cancel</button>}
      </p>
    </form>
  );
}

function DryRun() {
  const { gateway } = useSession();
  const [loadId, setLoadId] = useState('');
  const [result, setResult] = useState<RuleEvaluation | null>(null);
  const [error, setError] = useState('');

  async function run(event: FormEvent) {
    event.preventDefault();
    setError('');
    setResult(null);
    try {
      setResult(await gateway.evaluateAllocation(loadId.trim()));
    } catch (e) {
      setError((e as Error).message);
    }
  }

  return (
    <section data-testid="dry-run">
      <h2>Dry run</h2>
      <p className="muted">Which rule would fire for a load, and who would be asked first. Nothing is offered.</p>
      <form className="inline" onSubmit={run}>
        <input placeholder="Load id" aria-label="Load id" data-testid="dry-run-load" value={loadId}
          onChange={(e) => setLoadId(e.target.value)} required />
        <button type="submit" data-testid="dry-run-go">Evaluate</button>
      </form>
      {error && <p className="error" role="alert">{error}</p>}
      {result && (
        <dl data-testid="dry-run-result">
          <div><dt>Load</dt><dd>{result.loadNo}</dd></div>
          <div><dt>Rule</dt><dd data-testid="dry-run-rule">
            {result.ruleName ? `${result.ruleName} (${result.action})` : 'No rule fires'}
          </dd></div>
          <div><dt>Asked first</dt><dd data-testid="dry-run-next">{result.nextVendor || 'Nobody'}</dd></div>
          <div><dt>Order</dt><dd>
            <ol>{result.candidates.map((c) => <li key={c.id} data-testid="dry-run-order">{c.label}</li>)}</ol>
          </dd></div>
        </dl>
      )}
    </section>
  );
}

function AllocationLookUp() {
  const { gateway } = useSession();
  const [id, setId] = useState('');
  const [found, setFound] = useState<AllocationSummary | null>(null);
  const [error, setError] = useState('');

  async function lookUp(event: FormEvent) {
    event.preventDefault();
    setError('');
    setFound(null);
    try {
      setFound(await gateway.allocation(id.trim()));
    } catch (e) {
      setError((e as Error).message);
    }
  }

  return (
    <section data-testid="allocation-lookup">
      <h2>What decided an allocation</h2>
      <form className="inline" onSubmit={lookUp}>
        <input placeholder="Allocation id" aria-label="Allocation id" data-testid="allocation-id" value={id}
          onChange={(e) => setId(e.target.value)} required />
        <button type="submit" data-testid="allocation-go">Look up</button>
      </form>
      {error && <p className="error" role="alert">{error}</p>}
      {found && (
        <dl data-testid="allocation-result">
          <div><dt>Load</dt><dd>{found.loadNo}</dd></div>
          <div><dt>Status</dt><dd data-testid="allocation-status">{found.status}</dd></div>
          <div><dt>Rule</dt><dd data-testid="allocation-rule">
            {found.appliedRuleName ? `${found.appliedRuleName} (${found.appliedRuleAction})` : 'No rule fired'}
          </dd></div>
        </dl>
      )}
    </section>
  );
}
