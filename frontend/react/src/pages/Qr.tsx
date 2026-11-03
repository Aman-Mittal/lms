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

import { toDataURL } from 'qrcode';
import { useLoad } from '../useLoad';

/**
 * A QR code drawn in the browser. The code is made here, from text the page
 * already has, so the backend serves no image and stores none. The payload is
 * also left on the element, which is what a test (or a curious reader of the
 * page) can check without a scanner.
 */
export function Qr({ payload, size = 160 }: { payload: string; size?: number }) {
  const { data, error } = useLoad(() => toDataURL(payload, { margin: 1, width: size }), payload);
  if (error) {
    return <p className="error" role="alert">Could not draw the QR code.</p>;
  }
  if (!data) {
    return <span className="muted">Preparing QR code…</span>;
  }
  return (
    <img className="qr" src={data} width={size} height={size} alt={`QR code for ${payload}`}
      data-testid="qr" data-payload={payload} />
  );
}
