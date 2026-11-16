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

import { Component, ElementRef, HostListener, computed, effect, inject, signal, viewChild } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet, NavigationEnd } from '@angular/router';
import { Session } from '../session';
import { resetDisplay } from '../display';
import {
  DRAWER_QUERY, activeGroupId, groupOpen, isNarrow, loadGroupState, saveGroupState, visibleNav,
} from '../../../../shared/nav';

/** The sidebar: its groups come from shared/nav.ts, so both consoles offer the same links in the same order. */
@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <div class="app">
      <aside id="sidebar" class="sidebar" [class.open]="drawerOpen()" data-testid="sidebar">
        <div class="sidebar-head">
          <div class="brand">LMS</div>
          <button type="button" class="icon-button nav-close" aria-label="Close navigation" data-testid="nav-close"
                  (click)="closeDrawer(true)">
            <svg class="icon" viewBox="0 0 24 24" aria-hidden="true"><path d="M6 6l12 12M18 6L6 18" /></svg>
          </button>
        </div>
        <input #filter type="search" class="nav-filter" placeholder="Filter menu" aria-label="Filter menu"
               data-testid="nav-filter" autocomplete="off" [value]="query()"
               (input)="query.set($any($event.target).value)" />
        <nav aria-label="Main">
          @for (g of groups(); track g.id) {
            <div class="group" [attr.data-testid]="'nav-group-' + g.id">
              <button type="button" class="group-toggle" [attr.aria-expanded]="isOpen(g.id)"
                      [attr.aria-controls]="'nav-items-' + g.id" [attr.data-testid]="'nav-toggle-' + g.id"
                      (click)="toggle(g.id)">
                <svg class="icon" viewBox="0 0 24 24" aria-hidden="true"><path d="M6 9l6 6 6-6" /></svg>
                <span class="label">{{ g.label }}</span>
              </button>
              <div class="group-items" [id]="'nav-items-' + g.id" [hidden]="!isOpen(g.id)">
                @for (s of g.sections; track $index) {
                  <div [attr.data-testid]="s.testid ?? null">
                    @if (s.label && g.sections.length > 1) { <div class="section-label">{{ s.label }}</div> }
                    @for (i of s.items; track i.key) {
                      <a [routerLink]="i.route" routerLinkActive="active"
                         [routerLinkActiveOptions]="{ exact: i.route === '/' }"
                         [attr.data-testid]="'nav-' + i.key">{{ i.label }}</a>
                    }
                  </div>
                }
              </div>
            </div>
          } @empty {
            <p class="nav-empty" role="status">No menu entry matches.</p>
          }
        </nav>
      </aside>
      <div class="nav-backdrop" [class.open]="drawerOpen()" data-testid="nav-backdrop" aria-hidden="true"
           (click)="closeDrawer(true)"></div>
      <div class="main">
        <header class="topbar">
          <button #menuButton type="button" class="icon-button nav-toggle" aria-label="Open navigation"
                  aria-controls="sidebar" [attr.aria-expanded]="drawerOpen()" data-testid="nav-toggle"
                  (click)="openDrawer()">
            <svg class="icon" viewBox="0 0 24 24" aria-hidden="true"><path d="M4 6h16M4 12h16M4 18h16" /></svg>
          </button>
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
  private readonly filter = viewChild<ElementRef<HTMLInputElement>>('filter');
  private readonly toggleButton = viewChild<ElementRef<HTMLButtonElement>>('menuButton');

  protected readonly query = signal('');
  protected readonly drawerOpen = signal(false);
  private readonly narrow = signal(isNarrow());
  private readonly state = signal(loadGroupState());
  private readonly path = signal(this.router.url);
  private readonly active = computed(() => activeGroupId(this.path()));
  protected readonly groups = computed(() => visibleNav((p) => this.session.can(p), this.query()));

  constructor() {
    this.router.events.subscribe((e) => {
      if (e instanceof NavigationEnd) {
        this.path.set(e.urlAfterRedirects);
        this.closeDrawer(false);
      }
    });
    // The group holding the page being shown is opened, whatever was remembered.
    effect(() => {
      const id = this.active();
      if (id && !groupOpen(this.state(), id, id, this.narrow(), false)) {
        this.setOpen(id, true);
      }
    });
    try {
      const media = matchMedia(DRAWER_QUERY);
      media.addEventListener('change', (e) => {
        this.narrow.set(e.matches);
        if (!e.matches) {
          this.drawerOpen.set(false);
        }
      });
    } catch {
      // no matchMedia: the layout simply stays as it was
    }
  }

  protected isOpen(id: string): boolean {
    return groupOpen(this.state(), id, this.active(), this.narrow(), this.query().trim() !== '');
  }

  protected toggle(id: string): void {
    this.setOpen(id, !this.isOpen(id));
  }

  private setOpen(id: string, open: boolean): void {
    const next = { ...this.state(), [id]: open };
    this.state.set(next);
    saveGroupState(next);
  }

  protected openDrawer(): void {
    this.drawerOpen.set(true);
    setTimeout(() => this.filter()?.nativeElement.focus());
  }

  /** `refocus`: put the focus back on the menu button, where the person opened it from. */
  protected closeDrawer(refocus: boolean): void {
    if (this.drawerOpen()) {
      this.drawerOpen.set(false);
      if (refocus) {
        this.toggleButton()?.nativeElement.focus();
      }
    }
  }

  @HostListener('document:keydown.escape')
  protected onEscape(): void {
    this.closeDrawer(true);
  }

  signOut(): void {
    this.session.end();
    resetDisplay();
    this.router.navigateByUrl('/login');
  }
}
