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

import { work, type WorkRequest } from '../display-math';

// Runs display-math off the main thread: it holds no state, reads nothing from the page and
// has no network, so it can see only the rows and points it is handed.
// (Typed by hand: this project compiles with the DOM lib, whose `postMessage` is the window's.)
const scope = self as unknown as {
  postMessage(message: unknown): void;
  addEventListener(type: 'message', listener: (event: MessageEvent<WorkRequest>) => void): void;
};
scope.addEventListener('message', ({ data }) => {
  scope.postMessage(work(data));
});
