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

import { formatMoney, formatQuantity } from '../../../shared/format';

type Figure = number | string | null | undefined;

/**
 * An amount as money: Indian grouping, the currency symbol, two decimals. The raw value stays on the element as
 * data-value (`data-testid` is passed through); put the "num" class on the cell around it to right-align the column.
 * `empty` is what to show when there is no value.
 */
export function Money({ value, currency, empty = '', ...rest }: {
  value: Figure; currency?: string | null; empty?: string; 'data-testid'?: string;
}) {
  return <span {...rest} data-value={value ?? ''}>{formatMoney(value, currency ?? 'INR') || empty}</span>;
}

/** A count or measure with Indian grouping and its unit ("2,000 kg"); the raw value stays on the element as data-value. */
export function Quantity({ value, unit, empty = '', ...rest }: {
  value: Figure; unit?: string; empty?: string; 'data-testid'?: string;
}) {
  return <span {...rest} data-value={value ?? ''}>{formatQuantity(value, unit) || empty}</span>;
}
