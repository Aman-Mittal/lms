<!--
Copyright 2026 Aman Mittal

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->

# Deploying the demo to Render's free tier

The backend ships as a GraalVM native image (about 70 MiB resident, sub-second
start), which is what makes a 512 MB / 0.1 CPU free instance usable. CI builds
it and publishes `ghcr.io/aman-mittal/lms`; Render pulls that image, so no
build runs on Render.

## One-time setup

1. **Publish the image.** Merge to `main`. The *Native image* workflow
   compiles, smoke-tests and pushes `ghcr.io/aman-mittal/lms:latest`.
2. **Make the package public.** GitHub → Packages → `lms` → Package settings →
   Change visibility → Public. Render pulls with no credential, so a private
   package fails the deploy.
3. **Create a Postgres.** Render's own free Postgres expires after 30 days,
   so use an external one (Neon, Supabase, ...). Take the *direct* connection
   string, not a transaction-mode pooler: the application uses `SET ROLE` and
   per-request settings. Keep `?sslmode=require` if the provider needs it.
4. **Create the Blueprint.** Render → New → Blueprint → this repository, with
   the blueprint path `deploy/render.yaml`. It creates the free web service and
   asks for `DATABASE_URL` (the string from step 3), `JWT_PUBLIC_KEY` and
   `JWT_PRIVATE_KEY`.
5. **Recommended: set a JWT keypair.** Set `JWT_PUBLIC_KEY` and
   `JWT_PRIVATE_KEY` on the service (`deploy/.env.example` shows how to mint
   them). Without them an ephemeral pair is generated per process, and since a
   free instance sleeps after 15 idle minutes, every wake signs everybody out.
6. **Sign in.** `POST /api/v1/auth/login` with tenant `demo`, email
   `admin@demo.local`, and the password Render generated for
   `DEMO_ADMIN_PASSWORD` (the service's *Environment* tab).

### The database login

The first migration creates a restricted role, `lms_app`, and every request
runs as it; that is what makes row-level security apply. The role in
`DATABASE_URL` must be allowed to create roles and to be granted `lms_app`
(Neon's default owner role is). If it cannot, the migration only logs
`Could not grant lms_app ... SET ROLE will be skipped` and tenant isolation is
no longer enforced, so look for that line in the first startup log.

## Checking a deployment

`deploy/smoke.sh` checks what a health probe cannot: the contract is served,
the demo tenant exists, a real sign-in works, an anonymous read is refused and
every module's read side answers.

```bash
BASE_URL=https://lms-platform.onrender.com DEMO_ADMIN_PASSWORD=... bash deploy/smoke.sh
```

Or run the **Smoke (deployed)** workflow from the Actions tab, after adding
the generated password as the repository secret `DEMO_ADMIN_PASSWORD`. The same
script runs in the test suite and against every native binary CI builds, so a
broken check is noticed long before it is needed.

## Why there is a demo seed

Tenants have no self-service registration, so an empty database is a service
nobody can sign in to. With `LMS_DEMO_SEED=true`, every start ensures a `demo`
tenant, a root organisational unit, an `ADMIN` role holding every permission,
and one administrator. Each step is create-if-absent:

- later starts change nothing, and an administrator's password is never reset;
- permissions added by later migrations are granted to the role on the next
  start;
- when the database is replaced, the next start rebuilds the tenant
  unattended.

There is no default password. Enabled without one, the seeder logs an error
and the service still starts.

## What to expect on the free tier

- **Cold start.** Render takes roughly a minute to wake a sleeping service; the
  application itself starts in under a second once the container runs.
- **Database expiry.** An external Postgres does not expire with Render's free
  plan. Render's own free Postgres does (30 days after creation, 14-day grace),
  which is why the Blueprint no longer creates one. If your provider's free
  tier also recycles databases, point `DATABASE_URL` at the new one; migrations
  and the seed rebuild everything else.
- **Throughput.** A single 0.1 CPU instance sustains roughly 50–100 GPS pings
  per second. That is a demo figure, not the production target.

## Unverified

Render's documentation does not state outright that image-backed services can
use the *free* instance type. If the Blueprint is refused for that reason, use
the `jvm` target of `deploy/Dockerfile` instead: about 6 s to start and roughly
172 MiB resident, which still fits in 512 MB.
