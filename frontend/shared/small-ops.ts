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
 * Text-to-request parsing for the small API-only operations, shared by both consoles so a typed value means the same
 * thing in each: permission codes for an approval policy and the points of a route corridor.
 */

/** Permission codes typed as a list, separated by commas, spaces or new lines; blanks and repeats dropped, upper-cased. */
export function permissionCodes(typed: string): string[] {
  const seen = new Set<string>();
  for (const part of typed.split(/[\s,]+/)) {
    const code = part.trim().toUpperCase();
    if (code) {
      seen.add(code);
    }
  }
  return [...seen];
}

/**
 * The points of a corridor, one `longitude, latitude` pair per line, in the order travelled. A line that is not two
 * numbers in range, or fewer than two points, is refused with a message naming the line.
 */
export function corridorPoints(typed: string): number[][] {
  const points: number[][] = [];
  const lines = typed.split(/\r?\n/);
  lines.forEach((line, index) => {
    const trimmed = line.trim();
    if (!trimmed) {
      return;
    }
    const parts = trimmed.split(/[\s,;]+/);
    const lon = Number(parts[0]);
    const lat = Number(parts[1]);
    if (parts.length !== 2 || !Number.isFinite(lon) || !Number.isFinite(lat) || Math.abs(lon) > 180 || Math.abs(lat) > 90) {
      throw new Error(`Line ${index + 1} is not a longitude and a latitude: "${trimmed}".`);
    }
    points.push([lon, lat]);
  });
  if (points.length < 2) {
    throw new Error('A corridor needs at least two points, one longitude and latitude pair per line.');
  }
  return points;
}
