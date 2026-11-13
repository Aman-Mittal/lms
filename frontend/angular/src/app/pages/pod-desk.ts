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
import {
  ApiProblem, DeliveryProofView, LmsGateway, POD_DOCUMENT_TYPES, PodDocument, PodDocumentType, PodLineDraft, provesDelivery,
} from '../gateway/lms-gateway';
import { Session } from '../session';

/** A refusal as the desk shows it: what the server said, and its problem code. */
function problemText(e: unknown): string {
  const problem = e as ApiProblem;
  return problem.code ? `${problem.message} (${problem.code})` : problem.message;
}

/** What is typed against one order line; blank is not fed. */
interface LineFields {
  received: string;
  short: string;
  damaged: string;
  excess: string;
}

/**
 * The proof of delivery desk: start a consignment's draft, feed it with what
 * was received against each order line, list its documents and unloading
 * times, then submit it with the consignee's code or key it from paper. A
 * submitted proof is changed only by asking for its reversal.
 */
@Component({
  selector: 'app-pod-desk',
  imports: [FormsModule],
  template: `
    <h1>Proof of delivery desk</h1>
    <p class="muted">Start the proof, feed it line by line, list the documents and times, then submit it.</p>
    <form class="inline" (ngSubmit)="lookUp()" data-testid="pod-lookup">
      <input name="consignment" placeholder="Consignment id" aria-label="Consignment id" data-testid="pod-consignment-id"
             [ngModel]="typed()" (ngModelChange)="typed.set($event)" required />
      <button type="submit">Look up</button>
    </form>
    @if (error()) { <p class="error" role="alert" data-testid="pod-error">{{ error() }}</p> }
    @if (consignmentId() && loaded()) {
      @if (proof() === null) {
        <p class="muted" data-testid="pod-none">No proof has been started for this consignment.</p>
        @if (record()) { <p><button data-testid="pod-start" (click)="start()">Start proof</button></p> }
      } @else if (proof(); as p) {
        <section data-testid="pod-proof">
          <dl>
            <div><dt>Proof</dt><dd data-testid="pod-status">{{ p.status }}</dd></div>
            @if (p.paper) { <div><dt>Keyed from paper</dt><dd data-testid="pod-paper">Yes</dd></div> }
            @if (p.keyedBy) { <div><dt>Keyed by</dt><dd data-testid="pod-keyed-by">{{ p.keyedBy }}</dd></div> }
            @if (proves()) { <div><dt>Received by</dt><dd data-testid="pod-recipient-shown">{{ p.recipientName }}</dd></div> }
            @if (p.remark) { <div><dt>Remark</dt><dd data-testid="pod-remark-shown">{{ p.remark }}</dd></div> }
          </dl>

          <h2>Lines</h2>
          <table data-testid="pod-lines">
            <thead><tr><th>Line</th><th>Material</th><th>Gross</th><th>Received</th><th>Short</th><th>Damaged</th><th>Excess</th></tr></thead>
            <tbody>
              @for (l of p.lines; track l.orderLineId) {
                <tr data-testid="pod-line-row" [attr.data-id]="l.orderLineId">
                  <td>{{ l.lineNo }}</td>
                  <td>{{ l.materialDescription }}</td>
                  <td data-testid="pod-line-gross">{{ l.grossQty }} {{ l.uom }}</td>
                  <td><input type="text" inputmode="decimal" aria-label="Received" data-testid="pod-line-received" [disabled]="!editable()"
                             [value]="field(l.orderLineId).received" (input)="set(l.orderLineId, 'received', $any($event.target).value)" /></td>
                  <td><input type="text" inputmode="decimal" aria-label="Short" data-testid="pod-line-short" [disabled]="!editable()"
                             [value]="field(l.orderLineId).short" (input)="set(l.orderLineId, 'short', $any($event.target).value)" /></td>
                  <td><input type="text" inputmode="decimal" aria-label="Damaged" data-testid="pod-line-damaged" [disabled]="!editable()"
                             [value]="field(l.orderLineId).damaged" (input)="set(l.orderLineId, 'damaged', $any($event.target).value)" /></td>
                  <td><input type="text" inputmode="decimal" aria-label="Excess" data-testid="pod-line-excess" [disabled]="!editable()"
                             [value]="field(l.orderLineId).excess" (input)="set(l.orderLineId, 'excess', $any($event.target).value)" /></td>
                </tr>
              }
            </tbody>
          </table>
          @if (editable()) { <p><button data-testid="pod-save-lines" (click)="saveLines()">Save lines</button></p> }

          <h2>Documents</h2>
          <ul data-testid="pod-documents">
            @for (d of p.documents; track $index) {
              <li data-testid="pod-doc-row"><span data-testid="pod-doc-text">{{ d.type }}: {{ d.reference }}</span>
                @if (editable()) { <button class="link" data-testid="pod-doc-remove" (click)="removeDocument($index)">Remove</button> }
              </li>
            }
          </ul>
          @if (editable()) {
            <form class="inline" (ngSubmit)="addDocument()" data-testid="pod-doc-form">
              <select aria-label="Document type" data-testid="pod-doc-type" [value]="docType()"
                      (change)="docType.set($any($event.target).value)">
                @for (t of documentTypes; track t) { <option [value]="t" [selected]="t === docType()">{{ t }}</option> }
              </select>
              <input name="docRef" placeholder="Reference" aria-label="Document reference" data-testid="pod-doc-ref"
                     [ngModel]="docRef()" (ngModelChange)="docRef.set($event)" required />
              <button type="submit" data-testid="pod-doc-add">Add document</button>
            </form>
          }

          <h2>Times</h2>
          @if (editable()) {
            <form class="inline" (ngSubmit)="saveTimings()" data-testid="pod-timings">
              <label>Reported <input type="datetime-local" data-testid="pod-reported-at" [value]="reportedAt()"
                                     (input)="reportedAt.set($any($event.target).value)" /></label>
              <label>Unloading started <input type="datetime-local" data-testid="pod-unloading-start" [value]="startedAt()"
                                              (input)="startedAt.set($any($event.target).value)" /></label>
              <label>Unloading ended <input type="datetime-local" data-testid="pod-unloading-end" [value]="endedAt()"
                                            (input)="endedAt.set($any($event.target).value)" /></label>
              <button type="submit" data-testid="pod-save-timings">Save times</button>
            </form>
            @if (timingsSaved()) { <p class="ok" role="status" data-testid="pod-timings-saved">Times saved.</p> }
          } @else {
            <p data-testid="pod-timings-shown">{{ p.reportedAt || '—' }} / {{ p.unloadingStartedAt || '—' }} / {{ p.unloadingEndedAt || '—' }}</p>
          }

          @if (editable()) {
            <h2>Submit</h2>
            <p><button data-testid="pod-issue-otp" (click)="issue()">Issue a code</button>
              @if (otpShown()) { <span data-testid="pod-otp-code">{{ otpShown() }}</span> }</p>
            <form class="inline" (ngSubmit)="submit()" data-testid="pod-submit-form">
              <input name="otp" placeholder="Code" aria-label="Code" data-testid="pod-otp" [ngModel]="otp()" (ngModelChange)="otp.set($event)" required />
              <input name="recipient" placeholder="Recipient name" aria-label="Recipient name" data-testid="pod-recipient"
                     [ngModel]="recipient()" (ngModelChange)="recipient.set($event)" required />
              <input name="remark" placeholder="Remark" aria-label="Remark" data-testid="pod-remark"
                     [ngModel]="remark()" (ngModelChange)="remark.set($event)" />
              <button type="submit" data-testid="pod-submit">Submit proof</button>
            </form>
            @if (paperKey()) {
              <form class="inline" (ngSubmit)="keyPaper()" data-testid="pod-paper-form">
                <input name="paperRecipient" placeholder="Recipient name on the paper" aria-label="Paper recipient name" data-testid="pod-paper-recipient"
                       [ngModel]="paperRecipient()" (ngModelChange)="paperRecipient.set($event)" required />
                <input name="paperRemark" placeholder="Remark" aria-label="Paper remark" data-testid="pod-paper-remark"
                       [ngModel]="paperRemark()" (ngModelChange)="paperRemark.set($event)" />
                <button type="submit" data-testid="pod-paper-submit">Key from paper</button>
              </form>
            }
            <form class="inline" (ngSubmit)="notReceived()" data-testid="pod-not-received-form">
              <input name="notReceivedRemark" placeholder="Why nothing was received" aria-label="Not received remark"
                     data-testid="pod-not-received-remark" [ngModel]="notReceivedRemark()" (ngModelChange)="notReceivedRemark.set($event)" required />
              <button type="submit" data-testid="pod-not-received">Not received</button>
            </form>
          }

          @if (proves() && reverse()) {
            <form class="inline" (ngSubmit)="askReversal()" data-testid="pod-reverse-form">
              <input name="reversalReason" placeholder="Why the proof is wrong" aria-label="Reason to reverse" data-testid="pod-reversal-reason"
                     [ngModel]="reversalReason()" (ngModelChange)="reversalReason.set($event)" required />
              <button type="submit" data-testid="pod-request-reversal">Request reversal</button>
            </form>
          }
          @if (reversal()) { <p class="ok" role="status" data-testid="pod-reversal-requested">{{ reversal() }}</p> }
        </section>
      }
    }
  `,
})
export class PodDesk {
  private readonly gateway = inject(LmsGateway);
  private readonly session = inject(Session);

