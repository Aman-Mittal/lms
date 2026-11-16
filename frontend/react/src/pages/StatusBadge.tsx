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

import { labelFor, toneFor } from '../../../shared/format';

/**
 * A status as a coloured pill: the label says it in words, the tone colours it. The raw value stays on the element as
 * data-value, so tests and scripts read the value, not the wording; `data-testid` is passed through.
 */
export function StatusBadge({ value, ...rest }: { value: string | null | undefined; 'data-testid'?: string }) {
  const raw = value ?? '';
  return (
    <span {...rest} className={raw ? `badge badge--${toneFor(raw)}` : undefined} data-value={raw}>
      {labelFor(raw)}
    </span>
  );
}
