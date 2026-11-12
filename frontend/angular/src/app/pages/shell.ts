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

import { Component, inject } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { Session } from '../session';
import { resetDisplay } from '../display';
import { GROUPS, RESOURCES } from './resources';

@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <div class="app">
      <aside class="sidebar" data-testid="sidebar">
        <div class="brand">LMS</div>
        <nav aria-label="Main">
          <div class="group">
            <a routerLink="/" routerLinkActive="active" [routerLinkActiveOptions]="{ exact: true }"
               data-testid="nav-overview">Control tower</a>
          </div>
          @for (group of groups; track group) {
            <div class="group">
              <h6>{{ group }}</h6>
              @for (r of inGroup(group); track r.key) {
                <a [routerLink]="['/', r.key]" routerLinkActive="active"
                   [attr.data-testid]="'nav-' + r.key">{{ r.title }}</a>
              }
              @if (group === 'Operations' && session.can('LOAD_READ')) {
                <a routerLink="/consignments" routerLinkActive="active" data-testid="nav-consignments">Consignments</a>
              }
              @if (group === 'Operations' && session.can('TERMINAL_READ')) {
                <a routerLink="/map" routerLinkActive="active" data-testid="nav-map">Network map</a>
              }
              @if (group === 'Operations' && (session.can('GATE_REGISTER') || session.can('GATE_PASS_MANAGE'))) {
                <a routerLink="/gate" routerLinkActive="active" data-testid="nav-gate">Gate</a>
              }
              @if (group === 'Operations' && session.can('TRIP_READ')) {
                <a routerLink="/trip-route" routerLinkActive="active" data-testid="nav-trip-route">Trip route</a>
              }
              @if (group === 'Multimodal' && session.can('MULTIMODAL_READ')) {
                <a routerLink="/itineraries" routerLinkActive="active" data-testid="nav-itineraries">Itineraries</a>
              }
              @if (group === 'Multimodal' && session.can('MILKRUN_READ')) {
                <a routerLink="/milk-runs" routerLinkActive="active" data-testid="nav-milk-runs">Milk runs</a>
              }
              @if (group === 'Operations' && session.can('ORDER_READ')) {
                <a routerLink="/order-desk" routerLinkActive="active" data-testid="nav-order-desk">Order desk</a>
              }
              @if (group === 'Operations' && session.can('APPROVAL_READ')) {
                <a routerLink="/approvals" routerLinkActive="active" data-testid="nav-approvals">Approvals</a>
              }
              @if (group === 'Operations' && session.can('LOAD_READ')) {
                <a routerLink="/load-building" routerLinkActive="active" data-testid="nav-load-building">Load building</a>
              }
              @if (group === 'Operations' && session.can('ALLOCATION_RULE_READ')) {
                <a routerLink="/allocation-rules" routerLinkActive="active" data-testid="nav-allocation-rules">Allocation rules</a>
              }
              @if (group === 'Finance' && (session.can('INVOICE_READ') || session.can('TARIFF_MANAGE'))) {
                <a routerLink="/ltl" routerLinkActive="active" data-testid="nav-ltl">LTL rating</a>
              }
              @if (group === 'Finance' && session.can('CLIENT_BILLING_READ')) {
                <a routerLink="/client-billing" routerLinkActive="active" data-testid="nav-client-billing">Client billing</a>
              }
              <!-- Commercial -->
              @if (group === 'Finance' && (session.can('INVOICE_READ') || session.can('TARIFF_MANAGE')
                  || session.can('ROUTING_GUIDE_MANAGE') || session.can('LOAD_ALLOCATE'))) {
                <a routerLink="/commercial" routerLinkActive="active" data-testid="nav-commercial">Commercial</a>
              }
              @if (group === 'Control tower' && session.can('EVENT_DEAD_LETTER_READ')) {
                <a routerLink="/parked-events" routerLinkActive="active" data-testid="nav-parked-events">Parked events</a>
              }
              @if (group === 'Delivery' && session.can('POD_READ')) {
                <a routerLink="/delivery" routerLinkActive="active" data-testid="nav-delivery">Delivery desk</a>
              }
              @if (group === 'Delivery' && session.can('TRACKING_LINK_MANAGE')) {
                <a routerLink="/tracking" routerLinkActive="active" data-testid="nav-tracking">Tracking links</a>
              }
              <!-- Warehouse operations -->
              @if (group === 'Operations' && session.can('WAREHOUSE_READ')) {
                <a routerLink="/warehouse" routerLinkActive="active" data-testid="nav-warehouse">Warehouse operations</a>
              }
              <!-- Control tower -->
              @if (group === 'Control tower' && session.can('EXCEPTION_READ')) {
                <a routerLink="/exception-desk" routerLinkActive="active" data-testid="nav-exception-desk">Exception desk</a>
              }
            </div>
          }
          @if (session.can('CUSTOM_FIELD_READ') || session.can('DEMO_RESET') || session.can('WEBHOOK_READ')) {
            <div class="group">
              <h6>Settings</h6>
              @if (session.can('CUSTOM_FIELD_READ')) {
                <a routerLink="/settings/custom-fields" routerLinkActive="active"
                   data-testid="nav-custom-fields">Custom fields</a>
              }
              @if (session.can('DEMO_RESET')) {
                <a routerLink="/settings/demo" routerLinkActive="active" data-testid="nav-demo">Demo data</a>
              }
              @if (session.can('WEBHOOK_READ')) {
                <a routerLink="/settings/webhooks" routerLinkActive="active" data-testid="nav-webhooks">Webhooks</a>
              }
            </div>
          }
          <!-- Administration -->
          @if (session.can('ACCESS_POLICY_MANAGE') || session.can('VEHICLE_READ') || session.can('CREDIT_LIMIT_REQUEST')
               || session.can('CREDIT_LIMIT_APPROVE') || session.can('NUMBER_SERIES_MANAGE')
               || session.can('SETTINGS_READ') || session.can('SETTINGS_MANAGE') || session.can('ORG_UNIT_MANAGE')) {
            <div class="group" data-testid="nav-admin">
              <h6>Administration</h6>
              @if (session.can('ACCESS_POLICY_MANAGE')) {
                <a routerLink="/admin/users" routerLinkActive="active" data-testid="nav-admin-users">Users</a>
                <a routerLink="/admin/access-policies" routerLinkActive="active"
                   data-testid="nav-admin-policies">Access policies</a>
              }
              @if (session.can('VEHICLE_READ')) {
                <a routerLink="/admin/compliance" routerLinkActive="active" data-testid="nav-admin-compliance">Compliance</a>
              }
              @if (session.can('CREDIT_LIMIT_REQUEST') || session.can('CREDIT_LIMIT_APPROVE')) {
                <a routerLink="/admin/credit-limits" routerLinkActive="active" data-testid="nav-admin-credit">Credit limits</a>
              }
              @if (session.can('NUMBER_SERIES_MANAGE')) {
                <a routerLink="/admin/number-series" routerLinkActive="active"
                   data-testid="nav-admin-number-series">Number series</a>
              }
              @if (session.can('ORG_UNIT_MANAGE')) {
                <a routerLink="/admin/org-units" routerLinkActive="active"
                   data-testid="nav-admin-org-units">Organisation units</a>
              }
              @if (session.can('SETTINGS_READ') || session.can('SETTINGS_MANAGE')) {
                <a routerLink="/admin/settings" routerLinkActive="active"
                   data-testid="nav-admin-settings">Profile settings</a>
              }
            </div>
          }
        </nav>
      </aside>
      <div class="main">
        <header class="topbar">
          <a routerLink="/me" routerLinkActive="active" data-testid="nav-me">My profile</a>
          <span class="muted" data-testid="who">{{ session.permissionCount() }} permissions</span>
          <button class="link" (click)="signOut()" data-testid="sign-out">Sign out</button>
        </header>
        <main><router-outlet /></main>
      </div>
    </div>
  `,
})
export class Shell {
  protected readonly session = inject(Session);
  private readonly router = inject(Router);
  readonly groups = GROUPS;

  inGroup(group: string) {
    return RESOURCES.filter((r) => r.group === group && (!r.permission || this.session.can(r.permission)));
  }

  signOut(): void {
    this.session.end();
    resetDisplay();
    this.router.navigateByUrl('/login');
  }
}
