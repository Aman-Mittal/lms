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

import { Injector, inject } from '@angular/core';
import { LmsGateway } from './lms-gateway';

/** Names Angular or the runtime may look up on any provided object; none is a gateway call. */
const NOT_CALLS = new Set(['then', 'constructor', 'toJSON', 'ngOnDestroy', 'ngOnInit']);

/**
 * The gateway the screens inject, with the generated API client behind it loaded on first use. The client is about half
 * a megabyte of generated services; importing it from the app config put all of it in the initial bundle, though
 * nothing needs it until the sign-in form is submitted. Every gateway method returns a promise, so a call simply
 * waits for the code to arrive (once) and is then handed to the real gateway.
 */
export function lazyGateway(): LmsGateway {
  const parent = inject(Injector);
  let real: Promise<LmsGateway> | undefined;
  const load = (): Promise<LmsGateway> => real ??= import('./generated-lms-gateway').then((m) =>
    Injector.create({ providers: [{ provide: m.GeneratedLmsGateway, useClass: m.GeneratedLmsGateway }], parent })
      .get(m.GeneratedLmsGateway));
  return new Proxy({}, {
    get: (_target, name) => typeof name !== 'string' || NOT_CALLS.has(name)
      ? undefined
      : (...args: unknown[]) => load().then((gateway) =>
        (gateway as unknown as Record<string, (...a: unknown[]) => unknown>)[name]!.apply(gateway, args)),
  }) as LmsGateway;
}
