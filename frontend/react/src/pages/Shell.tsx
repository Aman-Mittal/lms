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

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { resetDisplay } from '../display';
import { useSession } from '../session';
import {
  DRAWER_QUERY, activeGroupId, groupOpen, isNarrow, loadGroupState, saveGroupState, visibleNav,
} from '../../../shared/nav';

/** The sidebar: its groups come from shared/nav.ts, so both consoles offer the same links in the same order. */
export function Shell() {
  const { auth, can, end } = useSession();
  const navigate = useNavigate();
  const { pathname } = useLocation();

  const [query, setQuery] = useState('');
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [narrow, setNarrow] = useState(isNarrow);
  const [state, setState] = useState(loadGroupState);
  const filterRef = useRef<HTMLInputElement>(null);
  const toggleRef = useRef<HTMLButtonElement>(null);

  const active = activeGroupId(pathname);
  const groups = useMemo(() => visibleNav(can, query), [can, query]);
  const searching = query.trim() !== '';

  const setOpen = useCallback((id: string, open: boolean) => {
    setState((prev) => {
      const next = { ...prev, [id]: open };
      saveGroupState(next);
      return next;
    });
  }, []);

  const closeDrawer = useCallback((refocus: boolean) => {
    setDrawerOpen(false);
    if (refocus) {
      toggleRef.current?.focus();
    }
  }, []);

  // Moving to another page closes the drawer, and opens the group that page sits in, whatever was remembered.
  useEffect(() => {
    setDrawerOpen(false);
    if (active && !groupOpen(state, active, active, narrow, false)) {
      setOpen(active, true);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pathname]);

  useEffect(() => {
    let media: MediaQueryList;
    try {
      media = matchMedia(DRAWER_QUERY);
    } catch {
      return undefined;
    }
    const onChange = (e: MediaQueryListEvent) => {
      setNarrow(e.matches);
      if (!e.matches) {
        setDrawerOpen(false);
      }
    };
    media.addEventListener('change', onChange);
    return () => media.removeEventListener('change', onChange);
  }, []);

  useEffect(() => {
    if (!drawerOpen) {
      return undefined;
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        closeDrawer(true);
      }
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [drawerOpen, closeDrawer]);

  function openDrawer() {
    setDrawerOpen(true);
    setTimeout(() => filterRef.current?.focus());
  }

  function signOut() {
    end();
    resetDisplay();
    navigate('/login');
  }

  return (
    <div className="app">
      <aside id="sidebar" className={drawerOpen ? 'sidebar open' : 'sidebar'} data-testid="sidebar">
        <div className="sidebar-head">
          <div className="brand">LMS</div>
          <button type="button" className="icon-button nav-close" aria-label="Close navigation" data-testid="nav-close"
                  onClick={() => closeDrawer(true)}>
            <svg className="icon" viewBox="0 0 24 24" aria-hidden="true"><path d="M6 6l12 12M18 6L6 18" /></svg>
          </button>
        </div>
        <input ref={filterRef} type="search" className="nav-filter" placeholder="Filter menu" aria-label="Filter menu"
               data-testid="nav-filter" autoComplete="off" value={query} onChange={(e) => setQuery(e.target.value)} />
        <nav aria-label="Main">
          {/* Rendering a list: map over data and give each item a stable key. */}
          {groups.map((g) => {
            const open = groupOpen(state, g.id, active, narrow, searching);
            return (
              <div className="group" key={g.id} data-testid={`nav-group-${g.id}`}>
                <button type="button" className="group-toggle" aria-expanded={open} aria-controls={`nav-items-${g.id}`}
                        data-testid={`nav-toggle-${g.id}`} onClick={() => setOpen(g.id, !open)}>
                  <svg className="icon" viewBox="0 0 24 24" aria-hidden="true"><path d="M6 9l6 6 6-6" /></svg>
                  <span className="label">{g.label}</span>
                </button>
                <div className="group-items" id={`nav-items-${g.id}`} hidden={!open}>
                  {g.sections.map((s, n) => (
                    <div key={n} data-testid={s.testid}>
                      {s.label && g.sections.length > 1 && <div className="section-label">{s.label}</div>}
                      {s.items.map((i) => (
                        <NavLink key={i.key} to={i.route} end={i.route === '/'} data-testid={`nav-${i.key}`}>{i.label}</NavLink>
                      ))}
                    </div>
                  ))}
                </div>
              </div>
            );
          })}
          {groups.length === 0 && <p className="nav-empty" role="status">No menu entry matches.</p>}
        </nav>
      </aside>
      <div className={drawerOpen ? 'nav-backdrop open' : 'nav-backdrop'} data-testid="nav-backdrop" aria-hidden="true"
           onClick={() => closeDrawer(true)} />
      <div className="main">
        <header className="topbar">
          <button ref={toggleRef} type="button" className="icon-button nav-toggle" aria-label="Open navigation"
                  aria-controls="sidebar" aria-expanded={drawerOpen} data-testid="nav-toggle" onClick={openDrawer}>
            <svg className="icon" viewBox="0 0 24 24" aria-hidden="true"><path d="M4 6h16M4 12h16M4 18h16" /></svg>
          </button>
          <NavLink to="/me" data-testid="nav-me">My profile</NavLink>
          <span className="muted" data-testid="who">{auth?.permissions.length ?? 0} permissions</span>
          <button className="link" onClick={signOut} data-testid="sign-out">Sign out</button>
        </header>
        <main><Outlet /></main>
      </div>
    </div>
  );
}
