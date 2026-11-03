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
 * Planning a split, in the browser: equal parts that each fit one vehicle, the
 * last taking what is left over, so the parts always add up to the whole.
 *
 * Quantities are handled as whole thousandths -- grams and litres -- because
 * that is the precision the server keeps, and adding up decimals in floating
 * point would make 0.1 + 0.2 a part that does not match.
 */

/** The fewest and most parts one split makes, as the server enforces. */
export const MIN_PARTS = 2;
export const MAX_PARTS = 500;

/** A non-negative quantity with up to three decimals, as whole thousandths; null if it is not one. */
export function thousandths(value: string): number | null {
  const match = /^(\d+)(?:\.(\d{1,3}))?$/.exec(value.trim());
  if (!match) {
    return null;
  }
  return Number(match[1]) * 1000 + Number((match[2] ?? '').padEnd(3, '0'));
}

/** Whole thousandths back to the shortest decimal: 20000000 is "20000", 1500 is "1.5". */
export function fromThousandths(value: number): string {
  const whole = Math.floor(value / 1000);
  const fraction = value % 1000;
  return fraction === 0 ? String(whole) : `${whole}.${String(fraction).padStart(3, '0').replace(/0+$/, '')}`;
}

export interface PlannedPart {
  deadWeightKg: string;
  volumeM3: string;
}

/**
 * Equal parts of at most `capacityKg` each. Throws with a sentence a planner
 * can act on when there is nothing to split or too much for one split.
 */
export function splitByCapacity(totalKg: string, totalM3: string, capacityKg: string): PlannedPart[] {
  const weight = thousandths(totalKg);
  const volume = thousandths(totalM3 || '0');
  const capacity = thousandths(capacityKg);
  if (weight === null || volume === null || weight === 0) {
    throw new Error('The consignment has no weight to split.');
  }
  if (capacity === null || capacity === 0) {
    throw new Error('Give the vehicle capacity in kilograms, above zero.');
  }
  const count = Math.ceil(weight / capacity);
  if (count < MIN_PARTS) {
    throw new Error('It already fits on one vehicle of that capacity.');
  }
  if (count > MAX_PARTS) {
    throw new Error(`That would be ${count} parts; one split makes at most ${MAX_PARTS}. Use a larger vehicle.`);
  }
  const weightEach = Math.floor(weight / count);
  const volumeEach = Math.floor(volume / count);
  return Array.from({ length: count }, (_, i) => {
    const last = i === count - 1;
    return {
      deadWeightKg: fromThousandths(last ? weight - weightEach * (count - 1) : weightEach),
      volumeM3: fromThousandths(last ? volume - volumeEach * (count - 1) : volumeEach),
    };
  });
}

/**
 * How the parts compare with the whole: their totals, and whether they match
 * exactly. A part that is not a number makes the whole plan unready.
 */
export function compareWithWhole(parts: PlannedPart[], totalKg: string, totalM3: string) {
  const weights = parts.map((p) => thousandths(p.deadWeightKg));
  const volumes = parts.map((p) => thousandths(p.volumeM3 || '0'));
  const valid = weights.every((w) => w !== null && w > 0) && volumes.every((v) => v !== null);
  const weight = weights.reduce<number>((sum, w) => sum + (w ?? 0), 0);
  const volume = volumes.reduce<number>((sum, v) => sum + (v ?? 0), 0);
  const ready = valid && parts.length >= MIN_PARTS && parts.length <= MAX_PARTS
    && weight === thousandths(totalKg) && volume === thousandths(totalM3 || '0');
  return { weightKg: fromThousandths(weight), volumeM3: fromThousandths(volume), ready };
}
