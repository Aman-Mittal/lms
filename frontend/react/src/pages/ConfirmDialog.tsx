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

import { useEffect, useId, useRef, useState, type FormEvent, type KeyboardEvent, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { reasonGiven, wrapFocus, type ConfirmWording, type PendingConfirm, type ReasonField } from '../../../shared/confirm';

interface Props {
  wording: ConfirmWording;
  /** Asks for a reason inside the dialog; it is handed to onConfirm, trimmed. */
  reason?: ReasonField;
  /** Danger (the default) for an action that cannot be taken back; neutral for one that can. */
  tone?: 'danger' | 'neutral';
  /** Holds the buttons while the request runs. */
  busy?: boolean;
  onConfirm: (reason: string) => void;
  onDismiss: () => void;
  /** Extra content under the fields, such as a refusal from the server; the dialog stays open while the page shows it. */
  children?: ReactNode;
}

/**
 * The one confirmation before a destructive action. Render it while an action is pending and not otherwise: a native <dialog> opened
 * with showModal(), so the page behind is inert, focus stays inside and Escape dismisses. Focus starts on the safe button ("Keep it")
 * and goes back to whatever opened the dialog when it closes. It is shown through a portal, so it is never nested in a table or a form.
 */
export function ConfirmDialog({ wording, reason, tone = 'danger', busy = false, onConfirm, onDismiss, children }: Props) {
  const dialog = useRef<HTMLDialogElement>(null);
  const dismiss = useRef<HTMLButtonElement>(null);
  const [text, setText] = useState('');
  const [missing, setMissing] = useState(false);
  const titleId = useId();
  const bodyId = useId();

  useEffect(() => {
    const el = dialog.current;
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    if (el && !el.open) { el.showModal(); }
    dismiss.current?.focus();
    return () => {
      if (el?.open) { el.close(); }
      if (opener?.isConnected) { opener.focus(); }
    };
  }, []);

  /** Keeps Tab inside the dialog: it wraps from the last control to the first and back. */
  function trap(event: KeyboardEvent<HTMLDialogElement>) {
    if (event.key !== 'Tab') { return; }
    const items = Array.from(event.currentTarget.querySelectorAll<HTMLElement>('input, textarea, button')).filter((e) => !(e as HTMLButtonElement).disabled);
    const at = wrapFocus(items.length, items.indexOf(document.activeElement as HTMLElement), event.shiftKey);
    if (at >= 0) {
      event.preventDefault();
      items[at].focus();
    }
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    if (reason?.required && !reasonGiven(text)) {
      setMissing(true);
      return;
    }
    onConfirm(text.trim());
  }

  return createPortal(
    <dialog ref={dialog} className="dialog" role="dialog" aria-modal="true" aria-labelledby={titleId} aria-describedby={wording.body ? bodyId : undefined}
      data-testid="confirm-dialog" onKeyDown={trap} onCancel={(e) => { e.preventDefault(); if (!busy) { onDismiss(); } }}>
      <form onSubmit={submit} noValidate>
        <h2 id={titleId}>{wording.title}</h2>
        {wording.body && <p id={bodyId}>{wording.body}</p>}
        {reason && (
          <div>
            <label className="field-label" htmlFor={`${titleId}-reason`} data-required={reason.required ? '' : undefined}>{reason.label}</label>
            {reason.multiline
              ? <textarea id={`${titleId}-reason`} rows={2} data-testid={reason.testId} placeholder={reason.placeholder} value={text}
                aria-invalid={missing ? true : undefined} onChange={(e) => { setText(e.target.value); setMissing(false); }} />
              : <input id={`${titleId}-reason`} data-testid={reason.testId} placeholder={reason.placeholder} value={text}
                aria-invalid={missing ? true : undefined} onChange={(e) => { setText(e.target.value); setMissing(false); }} />}
            {missing && <small className="dialog__error" role="alert" data-testid="reason-error">A reason is required.</small>}
          </div>
        )}
        {children}
        <div className="dialog__actions">
          <button type="button" className="link" ref={dismiss} data-testid="confirm-dismiss" disabled={busy} onClick={onDismiss}>{wording.dismissLabel}</button>
          <button type="submit" className={tone === 'danger' ? 'danger' : undefined} data-testid="confirm-accept" disabled={busy}>{wording.confirmLabel}</button>
        </div>
      </form>
    </dialog>,
    document.body,
  );
}

/**
 * For a screen with one or more destructive buttons: `ask(...)` from the button's click opens the dialog, and `dialog` goes once anywhere in
 * the screen's markup. The dialog closes as the action starts; the screen keeps showing the result or the refusal itself.
 */
export function useConfirm(): { ask: (pending: PendingConfirm) => void; dialog: ReactNode } {
  const [pending, setPending] = useState<PendingConfirm | null>(null);
  const dialog = pending && (
    <ConfirmDialog wording={pending.wording} reason={pending.reason} tone={pending.tone} onDismiss={() => setPending(null)}
      onConfirm={(why) => { setPending(null); void pending.run(why); }} />
  );
  return { ask: setPending, dialog };
}
