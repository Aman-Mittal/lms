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

Feature: Identity, access and tenant isolation
  Vision document 2.1 and 3.1.

  Data belonging to one tenant must be structurally unreachable from another,
  and that must hold even when application code forgets to say so. These
  scenarios exercise the guarantee rather than the configuration: they assert
  what a caller can actually read, not that a policy exists.

  Background:
    Given a tenant "acme" named "Acme Logistics"
    And a tenant "globex" named "Globex Freight"
    And tenant "acme" has an organisational unit "/acme/" named "Acme HQ" of type "HQ"
    And tenant "globex" has an organisational unit "/globex/" named "Globex HQ" of type "HQ"
    And tenant "acme" has a user "dispatcher@acme.test" with password "correct-horse" in unit "/acme/"
    And tenant "globex" has a user "dispatcher@globex.test" with password "correct-horse" in unit "/globex/"

  Scenario: A user authenticates with their tenant code
    When "dispatcher@acme.test" logs in to tenant "acme" with password "correct-horse"
    Then authentication succeeds
    And the access token carries the tenant of "acme"
    And the access token carries the organisational path "/acme/"

  Scenario: The same email may exist in two tenants without collision
    Given tenant "acme" has a user "shared@example.test" with password "acme-secret" in unit "/acme/"
    And tenant "globex" has a user "shared@example.test" with password "globex-secret" in unit "/globex/"
    When "shared@example.test" logs in to tenant "acme" with password "acme-secret"
    Then authentication succeeds
    And the access token carries the tenant of "acme"

  Scenario: Credentials from one tenant do not work against another
    When "shared@example.test" logs in to tenant "globex" with password "acme-secret"
    Then authentication fails

  Scenario: A wrong password is rejected
    When "dispatcher@acme.test" logs in to tenant "acme" with password "wrong"
    Then authentication fails

  Scenario: An unknown tenant code is rejected
    When "dispatcher@acme.test" logs in to tenant "no-such-tenant" with password "correct-horse"
    Then authentication fails

  Scenario: Failure messages do not distinguish between causes
    # Distinguishing "no such tenant" from "no such user" from "wrong password"
    # would let an unauthenticated caller enumerate tenants and accounts.
    When "dispatcher@acme.test" logs in to tenant "acme" with password "wrong"
    And "nobody@acme.test" logs in to tenant "acme" with password "correct-horse"
    Then both authentication failures report the same message

  Scenario: A suspended user cannot authenticate
    Given the user "dispatcher@acme.test" of tenant "acme" is suspended
    When "dispatcher@acme.test" logs in to tenant "acme" with password "correct-horse"
    Then authentication fails

  Scenario: Row-level security hides another tenant's users
    # The query deliberately carries NO tenant predicate. Postgres row-level
    # security must still confine the result to the scoped tenant. This is the
    # guarantee that survives a forgotten WHERE clause.
    When users are listed without a tenant predicate while scoped to "acme"
    Then only users belonging to "acme" are returned

  Scenario: An unscoped query returns nothing rather than everything
    # Failing closed is the whole point: with no tenant bound, the safe result
    # is zero rows, never every tenant's rows.
    When users are listed without a tenant predicate and without any tenant scope
    Then no users are returned

  Scenario: Organisational visibility is strictly top-down
    Given tenant "acme" has an organisational unit "/acme/north/" named "North Region" of type "REGION"
    And tenant "acme" has an organisational unit "/acme/north/branch-7/" named "Branch 7" of type "BRANCH"
    And tenant "acme" has an organisational unit "/acme/south/" named "South Region" of type "REGION"
    When the organisational subtree of "/acme/north/" is listed for tenant "acme"
    Then the subtree contains "/acme/north/" and "/acme/north/branch-7/"
    And the subtree does not contain "/acme/south/"

  Scenario: Refresh tokens rotate on use
    Given "dispatcher@acme.test" has logged in to tenant "acme" with password "correct-horse"
    When the refresh token is exchanged
    Then a new access token is issued
    And the previous refresh token is no longer accepted

  # ------------------------------------------------------------------ RBAC

  Scenario: A permission the user does not hold refuses the command
    # The permission vocabulary was seeded in V2 and carried in the token's
    # `perms` claim from the first commit, and until now nothing read it: every
    # authenticated user of a tenant could dispatch trips and blacklist
    # partners. These two scenarios are what stops that returning.
    Given the tenant scope is "acme" at "/acme/"
    And the signed-in user lacks "PARTNER_CREATE"
    When a partner "BLOCKED" is registered
    Then the command is refused as unauthorised

  Scenario: The permission the user does hold is enough
    Given the tenant scope is "acme" at "/acme/"
    And the signed-in user holds only "PARTNER_CREATE"
    When a partner "ALLOWED" is registered
    Then the command succeeds

  # ------------------------------------------------- brute-force resistance

  Scenario: Five wrong passwords lock the account
    # BCrypt makes each guess expensive, which is necessary and not sufficient:
    # an attacker with a password list just spends longer, and the only cost is
    # to a 0.1-CPU instance busy hashing their attempts.
    Given a tenant "lockco" named "Lock Co"
    And tenant "lockco" has an organisational unit "/lockco/" named "Lock HQ" of type "HQ"
    And tenant "lockco" has a user "ops@lockco.test" with password "correct-horse" in unit "/lockco/"
    When "ops@lockco.test" logs in to tenant "lockco" with password "wrong-1"
    And "ops@lockco.test" logs in to tenant "lockco" with password "wrong-2"
    And "ops@lockco.test" logs in to tenant "lockco" with password "wrong-3"
    And "ops@lockco.test" logs in to tenant "lockco" with password "wrong-4"
    And "ops@lockco.test" logs in to tenant "lockco" with password "wrong-5"
    Then authentication fails
    And the account "ops@lockco.test" of tenant "lockco" is locked

  Scenario: A locked account refuses even the right password
    # And with the same message as a wrong one. "This account is locked" would
    # confirm the address exists and tell an attacker their guessing worked.
    Given a tenant "lockco2" named "Lock Co 2"
    And tenant "lockco2" has an organisational unit "/lockco2/" named "Lock HQ" of type "HQ"
    And tenant "lockco2" has a user "ops@lockco2.test" with password "correct-horse" in unit "/lockco2/"
    And the account "ops@lockco2.test" of tenant "lockco2" is locked out
    When "ops@lockco2.test" logs in to tenant "lockco2" with password "correct-horse"
    Then authentication fails

  Scenario: A successful sign-in clears the failure count
    # Otherwise a legitimate user accumulates their way into a lockout across
    # months of occasional typos.
    Given a tenant "lockco3" named "Lock Co 3"
    And tenant "lockco3" has an organisational unit "/lockco3/" named "Lock HQ" of type "HQ"
    And tenant "lockco3" has a user "ops@lockco3.test" with password "correct-horse" in unit "/lockco3/"
    And "ops@lockco3.test" logs in to tenant "lockco3" with password "wrong-1"
    And "ops@lockco3.test" logs in to tenant "lockco3" with password "wrong-2"
    When "ops@lockco3.test" logs in to tenant "lockco3" with password "correct-horse"
    Then authentication succeeds
    And the account "ops@lockco3.test" of tenant "lockco3" has no recorded failures

  Scenario: Authentication attempts reach the audit log
    # V2 created audit_log with rules refusing UPDATE and DELETE, and nothing
    # ever wrote to it. An empty audit table answers "was there anything
    # suspicious" with silence, and silence reads as no.
    Given a tenant "audco" named "Aud Co"
    And tenant "audco" has an organisational unit "/audco/" named "Aud HQ" of type "HQ"
    And tenant "audco" has a user "ops@audco.test" with password "correct-horse" in unit "/audco/"
    When "ops@audco.test" logs in to tenant "audco" with password "nope"
    And "ops@audco.test" logs in to tenant "audco" with password "correct-horse"
    Then the audit log for tenant "audco" contains "LOGIN_FAILED"
    And the audit log for tenant "audco" contains "LOGIN_SUCCEEDED"
