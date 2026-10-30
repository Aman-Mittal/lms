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
 * The API the console talks to. A page can name its own backend with
 * {@code <meta name="lms-api-url" content="...">}, which is how one build is
 * pointed at the Render demo, a local stack or a test server without being
 * rebuilt. The generated client's own default names a different Render
 * service, so a value is always set explicitly.
 */
const fromPage = typeof document === 'undefined'
  ? null
  : document.querySelector('meta[name="lms-api-url"]')?.getAttribute('content');

export const API_URL = fromPage || 'https://lms-platform-du75.onrender.com';

/** The demo tenant the seeder creates; prefilled on the sign-in form. */
export const DEMO_TENANT = 'demo';
