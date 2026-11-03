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

// Vite replaces import.meta.env.* at build time, so a fork can point the
// console at its own backend with VITE_API_URL=... npm run build.
export const API_URL: string =
  import.meta.env.VITE_API_URL ?? 'https://lms-platform-du75.onrender.com';

/** The demo tenant the seeder creates; prefilled on the sign-in form. */
export const DEMO_TENANT = 'demo';

/**
 * Where map tiles come from, read at run time like the Angular console's API
 * address: `<meta name="lms-tile-url" content="...">` wins, then VITE_TILE_URL
 * at build time, then the public OpenStreetMap servers. Those allow light use
 * only; a deployment with real traffic points this at its own tile server.
 */
function fromMeta(name: string): string | null {
  return typeof document === 'undefined'
    ? null
    : document.querySelector(`meta[name="${name}"]`)?.getAttribute('content') || null;
}

export const TILE_URL: string =
  fromMeta('lms-tile-url') ?? import.meta.env.VITE_TILE_URL ?? 'https://tile.openstreetmap.org/{z}/{x}/{y}.png';

/** Credit for the tiles, shown on every map. Change it with the tile URL. */
export const TILE_ATTRIBUTION: string =
  fromMeta('lms-tile-attribution') ?? '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors';
