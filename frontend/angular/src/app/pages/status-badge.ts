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

import { Component, computed, input } from '@angular/core';
import { labelFor, toneFor } from '../../../../shared/format';

/**
 * A status as a coloured pill: the label says it in words, the tone colours it. The raw value stays on the element as
 * data-value, so tests and scripts read the value, not the wording; a data-testid set on the tag is kept on the pill.
 */
@Component({
  selector: 'app-status-badge',
  template: `{{ text() }}`,
  host: { '[class]': 'classes()', '[attr.data-value]': 'raw()' },
})
export class StatusBadge {
  readonly value = input<string | null | undefined>('');
  protected readonly raw = computed(() => this.value() ?? '');
  protected readonly text = computed(() => labelFor(this.raw()));
  protected readonly classes = computed(() => (this.raw() ? `badge badge--${toneFor(this.raw())}` : ''));
}
