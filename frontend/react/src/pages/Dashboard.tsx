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

import { Link } from 'react-router-dom';
import { useSession } from '../session';
import { useLoad } from '../useLoad';
import { RESOURCES } from './resources';

export function Dashboard() {
  const { gateway } = useSession();
  const { data: s, error } = useLoad(() => gateway.summary(), 'summary');

  return (
    <>
      <h1>Control tower</h1>
      {error && <p className="error" role="alert">{error}</p>}
      {s ? (
        <div className="tiles" data-testid="tiles">
          <Tile n={s.openP1} label="Open P1" />
          <Tile n={s.openP2} label="Open P2" />
          <Tile n={s.openP3} label="Open P3" />
          <Tile n={s.unassigned} label="Unassigned" />
          <Tile n={s.settlementHolds} label="Settlement holds" />
          <Tile n={s.openWithoutTrip} label="Without a trip" />
        </div>
      ) : (
        !error && <p>Loading…</p>
      )}
      <h2>Go to</h2>
      <div className="links">
        {RESOURCES.map((r) => <Link key={r.key} to={`/${r.key}`}>{r.title}</Link>)}
      </div>
    </>
  );
}

/** A component is a function that takes props and returns UI. */
function Tile({ n, label }: { n: number; label: string }) {
  return (
    <div className="tile">
      <span>{n}</span>
      {label}
    </div>
  );
}
