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

import { useMemo, useState, type FormEvent } from 'react';
import { ApiProblem, type Row } from '../gateway/lmsGateway';
import { APPROVAL_EXPIRIES, type ApprovalPolicyDraft, type ApprovalPolicyRow } from '../gateway/smallOpsTypes';
import { useSession } from '../session';
import { useLoad } from '../useLoad';
import { ErrorBanner } from './ErrorBanner';
import { RowTable } from './RowTable';

interface Refused {
  message: string;
  code: string;
}

const NONE: Refused = { message: '', code: '' };

const COLUMNS = [
  { header: 'Subject', field: 'subjectType' }, { header: 'Approval asked', field: 'required' },
  { header: 'Decided by', field: 'steps' }, { header: 'In order', field: 'ordered' },
  { header: 'Self-approval', field: 'self' }, { header: 'Open for (min)', field: 'due' },
  { header: 'On expiry', field: 'onExpiry' },
];

function draftOf(p: ApprovalPolicyRow): ApprovalPolicyDraft {
  return {
    required: p.required, decidePermissions: p.decidePermissions.join(', '), ordered: p.ordered,
    allowSelfApproval: p.allowSelfApproval, dueMinutes: p.dueMinutes, onExpiry: p.onExpiry,
  };
}

/**
 * The tenant's approval policies under the inbox: what each kind of request needs before it happens. Whoever may
 * manage them picks a subject, restates its policy and sets it, or resets it to the platform default.
 */
export function ApprovalPolicies() {
  const { gateway, can } = useSession();
  const [reload, setReload] = useState(0);
  const list = useLoad(() => gateway.approvalPolicies(), reload);
  const [picked, setPicked] = useState('');
  const [draft, setDraft] = useState<ApprovalPolicyDraft | null>(null);
  const [done, setDone] = useState('');
  const [error, setError] = useState<Refused>(NONE);
  const canManage = can('APPROVAL_POLICY_MANAGE');
  const rows = list.data ?? [];
  const current = rows.find((r) => r.subjectType === picked) ?? null;
  const table = useMemo<Row[]>(() => rows.map((p) => ({
    id: p.subjectType, subjectType: p.subjectType, required: p.required ? 'Yes' : 'No',
    steps: p.decidePermissions.join(' → '), ordered: p.ordered ? 'Yes' : 'No', self: p.allowSelfApproval ? 'Yes' : 'No',
    due: p.dueMinutes || '—', onExpiry: p.onExpiry,
  })), [rows]);

  function pick(subjectType: string) {
    setPicked(subjectType);
    setDone('');
    const p = rows.find((r) => r.subjectType === subjectType);
    setDraft(p ? draftOf(p) : null);
  }

  const change = <K extends keyof ApprovalPolicyDraft>(key: K, value: ApprovalPolicyDraft[K]) =>
    setDraft((d) => (d ? { ...d, [key]: value } : d));

  async function run(what: string, call: () => Promise<ApprovalPolicyRow>) {
    setError(NONE);
    setDone('');
    try {
      const policy = await call();
      setDraft(draftOf(policy));
      setReload((n) => n + 1);
      setDone(what);
    } catch (e) {
      setError({ message: (e as Error).message, code: e instanceof ApiProblem ? e.code : '' });
    }
  }

  function set(event: FormEvent, p: ApprovalPolicyRow) {
    event.preventDefault();
    if (draft) {
      void run(`Policy for ${p.subjectType} set.`, () => gateway.setApprovalPolicy(p.subjectType, draft));
    }
  }

  return (
    <section data-testid="approval-policies">
      <h2>Approval policies</h2>
      <p className="muted">What each kind of request needs before it goes ahead, for the whole organisation.</p>
      {list.error && <ErrorBanner message={list.error} testId="apol-error" />}
      {error.message && <ErrorBanner message={error.message} code={error.code} testId="apol-error" />}
      {done && <p className="ok" role="status" data-testid="apol-done">{done}</p>}
      {rows.length > 0 && (
        <RowTable testId="apol" rows={table} columns={COLUMNS} picked={picked}
          onPick={canManage ? (r) => pick(r.id) : undefined} />
      )}
      {current && draft && canManage && (
        <form data-testid="apol-form" onSubmit={(ev) => set(ev, current)}>
          <h3>{current.subjectType}</h3>
          <label>
            <input type="checkbox" data-testid="apol-required" checked={draft.required} disabled={current.mandatory}
              onChange={(e) => change('required', e.target.checked)} />
            Ask for approval{current.mandatory ? ' (always: this cannot be switched off)' : ''}
          </label>
          <input placeholder="Permissions that decide, in order" aria-label="Deciding permissions" data-testid="apol-steps"
            value={draft.decidePermissions} onChange={(e) => change('decidePermissions', e.target.value)} />
          <label>
            <input type="checkbox" data-testid="apol-ordered" checked={draft.ordered}
              onChange={(e) => change('ordered', e.target.checked)} />
            Decide in that order
          </label>
          <label>
            <input type="checkbox" data-testid="apol-self" checked={draft.allowSelfApproval}
              onChange={(e) => change('allowSelfApproval', e.target.checked)} />
            The requester may approve their own request
          </label>
          <input type="number" min="1" placeholder="Minutes a request stays open" aria-label="Minutes open"
            data-testid="apol-due" value={draft.dueMinutes} onChange={(e) => change('dueMinutes', e.target.value)} />
          <select aria-label="When it lapses" data-testid="apol-expiry" value={draft.onExpiry}
            onChange={(e) => change('onExpiry', e.target.value as ApprovalPolicyDraft['onExpiry'])}>
            {APPROVAL_EXPIRIES.map((x) => (
              <option key={x} value={x}>{x === 'EXPIRE' ? 'Lapses unapproved' : 'Approved automatically'}</option>
            ))}
          </select>
          <button type="submit" data-testid="apol-set">Set policy</button>
          <button type="button" className="link" data-testid="apol-reset"
            onClick={() => void run(`Policy for ${current.subjectType} reset to the default.`, () => gateway.resetApprovalPolicy(current.subjectType))}>
            Reset to default
          </button>
        </form>
      )}
    </section>
  );
}
