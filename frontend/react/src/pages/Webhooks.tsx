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

import { useState, type FormEvent } from 'react';
import {
  WEBHOOK_EVENT_TYPES, type Row, type WebhookEventType, type WebhookSecret, type WebhookView,
} from '../gateway/lmsGateway';
import { useSession } from '../session';
import { useLoad } from '../useLoad';
import { RowTable } from './RowTable';

/** A subscription as a table row. */
function rowOf(w: WebhookView): Row {
  const state = !w.active ? (w.pausedReason ? 'Paused' : 'Inactive') : 'Active';
  return { id: w.id, name: w.name, url: w.url, eventTypes: w.eventTypes.join(', '), state };
}

/**
 * Where a company's systems are told about orders, trips, deliveries and
 * claims. The signing secret is shown once, in the panel that issued it, and
 * nowhere else: the screens never read it back because the API never returns it.
 */
export function Webhooks() {
  const { gateway, can } = useSession();
  const manage = can('WEBHOOK_MANAGE');
  const [reload, setReload] = useState(0);
  const [picked, setPicked] = useState('');
  const [secret, setSecret] = useState<WebhookSecret | null>(null);
  const list = useLoad(() => gateway.webhooks(), reload);
  const current = list.data?.find((w) => w.id === picked) ?? null;

  function issued(s: WebhookSecret) {
    setSecret(s);
    setPicked(s.id);
    setReload((n) => n + 1);
  }

  return (
    <>
      <h1>Webhooks</h1>
      <p className="muted">Addresses your systems listen on, told about orders, trips, deliveries and claims as they happen.</p>
      {secret && <SecretOnce secret={secret.secret} onDone={() => setSecret(null)} />}
      {list.loading && <p className="muted">Loading…</p>}
      {list.error && <p className="error" role="alert">{list.error}</p>}
      {list.data && list.data.length === 0 && <p className="muted" data-testid="no-webhooks">No subscriptions yet.</p>}
      {list.data && list.data.length > 0 && (
        <RowTable testId="webhooks" rows={list.data.map(rowOf)} picked={picked} onPick={(r) => setPicked(r.id)} columns={[
          { header: 'Name', field: 'name' }, { header: 'Address', field: 'url' },
          { header: 'Events', field: 'eventTypes' }, { header: 'State', field: 'state' },
        ]} />
      )}
      {manage && !current && <Subscribe onIssued={issued} />}
      {current && (
        <Subscription key={current.id} webhook={current} manage={manage}
          onIssued={(s) => setSecret(s)} onChanged={() => setReload((n) => n + 1)} onClose={() => setPicked('')} />
      )}
      <SignatureHelp />
    </>
  );
}

/** The secret, once. Leaving the panel forgets it. */
function SecretOnce({ secret, onDone }: { secret: string; onDone: () => void }) {
  return (
    <section className="notice" data-testid="webhook-secret">
      <h2>Signing secret</h2>
      <p>Store it now: it cannot be shown again. If it is lost, rotate it.</p>
      <code data-testid="secret-value">{secret}</code>
      <button type="button" data-testid="copy-secret"
        onClick={() => navigator.clipboard?.writeText(secret).catch(() => undefined)}>Copy</button>
      <button type="button" data-testid="secret-done" onClick={onDone}>I have stored it</button>
    </section>
  );
}

function EventTypes({ value, onChange }: { value: WebhookEventType[]; onChange: (v: WebhookEventType[]) => void }) {
  return (
    <fieldset data-testid="event-types">
      <legend>Events</legend>
      {WEBHOOK_EVENT_TYPES.map((t) => (
        <label key={t}>
          <input type="checkbox" data-testid={`type-${t}`} checked={value.includes(t)}
            onChange={(e) => onChange(e.target.checked ? [...value, t] : value.filter((v) => v !== t))} />
          {t}
        </label>
      ))}
    </fieldset>
  );
}

function Subscribe({ onIssued }: { onIssued: (s: WebhookSecret) => void }) {
  const { gateway } = useSession();
  const [name, setName] = useState('');
  const [url, setUrl] = useState('');
  const [types, setTypes] = useState<WebhookEventType[]>([]);
  const [error, setError] = useState('');

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError('');
    try {
      onIssued(await gateway.subscribeWebhook({ name: name.trim(), url: url.trim(), eventTypes: types }));
    } catch (e) {
      setError((e as Error).message);
    }
  }

  return (
    <form onSubmit={submit} data-testid="subscribe-form">
      <h2>Subscribe an address</h2>
      <input placeholder="Name" aria-label="Name" data-testid="webhook-name" value={name}
        onChange={(e) => setName(e.target.value)} required />
      <input placeholder="https://…" aria-label="Address" data-testid="webhook-url" value={url}
        onChange={(e) => setUrl(e.target.value)} required />
      <EventTypes value={types} onChange={setTypes} />
      {error && <p className="error" role="alert">{error}</p>}
      <button type="submit" data-testid="subscribe">Subscribe</button>
    </form>
  );
}

