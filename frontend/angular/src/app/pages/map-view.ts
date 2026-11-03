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

import { Component, ElementRef, OnDestroy, afterRenderEffect, input, viewChild } from '@angular/core';
import L from 'leaflet';
import { TILE_ATTRIBUTION, TILE_URL } from '../config';
import { MapFeature } from '../gateway/lms-gateway';
import { drawFeatures } from '../map-drawing';

/**
 * A Leaflet map of some features. Leaflet owns the DOM inside the canvas, so
 * Angular only hands it the element and rebuilds the map when the features
 * change. The features are also listed as text, for a screen reader and for a
 * test to read.
 */
@Component({
  selector: 'app-map',
  template: `
    <figure class="map" data-testid="map">
      <div #canvas class="map-canvas" role="img" [attr.aria-label]="label()"></div>
      <figcaption>
        <ul class="map-features" data-testid="map-features">
          @for (f of features(); track f.kind + ':' + f.id) {
            <li data-testid="map-feature" [attr.data-kind]="f.kind" [attr.data-id]="f.id"
                [attr.data-lat]="f.points[0]?.lat?.toFixed(5)" [attr.data-lon]="f.points[0]?.lon?.toFixed(5)"
                [attr.data-points]="f.points.length" [attr.data-radius]="f.radiusM ?? ''">{{ f.label }}</li>
          }
        </ul>
      </figcaption>
    </figure>
  `,
})
export class MapView implements OnDestroy {
  readonly features = input.required<MapFeature[]>();
  readonly label = input('Map');
  private readonly canvas = viewChild.required<ElementRef<HTMLDivElement>>('canvas');
  private map: L.Map | null = null;

  constructor() {
    // After rendering: the canvas element has to exist before Leaflet can take it.
    afterRenderEffect(() => {
      const features = this.features();
      this.map?.remove();
      const map = L.map(this.canvas().nativeElement, { scrollWheelZoom: false });
      L.tileLayer(TILE_URL, { maxZoom: 18, attribution: TILE_ATTRIBUTION }).addTo(map);
      drawFeatures(map, features);
      this.map = map;
    });
  }

  ngOnDestroy(): void {
    this.map?.remove();
    this.map = null;
  }
}
