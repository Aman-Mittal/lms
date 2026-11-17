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

import { useSyncExternalStore } from 'react';
import { currentToasts, dismissToast, subscribeToasts } from '../../../shared/feedback';

/** The success toasts, bottom right; a polite live region, so a screen reader hears each without being interrupted. */
export function ToastHost() {
  const toasts = useSyncExternalStore(subscribeToasts, currentToasts);
  return (
    <div className="toasts" aria-live="polite" aria-atomic="false" data-testid="toasts">
      {toasts.map((t) => (
        <div className="toast" key={t.id} data-testid="toast">
          <span>{t.text}</span>
          <button type="button" aria-label="Dismiss" onClick={() => dismissToast(t.id)}>&times;</button>
        </div>
      ))}
    </div>
  );
}
