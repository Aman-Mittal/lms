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
import { formatMoney, formatQuantity } from '../../../../shared/format';

/**
 * An amount as money: Indian grouping, the currency symbol, two decimals. The raw value stays on the element as
 * data-value (a data-testid set on the tag is kept); put the "num" class on the cell around it to right-align the column.
 * `empty` is what to show when there is no value.
 */
@Component({
  selector: 'app-money',
  template: `{{ text() }}`,
  host: { '[attr.data-value]': 'raw()' },
})
export class Money {
  readonly value = input<number | string | null | undefined>(null);
  readonly currency = input<string | null | undefined>('INR');
  readonly empty = input('');
  protected readonly raw = computed(() => (this.value() ?? '') + '');
  protected readonly text = computed(() => formatMoney(this.value(), this.currency()) || this.empty());
}

/** A count or measure with Indian grouping and its unit ("2,000 kg"); the raw value stays on the element as data-value. */
@Component({
  selector: 'app-quantity',
  template: `{{ text() }}`,
  host: { '[attr.data-value]': 'raw()' },
})
export class Quantity {
  readonly value = input<number | string | null | undefined>(null);
  readonly unit = input('');
  readonly empty = input('');
  protected readonly raw = computed(() => (this.value() ?? '') + '');
  protected readonly text = computed(() => formatQuantity(this.value(), this.unit()) || this.empty());
}
