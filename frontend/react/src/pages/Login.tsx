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

import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { DEMO_TENANT } from '../config';
import { useSession } from '../session';

export function Login() {
  const { gateway, start } = useSession();
  const navigate = useNavigate();

  /*
   * REACT CONCEPT: controlled inputs.
   * Each field's value comes from state, and typing calls the setter. State is
   * the single source of truth; the input only displays it.
   */
  const [tenantCode, setTenantCode] = useState(DEMO_TENANT);
  const [email, setEmail] = useState('admin@demo.local');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  async function submit(event: FormEvent) {
    event.preventDefault(); // stop the browser's own form submission
    setBusy(true);
    setError('');
    try {
      const result = await gateway.signIn({ tenantCode, email, password });
      start(result);
      navigate('/');
    } catch (e) {
      setError((e as Error).message);
      setBusy(false);
    }
  }

  return (
    <main className="card narrow">
      <h1>LMS demo console</h1>
      <p className="muted">React · signs in to the demo tenant on Render.</p>
      <form onSubmit={submit}>
        <label>Tenant <input value={tenantCode} onChange={(e) => setTenantCode(e.target.value)} required /></label>
        <label>Email <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required /></label>
        <label>Password <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} required /></label>
        <button type="submit" disabled={busy}>{busy ? 'Signing in…' : 'Sign in'}</button>
      </form>
      {error && <p className="error" role="alert">{error}</p>}
    </main>
  );
}
