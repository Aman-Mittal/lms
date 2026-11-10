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
package com.lms.identity.query;

/**
 * What numbers in a proposed format look like, before any series is saved.
 *
 * @param sample          the first number of the current financial year
 * @param nextYearSample  the first number of the following one, showing the rollover;
 *                        the same counter continues for a series without a year code
 * @param financialYear   the current financial year, by the year it starts in (0 without a year code)
 */
public record FormatPreviewView(String sample, String nextYearSample, int financialYear) {
}
