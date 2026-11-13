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

import { Routes } from '@angular/router';
import { signedInOnly } from './auth';
import { LoginPage } from './pages/login';
import { Shell } from './pages/shell';
import { Track } from './pages/track';
import { Dashboard } from './pages/dashboard';
import { ListPage } from './pages/list';

// Administration
const adminDesk = () => import('./pages/admin-desk').then((m) => m.AdminDesk);

// Desks load on first visit, keeping the initial bundle under its budget: only
// the shell, the sign-in page, the control tower and the generic list screens
// are eager. The daily desks (orders, consignments, delivery) are lazy too --
// the QR library alone is the weight of the delivery and consignment desks.
export const routes: Routes = [
  { path: 'login', component: LoginPage },
  // Public: a customer follows a link with no account, so no sign-in is asked for.
  { path: 'track/:token', component: Track },
  {
    path: '',
    component: Shell,
    canActivate: [signedInOnly],
    children: [
      { path: '', pathMatch: 'full', component: Dashboard },
      { path: 'settings/custom-fields', loadComponent: () => import('./pages/fields-settings').then((m) => m.FieldsSettings) },
      { path: 'settings/demo', loadComponent: () => import('./pages/demo-settings').then((m) => m.DemoSettings) },
      { path: 'settings/webhooks', loadComponent: () => import('./pages/webhooks').then((m) => m.Webhooks) },
      { path: 'claims', loadComponent: () => import('./pages/claims').then((m) => m.ClaimsPage) },
      { path: 'delivery', loadComponent: () => import('./pages/delivery-desk').then((m) => m.DeliveryDesk) },
      { path: 'pod', loadComponent: () => import('./pages/pod-desk').then((m) => m.PodDesk) },
      { path: 'consignments', loadComponent: () => import('./pages/consignment-desk').then((m) => m.ConsignmentDesk) },
      { path: 'tracking', loadComponent: () => import('./pages/tracking-links').then((m) => m.TrackingLinks) },
      { path: 'allocation-rules', loadComponent: () => import('./pages/allocation-rules').then((m) => m.AllocationRules) },
      { path: 'load-building', loadComponent: () => import('./pages/load-building').then((m) => m.LoadBuilding) },
      { path: 'order-desk', loadComponent: () => import('./pages/order-desk').then((m) => m.OrderDeskPage) },
      { path: 'client-billing', loadComponent: () => import('./pages/client-billing').then((m) => m.ClientBilling) },
      // Commercial: loaded on first visit, like the other desks.
      { path: 'commercial', loadComponent: () => import('./pages/commercial').then((m) => m.Commercial) },
      // The map screens load on first visit: Leaflet is the console's largest
      // dependency and most sessions never open a map.
      { path: 'map', loadComponent: () => import('./pages/network-map').then((m) => m.NetworkMap) },
      { path: 'trip-route', loadComponent: () => import('./pages/trip-route').then((m) => m.TripRoute) },
      { path: 'charge-catalogue', loadComponent: () => import('./pages/charge-catalogue').then((m) => m.ChargeCatalogue) },
      { path: 'manual-charges', loadComponent: () => import('./pages/manual-charges').then((m) => m.ManualCharges) },
      { path: 'payables', loadComponent: () => import('./pages/payables').then((m) => m.Payables) },
      { path: 'gate', loadComponent: () => import('./pages/gate-desk').then((m) => m.GateDesk) },
      { path: 'itineraries', loadComponent: () => import('./pages/itineraries').then((m) => m.Itineraries) },
      { path: 'milk-runs', loadComponent: () => import('./pages/milk-runs').then((m) => m.MilkRuns) },
      { path: 'ltl', loadComponent: () => import('./pages/ltl-rating').then((m) => m.LtlRating) },
      { path: 'parked-events', loadComponent: () => import('./pages/parked-events').then((m) => m.ParkedEvents) },
      { path: 'approvals', loadComponent: () => import('./pages/approvals').then((m) => m.Approvals) },
      // Warehouse operations
      { path: 'warehouse', loadComponent: () => import('./pages/warehouse-desk').then((m) => m.WarehouseDesk) },
      // Administration: loaded on first visit, like the maps; few sessions open it.
      { path: 'admin/users', loadComponent: adminDesk, data: { section: 'users' } },
      { path: 'admin/access-policies', loadComponent: adminDesk, data: { section: 'policies' } },
      { path: 'admin/compliance', loadComponent: adminDesk, data: { section: 'compliance' } },
      { path: 'admin/credit-limits', loadComponent: () => import('./pages/credit').then((m) => m.CreditLimitsPage) },
      { path: 'admin/settings', loadComponent: () => import('./pages/profile-settings').then((m) => m.ProfileSettingsPage) },
      { path: 'admin/org-units', loadComponent: () => import('./pages/org-units').then((m) => m.OrgUnitsPage) },
      { path: 'me', loadComponent: () => import('./pages/my-profile').then((m) => m.MyProfilePage) },
      { path: 'admin/number-series', loadComponent: () => import('./pages/number-series').then((m) => m.NumberSeriesPage) },
      // Control tower
      { path: 'exception-desk', loadComponent: () => import('./pages/exception-desk').then((m) => m.ExceptionDeskPage) },
      { path: ':resource', component: ListPage },
    ],
  },
  { path: '**', redirectTo: '' },
];
