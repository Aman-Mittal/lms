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

// Vite replaces import.meta.env.* at build time, so a fork can point the
// console at its own backend with VITE_API_URL=... npm run build.
export const API_URL: string =
  import.meta.env.VITE_API_URL ?? 'https://lms-platform-du75.onrender.com';

/** The demo tenant the seeder creates; prefilled on the sign-in form. */
export const DEMO_TENANT = 'demo';
