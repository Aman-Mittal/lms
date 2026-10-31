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

import { lazy, Suspense, type ReactNode } from 'react';
import { Navigate, Outlet, Route, Routes } from 'react-router-dom';
import { AllocationRules } from './pages/AllocationRules';
import { ClaimsPage } from './pages/ClaimsPage';
import { Dashboard } from './pages/Dashboard';
import { DeliveryDesk } from './pages/DeliveryDesk';
import { DemoSettings } from './pages/DemoSettings';
import { FieldsSettings } from './pages/FieldsSettings';
import { ListPage } from './pages/ListPage';
import { Login } from './pages/Login';
import { Shell } from './pages/Shell';
import { TrackingLinks } from './pages/TrackingLinks';
import { TrackPage } from './pages/TrackPage';
import { useSession } from './session';

/*
 * REACT CONCEPT: lazy and Suspense.
 * The map screens are loaded on first visit: Leaflet is the console's largest
 * dependency and most sessions never open a map. `lazy` turns a dynamic import
 * into a component; `Suspense` shows something while its code arrives.
 */
const NetworkMap = lazy(() => import('./pages/NetworkMap').then((m) => ({ default: m.NetworkMap })));
const TripRoute = lazy(() => import('./pages/TripRoute').then((m) => ({ default: m.TripRoute })));
const Itineraries = lazy(() => import('./pages/Itineraries').then((m) => ({ default: m.Itineraries })));
const MilkRuns = lazy(() => import('./pages/MilkRuns').then((m) => ({ default: m.MilkRuns })));

function Deferred({ children }: { children: ReactNode }) {
  return <Suspense fallback={<p className="muted">Loading…</p>}>{children}</Suspense>;
}

/** Wraps the screens that need a signed-in user; others are sent to /login. */
function RequireSignIn() {
  const { auth } = useSession();
  return auth ? <Outlet /> : <Navigate to="/login" replace />;
}

export function App() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      {/* Public: a customer follows a link with no account, so no sign-in is asked for. */}
      <Route path="/track/:token" element={<TrackPage />} />
      <Route element={<RequireSignIn />}>
        <Route element={<Shell />}>
          <Route index element={<Dashboard />} />
          <Route path="settings/custom-fields" element={<FieldsSettings />} />
          <Route path="settings/demo" element={<DemoSettings />} />
          <Route path="claims" element={<ClaimsPage />} />
          <Route path="delivery" element={<DeliveryDesk />} />
          <Route path="tracking" element={<TrackingLinks />} />
          <Route path="allocation-rules" element={<AllocationRules />} />
          <Route path="map" element={<Deferred><NetworkMap /></Deferred>} />
          <Route path="trip-route" element={<Deferred><TripRoute /></Deferred>} />
          <Route path="itineraries" element={<Deferred><Itineraries /></Deferred>} />
          <Route path="milk-runs" element={<Deferred><MilkRuns /></Deferred>} />
          <Route path=":resource" element={<ListPage />} />
        </Route>
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
