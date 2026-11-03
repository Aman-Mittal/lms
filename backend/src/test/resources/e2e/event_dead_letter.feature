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

@dead-letter
Feature: An operator sees the events a listener could not handle, and replays or discards them, over the wire
  The event dead-letter, end to end through the published API. An event whose
  listener failed on every attempt is parked for the company it belongs to; its
  administrator lists it, sees why it failed, and either replays it to the same
  listener once the fault is fixed or discards it with a reason. The event is
  published and failed through the real publication registry; everything an
  operator does is a request made with the client generated from the contract.

  Background:
    Given the demo administrator is signed in to look after events

  Scenario: A parked event is replayed once the fault is fixed
    Given an event for the demo company has failed until it was parked
    When the administrator lists the parked events
    Then the parked event is listed with the failure that parked it
    When the fault is fixed and the administrator replays the event
    Then the replay reports the event as replayed
    And the event is no longer in the parked list

  Scenario: Replaying while the fault persists parks the event again
    Given an event for the demo company has failed until it was parked
    When the administrator replays the event
    Then the replay reports the event as parked again after 2 attempts

  Scenario: A parked event is discarded with a reason
    Given an event for the demo company has failed until it was parked
    When the administrator discards the event because "Handled by hand"
    Then the event is listed as discarded with the reason "Handled by hand"

  Scenario: Another company does not see the parked event
    Given an event for the demo company has failed until it was parked
    And a second company with its own administrator is signed in to look after events
    Then the second company is told the parked event does not exist
