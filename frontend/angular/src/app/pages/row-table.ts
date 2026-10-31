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

import { Component, input, output } from '@angular/core';
import { Row } from '../gateway/lms-gateway';

export interface RowColumn {
  header: string;
  field: string;
}

/** A plain table of display rows; with `pickable`, each row's first cell is a button that selects it. */
@Component({
  selector: 'app-row-table',
  template: `
    <table [attr.data-testid]="testId()">
      <thead>
        <tr>@for (c of columns(); track c.field) { <th>{{ c.header }}</th> }</tr>
      </thead>
      <tbody>
        @for (row of rows(); track row['id']) {
          <tr [attr.data-testid]="testId() + '-row'" [attr.data-id]="row['id']" [class.picked]="picked() === row['id']">
            @for (c of columns(); track c.field; let first = $first) {
              <td>
                @if (first && pickable()) {
                  <button type="button" class="link" (click)="pick.emit(row)">{{ row[c.field] }}</button>
                } @else {
                  {{ row[c.field] }}
                }
              </td>
            }
          </tr>
        }
      </tbody>
    </table>
  `,
})
export class RowTable {
  readonly rows = input.required<Row[]>();
  readonly columns = input.required<RowColumn[]>();
  readonly testId = input.required<string>();
  readonly pickable = input(false);
  readonly picked = input('');
  readonly pick = output<Row>();
}
