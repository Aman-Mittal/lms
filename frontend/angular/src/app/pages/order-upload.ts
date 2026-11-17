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

import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiProblem, LmsGateway } from '../gateway/lms-gateway';
import { UploadOutcome } from '../gateway/enquiry-types';
import { Session } from '../session';
import { EntityPicker } from './entity-picker';
import { PageHeader } from './page-header';
import { StatusBadge } from './status-badge';
import { ErrorBanner } from './error-banner';

/**
 * Orders from a spreadsheet: choose a customer and a CSV or XLSX file, and see what became of every row.
 * Rows that share an order reference make one order; a row that cannot be read fails its own order only.
 * Sending the same file again creates nothing, and says so row by row.
 */
@Component({
  selector: 'app-order-upload',
  imports: [ErrorBanner, FormsModule, EntityPicker, PageHeader, StatusBadge],
  template: `
    <app-page-header title="Bulk order upload" />
    <p class="muted">
      One row is one order line. Columns are the customer's own where a column mapping is set, else
      order_ref, origin, destination, pickup_date, delivery_date, load_type, material_code, material, quantity,
      uom, weight_kg and consignee.
    </p>
    <form ngNativeValidate class="inline" (ngSubmit)="send()" data-testid="upload-form">
      <app-entity-picker [kind]="'customer'" placeholder="Customer" ariaLabel="Customer of the file" testId="upload-customer"
                         [(value)]="customer" [required]="true" />
      <input type="file" name="file" accept=".csv,.xlsx" aria-label="Order file" data-testid="upload-file"
             (change)="choose($any($event.target).files)" />
      <button type="submit" data-testid="upload-submit" [disabled]="busy()">Upload</button>
    </form>
    @if (error()) { <app-error-banner [message]="error()" testId="upload-error" /> }
    @if (outcome(); as o) {
      <p role="status" data-testid="upload-summary">
        <span data-testid="upload-created">{{ o.created }}</span> created,
        <span data-testid="upload-duplicates">{{ o.duplicates }}</span> already there,
        <span data-testid="upload-failed">{{ o.failed }}</span> failed.
      </p>
      <div class="table-wrap"><table data-testid="upload-rows">
        <thead><tr><th>Row</th><th>Order reference</th><th>Result</th><th>Order</th><th>Reasons</th></tr></thead>
        <tbody>
          @for (r of o.rows; track r.row) {
            <tr data-testid="upload-row" [attr.data-id]="r.row">
              <td data-testid="upload-row-number">{{ r.row }}</td>
              <td data-testid="upload-row-ref">{{ r.orderRef }}</td>
              <td><app-status-badge data-testid="upload-row-status" [value]="r.status" /></td>
              <td data-testid="upload-row-order" [attr.data-id]="r.orderId">{{ r.orderId ? 'Made' : '' }}</td>
              <td data-testid="upload-row-reasons">{{ r.reasons.join('; ') }}</td>
            </tr>
          }
        </tbody>
      </table></div>
    }
  `,
})
export class OrderUpload {
  private readonly gateway = inject(LmsGateway);
  private readonly session = inject(Session);

  readonly customer = signal('');
  readonly busy = signal(false);
  readonly error = signal('');
  readonly outcome = signal<UploadOutcome | null>(null);
  private file: File | null = null;

  choose(files: FileList | null): void {
    this.file = files && files.length > 0 ? files[0] : null;
  }

  async send(): Promise<void> {
    const orgUnitId = this.session.orgUnitId();
    if (!this.file || !orgUnitId) {
      this.error.set('Choose a file to upload.');
      return;
    }
    this.busy.set(true);
    this.error.set('');
    this.outcome.set(null);
    try {
      this.outcome.set(await this.gateway.uploadOrderFile(orgUnitId, this.customer(), this.file));
    } catch (e) {
      const problem = e as ApiProblem;
      this.error.set(problem.code ? `${problem.message} (${problem.code})` : problem.message);
    } finally {
      this.busy.set(false);
    }
  }
}
