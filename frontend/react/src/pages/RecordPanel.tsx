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

import { useEffect, useState } from 'react';
import { useSession } from '../session';
import type { MasterKind } from '../gateway/lmsGateway';
import { MasterDataActions } from './MasterDataActions';
import { MasterDataForm } from './MasterDataForm';
import { CustomFieldsForm } from './CustomFieldsForm';
import type { Resource } from './resources';
import { Cell } from './Cell';

interface Props {
  resource: Resource;
  row: Record<string, string>;
  onClose: () => void;
  /** Called after the record was edited or switched, so the list can be read again. */
  onChanged?: () => void;
}

const MASTER_KINDS = ['partners', 'vehicles', 'drivers', 'terminals'];

/** One record's details, with its custom fields beneath them. */
export function RecordPanel({ resource, row, onClose, onChanged }: Props) {
  const { can, gateway, auth } = useSession();
  const [first, ...rest] = resource.columns;
  const kind = MASTER_KINDS.includes(resource.key) ? (resource.key as MasterKind) : null;
  const [editing, setEditing] = useState(false);
  const [fresh, setFresh] = useState<{ version: number; status: string } | null>(null);
  const [again, setAgain] = useState(0);

  // The record's own version and status, read again after every change, for If-Match and for which buttons apply.
  useEffect(() => {
    if (!kind) { return; }
    let ignore = false;
    gateway.masterRecord(kind, row.id)
      .then((r) => { if (!ignore) { setFresh({ version: r.version, status: String(r.values.status ?? '') }); } })
      .catch(() => { if (!ignore) { setFresh(null); } });
    return () => { ignore = true; };
  }, [kind, row.id, again, gateway]);

  if (kind && editing && auth) {
    return (
      <MasterDataForm kind={kind} recordId={row.id} orgUnitId={auth.orgUnitId ?? ''}
        onCancel={() => setEditing(false)}
        onSaved={() => { setEditing(false); setAgain((n) => n + 1); onChanged?.(); }} />
    );
  }

  return (
    <aside className="panel" data-testid="record-panel" aria-label="Record details">
      <header>
        <h2>{row[first.field]}</h2>
        <button className="link" onClick={onClose} aria-label="Close panel">Close</button>
      </header>
      <dl>
        {rest.map((c) => (
          <div key={c.field}>
            <dt>{c.header}</dt>
            <dd>{row[c.field] ? <Cell field={c.field} value={row[c.field]} /> : '—'}</dd>
          </div>
        ))}
      </dl>
      {kind && fresh && (
        <MasterDataActions kind={kind} id={row.id} name={row[first.field]} status={fresh.status} version={fresh.version}
          onEdit={() => setEditing(true)} onSwitched={() => { setAgain((n) => n + 1); onChanged?.(); }} />
      )}
      {resource.customKind && can('CUSTOM_FIELD_READ') && (
        <CustomFieldsForm kind={resource.customKind} recordId={row.id} />
      )}
    </aside>
  );
}
