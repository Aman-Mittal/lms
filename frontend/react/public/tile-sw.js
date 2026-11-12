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
 * Map tile cache (DOCS/adr/0007). Registered by the console in production
 * builds only, scoped to the console's own path.
 *
 * It handles one kind of request: a GET for an image whose path looks like a
 * map tile (/z/x/y.png). Everything else -- every API call, every request
 * that carries an Authorization header, every non-image -- is left alone, so
 * this worker can never hold a response that came from the API. Tiles are
 * public map imagery; what it reveals is which areas were looked at, so the
 * console deletes this cache on sign-out.
 *
 * Cache first with expiry. Bounded by entry count and tile size; when full the
 * least recently used tiles go first (a hit is rewritten, which moves it to
 * the end of the cache's key order, and the oldest keys are removed).
 */
const CACHE = 'lms-tiles-v1';
const MAX_ENTRIES = 600;
const MAX_TILE_BYTES = 200 * 1024;
const MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000;
const TOUCH_AFTER_MS = 10 * 60 * 1000;
const TILE_PATH = /\/\d{1,2}\/\d+\/\d+(@2x)?\.(png|jpe?g|webp)$/i;

self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (event) => event.waitUntil(self.clients.claim()));

self.addEventListener('fetch', (event) => {
  const request = event.request;
  if (request.method !== 'GET' || request.destination !== 'image' || request.headers.has('Authorization')) {
    return;
  }
  const url = new URL(request.url);
  if (!TILE_PATH.test(url.pathname) || url.pathname.includes('/api/')) {
    return;
  }
  event.respondWith(tile(request, url.href));
});

async function tile(request, key) {
  const cache = await caches.open(CACHE);
  const held = await cache.match(key);
  const now = Date.now();
  if (held) {
    const stored = Number(held.headers.get('x-tile-stored')) || 0;
    if (now - stored < MAX_AGE_MS) {
      if (now - (Number(held.headers.get('x-tile-used')) || 0) > TOUCH_AFTER_MS) {
        await keep(cache, key, held.clone(), stored, now);
      }
      return held;
    }
  }
  try {
    // Asked for again with CORS so the body can be kept; a tile server that does not allow it
    // still gets the page's own request below, uncached.
    const fresh = await fetch(key, { mode: 'cors', credentials: 'omit' });
    if (fresh.ok) {
      await keep(cache, key, fresh.clone(), now, now);
      return fresh;
    }
  } catch {
    // fall through
  }
  try {
    return await fetch(request);
  } catch (e) {
    if (held) {
      return held;
    }
    throw e;
  }
}

async function keep(cache, key, response, stored, used) {
  const body = await response.blob();
  if (body.size > MAX_TILE_BYTES) {
    return;
  }
  const headers = new Headers(response.headers);
  headers.set('x-tile-stored', String(stored));
  headers.set('x-tile-used', String(used));
  await cache.delete(key);
  await cache.put(key, new Response(body, { status: 200, headers }));
  const keys = await cache.keys();
  for (const old of keys.slice(0, Math.max(0, keys.length - MAX_ENTRIES))) {
    await cache.delete(old);
  }
}
