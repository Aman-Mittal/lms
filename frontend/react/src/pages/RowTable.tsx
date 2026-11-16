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

import { isDateColumn, isNumericColumn } from '../../../shared/format';
import type { Row } from '../gateway/lmsGateway';
import { Cell } from './Cell';

export interface RowColumn {
  header: string;
  field: string;
}

/** The classes of a column's cells: numbers right-aligned, dates on one line. */
function cellClass(field: string): string | undefined {
  return [isNumericColumn(field) ? 'num' : '', isDateColumn(field) ? 'nowrap' : ''].filter(Boolean).join(' ') || undefined;
}

/** A plain table of display rows; `onPick` makes each row a button that selects it. */
export function RowTable({ rows, columns, testId, onPick, picked }: {
  rows: Row[];
  columns: RowColumn[];
  testId: string;
  onPick?: (row: Row) => void;
  picked?: string;
}) {
  return (
    <div className="table-wrap">
    <table data-testid={testId}>
      <thead>
        <tr>{columns.map((c) => <th key={c.field} className={isNumericColumn(c.field) ? 'num' : undefined}>{c.header}</th>)}</tr>
      </thead>
      <tbody>
        {rows.map((row) => (
          <tr key={row.id} data-testid={`${testId}-row`} data-id={row.id}
            className={picked === row.id ? 'picked' : undefined}>
            {columns.map((c, i) => (
              <td key={c.field} className={cellClass(c.field)}>
                {i === 0 && onPick
                  ? <button type="button" className="link" onClick={() => onPick(row)}>{row[c.field]}</button>
                  : <Cell field={c.field} value={row[c.field]} />}
              </td>
            ))}
          </tr>
        ))}
        {rows.length === 0 && (
          <tr className="empty-row"><td className="table-state" colSpan={columns.length}>Nothing here yet.</td></tr>
        )}
      </tbody>
    </table>
    </div>
  );
}
