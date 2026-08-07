--
-- Copyright 2026 Aman Mittal
--
-- Licensed under the Apache License, Version 2.0 (the "License");
-- you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
--     http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.
--

-- Brute-force resistance on the login endpoint.
--
-- BCrypt makes each guess expensive, which is necessary and not sufficient: an
-- attacker with a password list and no rate limit simply spends longer, and the
-- only cost is to the platform, whose 0.1 CPU is consumed hashing their
-- attempts. Nothing else in this design notices.
--
-- Counted per account rather than per IP address. Per-IP is the more obvious
-- control and the weaker one -- a distributed attempt comes from many
-- addresses, while the account being attacked is the same one every time. It
-- also cannot be evaded by a proxy, which per-IP can.

ALTER TABLE app_user
    ADD COLUMN failed_login_attempts INT NOT NULL DEFAULT 0;

-- A timestamp rather than a boolean. A locked flag needs something to unlock
-- it, and on a free instance that sleeps there is no reliable scheduler to do
-- so -- an account would stay locked until somebody noticed. An expiry unlocks
-- itself by the passage of time, which is the one mechanism that always runs.
ALTER TABLE app_user
    ADD COLUMN locked_until TIMESTAMPTZ;

-- When the counter was last touched, so a slow trickle of failures spread over
-- weeks does not accumulate into a lockout. Three wrong passwords in a minute
-- is an attack; three across three months is a person with a bad memory.
ALTER TABLE app_user
    ADD COLUMN last_failed_login_at TIMESTAMPTZ;

-- ---------------------------------------------------------------------------
-- Authentication events belong in the audit log
-- ---------------------------------------------------------------------------

-- V2 created audit_log with immutability rules and nothing ever wrote to it.
-- An audit table that is empty is worse than no audit table: it answers "was
-- there any suspicious activity" with silence, and silence reads as no.
--
-- Failed logins are recorded against the tenant but with a null actor, because
-- at that point nobody has proved who they are. The email is deliberately not
-- stored: a log of addresses that failed to authenticate is a list of valid
-- usernames for whoever reads the log next.
CREATE INDEX audit_log_action_idx ON audit_log (tenant_id, action, occurred_at DESC);
