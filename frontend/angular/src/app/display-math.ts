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
 * Pure calculations the consoles run over rows they already hold, kept apart
 * from any component so a Web Worker can run them off the main thread and the
 * same code can run inline where a worker is unavailable.
 */

export type LonLat = [number, number];

/**
 * Douglas-Peucker: the fewest points that keep the line within `tolerance`
 * (in degrees) of the original. Iterative, so a track of hundreds of thousands
 * of points cannot overflow the call stack.
 */
export function simplify(points: LonLat[], tolerance: number): LonLat[] {
  const n = points.length;
  if (n <= 2 || tolerance <= 0) {
    return points;
  }
  const keep = new Uint8Array(n);
  keep[0] = 1;
  keep[n - 1] = 1;
  const stack: Array<[number, number]> = [[0, n - 1]];
  const squared = tolerance * tolerance;
  while (stack.length > 0) {
    const [first, last] = stack.pop()!;
    let worst = -1;
    let at = -1;
    for (let i = first + 1; i < last; i++) {
      const d = distanceSquared(points[i], points[first], points[last]);
      if (d > worst) {
        worst = d;
        at = i;
      }
    }
    if (worst > squared && at > 0) {
      keep[at] = 1;
      stack.push([first, at], [at, last]);
    }
  }
  const out: LonLat[] = [];
  for (let i = 0; i < n; i++) {
    if (keep[i]) {
      out.push(points[i]);
    }
  }
  return out;
}

/** Squared distance from p to the segment a-b, in squared degrees. */
function distanceSquared(p: LonLat, a: LonLat, b: LonLat): number {
  let x = a[0];
  let y = a[1];
  const dx = b[0] - x;
  const dy = b[1] - y;
  if (dx !== 0 || dy !== 0) {
    const t = ((p[0] - x) * dx + (p[1] - y) * dy) / (dx * dx + dy * dy);
    if (t > 1) {
      x = b[0];
      y = b[1];
    } else if (t > 0) {
      x += dx * t;
      y += dy * t;
    }
  }
  return (p[0] - x) ** 2 + (p[1] - y) ** 2;
}

/** How many rows have each value of a field, in first-seen order. */
export function tally(rows: Array<Record<string, string>>, field: string): Array<[string, number]> {
  const counts = new Map<string, number>();
  for (const row of rows) {
    const value = row[field] || '(none)';
    counts.set(value, (counts.get(value) ?? 0) + 1);
  }
  return [...counts.entries()];
}

/** The messages a display worker answers. */
export type WorkRequest =
  | { id: number; op: 'simplify'; points: LonLat[]; tolerance: number }
  | { id: number; op: 'tally'; rows: Array<Record<string, string>>; field: string };

export type WorkResult =
  | { id: number; op: 'simplify'; points: LonLat[] }
  | { id: number; op: 'tally'; counts: Array<[string, number]> };

export function work(request: WorkRequest): WorkResult {
  return request.op === 'simplify'
    ? { id: request.id, op: 'simplify', points: simplify(request.points, request.tolerance) }
    : { id: request.id, op: 'tally', counts: tally(request.rows, request.field) };
}
