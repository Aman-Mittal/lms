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
package com.lms.shared.geo;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Track simplification, which is what keeps a 1 GB database viable.
 *
 * <p>The property that matters is that simplification is lossy about volume and
 * faithful about shape: a track must still go to the same places afterwards.
 */
class SimplifyTest {

    @Test
    @DisplayName("collapses a straight run to its endpoints")
    void straightLineCollapses() {
        // Where nearly all the volume is: a lorry on a motorway reporting every
        // thirty seconds along a line a map already knows about.
        List<LatLon> straight = new ArrayList<>();
        for (int i = 0; i <= 100; i++) {
            straight.add(new LatLon(19.0 + i * 0.001, 72.8));
        }

        assertThat(Simplify.douglasPeucker(straight, 20)).hasSize(2);
    }

    @Test
    @DisplayName("keeps a turn")
    void keepsCorners() {
        // A right-angle detour. Losing the corner would draw the lorry through
        // whatever is between the two legs.
        List<LatLon> corner = List.of(
                new LatLon(19.00, 72.80),
                new LatLon(19.05, 72.80),
                new LatLon(19.05, 72.85));

        assertThat(Simplify.douglasPeucker(corner, 20)).hasSize(3);
    }

    @Test
    @DisplayName("always keeps the first and last point")
    void keepsEndpoints() {
        // Where the journey began and ended is not made uninteresting by any
        // tolerance argument.
        List<LatLon> track = List.of(
                new LatLon(19.00, 72.80),
                new LatLon(19.001, 72.8001),
                new LatLon(19.002, 72.8002));

        List<LatLon> simplified = Simplify.douglasPeucker(track, 100_000);

        assertThat(simplified).hasSize(2);
        assertThat(simplified.get(0)).isEqualTo(track.get(0));
        assertThat(simplified.get(1)).isEqualTo(track.get(track.size() - 1));
    }

    @Test
    @DisplayName("a coarser tolerance keeps fewer points")
    void toleranceIsMonotonic() {
        List<LatLon> wobbly = new ArrayList<>();
        for (int i = 0; i <= 200; i++) {
            wobbly.add(new LatLon(19.0 + i * 0.001, 72.8 + Math.sin(i / 5.0) * 0.002));
        }

        int fine = Simplify.douglasPeucker(wobbly, 10).size();
        int coarse = Simplify.douglasPeucker(wobbly, 500).size();

        assertThat(coarse).isLessThanOrEqualTo(fine);
        assertThat(fine).isLessThan(wobbly.size());
    }

    @Test
    @DisplayName("handles degenerate inputs without complaint")
    void degenerateInputs() {
        // A retention job runs unattended. A track of one point -- a vehicle
        // that reported once and never moved -- must not throw at 3am.
        assertThat(Simplify.douglasPeucker(null, 20)).isEmpty();
        assertThat(Simplify.douglasPeucker(List.of(), 20)).isEmpty();
        assertThat(Simplify.douglasPeucker(List.of(new LatLon(19, 72)), 20)).hasSize(1);
        assertThat(Simplify.douglasPeucker(
                List.of(new LatLon(19, 72), new LatLon(20, 73)), 20)).hasSize(2);
    }

    @Test
    @DisplayName("survives a very long track without a stack overflow")
    void longTrackDoesNotOverflow() {
        // The reason the implementation is iterative. A stalled vehicle produces
        // tens of thousands of near-identical points, which is precisely the
        // degenerate case where the recursive formulation recurses once per
        // point -- and it would fail inside an unattended retention job.
        List<LatLon> huge = new ArrayList<>();
        for (int i = 0; i < 50_000; i++) {
            huge.add(new LatLon(19.0 + i * 0.00001, 72.8 + (i % 2) * 0.00001));
        }

        assertThat(Simplify.douglasPeucker(huge, 1)).isNotEmpty();
    }

    @Test
    @DisplayName("length is measured on every point, not the simplified line")
    void lengthUsesFullTrack() {
        // Simplification discards small deviations, and those add up over a long
        // journey. A distance that shrank when the data was tidied would be
        // wrong in a way somebody eventually bills on.
        List<LatLon> zigzag = new ArrayList<>();
        for (int i = 0; i <= 100; i++) {
            zigzag.add(new LatLon(19.0 + i * 0.001, 72.8 + (i % 2) * 0.0005));
        }

        double full = Simplify.lengthMetres(zigzag);
        double simplified = Simplify.lengthMetres(Simplify.douglasPeucker(zigzag, 200));

        assertThat(full).isGreaterThan(simplified);
    }

    @Test
    @DisplayName("length of a degenerate path is zero")
    void lengthOfNothing() {
        assertThat(Simplify.lengthMetres(null)).isZero();
        assertThat(Simplify.lengthMetres(List.of())).isZero();
        assertThat(Simplify.lengthMetres(List.of(new LatLon(19, 72)))).isZero();
    }
}
