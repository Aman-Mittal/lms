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

import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import type { DemoResetOutcome } from '../gateway/lmsGateway';
import { useSession } from '../session';

/**
 * Puts the demo back to its seeded state.
 *
 * Two clicks on purpose: the first only asks. The flow is a small state
 * machine -- idle, confirming, working, done -- held in one piece of state, so
 * the screen can never show "confirm" and "done" at once.
 */
export function DemoSettings() {
  const { gateway, can, end } = useSession();
  const navigate = useNavigate();
  const [step, setStep] = useState<'idle' | 'confirming' | 'working' | 'done'>('idle');
  const [outcome, setOutcome] = useState<DemoResetOutcome | null>(null);
  const [error, setError] = useState('');
  const [typed, setTyped] = useState('');

  async function reset() {
    setStep('working');
    setError('');
    try {
      setOutcome(await gateway.resetDemoData(typed.trim()));
      setStep('done');
    } catch (e) {
      setError((e as Error).message);
      setStep('idle');
    }
  }

  function signInAgain() {
    end();
    navigate('/login');
  }

  if (!can('DEMO_RESET')) {
    return <p className="muted" data-testid="demo-reset-forbidden">You cannot reset the demo.</p>;
  }

  return (
    <>
      <h1>Demo data</h1>
      <p className="muted">
        Deletes everything in the demo -- orders, trips, partners, custom fields, users -- and seeds it
        again: one administrator with the same password. Anything you built here is lost.
      </p>
      {error && <p className="error" role="alert">{error}</p>}
      {step === 'idle' && (
        <p><button data-testid="demo-reset" onClick={() => setStep('confirming')}>
          Reset demo data
        </button></p>
      )}
      {(step === 'confirming' || step === 'working') && (
        <section data-testid="demo-reset-confirm" aria-label="Confirm the reset">
          <p><strong>Reset the whole demo?</strong> This cannot be undone. Type the demo's company
            code to confirm.</p>
          <p className="actions">
            <input aria-label="Company code" placeholder="demo" data-testid="demo-reset-code"
              value={typed} onChange={(e) => setTyped(e.target.value)} />
            <button data-testid="demo-reset-yes" disabled={step === 'working' || !typed.trim()}
              onClick={reset}>Yes, reset everything</button>
            <button className="link" data-testid="demo-reset-cancel" disabled={step === 'working'}
              onClick={() => { setStep('idle'); setTyped(''); }}>Cancel</button>
          </p>
        </section>
      )}
      {step === 'done' && outcome && (
        <section data-testid="demo-reset-done">
          <p>The demo is back to its seeded state: {outcome.rowsDeleted} rows deleted.</p>
          {outcome.signInAgain && (
            <p>
              Your user was recreated, so sign in again with the same password.{' '}
              <button data-testid="demo-reset-sign-in" onClick={signInAgain}>Sign in again</button>
            </p>
          )}
        </section>
      )}
    </>
  );
}
