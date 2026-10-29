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

import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter, withHashLocation } from '@angular/router';
import { provideApi } from './api';
import { GeneratedLmsGateway } from './gateway/generated-lms-gateway';
import { LmsGateway } from './gateway/lms-gateway';
import { bearerToken } from './auth';
import { API_URL } from './config';
import { routes } from './app.routes';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    // Hash routing: GitHub Pages cannot rewrite unknown paths to index.html.
    provideRouter(routes, withHashLocation()),
    provideHttpClient(withInterceptors([bearerToken])),
    provideApi(API_URL),
    // The one place the generated client is wired to the screens' interface.
    { provide: LmsGateway, useClass: GeneratedLmsGateway },
  ],
};
