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

import { Injectable, computed, signal } from '@angular/core';
import { SignedIn } from './gateway/lms-gateway';

/**
 * Who is signed in. Held in memory only: this is a demo, and a token left in
 * localStorage is a token any script on the page can read.
 */
@Injectable({ providedIn: 'root' })
export class Session {
  private readonly auth = signal<SignedIn | null>(null);

  readonly signedIn = computed(() => this.auth() !== null);
  readonly token = computed(() => this.auth()?.token ?? null);
  readonly orgUnitId = computed(() => this.auth()?.orgUnitId ?? null);
  readonly permissions = computed(() => this.auth()?.permissions ?? []);
  readonly permissionCount = computed(() => this.permissions().length);

  /** Whether the signed-in caller holds a permission. */
  can(permission: string): boolean {
    return this.permissions().includes(permission);
  }

  start(result: SignedIn): void {
    this.auth.set(result);
  }

  end(): void {
    this.auth.set(null);
  }
}
