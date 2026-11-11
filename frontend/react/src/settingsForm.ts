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

/*
 * Turning a setting's value into what a form field shows, and what a person
 * typed back into a value, by the setting's type. The API checks the value
 * again and answers with its own refusal; this only shapes it.
 */

export interface TypedDefinition {
  type: string;
  allowedValues: string[];
}

/** The value as a form field holds it. */
export function toInput(value: unknown): string {
  if (value === null || value === undefined) {
    return '';
  }
  return typeof value === 'object' ? JSON.stringify(value) : String(value);
}

/** What was typed, as the value to send; throws with a reason when it cannot be one. */
export function fromInput(definition: TypedDefinition, typed: string): string | number | boolean | Record<string, unknown> {
  const text = typed.trim();
  switch (definition.type) {
    case 'BOOLEAN':
      if (text === 'true' || text === 'false') {
        return text === 'true';
      }
      throw new Error('Choose true or false');
    case 'INTEGER':
    case 'DECIMAL': {
      const n = Number(text);
      if (text === '' || !Number.isFinite(n)) {
        throw new Error('Enter a number');
      }
      return n;
    }
    case 'JSON':
      try {
        return JSON.parse(text) as Record<string, unknown>;
      } catch {
        throw new Error('Enter a JSON object');
      }
    default:
      if (text === '') {
        throw new Error('Enter a value');
      }
      return text;
  }
}

/** The kind of form control a setting takes. */
export function controlOf(definition: TypedDefinition): 'select' | 'number' | 'text' {
  if (definition.type === 'BOOLEAN' || definition.type === 'ENUM') {
    return 'select';
  }
  return definition.type === 'INTEGER' || definition.type === 'DECIMAL' ? 'number' : 'text';
}

/** The choices a select offers. */
export function choicesOf(definition: TypedDefinition): string[] {
  return definition.type === 'BOOLEAN' ? ['true', 'false'] : definition.allowedValues;
}

/** Today as `YYYY-MM-DD`, for a new version's first day. */
export function todayIso(): string {
  return new Date().toISOString().slice(0, 10);
}
