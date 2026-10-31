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
          <Route path=":resource" element={<ListPage />} />
        </Route>
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
