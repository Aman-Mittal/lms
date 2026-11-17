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
import { Component, effect, inject, input, signal } from '@angular/core';
import { LmsGateway, Row } from '../gateway/lms-gateway';
import { ConfigActions } from './config-actions';
import { StatusFilter } from './status-filter';

/**
 * The vendors on a routing guide, in rank order, with the status filter (the active ones by default) and a Deactivate
 * or Reactivate button on each. Allocation skips an inactive vendor and offers to the next rank; a vendor holding an
 * unanswered offer on the guide refuses to switch off.
 */
@Component({
  selector: 'app-guide-vendors',
  imports: [ConfigActions, StatusFilter],
  template: `
    <app-status-filter [value]="status()" (valueChange)="setStatus($event)" />
    @if (loaded() && rows().length === 0) {
      <p class="muted" data-testid="no-vendors">{{ status() ? 'No vendor matches.' : 'No vendor on this guide yet.' }}</p>
    } @else {
      <div class="table-wrap"><table>
        <thead><tr><th>Rank</th><th>Vendor</th><th>Answers within (min)</th><th>Agreed rate</th><th>Status</th><th></th></tr></thead>
        <tbody>
          @for (e of rows(); track e['id']) {
            <tr data-testid="guide-vendor" [attr.data-id]="e['id']">
              <td>{{ e['rankNo'] }}</td><td>{{ e['vendorName'] }}</td><td>{{ e['responseSlaMinutes'] }}</td>
              <td>{{ e['agreedRate'] }}</td><td>{{ e['active'] ? 'Active' : 'Inactive' }}</td>
              <td>
                @if (manage()) {
                  <app-config-actions kind="guideVendors" [recordId]="e['id']" [parentId]="guideId()" [active]="!!e['active']"
                                      [label]="e['vendorName']" [version]="e['version']" (changed)="load()" />
                }
              </td>
            </tr>
          }
        </tbody>
      </table></div>
    }
  `,
})
export class GuideVendors {
  private readonly gateway = inject(LmsGateway);

  readonly guideId = input.required<string>();
  readonly manage = input(false);
  /** Bumped by the guide's panel when it changes the guide, so the vendors follow. */
  readonly tick = input(0);

  protected readonly rows = signal<Row[]>([]);
  protected readonly loaded = signal(false);
  protected readonly status = signal('');

  constructor() {
    effect(() => {
      this.guideId();
      this.tick();
      void this.load();
    });
  }

  protected setStatus(value: string): void {
    this.status.set(value);
    void this.load();
  }

  protected async load(): Promise<void> {
    const detail = await this.gateway.routingGuide(this.guideId(), this.status());
    this.rows.set(detail.entries);
    this.loaded.set(true);
  }
}