  readonly typed = signal('');
  readonly consignmentId = signal('');
  readonly proof = signal<DeliveryProofView | null>(null);
  readonly loaded = signal(false);
  readonly error = signal('');
  readonly record = computed(() => this.session.can('POD_RECORD'));
  readonly paperKey = computed(() => this.session.can('POD_PAPER_KEY'));
  readonly reverse = computed(() => this.session.can('POD_REVERSE'));
  readonly proves = computed(() => provesDelivery(this.proof()?.status));
  /** A proof is fed and submitted only while it is a draft, by someone who may record. */
  readonly editable = computed(() => this.record() && !this.proves());

  readonly documentTypes = POD_DOCUMENT_TYPES;
  readonly docType = signal<PodDocumentType>('FRONT');
  readonly docRef = signal('');
  readonly lines = signal<Record<string, LineFields>>({});
  readonly reportedAt = signal('');
  readonly startedAt = signal('');
  readonly endedAt = signal('');
  readonly timingsSaved = signal(false);
  readonly otp = signal('');
  readonly otpShown = signal('');
  readonly recipient = signal('');
  readonly remark = signal('');
  readonly paperRecipient = signal('');
  readonly paperRemark = signal('');
  readonly notReceivedRemark = signal('');
  readonly reversalReason = signal('');
  readonly reversal = signal('');

