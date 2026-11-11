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
import { DeliveryProofView, GOODS_CONDITIONS, GoodsCondition, LmsGateway, OtpIssued } from '../gateway/lms-gateway';
import { Session } from '../session';
import { Qr } from './qr';
import { EntityPicker } from './entity-picker';

/** The delivery desk: issue a one-time code, record the proof, print the slip. */
@Component({
  selector: 'app-delivery-desk',
  imports: [FormsModule, Qr, EntityPicker],
  template: `
    <h1>Delivery desk</h1>
    <p class="muted">Issue the consignee's one-time code, then record the proof when they give it back.</p>
    <!-- A signal, not ngModel: ngModel connects after the screen is shown, and would wipe an id typed before it. -->
    <form class="inline" (ngSubmit)="lookUp()" data-testid="lookup">
      <app-entity-picker [kind]="'consignment'" placeholder="Consignment" ariaLabel="Consignment id" testId="consignment-id" [required]="true"
                         [(value)]="typed" />
      <button type="submit">Look up</button>
    </form>
    @if (loadError()) { <p class="error" role="alert">{{ loadError() }}</p> }
    @if (consignmentId() && loaded()) {
      <section data-testid="proof">
        @if (consignmentStatus()) {
          <p data-testid="delivery-consignment-status">Consignment: <strong>{{ consignmentStatus() }}</strong></p>
        }
        @if (proof() === null) {
          <p class="muted" data-testid="no-proof">No code has been issued for this consignment yet.</p>
        } @else if (proof(); as p) {
          <dl>
            <div><dt>Proof</dt><dd data-testid="proof-status">{{ p.status }}</dd></div>
            @if (confirmed()) {
              <div><dt>Received by</dt><dd data-testid="proof-recipient">{{ p.recipientName }}</dd></div>
              <div><dt>Photo</dt><dd>{{ p.photoRef || '—' }}</dd></div>
              <div><dt>Delivered</dt><dd>{{ p.deliveredAt }}</dd></div>
              <div><dt>Goods</dt><dd data-testid="proof-condition">{{ p.goodsCondition }}</dd></div>
              @if (p.remark) { <div><dt>Remark</dt><dd data-testid="proof-remark">{{ p.remark }}</dd></div> }
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
              <select aria-label="Goods received" data-testid="proof-condition-input" [value]="condition()"
                      (change)="condition.set($any($event.target).value)">
                @for (c of conditions; track c) { <option [value]="c" [selected]="c === condition()">{{ c }}</option> }
              </select>
              <input placeholder="Remark: what was short or damaged" aria-label="Remark" data-testid="proof-remark-input"
                     [value]="remark()" (input)="remark.set($any($event.target).value)" [required]="condition() !== 'CLEAN'" />
              <button type="submit">Record proof</button>
            </form>
          }
        }
        @if (confirmed() && reverse()) {
          <form class="inline" (ngSubmit)="askReversal()" data-testid="reverse-proof">
            <input name="reversalReason" placeholder="Why the proof is wrong" aria-label="Reason to reverse"
                   data-testid="reversal-reason" [value]="reversalReason()"
                   (input)="reversalReason.set($any($event.target).value)" required />
            <button type="submit">Reverse proof</button>
          </form>
        }
        @if (reversal()) { <p class="ok" role="status" data-testid="reversal-requested">{{ reversal() }}</p> }
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
                  <div><dt>Goods</dt><dd>{{ p.goodsCondition }}@if (p.remark) { — {{ p.remark }}}</dd></div>
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
  /** A proof is reversed only through an approval; asking needs its own right. */
  readonly reverse = computed(() => this.session.can('POD_REVERSE'));
  readonly reversalReason = signal('');
  readonly reversal = signal('');
  readonly confirmed = computed(() => this.proof()?.status === 'CONFIRMED');
  readonly conditions = GOODS_CONDITIONS;
  readonly condition = signal<GoodsCondition>('CLEAN');
  readonly remark = signal('');
  /** What the proof did to the consignment; empty when it cannot be read. */
  readonly consignmentStatus = signal('');

  readonly typed = signal('');
  otp = '';
  recipient = '';
  photo = '';

  async lookUp(): Promise<void> {
    this.consignmentId.set(this.typed().trim());
    this.issued.set(null);
    this.slip.set(false);
    this.reversal.set('');
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
      await this.gateway.recordDeliveryProof(this.consignmentId(), {
        otp: this.otp, recipientName: this.recipient, photoRef: this.photo,
        goodsCondition: this.condition(), remark: this.remark(),
      });
      await this.refresh();
    } catch (e) {
      this.error.set((e as Error).message);
    }
  }

  async askReversal(): Promise<void> {
    this.error.set('');
    this.reversal.set('');
    try {
      await this.gateway.requestProofReversal(this.consignmentId(), this.reversalReason().trim());
      this.reversal.set('Reversal requested: awaiting approval.');
      this.reversalReason.set('');
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
      // Best effort: a caller who may prove a delivery but not read consignments still has the desk.
      this.consignmentStatus.set(await this.gateway.consignment(this.consignmentId()).then((c) => c.status, () => ''));
    } catch (e) {
      this.loaded.set(false);
      this.loadError.set((e as Error).message);
    }
  }
}
