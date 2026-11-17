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

import { describeProblem, splitProblemText } from '../../../shared/problems';

/**
 * The one way a refusal is shown: the plain sentence for its code first, the server's own words under it when they add
 * something, and the code in small print for support. A message that still carries its code ("code: message" or
 * "message (code)") is split first. `testId` and `codeTestId` keep the test ids the screens already had.
 */
export function ErrorBanner({ message, code = '', testId, codeTestId }: {
  message: string; code?: string; testId?: string; codeTestId?: string;
}) {
  const split = code ? { code, message } : splitProblemText(message);
  const view = describeProblem(split.code, split.message);
  return (
    <div className="banner" role="alert" data-testid={testId}>
      <svg className="icon" viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="9" /><path d="M12 8v5M12 16.5v.01" /></svg>
      <div className="banner__body">
        <p className="banner__headline">{view.headline}</p>
        {view.detail && <p className="banner__detail">{view.detail}</p>}
        {view.code && <p className="banner__code">Code: <code data-testid={codeTestId}>{view.code}</code></p>}
      </div>
    </div>
  );
}
