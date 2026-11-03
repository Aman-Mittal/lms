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

@e2e_trip_cancel_terminal
Feature: Cancelling a trip and maintaining a terminal, over the wire
  The published API only. A trip that has not left the yard can be cancelled
  with a reason, gives its load back and is left alone by a second cancel; one
  that has been dispatched cannot. A terminal can be renamed, resized and
  moved under optimistic concurrency, and deleted only while nothing refers
  to it.

  Background:
    Given the demo administrator is signed in at the cancellation desk

  Scenario: A trip is cancelled before it leaves and its load can carry a replacement
    Given two sites, a lorry and an assigned trip are registered for the cancellation desk
    When the cancellation-desk trip is cancelled because "driver fell ill"
    Then the cancellation-desk trip reads CANCELLED with the reason "driver fell ill" and a cancellation time
    And cancelling it again succeeds and keeps the first reason
    And the cancellation-desk load is still AWARDED and a replacement trip can be raised against it but a second one cannot

  Scenario: A dispatched trip cannot be cancelled
    Given two sites, a lorry and a dispatched trip are registered for the cancellation desk
    Then cancelling the cancellation-desk trip is refused as not cancellable and it stays DISPATCHED

  Scenario: Cancellations that are refused, and who may not do it
    Given two sites, a lorry and an assigned trip are registered for the cancellation desk
    Then a cancellation without a reason is refused as invalid
    And a caller without TRIP_EXECUTE is refused as forbidden and the trip is untouched
    And an unknown trip, another tenant's trip and an anonymous caller are refused as not found, not found and unauthorised

  Scenario: A terminal is updated under optimistic concurrency
    Given two sites, a lorry and an assigned trip are registered for the cancellation desk
    When the first site is renamed "Renamed yard" with 9 docks using the version read
    Then the first site reads back renamed with 9 docks, the same code and a newer version
    And renaming it again with the version read before is refused as a concurrent modification
    And moving it onto the second site is refused as an overlap and bad bodies are refused as invalid
    And a caller without TERMINAL_UPDATE is refused as forbidden

  Scenario: A terminal is deleted only while nothing refers to it
    Given two sites, a lorry and an assigned trip are registered for the cancellation desk
    And a spare site is registered for the cancellation desk
    Then deleting the spare site succeeds, and it is then gone and cannot be deleted again
    And deleting the first site is refused as in use while the trip starts there
    And the first site is still refused after that trip is cancelled
    And a caller without TERMINAL_DELETE is refused as forbidden and another tenant's administrator is told it is not found
