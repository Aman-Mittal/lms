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

const yes = (row: Row) => row['active'] !== 'false';

/**
 * The warehouse's bins and the tenant's SKUs, each with its own status filter (the active ones by default) and a
 * Deactivate or Reactivate button on every row. A bin holding stock, or a SKU still on an open ASN or order, refuses to
 * switch off and says why; an inactive one takes no stock and cannot be ordered or received.
 */
@Component({
  selector: 'app-wh-setup-lists',
  imports: [ConfigActions, StatusFilter],
  template: `
    <h2>Bins</h2>
    <app-status-filter testId="bin-status-filter" [value]="binStatus()" (valueChange)="setBins($event)" />
    @if (loaded() && bins().length === 0) {
      <p class="muted" data-testid="no-bins">{{ binStatus() ? 'No bins match.' : 'This warehouse has no bins yet.' }}</p>
    } @else {
      <div class="table-scroll"><table>
        <thead><tr><th>Bin</th><th>Capacity</th><th>Occupied</th><th>Status</th><th></th></tr></thead>
        <tbody>
          @for (b of bins(); track b['id']) {
            <tr data-testid="bin-row" [attr.data-id]="b['id']">
              <td>{{ b['code'] }}</td><td>{{ b['capacityUnits'] }}</td><td>{{ b['occupiedUnits'] }}</td>
              <td>{{ active(b) ? 'Active' : 'Inactive' }}</td>
              <td>
                <app-config-actions kind="bins" [recordId]="b['id']" [parentId]="warehouseId()" [active]="active(b)"
                                    [label]="'bin ' + b['code']" [version]="b['version']" (changed)="load()" />
              </td>
            </tr>
          }
        </tbody>
      </table></div>
    }
    <ng-content select="[bins]" />
    <h2>SKUs</h2>
    <app-status-filter testId="sku-status-filter" [value]="skuStatus()" (valueChange)="setSkus($event)" />
    <div class="table-scroll"><table>
      <thead><tr><th>SKU</th><th>Description</th><th>Unit</th><th>Status</th><th></th></tr></thead>
      <tbody>
        @for (s of skus(); track s['id']) {
          <tr data-testid="sku-row" [attr.data-id]="s['id']">
            <td>{{ s['code'] }}</td><td>{{ s['description'] }}</td><td>{{ s['uom'] }}</td>
            <td>{{ active(s) ? 'Active' : 'Inactive' }}</td>
            <td>
              <app-config-actions kind="skus" [recordId]="s['id']" [active]="active(s)" [label]="'SKU ' + s['code']"
                                  [version]="s['version']" (changed)="load()" />
            </td>
          </tr>
        }
      </tbody>
    </table></div>
    <ng-content select="[skus]" />
  `,
})
export class WarehouseSetupLists {
  private readonly gateway = inject(LmsGateway);

  readonly warehouseId = input.required<string>();
  /** Bumped by the desk when it reads the warehouse again, so these lists follow. */
  readonly tick = input(0);

  protected readonly bins = signal<Row[]>([]);
  protected readonly skus = signal<Row[]>([]);
  protected readonly loaded = signal(false);
  protected readonly binStatus = signal('');
  protected readonly skuStatus = signal('');
  protected readonly active = yes;

  constructor() {
    effect(() => {
      this.warehouseId();
      this.tick();
      void this.load();
    });
  }

  protected setBins(value: string): void {
    this.binStatus.set(value);
    void this.load();
  }

  protected setSkus(value: string): void {
    this.skuStatus.set(value);
    void this.load();
  }

  protected async load(): Promise<void> {
    const [bins, skus] = await Promise.all([
      this.gateway.warehouseBins(this.warehouseId(), this.binStatus()), this.gateway.skus(this.skuStatus()),
    ]);
    this.bins.set(bins);
    this.skus.set(skus);
    this.loaded.set(true);
  }
}
