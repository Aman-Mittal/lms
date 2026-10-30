#!/usr/bin/env bash
#
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

# Assembles the GitHub Pages site into frontend/site:
#
#   /           landing page
#   /angular/   the Angular console
#   /react/     the React console
#   /api/       Swagger UI over the OpenAPI contract
#
# Both consoles' API clients are generated from backend/target/openapi/openapi.yaml,
# which Maven writes. Produce it first:
#
#   (cd backend && ./mvnw -q generate-sources)
#
# PAGES_BASE is the path the site is served under (default /lms/), and
# API_URL is the backend the consoles and Swagger UI talk to.

set -euo pipefail

cd "$(dirname "$0")"
PAGES_BASE="${PAGES_BASE:-/lms/}"
API_URL="${API_URL:-https://lms-platform-du75.onrender.com}"
SPEC=../backend/target/openapi/openapi.yaml

if [ ! -f "$SPEC" ]; then
    echo "build-site.sh: $SPEC not found; run 'cd backend && ./mvnw -q generate-sources' first" >&2
    exit 2
fi

rm -rf site
mkdir -p site

cp landing/index.html site/index.html

echo "== Angular"
(cd angular && npm install --no-audit --no-fund && npm run build -- --base-href "${PAGES_BASE}angular/")
cp -r angular/dist/angular/browser site/angular
# The Angular console reads its API address from a <meta> tag at run time, so
# the same build serves any backend. The build minifies the tag, so match it
# loosely and check that it was rewritten.
sed -E -i "s#<meta name=\"?lms-api-url\"? content[^>]*>#<meta name=\"lms-api-url\" content=\"${API_URL}\">#" site/angular/index.html
grep -q "content=\"${API_URL}\"" site/angular/index.html \
    || { echo "build-site.sh: the Angular API address was not set" >&2; exit 1; }

echo "== React"
(cd react && npm install --no-audit --no-fund && BASE_PATH="${PAGES_BASE}react/" VITE_API_URL="$API_URL" npm run build)
cp -r react/dist site/react

echo "== Swagger UI"
(cd swagger && npm install --no-audit --no-fund)
mkdir -p site/api
cp swagger/index.html site/api/index.html
for f in swagger-ui.css swagger-ui-bundle.js; do
    cp "swagger/node_modules/swagger-ui-dist/$f" site/api/
done
# The contract's own server entry names a different Render service, so
# "Try it out" would call the wrong host. Point it at the live API.
sed "s#^  - url: https://lms-platform.onrender.com\$#  - url: ${API_URL}#" "$SPEC" > site/api/openapi.yaml
grep -q "url: ${API_URL}" site/api/openapi.yaml || { echo "build-site.sh: server url was not rewritten" >&2; exit 1; }

# A Pages site is served as it is, and a leading underscore would make Jekyll skip files.
touch site/.nojekyll
echo "site ready in frontend/site"
