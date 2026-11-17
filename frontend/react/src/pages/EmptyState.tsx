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

import type { ReactNode } from 'react';

/** "Nothing here": a calm box with the sentence that says why, and optionally what to do about it. */
export function EmptyState({ message, testId, children }: { message: string; testId?: string; children?: ReactNode }) {
  return (
    <div className="empty-state" data-testid={testId}>
      <svg className="icon" viewBox="0 0 24 24" aria-hidden="true"><path d="M4 7h16v12H4zM4 7l3-3h10l3 3M9 12h6" /></svg>
      <p>{message}</p>
      {children}
    </div>
  );
}

/** "Working on it": a spinner and a sentence; announced politely so a reader knows the list is on its way. */
export function LoadingState({ message = 'Loading…', testId }: { message?: string; testId?: string }) {
  return <div className="loading-state" role="status" data-testid={testId}><p>{message}</p></div>;
}
