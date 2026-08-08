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

# 3. GraalVM licensing and Apache-2.0 compliance

Status: **Accepted** · 2026-08-04

## Context

This project is distributed under the Apache License 2.0, and its binaries are
native executables produced by GraalVM Native Image. A native executable is not
a jar: parts of the GraalVM runtime — the SubstrateVM garbage collector,
threading, and JDK class library implementations — are **statically linked into
the shipped binary**.

That makes the GraalVM license a question about the *distributed artifact*, not
merely about a build tool, and it deserves a written answer rather than an
assumption.

A second, separate question: the repository is Apache-2.0 licensed but is **not**
an Apache Software Foundation project.

## Decision

### Native image toolchain

Build with **GraalVM Community Edition** or **Liberica NIK**, both distributed
under **GPL version 2 with the Classpath Exception** (GPLv2+CE) — the same
license as OpenJDK itself.

The Classpath Exception is what resolves this. It states that linking the
library with independent modules to produce an executable does not by itself
bring that executable under the GPL, and permits distributing the resulting
executable under terms of the author's choosing, provided the terms of the
independent modules are satisfied.

This is precisely the situation of every Java application ever shipped against
OpenJDK's class library. Statically linking the runtime rather than loading it
from a JVM at startup does not change the analysis: the exception is written in
terms of linking and combined works, not in terms of when the linking happens.

**Therefore:** LMS native binaries are distributed under the Apache License 2.0.
The GraalVM components linked into them remain GPLv2+CE, and the `NOTICE` file
records this.

**Explicitly avoided:** Oracle GraalVM under the GraalVM Free Terms and
Conditions (GFTC). GFTC is a proprietary license with usage conditions and
revocation terms that are not compatible with distributing an open-source
artifact. CI must pin a GPLv2+CE distribution. If a build ever silently resolves
Oracle GraalVM, that is a compliance defect, not a convenience.

### Dependency policy

No compile- or runtime-scope dependency may be licensed under GPL, AGPL, SSPL,
CDDL, EUPL, or MPL. Allowed: Apache-2.0, MIT, BSD-2-Clause, BSD-3-Clause, ISC,
0BSD. EPL-2.0 is tolerated in **test scope only** (JUnit), because test
dependencies are not distributed.

This is enforced mechanically, not by review:

- `license-maven-plugin` with `failOnBlacklist`, scoped to compile and runtime.
- `license-checker --production --onlyAllow "MIT;Apache-2.0;BSD-2-Clause;BSD-3-Clause;ISC;0BSD"`
  on the frontend.
- Apache RAT over the whole repository for file headers.

The most significant consequence of this policy is documented separately in
ADR-0004: **PostGIS is GPLv2 and is therefore not used**, which is why the
platform carries its own geometry code.

### File headers — not the ASF boilerplate

Sibling repositories in this workspace are genuine ASF projects and correctly
carry the header beginning "Licensed to the Apache Software Foundation (ASF)
under one or more contributor license agreements". **This repository must not
copy that header.** It would assert ASF provenance and a contributor license
agreement regime that do not exist here, which is a false statement about the
provenance of the code.

The correct header is the one from the Apache License appendix, with the actual
copyright holder — see `AGENTS.md` §5 for the exact text.

## Consequences

- A reviewer asking "how can you ship an Apache-2.0 binary built with a GPL
  toolchain?" gets a written answer instead of a shrug.
- Geometry must be implemented in-project rather than delegated to PostGIS
  (ADR-0004). This is real work and a real limitation on geospatial capability.
- CI must pin the GraalVM distribution explicitly. An unpinned setup action that
  changes its default to Oracle GraalVM would introduce a license defect that no
  test would catch.
- The `NOTICE` file must stay accurate as dependencies change. It is not
  decorative; it is the attribution required by §4 of the license.
