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

import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import { useEffect, useRef } from 'react';
import { TILE_ATTRIBUTION, TILE_URL } from '../config';
import type { MapFeature } from '../gateway/lmsGateway';
import { drawFeatures } from '../mapDrawing';

/*
 * REACT CONCEPT: useRef for something React does not render.
 * Leaflet owns the DOM inside the map's div and changes it as the user pans.
 * React must not touch that div's children, so it only hands Leaflet the
 * element (a ref) and lets an effect create and destroy the map.
 */
export function MapView({ features, label }: { features: MapFeature[]; label: string }) {
  const canvas = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!canvas.current) {
      return;
    }
    const map = L.map(canvas.current, { scrollWheelZoom: false });
    L.tileLayer(TILE_URL, { maxZoom: 18, attribution: TILE_ATTRIBUTION }).addTo(map);
    drawFeatures(map, features);
    // Cleanup: the next features, or leaving the page, starts from a fresh map.
    return () => {
      map.remove();
    };
  }, [features]);

  return (
    <figure className="map" data-testid="map">
      <div ref={canvas} className="map-canvas" role="img" aria-label={label} />
      {/* The same features as text: for a screen reader, and for a test to read. */}
      <figcaption>
        <ul className="map-features" data-testid="map-features">
          {features.map((f) => (
            <li key={`${f.kind}:${f.id}`} data-testid="map-feature" data-kind={f.kind} data-id={f.id}
              data-lat={f.points[0]?.lat.toFixed(5)} data-lon={f.points[0]?.lon.toFixed(5)}
              data-points={f.points.length} data-radius={f.radiusM ?? ''}>
              {f.label}
            </li>
          ))}
        </ul>
      </figcaption>
    </figure>
  );
}
