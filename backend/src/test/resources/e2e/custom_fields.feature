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

@customfields
Feature: A shipper tracks its own references on its records, over the wire
  Custom fields, end to end through the published API only. A shipper defines
  what it wants to record -- a purchase-order number on a customer, a fitness
  date on a lorry -- and then records values that the platform checks against
  the definition. Nothing here calls a service in-process: every step is a
  request made with the client generated from the contract.

  Background:
    Given the demo administrator is signed in at the custom fields desk

  Scenario: A customer carries a required purchase-order reference and an optional tier
    Given a customer is registered for custom values
    And the shipper defines a required text field "po" and a choice field "tier" of "GOLD" or "SILVER" for partners
    When the shipper records only the tier on the customer
    Then the write is refused as a conflict naming "po" as required
    When the shipper records the po "PO-7781" and the tier "GOLD" on the customer
    Then the customer's custom values are po "PO-7781" and tier "GOLD"
    And the partner definitions are listed with "po" before "tier"

  Scenario: A lorry's number, date and flag are each checked, and every problem is named at once
    Given a lorry is registered for custom values
    And the shipper defines a number field "axles", a date field "fitness" and a flag field "tracker" for vehicles
    When the shipper records axles "three", fitness "31/12/2026" and tracker "yes" on the lorry
    Then the write is refused as a conflict naming "axles", "fitness" and "tracker"
    When the shipper records axles 3, fitness "2026-12-31" and tracker true on the lorry
    Then the lorry's custom values are axles 3, fitness "2026-12-31" and tracker true

  Scenario: A retired field keeps what was stored and stops being accepted
    Given a driver is registered for custom values
    And the shipper defines a required text field "union" for drivers
    And the shipper records the union "UN-42" on the driver
    When the shipper retires the "union" field
    Then the driver's custom values still hold the union "UN-42"
    And recording the union "UN-43" on the driver is refused as an unknown field

  Scenario: A caller who may only read cannot reshape the form or fill it in
    Given a customer is registered for custom values
    And a custom field caller holding only "CUSTOM_FIELD_READ"
    Then that caller can list the partner definitions
    But that caller may not define a field
    And that caller may not record values on the customer

  Scenario: Another company sees none of it
    Given a customer is registered for custom values
    And the shipper defines a required text field "po" for partners
    And a second company with its own administrator is signed in for custom fields
    Then the second company sees no definition named "po" for partners
    And the second company is told the customer does not exist when it records values
