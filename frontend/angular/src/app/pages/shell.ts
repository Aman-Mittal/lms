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
import { RESOURCES } from './resources';

@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <header>
      <strong>LMS demo</strong>
      <nav>
        <a routerLink="/" routerLinkActive="active" [routerLinkActiveOptions]="{ exact: true }">Control tower</a>
        @for (r of resources; track r.key) {
          <a [routerLink]="['/', r.key]" routerLinkActive="active">{{ r.title }}</a>
        }
      </nav>
      <button class="link" (click)="signOut()">Sign out</button>
    </header>
    <main><router-outlet /></main>
  `,
})
export class Shell {
  private readonly session = inject(Session);
  private readonly router = inject(Router);
  readonly resources = RESOURCES;

  signOut(): void {
    this.session.end();
    this.router.navigateByUrl('/login');
  }
}
