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
  APPROVAL_SCOPES, APPROVAL_STATUSES, type ApprovalFilter, type ApprovalScopeName, type ApprovalStatusName,
} from '../gateway/lmsGateway';
import { useSession } from '../session';
import { useLoad } from '../useLoad';
import { RowTable } from './RowTable';

const SCOPE_LABELS: Record<ApprovalScopeName, string> = {
  MINE_TO_DECIDE: 'Mine to decide', REQUESTED_BY_ME: 'Requested by me', ALL: 'Everything',
};

/**
 * The approvals inbox: every request waiting for a person to agree -- an
 * order's cancellation, a proof of delivery's reversal -- in one list. Open
 * one to see what approving will do, the policy it is decided under and its
 * trail, then approve, reject with a reason, or (as its requester) cancel it.
 * The server decides who may: the buttons are offered to whoever it says
 * could decide now.
 */
export function ApprovalsPage() {
  const { gateway } = useSession();
  const [scope, setScope] = useState<ApprovalScopeName>('MINE_TO_DECIDE');
  const [status, setStatus] = useState<ApprovalStatusName | ''>('');
  const [subjectType, setSubjectType] = useState('');
  const [filter, setFilter] = useState<ApprovalFilter>({ scope: 'MINE_TO_DECIDE' });
  const [picked, setPicked] = useState('');
  const [reload, setReload] = useState(0);
  const list = useLoad(() => gateway.approvals(filter), `${JSON.stringify(filter)}:${reload}`);

  function apply(event: FormEvent) {
    event.preventDefault();
    setPicked('');
    setFilter({ scope, status: status || undefined, subjectType: subjectType.trim() || undefined });
  }

  return (
    <>
      <h1>Approvals</h1>
      <p className="muted">Requests waiting for somebody to agree before anything happens.</p>
      <form className="inline" onSubmit={apply} data-testid="approvals-filter">
        <select aria-label="Scope" data-testid="approvals-scope" value={scope}
          onChange={(e) => setScope(e.target.value as ApprovalScopeName)}>
          {APPROVAL_SCOPES.map((s) => <option key={s} value={s}>{SCOPE_LABELS[s]}</option>)}
        </select>
        <select aria-label="Status" data-testid="approvals-status" value={status}
          onChange={(e) => setStatus(e.target.value as ApprovalStatusName | '')}>
          <option value="">Any status</option>
          {APPROVAL_STATUSES.map((s) => <option key={s} value={s}>{s}</option>)}
        </select>
        <input placeholder="Subject type" aria-label="Subject type" data-testid="approvals-subject-type"
          value={subjectType} onChange={(e) => setSubjectType(e.target.value)} />
        <button type="submit">Filter</button>
      </form>
      {list.loading && <p className="muted">Loading…</p>}
      {list.error && <p className="error" role="alert">{list.error}</p>}
      {list.data && list.data.length === 0 && (
        <p className="muted" data-testid="no-approvals">Nothing matches.</p>
      )}
      {list.data && list.data.length > 0 && (
        <RowTable testId="approvals" rows={list.data} picked={picked} onPick={(r) => setPicked(r.id)} columns={[
          { header: 'Subject', field: 'subjectType' }, { header: 'Summary', field: 'summary' },
          { header: 'Status', field: 'status' }, { header: 'Approvals', field: 'approvals' },
          { header: 'Waiting for', field: 'waitingFor' }, { header: 'Requested', field: 'requestedAt' },
          { header: 'Due', field: 'dueAt' },
        ]} />
      )}
      {picked && <Detail key={`${picked}:${reload}`} id={picked} onChanged={() => setReload((n) => n + 1)} />}
    </>
  );
}

function Detail({ id, onChanged }: { id: string; onChanged: () => void }) {
  const { gateway } = useSession();
  const detail = useLoad(() => gateway.approval(id), id);
  const [note, setNote] = useState('');
  const [reason, setReason] = useState('');
  const [outcome, setOutcome] = useState('');
  const [error, setError] = useState('');

  async function act(what: string, call: () => Promise<void>) {
    setError('');
    try {
      await call();
      setOutcome(what);
      onChanged();
    } catch (e) {
      setError((e as Error).message);
    }
  }

  if (detail.error) {
    return <p className="error" role="alert">{detail.error}</p>;
  }
  if (!detail.data) {
    return <p className="muted">Loading…</p>;
  }
  const d = detail.data;
  const s = d.summary;
  return (
    <section data-testid="approval-detail">
      <h2>{s.summary}</h2>
      <dl className="facts">
        <div><dt>Subject</dt><dd>{s.subjectType} {s.action}</dd></div>
        <div><dt>Status</dt><dd data-testid="approval-status">{s.status}</dd></div>
        <div><dt>Approvals</dt><dd>{s.approvals}</dd></div>
        <div><dt>Requested</dt><dd>{s.requestedAt}</dd></div>
        <div><dt>Due</dt><dd>{s.dueAt}</dd></div>
        {s.reason && <div><dt>Reason</dt><dd data-testid="approval-reason">{s.reason}</dd></div>}
      </dl>
      <h3>What approving will do</h3>
      <pre data-testid="approval-payload">{d.payload}</pre>
      <p className="muted" data-testid="approval-policy">Policy: {d.policy}</p>
      <h3>Trail</h3>
      {d.trail.length === 0 ? <p className="muted" data-testid="approval-no-trail">No decision yet.</p> : (
        <RowTable testId="approval-trail" rows={d.trail} columns={[
          { header: 'Decision', field: 'decision' }, { header: 'Step', field: 'step' },
          { header: 'Under', field: 'permission' }, { header: 'By', field: 'decidedBy' },
          { header: 'At', field: 'decidedAt' }, { header: 'Note', field: 'note' },
        ]} />
      )}
      {outcome && <p data-testid="approval-outcome">{outcome}</p>}
      {error && <p className="error" role="alert">{error}</p>}
      {d.open && d.mineToDecide && (
        <>
          <form className="inline" data-testid="approve-form"
            onSubmit={(e) => { e.preventDefault(); void act('Approved', () => gateway.approveApproval(id, d.version, note.trim())); }}>
            <input placeholder="Note (optional)" aria-label="Note" data-testid="approve-note" value={note}
              onChange={(e) => setNote(e.target.value)} />
            <button type="submit" data-testid="approve">Approve</button>
          </form>
          <form className="inline" data-testid="reject-form"
            onSubmit={(e) => { e.preventDefault(); void act('Rejected', () => gateway.rejectApproval(id, d.version, reason.trim())); }}>
            <input placeholder="Reason" aria-label="Reason to reject" data-testid="reject-reason" value={reason}
              onChange={(e) => setReason(e.target.value)} required />
            <button type="submit" data-testid="reject">Reject</button>
          </form>
        </>
      )}
      {d.open && d.requestedByMe && (
        <button type="button" data-testid="cancel-approval"
          onClick={() => void act('Cancelled', () => gateway.cancelApproval(id, d.version, ''))}>
          Cancel my request
        </button>
      )}
    </section>
  );
}
