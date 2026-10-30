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
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DeliveryProofView, LmsGateway, OtpIssued } from '../gateway/lms-gateway';
import { Session } from '../session';
import { Qr } from './qr';

/** The delivery desk: issue a one-time code, record the proof, print the slip. */
@Component({
  selector: 'app-delivery-desk',
  imports: [FormsModule, Qr],
  template: `
    <h1>Delivery desk</h1>
    <p class="muted">Issue the consignee's one-time code, then record the proof when they give it back.</p>
    <form class="inline" (ngSubmit)="lookUp()" data-testid="lookup">
      <input name="consignmentId" placeholder="Consignment id" aria-label="Consignment id"
             data-testid="consignment-id" [(ngModel)]="typed" required />
      <button type="submit">Look up</button>
    </form>
    @if (loadError()) { <p class="error" role="alert">{{ loadError() }}</p> }
    @if (consignmentId() && loaded()) {
      <section data-testid="proof">
        @if (proof() === null) {
          <p class="muted" data-testid="no-proof">No code has been issued for this consignment yet.</p>
        } @else if (proof(); as p) {
          <dl>
            <div><dt>Proof</dt><dd data-testid="proof-status">{{ p.status }}</dd></div>
            @if (confirmed()) {
              <div><dt>Received by</dt><dd data-testid="proof-recipient">{{ p.recipientName }}</dd></div>
              <div><dt>Photo</dt><dd>{{ p.photoRef || '—' }}</dd></div>
              <div><dt>Delivered</dt><dd>{{ p.deliveredAt }}</dd></div>
            } @else {
              <div><dt>Code valid until</dt><dd>{{ p.otpExpiresAt }}</dd></div>
            }
          </dl>
        }
        @if (error()) { <p class="error" role="alert">{{ error() }}</p> }
        @if (record() && !confirmed()) {
          <p><button data-testid="issue-otp" (click)="issue()">Issue a code</button></p>
          @if (issued(); as i) {
            <p data-testid="otp-issued">
              Code issued, valid until {{ i.expiresAt }}.
              @if (i.otp) { Demo code: <strong data-testid="otp-code">{{ i.otp }}</strong> }
            </p>
          }
          @if (proof() !== null) {
            <form class="inline" (ngSubmit)="submit()" data-testid="record-proof">
              <input name="otp" placeholder="Code" aria-label="Code" data-testid="proof-otp"
                     [(ngModel)]="otp" required />
              <input name="recipient" placeholder="Recipient name" aria-label="Recipient name"
                     [(ngModel)]="recipient" required />
              <input name="photo" placeholder="Photo reference" aria-label="Photo reference"
                     [(ngModel)]="photo" />
              <button type="submit">Record proof</button>
            </form>
          }
        }
        @if (confirmed() && proof(); as p) {
          <p><button class="link" data-testid="print-proof" (click)="slip.set(true)">Print slip</button></p>
          @if (slip()) {
            <section class="slip" data-testid="proof-slip" aria-label="Proof of delivery slip">
              <h3>Proof of delivery</h3>
              <div class="slip-body">
                <dl>
                  <div><dt>Consignment</dt><dd>{{ p.consignmentId }}</dd></div>
                  <div><dt>Received by</dt><dd>{{ p.recipientName }}</dd></div>
                  <div><dt>Delivered</dt><dd>{{ p.deliveredAt }}</dd></div>
                  <div><dt>Photo</dt><dd>{{ p.photoRef || '—' }}</dd></div>
                </dl>
                <app-qr [payload]="'LMS:CONSIGNMENT:' + p.consignmentId" />
              </div>
              <p class="actions no-print">
                <button data-testid="print-now" (click)="print()">Print</button>
              </p>
            </section>
          }
        }
      </section>
    }
  `,
})
export class DeliveryDesk {
  private readonly gateway = inject(LmsGateway);
  private readonly session = inject(Session);

  readonly consignmentId = signal('');
  readonly proof = signal<DeliveryProofView | null>(null);
  readonly loaded = signal(false);
  readonly loadError = signal('');
  readonly issued = signal<OtpIssued | null>(null);
  readonly error = signal('');
  readonly slip = signal(false);
  readonly record = computed(() => this.session.can('POD_RECORD'));
  readonly confirmed = computed(() => this.proof()?.status === 'CONFIRMED');

  typed = '';
  otp = '';
  recipient = '';
  photo = '';

  async lookUp(): Promise<void> {
    this.consignmentId.set(this.typed.trim());
    this.issued.set(null);
    this.slip.set(false);
    await this.refresh();
  }

  async issue(): Promise<void> {
    this.error.set('');
    try {
      this.issued.set(await this.gateway.issueDeliveryOtp(this.consignmentId()));
      await this.refresh();
    } catch (e) {
      this.error.set((e as Error).message);
    }
  }

  async submit(): Promise<void> {
    this.error.set('');
    try {
      await this.gateway.recordDeliveryProof(this.consignmentId(),
        { otp: this.otp, recipientName: this.recipient, photoRef: this.photo });
      await this.refresh();
    } catch (e) {
      this.error.set((e as Error).message);
    }
  }

  print(): void {
    window.print();
  }

  private async refresh(): Promise<void> {
    this.loadError.set('');
    try {
      this.proof.set(await this.gateway.deliveryProof(this.consignmentId()));
      this.loaded.set(true);
    } catch (e) {
      this.loaded.set(false);
      this.loadError.set((e as Error).message);
    }
  }
}
