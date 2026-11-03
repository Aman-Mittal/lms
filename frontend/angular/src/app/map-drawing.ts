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
import type { MapFeature } from './gateway/lms-gateway';

/*
 * Draws features on a Leaflet map. Kept apart from any component so the
 * drawing is one plain function: a component only owns the map's lifetime.
 *
 * Circles and lines only, no image markers: Leaflet's default marker icons are
 * image files a bundler has to be told about, and a circle says as much here.
 */
const COLOURS: Record<MapFeature['kind'], string> = {
  site: '#2563eb', area: '#2563eb', vehicle: '#d97706', track: '#7c3aed', position: '#dc2626',
  'leg-ROAD': '#059669', 'leg-RAIL': '#9333ea', stop: '#0891b2', plant: '#b91c1c',
};

export function drawFeatures(map: L.Map, features: MapFeature[]): void {
  const bounds = L.latLngBounds([]);
  for (const f of features) {
    const colour = COLOURS[f.kind];
    const latLngs = f.points.map((p) => L.latLng(p.lat, p.lon));
    latLngs.forEach((p) => bounds.extend(p));
    if (f.kind === 'area') {
      L.polygon(latLngs, { color: colour, weight: 2, fillOpacity: 0.15 }).bindTooltip(f.label).addTo(map);
    } else if (latLngs.length >= 2) {
      L.polyline(latLngs, {
        color: colour, weight: f.kind === 'track' ? 3 : 4, dashArray: f.kind === 'leg-RAIL' ? '8 6' : undefined,
      }).bindTooltip(f.label).addTo(map);
    } else if (latLngs.length === 1) {
      if (f.radiusM) {
        L.circle(latLngs[0], { radius: f.radiusM, color: colour, weight: 1, fillOpacity: 0.1 }).addTo(map);
      }
      L.circleMarker(latLngs[0], { radius: f.kind === 'vehicle' || f.kind === 'position' ? 7 : 5, color: colour, fillOpacity: 0.9 })
        .bindTooltip(f.label).addTo(map);
    }
  }
  if (bounds.isValid()) {
    map.fitBounds(bounds, { padding: [24, 24], maxZoom: 13 });
  } else {
    map.setView([20, 78], 4);
  }
}
