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
              @if (group === 'Operations' && session.can('TERMINAL_READ')) {
                <a routerLink="/map" routerLinkActive="active" data-testid="nav-map">Network map</a>
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
              @if (group === 'Operations' && session.can('ALLOCATION_RULE_READ')) {
                <a routerLink="/allocation-rules" routerLinkActive="active" data-testid="nav-allocation-rules">Allocation rules</a>
              }
              @if (group === 'Delivery' && session.can('POD_READ')) {
                <a routerLink="/delivery" routerLinkActive="active" data-testid="nav-delivery">Delivery desk</a>
              }
              @if (group === 'Delivery' && session.can('TRACKING_LINK_MANAGE')) {
                <a routerLink="/tracking" routerLinkActive="active" data-testid="nav-tracking">Tracking links</a>
              }
            </div>
          }
          @if (session.can('CUSTOM_FIELD_READ') || session.can('DEMO_RESET')) {
            <div class="group">
              <h6>Settings</h6>
              @if (session.can('CUSTOM_FIELD_READ')) {
                <a routerLink="/settings/custom-fields" routerLinkActive="active"
                   data-testid="nav-custom-fields">Custom fields</a>
              }
              @if (session.can('DEMO_RESET')) {
                <a routerLink="/settings/demo" routerLinkActive="active" data-testid="nav-demo">Demo data</a>
              }
            </div>
          }
        </nav>
      </aside>
      <div class="main">
        <header class="topbar">
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
    this.router.navigateByUrl('/login');
  }
}
