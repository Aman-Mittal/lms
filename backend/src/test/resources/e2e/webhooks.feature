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

@webhooks
Feature: Signed webhooks tell an integrator what happened, over the wire
  Outbound webhooks end to end through the published API only. The
  administrator subscribes a receiver to event types; the platform posts each
  event to it, signed with the subscription's secret, on its own timer, and
  tries again when the receiver refuses. The receiver here is a local endpoint
  playing the integrator's server.

  Background:
    Given the demo administrator is signed in to manage webhooks
    And a webhook receiver is listening
    And a webhook subscription to trip events points at the receiver

  Scenario: A test ping is delivered signed with the subscription's secret
    When the administrator sends a test ping
    Then the receiver gets a "PING" delivery signed with the secret
    And the ping is listed as delivered after 1 attempt

  Scenario: A dispatched trip is announced to its subscriber, naming no tenant
    When a trip is dispatched with the webhook subscriber listening
    Then the receiver gets a "TRIP_DISPATCHED" delivery signed with the secret
    And that delivery names the dispatched trip and no tenant

  Scenario: A receiver that refuses is tried again until it accepts
    Given the receiver refuses the next delivery
    When the administrator sends a test ping
    Then the ping is listed as delivered after 2 attempts
    And the receiver saw the same delivery id both times
