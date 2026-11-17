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

/*
 * Form behaviour both consoles share, done once on the rendered page so the two cannot drift apart. A MutationObserver
 * watches the main content and, for every field that has no label of its own:
 *   - puts a visible label above it (from its aria-label, else its placeholder), and the part of a long placeholder that
 *     does not fit the label as help text; a field with an aria-label gets a real label, so its accessible name is unchanged,
 *   - links it with aria-describedby to its help and to any error shown beside it or for its form,
 *   - marks it aria-invalid once the browser has refused it, until it is edited.
 * It also turns a new success note (.ok with role status) into a toast, and keeps a form's submit button disabled while the
 * request it started is in flight. Nothing is moved: elements are only added before a field or given attributes, which keeps
 * both frameworks' own rendering intact.
 */
import { openSince, requestMark, showToast, subscribePending } from './feedback';

const SKIP_TYPES = new Set(['hidden', 'checkbox', 'radio', 'submit', 'button', 'reset', 'file', 'image', 'search', 'range', 'color']);
const AUTO = 'data-auto-field';
/** A placeholder longer than this is likely to be cut off in a field of ordinary width. */
const LONG_PLACEHOLDER = 24;

let serial = 0;

function idOf(el: Element, prefix: string): string {
  if (!el.id) {
    el.id = `${prefix}-${++serial}`;
  }
  return el.id;
}

type Control = HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement;

function isControl(el: Element): el is Control {
  return el instanceof HTMLInputElement || el instanceof HTMLSelectElement || el instanceof HTMLTextAreaElement;
}

/** The label text and the help text for a field, from its aria-label and placeholder. */
export function fieldText(ariaLabel: string, placeholder: string): { label: string; help: string; real: boolean } {
  const aria = ariaLabel.trim();
  const hint = placeholder.trim();
  if (aria) {
    return { label: aria, help: hint && hint !== aria && hint.length > LONG_PLACEHOLDER ? hint : '', real: true };
  }
  const open = hint.indexOf(' (');
  if (open > 0 && hint.endsWith(')')) {
    return { label: hint.slice(0, open), help: hint.slice(open + 2, -1), real: false };
  }
  return { label: hint, help: '', real: false };
}

function wantsLabel(el: Control): boolean {
  if (el instanceof HTMLInputElement && SKIP_TYPES.has(el.type)) {
    return false;
  }
  if (el.closest('table, nav, aside.sidebar, [hidden], .sr-only, [data-no-auto-label]')) {
    return false;
  }
  return !el.labels || el.labels.length === 0;
}

/** The element a label goes before: a picker is a span round its input, and the label belongs before the span. */
function anchorOf(el: Control): Element {
  return el.closest('.picker') ?? el;
}

function autoElement(tag: string, className: string, forId: string, text: string): HTMLElement {
  const node = document.createElement(tag);
  node.className = className;
  node.textContent = text;
  node.setAttribute(AUTO, forId);
  return node;
}

function addLabel(el: Control): void {
  // A visual-only label is not a label in the browser's eyes, so look for the one already added.
  if (el.id && document.querySelector(`.field-label[${AUTO}="${CSS.escape(el.id)}"]`)) {
    return;
  }
  const text = fieldText(el.getAttribute('aria-label') ?? '', el.getAttribute('placeholder') ?? '');
  if (!text.label) {
    return;
  }
  const id = idOf(el, 'fld');
  const anchor = anchorOf(el);
  const label = autoElement(text.real ? 'label' : 'span', 'field-label', id, text.label);
  if (text.real) {
    label.setAttribute('for', id);
  } else {
    // A placeholder-only field keeps the placeholder as its accessible name; the visible label would only say it twice.
    label.setAttribute('aria-hidden', 'true');
  }
  anchor.before(label);
  if (text.help) {
    const help = autoElement('small', 'field-help muted', id, text.help);
    help.id = `${id}-help`;
    anchor.after(help);
  }
}

function setRequiredMarks(root: ParentNode): void {
  root.querySelectorAll<HTMLElement>(`[${AUTO}].field-label`).forEach((label) => {
    const target = document.getElementById(label.getAttribute(AUTO) ?? '');
    const required = target instanceof HTMLElement && (target as Control).required === true;
    label.toggleAttribute('data-required', required);
  });
}

/** Drops labels and help whose field has been removed, or whose field has since been given a label of its own. */
function sweep(root: ParentNode): void {
  root.querySelectorAll<HTMLElement>(`[${AUTO}]`).forEach((node) => {
    const target = document.getElementById(node.getAttribute(AUTO) ?? '');
    if (!target || !target.isConnected) {
      node.remove();
    }
  });
}

