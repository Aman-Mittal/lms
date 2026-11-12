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

import { work, type LonLat, type WorkRequest, type WorkResult } from './display-math';

/*
 * The worker's caller. One worker is created on first use and kept; a call
 * where workers are unavailable (or the worker fails) runs the same code inline,
 * so a screen never depends on the worker to draw.
 */
let worker: Worker | null = null;
let failed = false;
let next = 0;
const waiting = new Map<number, (result: WorkResult) => void>();

function start(): Worker | null {
  if (failed || typeof Worker === 'undefined') {
    return null;
  }
  if (!worker) {
    try {
      worker = new Worker(new URL('./workers/display.worker.ts', import.meta.url), { type: 'module' });
      worker.onmessage = ({ data }: MessageEvent<WorkResult>) => {
        waiting.get(data.id)?.(data);
        waiting.delete(data.id);
      };
      worker.onerror = () => {
        failed = true;
        worker = null;
        waiting.clear();
      };
    } catch {
      failed = true;
      worker = null;
    }
  }
  return worker;
}

type Without<T, K extends string> = T extends unknown ? Omit<T, K> : never;

function run(request: Without<WorkRequest, 'id'>): Promise<WorkResult> {
  const message = { ...request, id: ++next } as WorkRequest;
  const w = start();
  if (!w) {
    return Promise.resolve(work(message));
  }
  return new Promise((resolve) => {
    const timer = setTimeout(() => {
      waiting.delete(message.id);
      resolve(work(message));
    }, 5000);
    waiting.set(message.id, (result) => {
      clearTimeout(timer);
      resolve(result);
    });
    w.postMessage(message);
  });
}

/** Tracks longer than this are simplified before drawing; shorter ones are drawn as they are. */
export const SIMPLIFY_ABOVE = 1500;
/** About 3 m: below what a screen shows at any zoom a trip is viewed at. */
export const TRACK_TOLERANCE_DEG = 0.00003;

/** The track, thinned by Douglas-Peucker off the main thread when it is long. */
export async function simplifyTrack(points: LonLat[]): Promise<LonLat[]> {
  if (points.length <= SIMPLIFY_ABOVE) {
    return points;
  }
  const result = await run({ op: 'simplify', points, tolerance: TRACK_TOLERANCE_DEG });
  return result.op === 'simplify' ? result.points : points;
}

/** Counts of rows by the value of a field, worked out off the main thread. */
export async function tallyBy(rows: Array<Record<string, string>>, field: string): Promise<Array<[string, number]>> {
  const result = await run({ op: 'tally', rows, field });
  return result.op === 'tally' ? result.counts : [];
}
