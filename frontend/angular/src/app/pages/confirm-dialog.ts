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

import { Component, ElementRef, OnDestroy, afterNextRender, input, model, output, signal, viewChild } from '@angular/core';
import { reasonGiven, wrapFocus, type ConfirmWording, type PendingConfirm, type ReasonField } from '../../../../shared/confirm';

/**
 * The one confirmation before a destructive action. Render it (`@if`) while an action is pending and not otherwise: a native <dialog>
 * opened with showModal(), so the page behind is inert, focus stays inside and Escape dismisses. Focus starts on the safe button
 * ("Keep it") and goes back to whatever opened the dialog when it closes. `confirmed` carries the trimmed reason ('' when none is asked);
 * anything projected into it, such as a refusal from the server, shows under the fields while the page keeps the dialog open.
 */
@Component({
  selector: 'app-confirm-dialog',
  template: `
    <dialog #box class="dialog" role="dialog" aria-modal="true" [attr.aria-labelledby]="uid + '-title'"
            [attr.aria-describedby]="wording().body ? uid + '-body' : null" data-testid="confirm-dialog"
            (cancel)="escape($event)" (keydown)="trap($event)">
      <form novalidate (submit)="submit($event)">
        <h2 [id]="uid + '-title'">{{ wording().title }}</h2>
        @if (wording().body) { <p [id]="uid + '-body'">{{ wording().body }}</p> }
        @if (reason(); as r) {
          <div>
            <label class="field-label" [for]="uid + '-reason'" [attr.data-required]="r.required ? '' : null">{{ r.label }}</label>
            @if (r.multiline) {
              <textarea [id]="uid + '-reason'" rows="2" [attr.data-testid]="r.testId" [attr.placeholder]="r.placeholder || null"
                        [value]="text()" [attr.aria-invalid]="missing() ? true : null" (input)="typed($any($event.target).value)"></textarea>
            } @else {
              <input [id]="uid + '-reason'" [attr.data-testid]="r.testId" [attr.placeholder]="r.placeholder || null"
                     [value]="text()" [attr.aria-invalid]="missing() ? true : null" (input)="typed($any($event.target).value)" />
            }
            @if (missing()) { <small class="dialog__error" role="alert" data-testid="reason-error">A reason is required.</small> }
          </div>
        }
        <ng-content />
        <div class="dialog__actions">
          <button #dismissButton type="button" class="link" data-testid="confirm-dismiss" [disabled]="busy()"
                  (click)="dismissed.emit()">{{ wording().dismissLabel }}</button>
          <button type="submit" [class.danger]="tone() === 'danger'" data-testid="confirm-accept" [disabled]="busy()">{{ wording().confirmLabel }}</button>
        </div>
      </form>
    </dialog>
  `,
})
export class ConfirmDialog implements OnDestroy {
  private static nextId = 1;

  readonly wording = input.required<ConfirmWording>();
  /** Asks for a reason inside the dialog. */
  readonly reason = input<ReasonField | null>(null);
  /** Danger (the default) for an action that cannot be taken back; neutral for one that can. */
  readonly tone = input<'danger' | 'neutral'>('danger');
  /** Holds the buttons while the request runs. */
  readonly busy = input(false);
  readonly confirmed = output<string>();
  readonly dismissed = output<void>();

  protected readonly uid = `confirm-${ConfirmDialog.nextId++}`;
  protected readonly text = signal('');
  protected readonly missing = signal(false);
  private readonly box = viewChild.required<ElementRef<HTMLDialogElement>>('box');
  /** The native dialog, kept once shown so it can be closed on destroy, when the query no longer answers. */
  private shown: HTMLDialogElement | null = null;
  private readonly dismissButton = viewChild.required<ElementRef<HTMLButtonElement>>('dismissButton');
  /** Whatever had focus when the dialog was made, normally the button that asked for it. */
  private readonly opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;

  constructor() {
    afterNextRender(() => {
      const el = this.box().nativeElement;
      this.shown = el;
      if (!el.open) { el.showModal(); }
      this.dismissButton().nativeElement.focus();
    });
  }

  protected typed(value: string): void {
    this.text.set(value);
    this.missing.set(false);
  }

  /** Keeps Tab inside the dialog: it wraps from the last control to the first and back. */
  protected trap(event: KeyboardEvent): void {
    if (event.key !== 'Tab') { return; }
    const root = event.currentTarget as HTMLElement;
    const items = Array.from(root.querySelectorAll<HTMLElement>('input, textarea, button')).filter((e) => !(e as HTMLButtonElement).disabled);
    const at = wrapFocus(items.length, items.indexOf(document.activeElement as HTMLElement), event.shiftKey);
    if (at >= 0) {
      event.preventDefault();
      items[at].focus();
    }
  }

  protected escape(event: Event): void {
    event.preventDefault();
    if (!this.busy()) { this.dismissed.emit(); }
  }

  protected submit(event: Event): void {
    event.preventDefault();
    if (this.reason()?.required && !reasonGiven(this.text())) {
      this.missing.set(true);
      return;
    }
    this.confirmed.emit(this.text().trim());
  }

  ngOnDestroy(): void {
    if (this.shown?.open) { this.shown.close(); }
    if (this.opener?.isConnected) { this.opener.focus(); }
  }
}

/**
 * For a screen with one or more destructive buttons: the button's click sets `pending` (a PendingConfirm), and this host, placed once in
 * the screen's template as `<app-confirm-host [(pending)]="pending" />`, shows the dialog and runs the action when it is confirmed. It
 * closes as the action starts; the screen keeps showing the result or the refusal itself.
 */
@Component({
  selector: 'app-confirm-host',
  imports: [ConfirmDialog],
  template: `
    @if (pending(); as p) {
      <app-confirm-dialog [wording]="p.wording" [reason]="p.reason ?? null" [tone]="p.tone ?? 'danger'"
                          (dismissed)="pending.set(null)" (confirmed)="proceed(p, $event)" />
    }
  `,
})
export class ConfirmHost {
  readonly pending = model<PendingConfirm | null>(null);

  protected proceed(p: PendingConfirm, reason: string): void {
    this.pending.set(null);
    void p.run(reason);
  }
}
