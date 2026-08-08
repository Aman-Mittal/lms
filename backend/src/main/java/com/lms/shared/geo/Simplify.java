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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Douglas-Peucker line simplification.
 *
 * <p>Reduces a GPS track to the points that carry its shape, discarding the
 * ones that lie close to a line already described by their neighbours. A lorry
 * on a motorway reports two hundred points that a straight line describes
 * exactly; keeping them costs storage on a 1 GB database and tells a map
 * nothing it does not already know.
 *
 * <p>Iterative rather than recursive. A stalled vehicle can produce tens of
 * thousands of points in a single track, and the recursive formulation is
 * depth-proportional to the input in the degenerate case -- which is a stack
 * overflow in a retention job that runs unattended at three in the morning.
 */
public final class Simplify {

    private Simplify() {
    }

    /**
     * Simplifies to a tolerance in metres.
     *
     * @param tolerance how far a point may sit from the line between its
     *                  retained neighbours before it must be kept itself
     */
    public static List<LatLon> douglasPeucker(List<LatLon> points, double tolerance) {
        if (points == null || points.size() <= 2) {
            return points == null ? List.of() : List.copyOf(points);
        }

        boolean[] keep = new boolean[points.size()];
        // The endpoints always survive: they are where the journey began and
        // ended, and no tolerance argument makes those uninteresting.
        keep[0] = true;
        keep[points.size() - 1] = true;

        Deque<int[]> pending = new ArrayDeque<>();
        pending.push(new int[] {0, points.size() - 1});

        while (!pending.isEmpty()) {
            int[] span = pending.pop();
            int first = span[0];
            int last = span[1];
            if (last <= first + 1) {
                continue;
            }

            double worst = -1;
            int worstIndex = -1;
            LatLon start = points.get(first);
            LatLon end = points.get(last);

            for (int i = first + 1; i < last; i++) {
                double distance = GeoUtils.distanceToSegmentMetres(points.get(i), start, end);
                if (distance > worst) {
                    worst = distance;
                    worstIndex = i;
                }
            }

            if (worst > tolerance) {
                keep[worstIndex] = true;
                pending.push(new int[] {first, worstIndex});
                pending.push(new int[] {worstIndex, last});
            }
        }

        List<LatLon> simplified = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            if (keep[i]) {
                simplified.add(points.get(i));
            }
        }
        return simplified;
    }

    /** Total length of a path, for the distance a completed trip actually covered. */
    public static double lengthMetres(List<LatLon> points) {
        if (points == null || points.size() < 2) {
            return 0;
        }
        double total = 0;
        for (int i = 1; i < points.size(); i++) {
            total += GeoUtils.haversineMetres(points.get(i - 1), points.get(i));
        }
        return total;
    }
}
