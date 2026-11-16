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
import { columnKind } from '../../../../shared/format';
import { Money, Quantity } from './figures';
import { When } from './when';
import { StatusBadge } from './status-badge';

/** One cell of a generic record table: a status as a badge, money and weights formatted, anything else as the text it is. */
@Component({
  selector: 'app-cell',
  imports: [StatusBadge, Money, Quantity, When],
  template: `
    @switch (kind()) {
      @case ('status') { <app-status-badge [value]="value()" /> }
      @case ('money') { <app-money [value]="value()" /> }
      @case ('weight') { <app-quantity [value]="value()" unit="kg" /> }
      @case ('quantity') { <app-quantity [value]="value()" /> }
      @default { {{ value() | when }} }
    }
  `,
})
export class Cell {
  readonly field = input.required<string>();
  readonly value = input<string | undefined>('');
  protected readonly kind = computed(() => columnKind(this.field(), this.value() ?? ''));
}
