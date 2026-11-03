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

import { useSession } from '../session';
import { CustomFieldsForm } from './CustomFieldsForm';
import type { Resource } from './resources';

interface Props {
  resource: Resource;
  row: Record<string, string>;
  onClose: () => void;
}

/** One record's details, with its custom fields beneath them. */
export function RecordPanel({ resource, row, onClose }: Props) {
  const { can } = useSession();
  const [first, ...rest] = resource.columns;

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
            <dd>{row[c.field] || '—'}</dd>
          </div>
        ))}
      </dl>
      {resource.customKind && can('CUSTOM_FIELD_READ') && (
        <CustomFieldsForm kind={resource.customKind} recordId={row.id} />
      )}
    </aside>
  );
}
