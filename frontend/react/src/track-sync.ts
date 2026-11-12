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

import { TRACK_TTL_MS, cacheGet, cachePut, clientCacheEnabled } from './client-cache';
import type { LonLat } from './display-math';

/** The server returns at most this many points a call (`TelematicsQueryService.TRACK_PAGE`). */
const PAGE = 5000;
/** A safety stop: 20 pages is 100 000 points, far beyond a real trip. */
const MAX_ROUNDS = 20;

interface HeldTrack {
  points: LonLat[];
  /** The cursor of the last point held: what the next read asks after. */
  seq: number;
}

/**
 * A trip's track, from the browser's copy plus whatever the server has recorded
 * since. The server is asked only for points after the last one held
 * (`afterSeq`); a reply of `[lon, lat, seq]` triples is appended, and a reply of
 * plain pairs means retention has collapsed the raw points, so the copy is
 * replaced by that polyline. With the cache off this is the plain read.
 */
export async function syncTrack(
  tripId: string,
  readAfter: (afterSeq: number) => Promise<number[][]>,
  readAll: () => Promise<number[][]>,
): Promise<LonLat[]> {
  if (!clientCacheEnabled()) {
    return (await readAll()).map(([lon, lat]): LonLat => [lon, lat]);
  }
  const key = `track:${tripId}`;
  const held = await cacheGet<HeldTrack>(key);
  let points: LonLat[] = held ? [...held.value.points] : [];
  let seq = held ? held.value.seq : 0;
  for (let round = 0; round < MAX_ROUNDS; round++) {
    const page = await readAfter(seq);
    if (page.length === 0) {
      break;
    }
    if (page[0].length < 3) {
      points = page.map(([lon, lat]): LonLat => [lon, lat]);
      seq = 0;
      break;
    }
    for (const [lon, lat] of page) {
      points.push([lon, lat]);
    }
    seq = page[page.length - 1][2];
    if (page.length < PAGE) {
      break;
    }
  }
  await cachePut<HeldTrack>(key, { points, seq }, TRACK_TTL_MS);
  return points;
}