function Subscription({ webhook, manage, onIssued, onChanged, onClose }: {
  webhook: WebhookView;
  manage: boolean;
  onIssued: (s: WebhookSecret) => void;
  onChanged: () => void;
  onClose: () => void;
}) {
  const { gateway } = useSession();
  const [name, setName] = useState(webhook.name);
  const [url, setUrl] = useState(webhook.url);
  const [types, setTypes] = useState(webhook.eventTypes as WebhookEventType[]);
  const [active, setActive] = useState(webhook.active);
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');
  const [pages, setPages] = useState(0);

  async function act(action: () => Promise<string | void>) {
    setError('');
    setMessage('');
    try {
      const said = await action();
      if (said) {
        setMessage(said);
      }
    } catch (e) {
      setError((e as Error).message);
    }
  }

  function save(event: FormEvent) {
    event.preventDefault();
    act(async () => {
      await gateway.reviseWebhook(webhook.id, { name: name.trim(), url: url.trim(), eventTypes: types, active });
      onChanged();
      return 'Saved.';
    });
  }

  return (
    <section data-testid="webhook-detail">
      <h2>{webhook.name}</h2>
      <button type="button" className="link" onClick={onClose}>Back to subscribing</button>
      {webhook.pausedReason && (
        <p className="error" data-testid="paused-reason">Paused: {webhook.pausedReason}</p>
      )}
      {manage ? (
        <form onSubmit={save} data-testid="revise-form">
          <input aria-label="Name" data-testid="edit-name" value={name} onChange={(e) => setName(e.target.value)} required />
          <input aria-label="Address" data-testid="edit-url" value={url} onChange={(e) => setUrl(e.target.value)} required />
          <EventTypes value={types} onChange={setTypes} />
          <label>
            <input type="checkbox" data-testid="edit-active" checked={active} onChange={(e) => setActive(e.target.checked)} />
            Active
          </label>
          <button type="submit" data-testid="save-webhook">Save</button>
          <button type="button" data-testid="rotate-secret"
            onClick={() => act(async () => { onIssued(await gateway.rotateWebhookSecret(webhook.id)); })}>
            Rotate secret
          </button>
          <button type="button" data-testid="ping"
            onClick={() => act(async () => { await gateway.pingWebhook(webhook.id); setPages((n) => n + 1); return 'Test ping queued.'; })}>
            Send test ping
          </button>
        </form>
      ) : (
        <dl className="facts">
          <div><dt>Address</dt><dd>{webhook.url}</dd></div>
          <div><dt>Events</dt><dd>{webhook.eventTypes.join(', ')}</dd></div>
          <div><dt>Active</dt><dd>{webhook.active ? 'Yes' : 'No'}</dd></div>
        </dl>
      )}
      {message && <p data-testid="webhook-message">{message}</p>}
      {error && <p className="error" role="alert">{error}</p>}
      <Deliveries key={pages} webhookId={webhook.id} manage={manage} />
    </section>
  );
}

function Deliveries({ webhookId, manage }: { webhookId: string; manage: boolean }) {
  const { gateway } = useSession();
  const [rows, setRows] = useState<Row[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [reload, setReload] = useState(0);
  const [picked, setPicked] = useState('');
  const [error, setError] = useState('');
  const first = useLoad(async () => {
    const page = await gateway.webhookDeliveries(webhookId);
    setRows(page.rows);
    setCursor(page.nextCursor);
    return page;
  }, reload);

  async function more() {
    if (!cursor) {
      return;
    }
    try {
      const page = await gateway.webhookDeliveries(webhookId, cursor);
      setRows((r) => [...r, ...page.rows]);
      setCursor(page.nextCursor);
    } catch (e) {
      setError((e as Error).message);
    }
  }

  async function redeliver() {
    setError('');
    try {
      await gateway.redeliverWebhook(picked);
      setReload((n) => n + 1);
    } catch (e) {
      setError((e as Error).message);
    }
  }

  return (
    <section data-testid="webhook-deliveries">
      <h3>Deliveries</h3>
      <button type="button" data-testid="refresh-deliveries" onClick={() => setReload((n) => n + 1)}>Refresh</button>
      {first.error && <p className="error" role="alert">{first.error}</p>}
      {error && <p className="error" role="alert">{error}</p>}
      {!first.loading && rows.length === 0 && <p className="muted" data-testid="no-deliveries">Nothing sent yet.</p>}
      {rows.length > 0 && (
        <RowTable testId="deliveries" rows={rows} picked={picked} onPick={(r) => setPicked(r.id)} columns={[
          { header: 'Event', field: 'eventType' }, { header: 'Status', field: 'status' },
          { header: 'Attempts', field: 'attempts' }, { header: 'Last code', field: 'lastStatusCode' },
          { header: 'Last error', field: 'lastError' }, { header: 'Next attempt', field: 'nextAttemptAt' },
          { header: 'Delivered', field: 'deliveredAt' }, { header: 'Created', field: 'createdAt' },
        ]} />
      )}
      {cursor && <button type="button" data-testid="more-deliveries" onClick={more}>Load more</button>}
      {manage && picked && <button type="button" data-testid="redeliver" onClick={redeliver}>Redeliver</button>}
    </section>
  );
}

/** How a receiver checks that a delivery came from here. No secret appears in it. */
function SignatureHelp() {
  return (
    <details data-testid="signature-help">
      <summary>Verifying a delivery</summary>
      <p>Every delivery is a POST of a JSON envelope with these headers:</p>
      <ul>
        <li><code>X-Lms-Event</code>: the event type.</li>
        <li><code>X-Lms-Delivery</code>: the delivery id, the same on every retry. Use it to ignore duplicates.</li>
        <li><code>X-Lms-Timestamp</code>: seconds since the epoch. Reject one more than a few minutes from your clock.</li>
        <li><code>X-Lms-Signature</code>: <code>v1=</code> and the hex HMAC-SHA256 of <code>timestamp.body</code>, keyed with the secret.</li>
      </ul>
      <p>Compute the HMAC over the raw body exactly as received, and compare in constant time. Answer with any 2xx; anything else is retried.</p>
    </details>
  );
}
