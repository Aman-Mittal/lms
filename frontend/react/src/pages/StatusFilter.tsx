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

interface Props {
  /** '' is the server's default, the active records. */
  value: string;
  onChange: (value: string) => void;
  /** `status-filter` unless a screen holds two lists. */
  testId?: string;
}

/** The status filter of a switch-on-and-off list: the active records by default, the inactive ones, or every one. */
export function StatusFilter({ value, onChange, testId = 'status-filter' }: Props) {
  return (
    <label className="filter">Status
      <select data-testid={testId} name="status" value={value} onChange={(e) => onChange(e.target.value)}>
        <option value="">Active</option>
        <option value="INACTIVE">Inactive</option>
        <option value="ALL">All</option>
      </select>
    </label>
  );
}
