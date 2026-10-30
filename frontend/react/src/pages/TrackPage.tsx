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

import { useParams } from 'react-router-dom';
import { useSession } from '../session';
import { useLoad } from '../useLoad';

/**
 * Where a customer lands from a tracking link. It needs no sign-in, shows the
 * small view the public route returns, and says the same thing whether the link
 * never existed, has expired or was revoked: the server does not tell them apart.
 */
export function TrackPage() {
  const { token = '' } = useParams();
  const { gateway } = useSession();
  const view = useLoad(() => gateway.publicTracking(token), token);

  return (
    <main className="public">
      <h1>Track your shipment</h1>
      {view.loading && <p className="muted">Loading…</p>}
      {view.error && <p className="error" role="alert">{view.error}</p>}
      {!view.loading && !view.error && view.data === null && (
        <p data-testid="track-not-found" role="alert">
          This tracking link is not valid. It may have expired or been withdrawn.
        </p>
      )}
      {view.data && (
        <section data-testid="track-view">
          <dl>
            <div><dt>Reference</dt><dd data-testid="track-reference">{view.data.reference}</dd></div>
            <div><dt>Status</dt><dd data-testid="track-status">{view.data.status}</dd></div>
            <div><dt>From</dt><dd data-testid="track-origin">{view.data.originName || '—'}</dd></div>
            <div><dt>To</dt><dd data-testid="track-destination">{view.data.destinationName || '—'}</dd></div>
            <div><dt>Requested delivery</dt><dd>{view.data.requestedDeliveryAt || '—'}</dd></div>
            <div><dt>Delivered</dt><dd>{view.data.deliveredAt || '—'}</dd></div>
            <div>
              <dt>Receipt confirmed</dt>
              <dd data-testid="track-proof">{view.data.proofRecorded ? 'Yes' : 'No'}</dd>
            </div>
            {view.data.position && (
              <div>
                <dt>Last seen</dt>
                <dd data-testid="track-position">
                  {view.data.position.lat}, {view.data.position.lon} ({view.data.position.recordedAt})
                </dd>
              </div>
            )}
          </dl>
        </section>
      )}
    </main>
  );
}
