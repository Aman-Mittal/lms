#!/usr/bin/env python3
# Copyright 2026 Aman Mittal
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Judge a JMeter result file.

usage: summarise.py RESULTS.jtl MAX_ERROR_PERCENT MAX_P95_MS

Prints one line per request label and an overall line, and exits 1 when the
overall error rate or 95th percentile is over its limit, or when nothing was
measured at all -- an empty result is a failed run, not a clean one.
"""
import csv
import sys
from collections import defaultdict


def percentile(sorted_values, p):
    if not sorted_values:
        return 0
    rank = max(1, round(p / 100 * len(sorted_values)))
    return sorted_values[rank - 1]


def main(path, max_error_percent, max_p95_ms):
    times = defaultdict(list)
    errors = defaultdict(int)
    with open(path, newline="") as f:
        for row in csv.DictReader(f):
            label = row["label"]
            times[label].append(int(row["elapsed"]))
            if row["success"] != "true":
                errors[label] += 1

    if not times:
        print("FAIL no samples were recorded")
        return 1

    print(f"{'request':32} {'count':>6} {'errors':>7} {'p50 ms':>7} {'p95 ms':>7} {'max ms':>7}")
    everything = []
    for label in sorted(times):
        values = sorted(times[label])
        everything.extend(values)
        print(f"{label:32} {len(values):6d} {errors[label]:7d} "
              f"{percentile(values, 50):7d} {percentile(values, 95):7d} {values[-1]:7d}")

    everything.sort()
    total = len(everything)
    failed = sum(errors.values())
    error_percent = 100 * failed / total
    p95 = percentile(everything, 95)
    print(f"{'ALL':32} {total:6d} {failed:7d} {percentile(everything, 50):7d} "
          f"{p95:7d} {everything[-1]:7d}")

    ok = True
    if error_percent > max_error_percent:
        print(f"FAIL error rate {error_percent:.2f}% is over {max_error_percent}%")
        ok = False
    if p95 > max_p95_ms:
        print(f"FAIL 95th percentile {p95} ms is over {max_p95_ms} ms")
        ok = False
    if ok:
        print(f"PASS {total} requests, {error_percent:.2f}% errors, p95 {p95} ms")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1], float(sys.argv[2]), int(sys.argv[3])))
