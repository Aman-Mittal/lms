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
3. **Create the Blueprint.** Render → New → Blueprint → this repository, with
   the blueprint path `deploy/render.yaml`. It creates the free web service and
   the free Postgres and wires `DATABASE_URL` between them.
4. **Recommended: set a JWT keypair.** Set `JWT_PUBLIC_KEY` and
   `JWT_PRIVATE_KEY` on the service (`deploy/.env.example` shows how to mint
   them). Without them an ephemeral pair is generated per process, and since a
   free instance sleeps after 15 idle minutes, every wake signs everybody out.
5. **Sign in.** `POST /api/v1/auth/login` with tenant `demo`, email
   `admin@demo.local`, and the password Render generated for
   `DEMO_ADMIN_PASSWORD` (the service's *Environment* tab).

## Why there is a demo seed

Tenants have no self-service registration, so an empty database is a service
nobody can sign in to. With `LMS_DEMO_SEED=true`, every start ensures a `demo`
tenant, a root organisational unit, an `ADMIN` role holding every permission,
and one administrator. Each step is create-if-absent:

- later starts change nothing, and an administrator's password is never reset;
- permissions added by later migrations are granted to the role on the next
  start;
- when the free database expires (30 days after creation) and is replaced, the
  next start rebuilds the tenant unattended.

There is no default password. Enabled without one, the seeder logs an error
and the service still starts.

## What to expect on the free tier

- **Cold start.** Render takes roughly a minute to wake a sleeping service; the
  application itself starts in under a second once the container runs.
- **Database expiry.** Free Postgres expires 30 days after creation, with a
  14-day grace period. Recreate it from the Blueprint; migrations and the seed
  rebuild everything else.
- **Throughput.** A single 0.1 CPU instance sustains roughly 50–100 GPS pings
  per second. That is a demo figure, not the production target.

## Unverified

Render's documentation does not state outright that image-backed services can
use the *free* instance type. If the Blueprint is refused for that reason, use
the `jvm` target of `deploy/Dockerfile` instead: about 6 s to start and roughly
172 MiB resident, which still fits in 512 MB.
