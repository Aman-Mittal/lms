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
import { GROUPS, RESOURCES } from './resources';

export function Shell() {
  const { auth, can, end } = useSession();
  const navigate = useNavigate();

  function signOut() {
    end();
    navigate('/login');
  }

  return (
    <div className="app">
      <aside className="sidebar" data-testid="sidebar">
        <div className="brand">LMS</div>
        <nav aria-label="Main">
          <div className="group">
            <NavLink to="/" end data-testid="nav-overview">Control tower</NavLink>
          </div>
          {/* Rendering a list: map over data and give each item a stable key. */}
          {GROUPS.map((group) => (
            <div className="group" key={group}>
              <h6>{group}</h6>
              {RESOURCES.filter((r) => r.group === group && (!r.permission || can(r.permission))).map((r) => (
                <NavLink key={r.key} to={`/${r.key}`} data-testid={`nav-${r.key}`}>{r.title}</NavLink>
              ))}
              {group === 'Operations' && can('TERMINAL_READ') && (
                <NavLink to="/map" data-testid="nav-map">Network map</NavLink>
              )}
              {group === 'Operations' && can('TRIP_READ') && (
                <NavLink to="/trip-route" data-testid="nav-trip-route">Trip route</NavLink>
              )}
              {group === 'Multimodal' && can('MULTIMODAL_READ') && (
                <NavLink to="/itineraries" data-testid="nav-itineraries">Itineraries</NavLink>
              )}
              {group === 'Multimodal' && can('MILKRUN_READ') && (
                <NavLink to="/milk-runs" data-testid="nav-milk-runs">Milk runs</NavLink>
              )}
              {group === 'Operations' && can('ALLOCATION_RULE_READ') && (
                <NavLink to="/allocation-rules" data-testid="nav-allocation-rules">Allocation rules</NavLink>
              )}
              {group === 'Delivery' && can('POD_READ') && (
                <NavLink to="/delivery" data-testid="nav-delivery">Delivery desk</NavLink>
              )}
              {group === 'Delivery' && can('TRACKING_LINK_MANAGE') && (
                <NavLink to="/tracking" data-testid="nav-tracking">Tracking links</NavLink>
              )}
            </div>
          ))}
          {(can('CUSTOM_FIELD_READ') || can('DEMO_RESET')) && (
            <div className="group">
              <h6>Settings</h6>
              {can('CUSTOM_FIELD_READ') && (
                <NavLink to="/settings/custom-fields" data-testid="nav-custom-fields">Custom fields</NavLink>
              )}
              {can('DEMO_RESET') && <NavLink to="/settings/demo" data-testid="nav-demo">Demo data</NavLink>}
            </div>
          )}
        </nav>
      </aside>
      <div className="main">
        <header className="topbar">
          <span className="muted" data-testid="who">{auth?.permissions.length ?? 0} permissions</span>
          <button className="link" onClick={signOut} data-testid="sign-out">Sign out</button>
        </header>
        <main><Outlet /></main>
      </div>
    </div>
  );
}
