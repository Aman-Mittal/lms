/*
 * Copyright 2026 Aman Mittal
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * The address a customer opens, built from the console's own location so it is
 * right wherever the console is served from (a Pages path, a custom domain).
 * The route is a hash route, like every other.
 */
export function trackingUrl(token: string): string {
  return `${window.location.href.split('#')[0]}#/track/${encodeURIComponent(token)}`;
}
