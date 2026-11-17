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

import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { ToastHost } from './pages/toast-host';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, ToastHost],
  // The toasts are loaded when the browser is idle: nothing needs them until a save has been made.
  template: '<router-outlet />@defer (on idle) { <app-toast-host /> }',
})
export class App {}
