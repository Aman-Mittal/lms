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
 * The browser-side cache (DOCS/adr/0007): reference geometry, track points and
 * the tile cache, kept in IndexedDB through the native API, no library.
 *
 * What it is: a copy of what the server already returned to this person, kept
 * so the next view asks for less. What it is not: a source of truth. Nothing
 * here decides a permission, a state change or an amount, and no token is ever
 * stored. Reading it needs the same browser profile and the same page origin,
 * so a script injected into the console could read it; that is why it holds
 * only what the screens already show and is wiped on sign-out.
 *
 * One database per tenant and user, named `lms-cache:<tenant>:<user>`. It is
 * wiped on sign-out, on a change of tenant or user (every other database with
 * the prefix is deleted when one is bound), when the server's `cacheEpoch`
 * differs from the stored one, and entries expire by TTL. Switched off by the
 * `ui.clientCache` setting, in which case nothing is opened or stored and every
 * read falls through to the network.
 */

const PREFIX = 'lms-cache:';
const STORE = 'entries';
const EPOCH_KEY = '__epoch';
const TILE_CACHE = 'lms-tiles-v1';

/** How long reference geometry is trusted without asking the server. */
export const FRESH_MS = 5 * 60 * 1000;
/** How long any entry survives at all, however often it is revalidated. */
export const GEOMETRY_TTL_MS = 24 * 60 * 60 * 1000;
export const TRACK_TTL_MS = 24 * 60 * 60 * 1000;

interface Entry<T> {
  key: string;
  value: T;
  version: string | null;
  storedAt: number;
  expiresAt: number;
}

export interface Cached<T> {
  value: T;
  version: string | null;
  storedAt: number;
}

let dbName: string | null = null;
let opened: Promise<IDBDatabase> | null = null;
let enabled = false;

function supported(): boolean {
  return typeof indexedDB !== 'undefined';
}

function request<T>(r: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    r.onsuccess = () => resolve(r.result);
    r.onerror = () => reject(r.error);
  });
}

function open(): Promise<IDBDatabase> {
  if (!dbName) {
    return Promise.reject(new Error('client cache is not bound'));
  }
  if (!opened) {
    const name = dbName;
    opened = new Promise<IDBDatabase>((resolve, reject) => {
      const r = indexedDB.open(name, 1);
      r.onupgradeneeded = () => r.result.createObjectStore(STORE, { keyPath: 'key' });
      r.onsuccess = () => {
        // Let another tab's wipe through rather than block it.
        r.result.onversionchange = () => { r.result.close(); opened = null; };
        resolve(r.result);
      };
      r.onerror = () => reject(r.error);
    });
    opened.catch(() => { opened = null; });
  }
  return opened;
}

async function closeOpen(): Promise<void> {
  const pending = opened;
  opened = null;
  if (pending) {
    try { (await pending).close(); } catch { /* it never opened */ }
  }
}

function deleteDatabase(name: string): Promise<void> {
  return new Promise((resolve) => {
    const r = indexedDB.deleteDatabase(name);
    r.onsuccess = () => resolve();
    r.onerror = () => resolve();
    r.onblocked = () => resolve();
  });
}

/** Every cache database this origin holds; where the browser cannot list them, the one bound. */
async function cacheDatabases(): Promise<string[]> {
  const names = new Set<string>(dbName ? [dbName] : []);
  try {
    for (const d of (await indexedDB.databases?.()) ?? []) {
      if (d.name?.startsWith(PREFIX)) {
        names.add(d.name);
      }
    }
  } catch { /* an older browser: the bound database is all that can be named */ }
  return [...names];
}

/** Deletes every cached thing for everyone: the databases and the tile cache. Never throws. */
export async function clearAllClientCache(): Promise<void> {
  try {
    if (supported()) {
      await closeOpen();
      await Promise.all((await cacheDatabases()).map(deleteDatabase));
    }
    if (typeof caches !== 'undefined') {
      await caches.delete(TILE_CACHE);
    }
  } catch { /* wiping is best effort; the next sign-in wipes again */ }
  dbName = null;
  enabled = false;
}

export interface CacheBinding {
  tenantId: string;
  userId: string;
  /** The server's `cacheEpoch` from `/me`. */
  epoch: string;
  /** The `ui.clientCache` setting. */
  enabled: boolean;
  /** Register the tile worker: true in a production build only. */
  tileWorker: boolean;
}

