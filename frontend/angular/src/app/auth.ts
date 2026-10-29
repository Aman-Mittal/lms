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

import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { tap } from 'rxjs';
import { Session } from './session';

/** Adds the bearer token to every request once someone is signed in. */
export const bearerToken: HttpInterceptorFn = (req, next) => {
  const session = inject(Session);
  const router = inject(Router);
  const token = session.token();
  const authorised = token ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : req;
  return next(authorised).pipe(
    tap({
      error: (e) => {
        // An expired token is the one error every screen would otherwise handle alike.
        if (e instanceof HttpErrorResponse && e.status === 401 && token) {
          session.end();
          router.navigateByUrl('/login');
        }
      },
    }),
  );
};

export const signedInOnly: CanActivateFn = () => {
  const ok = inject(Session).signedIn();
  return ok ? true : inject(Router).parseUrl('/login');
};
