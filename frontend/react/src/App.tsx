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
import { ConsignmentDesk } from './pages/ConsignmentDesk';
import { Dashboard } from './pages/Dashboard';
import { DeliveryDesk } from './pages/DeliveryDesk';
import { DemoSettings } from './pages/DemoSettings';
import { FieldsSettings } from './pages/FieldsSettings';
import { ListPage } from './pages/ListPage';
import { LtlRating } from './pages/LtlRating';
import { LoadBuilding } from './pages/LoadBuilding';
import { OrderDesk } from './pages/OrderDesk';
import { ClientBilling } from './pages/ClientBilling';
// Commercial
import { Commercial } from './pages/Commercial';
import { Login } from './pages/Login';
import { ParkedEvents } from './pages/ParkedEvents';
import { ApprovalsPage } from './pages/ApprovalsPage';
import { Shell } from './pages/Shell';
import { TrackingLinks } from './pages/TrackingLinks';
import { TrackPage } from './pages/TrackPage';
import { Webhooks } from './pages/Webhooks';
import { useSession } from './session';
// Warehouse operations
import { WarehouseDesk } from './pages/WarehouseDesk';
// Control tower
import { ExceptionDesk } from './pages/ExceptionDesk';

/*
 * REACT CONCEPT: lazy and Suspense.
 * The map screens are loaded on first visit: Leaflet is the console's largest
 * dependency and most sessions never open a map. `lazy` turns a dynamic import
 * into a component; `Suspense` shows something while its code arrives.
 */
const NetworkMap = lazy(() => import('./pages/NetworkMap').then((m) => ({ default: m.NetworkMap })));
const TripRoute = lazy(() => import('./pages/TripRoute').then((m) => ({ default: m.TripRoute })));
const GateDesk = lazy(() => import('./pages/GateDesk').then((m) => ({ default: m.GateDesk })));
const Itineraries = lazy(() => import('./pages/Itineraries').then((m) => ({ default: m.Itineraries })));
const MilkRuns = lazy(() => import('./pages/MilkRuns').then((m) => ({ default: m.MilkRuns })));
// Administration: loaded on first visit too; few sessions open it.
const AdminDesk = lazy(() => import('./pages/AdminDesk').then((m) => ({ default: m.AdminDesk })));
const CreditLimits = lazy(() => import('./pages/Credit').then((m) => ({ default: m.CreditLimits })));
const NumberSeries = lazy(() => import('./pages/NumberSeries').then((m) => ({ default: m.NumberSeries })));
const ProfileSettings = lazy(() => import('./pages/ProfileSettings').then((m) => ({ default: m.ProfileSettings })));
const OrgUnits = lazy(() => import('./pages/OrgUnits').then((m) => ({ default: m.OrgUnits })));
const ChargeCatalogue = lazy(() => import('./pages/ChargeCatalogue').then((m) => ({ default: m.ChargeCatalogue })));
const ManualCharges = lazy(() => import('./pages/ManualCharges').then((m) => ({ default: m.ManualCharges })));
const VendorPayables = lazy(() => import('./pages/VendorPayables').then((m) => ({ default: m.VendorPayables })));
const MyProfile = lazy(() => import('./pages/MyProfile').then((m) => ({ default: m.MyProfile })));

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
          <Route path="settings/webhooks" element={<Webhooks />} />
          <Route path="claims" element={<ClaimsPage />} />
          <Route path="delivery" element={<DeliveryDesk />} />
          <Route path="consignments" element={<ConsignmentDesk />} />
          <Route path="tracking" element={<TrackingLinks />} />
          <Route path="allocation-rules" element={<AllocationRules />} />
          <Route path="load-building" element={<LoadBuilding />} />
          <Route path="order-desk" element={<OrderDesk />} />
          <Route path="client-billing" element={<ClientBilling />} />
          {/* Commercial */}
          <Route path="commercial" element={<Commercial />} />
          <Route path="map" element={<Deferred><NetworkMap /></Deferred>} />
          <Route path="trip-route" element={<Deferred><TripRoute /></Deferred>} />
          <Route path="gate" element={<Deferred><GateDesk /></Deferred>} />
          <Route path="itineraries" element={<Deferred><Itineraries /></Deferred>} />
          <Route path="milk-runs" element={<Deferred><MilkRuns /></Deferred>} />
          <Route path="ltl" element={<LtlRating />} />
          <Route path="parked-events" element={<ParkedEvents />} />
          <Route path="approvals" element={<ApprovalsPage />} />
          {/* Warehouse operations */}
          <Route path="warehouse" element={<WarehouseDesk />} />
          {/* Administration */}
          <Route path="admin/users" element={<Deferred><AdminDesk section="users" /></Deferred>} />
          <Route path="admin/access-policies" element={<Deferred><AdminDesk section="policies" /></Deferred>} />
          <Route path="admin/compliance" element={<Deferred><AdminDesk section="compliance" /></Deferred>} />
          <Route path="admin/credit-limits" element={<Deferred><CreditLimits /></Deferred>} />
          <Route path="admin/number-series" element={<Deferred><NumberSeries /></Deferred>} />
          <Route path="admin/settings" element={<Deferred><ProfileSettings /></Deferred>} />
          <Route path="admin/org-units" element={<Deferred><OrgUnits /></Deferred>} />
          <Route path="charge-catalogue" element={<Deferred><ChargeCatalogue /></Deferred>} />
          <Route path="manual-charges" element={<Deferred><ManualCharges /></Deferred>} />
          <Route path="payables" element={<Deferred><VendorPayables /></Deferred>} />
          <Route path="me" element={<Deferred><MyProfile /></Deferred>} />
          {/* Control tower */}
          <Route path="exception-desk" element={<ExceptionDesk />} />
          <Route path=":resource" element={<ListPage />} />
        </Route>
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