/**
 * Binds the cache to who just signed in. A different person's (or tenant's)
 * data is deleted, a changed epoch wipes this person's, and with the setting
 * off everything is deleted and nothing is opened.
 */
export async function activateClientCache(b: CacheBinding): Promise<void> {
  try {
    if (!supported() || !b.enabled || !b.tenantId || !b.userId || !b.epoch) {
      await clearAllClientCache();
      await setTileWorker(false);
      return;
    }
    const name = `${PREFIX}${b.tenantId}:${b.userId}`;
    if (dbName !== name) {
      await closeOpen();
      await Promise.all((await cacheDatabases()).filter((n) => n !== name).map(deleteDatabase));
      dbName = name;
    }
    enabled = true;
    await onEpoch(b.epoch);
    await setTileWorker(b.tileWorker);
  } catch {
    enabled = false;
  }
}

/** Wipes the entries when the server's epoch is not the one they were stored under. */
export async function onEpoch(epoch: string): Promise<void> {
  if (!enabled || !dbName || !epoch) {
    return;
  }
  try {
    const db = await open();
    const stored = await request<Entry<string> | undefined>(db.transaction(STORE).objectStore(STORE).get(EPOCH_KEY));
    if (stored?.value === epoch) {
      return;
    }
    const tx = db.transaction(STORE, 'readwrite');
    const store = tx.objectStore(STORE);
    store.clear();
    store.put({ key: EPOCH_KEY, value: epoch, version: null, storedAt: Date.now(), expiresAt: Infinity } satisfies Entry<string>);
    await new Promise<void>((resolve, reject) => { tx.oncomplete = () => resolve(); tx.onerror = () => reject(tx.error); });
    if (typeof caches !== 'undefined') {
      await caches.delete(TILE_CACHE);
    }
  } catch { /* an unusable database is the same as no cache */ }
}

export function clientCacheEnabled(): boolean {
  return enabled;
}

/** The entry, unless it is missing or past its TTL (an expired one is removed). Never throws. */
export async function cacheGet<T>(key: string): Promise<Cached<T> | undefined> {
  if (!enabled) {
    return undefined;
  }
  try {
    const db = await open();
    const entry = await request<Entry<T> | undefined>(db.transaction(STORE).objectStore(STORE).get(key));
    if (!entry) {
      return undefined;
    }
    if (entry.expiresAt <= Date.now()) {
      db.transaction(STORE, 'readwrite').objectStore(STORE).delete(key);
      return undefined;
    }
    return { value: entry.value, version: entry.version, storedAt: entry.storedAt };
  } catch {
    return undefined;
  }
}

export async function cachePut<T>(key: string, value: T, ttlMs: number, version: string | null = null): Promise<void> {
  if (!enabled) {
    return;
  }
  try {
    const db = await open();
    const now = Date.now();
    db.transaction(STORE, 'readwrite').objectStore(STORE)
      .put({ key, value, version, storedAt: now, expiresAt: now + ttlMs } satisfies Entry<T>);
  } catch { /* the entry is simply not kept */ }
}

/** A revalidation said "unchanged": the entry is as fresh as a new one. */
export async function cacheTouch(key: string, ttlMs: number): Promise<void> {
  const held = await cacheGet<unknown>(key);
  if (held) {
    await cachePut(key, held.value, ttlMs, held.version);
  }
}

/** Whether a cached entry is recent enough to use with no request at all. */
export function isFresh(held: Cached<unknown>, now = Date.now()): boolean {
  return now - held.storedAt < FRESH_MS;
}

// ------------------------------------------------------------------ tiles

/**
 * Registers the tile worker (production builds only, scoped to the console's
 * own path) while the cache is on, and removes it, with its cache, when off.
 * The worker only ever sees image requests for tile-shaped URLs; see tile-sw.js.
 */
export async function setTileWorker(on: boolean): Promise<void> {
  if (typeof navigator === 'undefined' || !('serviceWorker' in navigator)) {
    return;
  }
  try {
    if (on) {
      const script = new URL('tile-sw.js', document.baseURI);
      const scope = new URL('./', document.baseURI).pathname;
      await navigator.serviceWorker.register(script.href, { scope });
    } else {
      const registration = await navigator.serviceWorker.getRegistration(new URL('./', document.baseURI).href);
      await registration?.unregister();
    }
  } catch { /* tiles then come from the network, as before */ }
}
