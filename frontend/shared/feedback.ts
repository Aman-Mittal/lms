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
 * Feedback state shared by both consoles, with no framework in it: the success toasts, and a count of the requests in
 * flight (the Angular HTTP interceptor and the React gateway guard report into it). A console's toast host subscribes and
 * renders; the form helper in fields.ts reads the count to keep a submit button disabled while its request runs.
 */

export interface ToastMessage {
  id: number;
  text: string;
}

const TOAST_MS = 4500;
const MAX_TOASTS = 4;

let toasts: readonly ToastMessage[] = [];
let nextId = 1;
const toastListeners = new Set<() => void>();

function publishToasts(next: readonly ToastMessage[]): void {
  toasts = next;
  toastListeners.forEach((listener) => listener());
}

/** The toasts on screen now; the same array until one comes or goes, so a framework can compare it by identity. */
export function currentToasts(): readonly ToastMessage[] {
  return toasts;
}

/** Calls `listener` whenever the toasts change; returns the function that stops it. */
export function subscribeToasts(listener: () => void): () => void {
  toastListeners.add(listener);
  return () => { toastListeners.delete(listener); };
}

/** Takes a toast down. */
export function dismissToast(id: number): void {
  publishToasts(toasts.filter((t) => t.id !== id));
}

/** Shows a short confirmation that goes away by itself; the same words already on screen are not stacked. */
export function showToast(text: string, lifetimeMs: number = TOAST_MS): void {
  const said = text.trim();
  if (!said || toasts.some((t) => t.text === said)) {
    return;
  }
  const id = nextId++;
  publishToasts([...toasts, { id, text: said }].slice(-MAX_TOASTS));
  setTimeout(() => dismissToast(id), lifetimeMs);
}

let serial = 0;
const open = new Set<number>();
const pendingListeners = new Set<() => void>();

function publishPending(): void {
  pendingListeners.forEach((listener) => listener());
}

/** A request has started; hand the number back to endRequest when it ends. */
export function beginRequest(): number {
  const id = ++serial;
  open.add(id);
  publishPending();
  return id;
}

/** A request has ended, whatever the outcome. */
export function endRequest(id: number): void {
  open.delete(id);
  publishPending();
}

/** A mark in the order requests start; ask openSince(mark) for the ones begun after it that are still running. */
export function requestMark(): number {
  return serial;
}

/** How many requests begun after `mark` are still in flight. */
export function openSince(mark: number): number {
  let count = 0;
  open.forEach((id) => { if (id > mark) { count++; } });
  return count;
}

/** How many requests are in flight. */
export function pendingRequests(): number {
  return open.size;
}

/** Calls `listener` whenever a request starts or ends; returns the function that stops it. */
export function subscribePending(listener: () => void): () => void {
  pendingListeners.add(listener);
  return () => { pendingListeners.delete(listener); };
}

/** Runs `call`, counted as a request in flight until it settles. */
export async function tracked<T>(call: () => Promise<T>): Promise<T> {
  const id = beginRequest();
  try {
    return await call();
  } finally {
    endRequest(id);
  }
}
