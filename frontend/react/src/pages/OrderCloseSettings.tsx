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

import { useState, type FormEvent, type SyntheticEvent } from 'react';
import { ApiProblem } from '../gateway/lmsGateway';
import type { OrderCloseSettingsView } from '../gateway/smallOpsTypes';
import { useSession } from '../session';
import { ErrorBanner } from './ErrorBanner';

interface Refused {
  message: string;
  code: string;
}

const NONE: Refused = { message: '', code: '' };

/**
 * The organisation's order close policy, folded away on the order desk until it is wanted: how far short of its
 * quantity an order may be and still close by itself, and whether fulfilled orders close automatically. Changing it
 * belongs to whoever may force-close an order.
 */
export function OrderCloseSettings() {
  const { gateway, can } = useSession();
  const [settings, setSettings] = useState<OrderCloseSettingsView | null>(null);
  const [tolerance, setTolerance] = useState('');
  const [autoFulfil, setAutoFulfil] = useState(false);
  const [done, setDone] = useState(false);
  const [error, setError] = useState<Refused>(NONE);

  function show(s: OrderCloseSettingsView) {
    setSettings(s);
    setTolerance(s.tolerancePercent);
    setAutoFulfil(s.autoFulfil);
  }

  async function run(action: () => Promise<void>) {
    setDone(false);
    setError(NONE);
    try {
      await action();
    } catch (e) {
      setError({ message: (e as Error).message, code: e instanceof ApiProblem ? e.code : '' });
    }
  }

  function toggled(event: SyntheticEvent<HTMLDetailsElement>) {
    if (event.currentTarget.open && !settings) {
      void run(async () => show(await gateway.orderCloseSettings()));
    }
  }

  function apply(event: FormEvent) {
    event.preventDefault();
    void run(async () => {
      show(await gateway.setOrderCloseSettings(tolerance, autoFulfil));
      setDone(true);
    });
  }

  return (
    <details data-testid="close-settings" onToggle={toggled}>
      <summary>Order close policy</summary>
      {settings && (
        <p className="muted" data-testid="close-settings-state">
          Tolerance {settings.tolerancePercent}%, fulfilled orders {settings.autoFulfil ? 'close by themselves' : 'stay open until closed'}
          {' '}({settings.customised ? 'set by this organisation' : 'platform default'}).
        </p>
      )}
      {settings && can('ORDER_FORCE_CLOSE') && (
        <form className="inline" data-testid="close-settings-form" onSubmit={apply}>
          <input type="number" min="0" max="100" step="0.01" aria-label="Close tolerance percent" data-testid="close-tolerance"
            required value={tolerance} onChange={(e) => setTolerance(e.target.value)} />
          <label>
            <input type="checkbox" data-testid="close-auto-fulfil" checked={autoFulfil}
              onChange={(e) => setAutoFulfil(e.target.checked)} />
            Close fulfilled orders automatically
          </label>
          <button type="submit" data-testid="close-settings-apply">Apply close policy</button>
        </form>
      )}
      {done && <p className="ok" role="status" data-testid="close-settings-done">Close policy updated.</p>}
      {error.message && (
        <ErrorBanner message={error.message} code={error.code} testId="close-settings-error" codeTestId="close-settings-refusal" />
      )}
    </details>
  );
}