function errorsFor(el: Control): HTMLElement[] {
  const found: HTMLElement[] = [];
  const near = [anchorOf(el), el.closest('.picker') ? el : null];
  for (const n of near) {
    for (let sib = n?.nextElementSibling ?? null; sib && !isControl(sib) && !sib.matches('label, .field-label, .picker'); sib = sib.nextElementSibling) {
      if (sib instanceof HTMLElement && sib.matches('.error, [role=alert]')) {
        found.push(sib);
      }
    }
  }
  const form = el.form;
  if (form) {
    // A banner is a component element round the alert, so the alert may be the sibling or inside it.
    const after = form.nextElementSibling;
    const alert = after instanceof HTMLElement ? (after.matches('[role=alert]') ? after : after.querySelector<HTMLElement>('[role=alert]')) : null;
    if (alert) {
      found.push(alert);
    }
  }
  return found;
}

function describe(el: Control): void {
  const ours = new Set<string>();
  const help = document.getElementById(`${el.id}-help`);
  if (help) {
    ours.add(help.id);
  }
  errorsFor(el).forEach((err) => ours.add(idOf(err, 'err')));
  const before = (el.getAttribute('data-auto-desc') ?? '').split(' ').filter(Boolean);
  const mine = new Set(before);
  const kept = (el.getAttribute('aria-describedby') ?? '').split(' ').filter((t) => t && !mine.has(t));
  const next = [...kept, ...ours];
  if (next.length) {
    el.setAttribute('aria-describedby', next.join(' '));
    el.setAttribute('data-auto-desc', [...ours].join(' '));
  } else if (before.length) {
    el.removeAttribute('aria-describedby');
    el.removeAttribute('data-auto-desc');
  }
}

const said = new WeakMap<Element, string>();

function announceSuccess(root: ParentNode, first: boolean): void {
  root.querySelectorAll<HTMLElement>('.ok[role=status]').forEach((note) => {
    const text = (note.textContent ?? '').trim();
    if (text && said.get(note) !== text) {
      said.set(note, text);
      if (!first) {
        showToast(text);
      }
    }
  });
}

/** One pass over the content; safe to repeat. */
export function enhance(root: ParentNode, first = false): void {
  sweep(root);
  root.querySelectorAll('input, select, textarea').forEach((el) => {
    if (isControl(el) && wantsLabel(el)) {
      addLabel(el);
    }
  });
  setRequiredMarks(root);
  root.querySelectorAll('input, select, textarea').forEach((el) => {
    if (isControl(el) && !(el instanceof HTMLInputElement && SKIP_TYPES.has(el.type)) && el.id) {
      describe(el);
    }
  });
  announceSuccess(root, first);
}

/** Where the requests stood when the submit began; read in the capture phase, before the screen's own handler runs. */
let markAtSubmit = 0;

function markSubmit(): void {
  markAtSubmit = requestMark();
}

function holdSubmit(event: Event): void {
  const form = event.target;
  if (!(form instanceof HTMLFormElement)) {
    return;
  }
  const submitter = (event as SubmitEvent).submitter ?? form.querySelector('button[type=submit], button:not([type])');
  if (!(submitter instanceof HTMLButtonElement)) {
    return;
  }
  const mark = markAtSubmit;
  // The request may start a moment after the submit (the API client loads on first use), so look a few times.
  [0, 60, 200].forEach((ms) => setTimeout(() => {
    if (submitter.disabled || openSince(mark) === 0 || !submitter.isConnected) {
      return;
    }
    submitter.disabled = true;
    submitter.setAttribute('aria-busy', 'true');
    const stop = subscribePending(() => {
      if (openSince(mark) === 0 || !submitter.isConnected) {
        submitter.disabled = false;
        submitter.removeAttribute('aria-busy');
        stop();
      }
    });
  }, ms));
}

/** Starts the form behaviour on the page; returns the function that stops it. Call once, after the first render. */
export function installFormHelpers(doc: Document = document): () => void {
  let timer: ReturnType<typeof setTimeout> | null = null;
  const run = () => {
    timer = null;
    const root = doc.querySelector('main') ?? doc.body;
    enhance(root);
  };
  const observer = new MutationObserver(() => {
    if (timer === null) {
      timer = setTimeout(run, 40);
    }
  });
  observer.observe(doc.body, { childList: true, subtree: true, characterData: true });
  const refused = (e: Event) => { if (isControl(e.target as Element)) { (e.target as Control).setAttribute('aria-invalid', 'true'); } };
  const edited = (e: Event) => { if (e.target instanceof Element) { e.target.removeAttribute('aria-invalid'); } };
  doc.addEventListener('invalid', refused, true);
  doc.addEventListener('input', edited, true);
  doc.addEventListener('submit', markSubmit, true);
  doc.addEventListener('submit', holdSubmit);
  enhance(doc.querySelector('main') ?? doc.body, true);
  return () => {
    observer.disconnect();
    doc.removeEventListener('invalid', refused, true);
    doc.removeEventListener('input', edited, true);
    doc.removeEventListener('submit', markSubmit, true);
    doc.removeEventListener('submit', holdSubmit);
    if (timer !== null) { clearTimeout(timer); }
  };
}
