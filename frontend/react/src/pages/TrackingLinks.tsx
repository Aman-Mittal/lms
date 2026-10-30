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
import type { IssuedTrackingLink, TrackingLinkView } from '../gateway/lmsGateway';
import { useSession } from '../session';
import { useLoad } from '../useLoad';
import { Qr } from './Qr';
import { trackingUrl } from './trackingUrl';

/** Issue, list, revoke and print the links a customer follows a consignment by. */
export function TrackingLinks() {
  const { gateway, can } = useSession();
  const [typed, setTyped] = useState('');
  const [consignmentId, setConsignmentId] = useState('');
  const [reload, setReload] = useState(0);
  // Held here, not in the panel: the panel is rebuilt after each reload, and the
  // token just issued is shown once and must outlive that.
  const [issued, setIssued] = useState<IssuedTrackingLink | null>(null);

  const links = useLoad(
    () => (consignmentId ? gateway.trackingLinks(consignmentId) : Promise.resolve([])),
    `${consignmentId}:${reload}`,
  );

  function lookUp(event: FormEvent) {
    event.preventDefault();
    setConsignmentId(typed.trim());
    setIssued(null);
    setReload((n) => n + 1);
  }

  return (
    <>
      <h1>Tracking links</h1>
      <p className="muted">
        Give a customer a link to follow a consignment without signing in. A link's secret is shown once, when it is issued.
      </p>
      {!can('TRACKING_LINK_MANAGE') ? (
        <p className="muted" data-testid="no-rights">You do not have the right to manage tracking links.</p>
      ) : (
        <>
          <form className="inline" onSubmit={lookUp} data-testid="lookup">
            <input placeholder="Consignment id" aria-label="Consignment id" data-testid="consignment-id"
              value={typed} onChange={(e) => setTyped(e.target.value)} required />
            <button type="submit">Look up</button>
          </form>
          {links.error && <p className="error" role="alert">{links.error}</p>}
          {consignmentId && !links.loading && !links.error && (
            <Panel key={`${consignmentId}:${reload}`} consignmentId={consignmentId}
              links={links.data ?? []} issued={issued} onIssued={setIssued}
              onChanged={() => setReload((n) => n + 1)} />
          )}
        </>
      )}
    </>
  );
}

interface PanelProps {
  consignmentId: string;
  links: TrackingLinkView[];
  issued: IssuedTrackingLink | null;
  onIssued: (issued: IssuedTrackingLink) => void;
  onChanged: () => void;
}

function Panel({ consignmentId, links, issued, onIssued, onChanged }: PanelProps) {
  const { gateway } = useSession();
  const [error, setError] = useState('');
  const [days, setDays] = useState('30');
  const [slip, setSlip] = useState(false);
  const [copied, setCopied] = useState(false);

  async function issue(event: FormEvent) {
    event.preventDefault();
    setError('');
    setSlip(false);
    setCopied(false);
    try {
      onIssued(await gateway.issueTrackingLink(consignmentId, Number(days)));
      onChanged();
    } catch (e) {
      setError((e as Error).message);
    }
  }

  async function revoke(id: string) {
    setError('');
    try {
      await gateway.revokeTrackingLink(id);
      onChanged();
    } catch (e) {
      setError((e as Error).message);
    }
  }

  async function copy(url: string) {
    await navigator.clipboard?.writeText(url).catch(() => undefined);
    setCopied(true);
  }

  const url = issued ? trackingUrl(issued.token) : '';

  return (
    <section data-testid="links-panel">
      <form className="inline" onSubmit={issue} data-testid="issue-link-form">
        <label>
          Valid for (days)
          <input type="number" min={1} max={180} aria-label="Valid for (days)" data-testid="link-days"
            value={days} onChange={(e) => setDays(e.target.value)} required />
        </label>
        <button type="submit" data-testid="issue-link">Issue a link</button>
      </form>
      {error && <p className="error" role="alert">{error}</p>}
      {issued && (
        <section data-testid="issued-link">
          <p>
            Link issued, valid until {issued.expiresAt}. Copy it now: the secret is not shown again.
          </p>
          <p>
            <input readOnly aria-label="Tracking link" data-testid="link-url" value={url} size={60} />{' '}
            <button type="button" data-testid="copy-link" onClick={() => copy(url)}>
              {copied ? 'Copied' : 'Copy'}
            </button>{' '}
            <button type="button" className="link" data-testid="print-link" onClick={() => setSlip(true)}>
              Print slip
            </button>
          </p>
          {slip && (
            <section className="slip" data-testid="link-slip" aria-label="Tracking link slip">
              <h3>Follow your shipment</h3>
              <div className="slip-body">
                <dl>
                  <div><dt>Open</dt><dd>{url}</dd></div>
                  <div><dt>Valid until</dt><dd>{issued.expiresAt}</dd></div>
                </dl>
                <Qr payload={url} />
              </div>
              <p className="actions no-print">
                <button data-testid="print-now" onClick={() => window.print()}>Print</button>
              </p>
            </section>
          )}
        </section>
      )}
      {links.length === 0 ? (
        <p className="muted" data-testid="no-links">This consignment has no tracking links.</p>
      ) : (
        <table data-testid="links-table">
          <thead>
            <tr><th>Status</th><th>Created</th><th>Expires</th><th>Revoked</th><th /></tr>
          </thead>
          <tbody>
            {links.map((l) => (
              <tr key={l.id} data-testid="link-row">
                <td data-testid="link-status">{l.status}</td>
                <td>{l.createdAt}</td>
                <td>{l.expiresAt}</td>
                <td>{l.revokedAt || '—'}</td>
                <td>
                  {l.status === 'ACTIVE' && (
                    <button className="link" data-testid="revoke-link" onClick={() => revoke(l.id)}>Revoke</button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
