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

@number-series-e2e
Feature: An administrator numbers documents from a series, over the wire
  Number series end to end through the published API only: a format is
  previewed before it is saved, numbers are issued in turn, a number given
  back is logged and verifiable, and a series that has issued numbers keeps
  its format and can be switched off but not removed.

  Background:
    Given the demo administrator is signed in to manage number series

  Scenario: A gate-pass series issues in turn, takes a number back and verifies what it issued
    When the administrator previews a gate-pass format with a two-year code
    Then the preview shows this financial year and the next
    When the administrator sets up a tenant-wide gate-pass series
    And two gate passes are numbered from it
    Then the two numbers are consecutive and the preview offers the third
    When the first gate-pass number is given back because the pass was torn
    Then the first number verifies as released and the second as issued
    And the release is listed with its reason
    And giving the first number back again is refused with "number-already-released"
    And removing the series is refused with "number-series-in-use"
    When the series is switched off
    Then numbering from it is refused with "number-series-inactive"
    And a series that does not exist is not found