  async lookUp(): Promise<void> {
    this.consignmentId.set(this.typed().trim());
    this.otpShown.set('');
    this.reversal.set('');
    this.timingsSaved.set(false);
    await this.refresh();
  }

  field(id: string): LineFields {
    return this.lines()[id] ?? { received: '', short: '', damaged: '', excess: '' };
  }

  set(id: string, which: keyof LineFields, value: string): void {
    this.lines.update((all) => ({ ...all, [id]: { ...this.field(id), [which]: value } }));
  }

  start(): Promise<void> {
    return this.act(() => this.gateway.ensureProof(this.consignmentId()));
  }

  saveLines(): Promise<void> {
    const drafts: PodLineDraft[] = (this.proof()?.lines ?? [])
      .map((l) => ({ orderLineId: l.orderLineId, f: this.field(l.orderLineId) }))
      .filter(({ f }) => f.received.trim() || f.short.trim() || f.damaged.trim() || f.excess.trim())
      .map(({ orderLineId, f }) => ({
        orderLineId, receivedQty: f.received, shortQty: f.short, damagedQty: f.damaged, excessQty: f.excess,
      }));
    return this.act(() => this.gateway.feedProofLines(this.consignmentId(), drafts));
  }

  addDocument(): Promise<void> {
    const next: PodDocument[] = [...(this.proof()?.documents ?? []), { type: this.docType(), reference: this.docRef().trim() }];
    return this.act(async () => {
      await this.gateway.setProofDocuments(this.consignmentId(), next);
      this.docRef.set('');
    });
  }

  removeDocument(index: number): Promise<void> {
    const next = (this.proof()?.documents ?? []).filter((_, i) => i !== index);
    return this.act(() => this.gateway.setProofDocuments(this.consignmentId(), next));
  }

  async saveTimings(): Promise<void> {
    this.timingsSaved.set(false);
    await this.act(() => this.gateway.setProofTimings(this.consignmentId(), {
      reportedAt: this.reportedAt(), unloadingStartedAt: this.startedAt(), unloadingEndedAt: this.endedAt(),
    }));
    this.timingsSaved.set(!this.error());
  }

  async issue(): Promise<void> {
    await this.act(async () => {
      const issued = await this.gateway.issueDeliveryOtp(this.consignmentId());
      this.otpShown.set(issued.otp ?? '');
    });
  }

  submit(): Promise<void> {
    return this.act(() => this.gateway.submitProof(this.consignmentId(),
      { otp: this.otp(), recipientName: this.recipient(), remark: this.remark() }));
  }

  keyPaper(): Promise<void> {
    return this.act(() => this.gateway.keyPaperProof(this.consignmentId(),
      { recipientName: this.paperRecipient(), remark: this.paperRemark() }));
  }

  notReceived(): Promise<void> {
    return this.act(() => this.gateway.markProofNotReceived(this.consignmentId(), this.notReceivedRemark().trim()));
  }

  async askReversal(): Promise<void> {
    this.error.set('');
    this.reversal.set('');
    try {
      await this.gateway.requestProofReversal(this.consignmentId(), this.reversalReason().trim());
      this.reversal.set('Reversal requested: awaiting approval.');
      this.reversalReason.set('');
    } catch (e) {
      this.error.set(problemText(e));
    }
  }

  /** Runs a change, then reads the proof again so the desk shows what the server holds. */
  private async act(change: () => Promise<unknown>): Promise<void> {
    this.error.set('');
    try {
      await change();
      await this.refresh();
    } catch (e) {
      this.error.set(problemText(e));
    }
  }

  private async refresh(): Promise<void> {
    try {
      const proof = await this.gateway.deliveryProof(this.consignmentId());
      this.proof.set(proof);
      const fed: Record<string, LineFields> = {};
      for (const l of proof?.lines ?? []) {
        fed[l.orderLineId] = { received: l.receivedQty, short: l.shortQty, damaged: l.damagedQty, excess: l.excessQty };
      }
      this.lines.set(fed);
      this.loaded.set(true);
    } catch (e) {
      this.loaded.set(false);
      this.error.set(problemText(e));
    }
  }
}
