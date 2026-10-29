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

import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { useSession } from '../session';
import { RESOURCES } from './resources';

export function Shell() {
  const { end } = useSession();
  const navigate = useNavigate();

  function signOut() {
    end();
    navigate('/login');
  }

  return (
    <>
      <header>
        <strong>LMS demo</strong>
        <nav>
          <NavLink to="/" end>Control tower</NavLink>
          {/* Rendering a list: map over data and give each item a stable key. */}
          {RESOURCES.map((r) => (
            <NavLink key={r.key} to={`/${r.key}`}>{r.title}</NavLink>
          ))}
        </nav>
        <button className="link" onClick={signOut}>Sign out</button>
      </header>
      <main><Outlet /></main>
    </>
  );
}
