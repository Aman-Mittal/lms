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

import { useEffect, useRef, useState } from 'react';
import type { MasterKind, MasterSwitch } from '../gateway/lmsGateway';
import { useSession } from '../session';
import { CONFLICT_TEXT } from './MasterDataForm';
import { EDIT_PERMISSION, KIND_LABEL } from './masterDataFields';
import { ApiProblem } from '../gateway/lmsGateway';
import { ErrorBanner } from './ErrorBanner';

interface Props {
  kind: MasterKind;
  id: string;
  /** What the record is called, for the confirmation. */
  name: string;
  status: string;
  version: number;
  onEdit: () => void;
  /** Called after a switch went through, so the list and the record can be read again. */
  onSwitched: () => void;
}

const VERB: Record<MasterSwitch, string> = { deactivate: 'Deactivate', reactivate: 'Reactivate', blacklist: 'Blacklist' };

/** The panel's Edit, Deactivate, Reactivate and Blacklist buttons, and the confirmation each asks for. */
export function MasterDataActions({ kind, id, name, status, version, onEdit, onSwitched }: Props) {
  const { can, gateway } = useSession();
  const [pending, setPending] = useState<MasterSwitch | null>(null);
  const [reason, setReason] = useState('');
  const [reasonError, setReasonError] = useState('');
  const [refusal, setRefusal] = useState('');
  const [busy, setBusy] = useState(false);
  const box = useRef<HTMLTextAreaElement>(null);

  useEffect(() => { if (pending) { box.current?.focus(); } }, [pending]);

  const canEdit = can(EDIT_PERMISSION[kind]);
  const canOff = kind === 'terminals' ? can('TERMINAL_DELETE') : canEdit;
  const partner = kind === 'partners';
  const offAction = partner ? status === 'ACTIVE' : status !== 'INACTIVE';
  const onAction = partner ? status === 'SUSPENDED' : status === 'INACTIVE';
  const blacklist = partner && status !== 'BLACKLISTED';

  function ask(action: MasterSwitch) {
    setPending(action);
    setReason('');
    setReasonError('');
    setRefusal('');
  }

  async function confirm() {
    if (!pending) { return; }
    if (!reason.trim()) {
      setReasonError('A reason is required.');
      return;
    }
    setReasonError('');
    setRefusal('');
    setBusy(true);
    try {
      await gateway.switchMasterRecord(kind, id, pending, reason.trim(), version);
      setPending(null);
      onSwitched();
    } catch (e) {
      setRefusal(e instanceof ApiProblem && e.code === 'concurrent-modification' ? CONFLICT_TEXT : (e as Error).message);
      if (e instanceof ApiProblem && e.code === 'concurrent-modification') { onSwitched(); }
    } finally {
      setBusy(false);
    }
  }

  if (!canEdit && !canOff) { return null; }
  const thing = KIND_LABEL[kind];

  return (
    <>
      {!pending && (
        <div className="actions">
          {canEdit && <button type="button" data-testid="edit-record" onClick={onEdit}>Edit</button>}
          {canOff && offAction && <button type="button" data-testid="deactivate-record" onClick={() => ask('deactivate')}>Deactivate</button>}
          {canOff && onAction && <button type="button" data-testid="reactivate-record" onClick={() => ask('reactivate')}>Reactivate</button>}
          {canOff && blacklist && <button type="button" data-testid="blacklist-record" onClick={() => ask('blacklist')}>Blacklist</button>}
        </div>
      )}
      {pending && (
        <section className="confirm" role="group" aria-label={`${VERB[pending]} ${thing}`} data-testid="confirm-panel">
          <p><strong>{VERB[pending]} {name}?</strong>{' '}
            {pending === 'blacklist' ? 'A blacklisted partner cannot be reinstated.'
              : pending === 'reactivate' ? `The ${thing} will be available again.`
                : `The ${thing} is kept, but can no longer be used until it is reactivated.`}</p>
          <label htmlFor="md-reason">Reason</label>
          <textarea id="md-reason" ref={box} rows={2} data-testid="reason-input" value={reason}
            aria-invalid={reasonError ? true : undefined} aria-describedby={reasonError ? 'md-reason-error' : undefined}
            onChange={(e) => setReason(e.target.value)} />
          {reasonError && <small id="md-reason-error" className="error" role="alert" data-testid="reason-error">{reasonError}</small>}
          {refusal && <ErrorBanner message={refusal} testId="action-error" />}
          <div className="actions">
            <button type="button" data-testid="confirm-action" disabled={busy} onClick={confirm}>{VERB[pending]}</button>
            <button type="button" className="link" data-testid="cancel-action" onClick={() => setPending(null)}>Cancel</button>
          </div>
        </section>
      )}
    </>
  );
}
