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

import { columnKind, formatIfDateText } from '../../../shared/format';
import { Money, Quantity } from './Figures';
import { StatusBadge } from './StatusBadge';

/** One cell of a generic record table: a status as a badge, money and weights formatted, anything else as the text it is. */
export function Cell({ field, value }: { field: string; value: string | undefined }) {
  const text = value ?? '';
  switch (columnKind(field, text)) {
    case 'status': return <StatusBadge value={text} />;
    case 'money': return <Money value={text} />;
    case 'weight': return <Quantity value={text} unit="kg" />;
    case 'quantity': return <Quantity value={text} />;
    default: return <>{formatIfDateText(text) ?? text}</>;
  }
}
