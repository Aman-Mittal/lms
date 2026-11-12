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

import { ApiProblem } from '../gateway/lmsGateway';

/** A refusal as a person reads it: the server's message, led by its problem code when it has one. */
export function problemText(e: unknown): string {
  if (e instanceof ApiProblem && e.code) {
    return `${e.code}: ${e.message}`;
  }
  return (e as Error).message;
}

/** Money as the desks show it: two decimals. */
export function money(value: number | undefined): string {
  return value === undefined || value === null ? '' : value.toFixed(2);
}

/** Today as `yyyy-mm-dd`, for a date input's starting value. */
export function today(): string {
  return new Date().toISOString().substring(0, 10);
}
