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

import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { expect, test } from '@playwright/test';
import { TESTIDS } from './support-masterdata';

/*
 * The testid table: every data-testid master-data-crud.spec.ts uses must be in both consoles' sources, so a
 * console that drops one fails here, naming it, instead of timing out inside the browser spec. Runs without a
 * browser or a server. A field's id is read as written (`field-code`), and an error's as its prefix and the
 * field it belongs to, because the consoles build it from the field's name.
 */

const roots = {
  angular: join(__dirname, '../../angular/src/app'),
  react: join(__dirname, '../../react/src'),
} as const;

function sources(dir: string): string {
  let text = '';
  for (const name of readdirSync(dir)) {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) {
      if (name !== 'api' && name !== 'node_modules') {
        text += sources(path);
      }
    } else if (/\.(ts|tsx)$/.test(name) && !name.includes('generated')) {
      text += readFileSync(path, 'utf8');
    }
  }
  return text;
}

const present = (source: string, id: string) => {
  if (id.startsWith('error-')) {
    const field = id.slice('error-'.length);
    return /error-/.test(source) && new RegExp(`['"\`]${field}['"\`]`).test(source);
  }
  return source.includes(`'${id}'`) || source.includes(`"${id}"`) || source.includes(`\`${id}\``);
};

for (const [console, root] of Object.entries(roots)) {
  test(`${console} console carries every master-data testid`, () => {
    const source = sources(root);
    const all = Object.values(TESTIDS).flat();
    const missing = all.filter((id) => !present(source, id));
    expect(missing, `testids missing from the ${console} console`).toEqual([]);
  });
}

test('the table of testids, one row per id and console', () => {
  const rows: string[] = [];
  const angular = sources(roots.angular);
  const react = sources(roots.react);
  for (const [group, ids] of Object.entries(TESTIDS)) {
    for (const id of ids) {
      rows.push(`${group.padEnd(8)} ${id.padEnd(26)} angular=${present(angular, id)} react=${present(react, id)}`);
    }
  }
  console.log(rows.join('\n'));
  expect(rows.filter((r) => r.includes('false'))).toEqual([]);
});
