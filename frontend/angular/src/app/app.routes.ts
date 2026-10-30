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
import { AllocationRules } from './pages/allocation-rules';
import { LoginPage } from './pages/login';
import { Shell } from './pages/shell';
import { Track } from './pages/track';
import { TrackingLinks } from './pages/tracking-links';
import { ClaimsPage } from './pages/claims';
import { Dashboard } from './pages/dashboard';
import { DeliveryDesk } from './pages/delivery-desk';
import { ListPage } from './pages/list';
import { FieldsSettings } from './pages/fields-settings';

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
      { path: 'settings/custom-fields', component: FieldsSettings },
      { path: 'claims', component: ClaimsPage },
      { path: 'delivery', component: DeliveryDesk },
      { path: 'tracking', component: TrackingLinks },
      { path: 'allocation-rules', component: AllocationRules },
      { path: ':resource', component: ListPage },
    ],
  },
  { path: '**', redirectTo: '' },
];
