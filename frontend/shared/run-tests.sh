#!/usr/bin/env bash
# Unit tests for the shared formatters. Neither console has a unit runner, so this compiles them with the
# TypeScript the React console already has and runs them on Node's built-in test runner. Run from anywhere.
set -euo pipefail
cd "$(dirname "$0")"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
../react/node_modules/.bin/tsc --outDir "$out" --module commonjs --target es2022 --strict --skipLibCheck \
  --types node --typeRoots ../react/node_modules/@types format.ts format.test.ts
node --test "$out"/format.test.js
