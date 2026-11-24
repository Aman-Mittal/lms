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

import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiProblem, LmsGateway } from '../gateway/lms-gateway';
import { CorridorDraft, CorridorRow } from '../gateway/small-ops-types';
import { EntityPicker } from './entity-picker';
import { ErrorBanner } from './error-banner';
import { ConfirmHost } from './confirm-dialog';
import { confirmWording, type PendingConfirm } from '../../../../shared/confirm';

const BLANK: CorridorDraft = { originTerminalId: '', destinationTerminalId: '', name: '', points: '', widthMetres: '' };

/**
 * The route corridors of the lanes: a trip on a lane with a corridor is measured against its polyline instead of the
 * straight line between its terminals. Import replaces a lane's corridor; clearing it takes the lane back to the
 * straight line.
 */
@Component({
  selector: 'app-corridor-desk',
  imports: [ConfirmHost, EntityPicker, ErrorBanner, FormsModule],
  template: `
    <section data-testid="corridors">
      <h3>Route corridors</h3>
      <app-confirm-host [(pending)]="pending" />
      @if (rows().length === 0 && read()) {
        <p class="muted" data-testid="no-corridors">No lane has a corridor: its trips are measured against the straight line.</p>
      }
      @if (rows().length > 0) {
        <div class="table-wrap"><table data-testid="corridor-list">
          <thead><tr><th>Name</th><th>From</th><th>To</th><th>Points</th><th>Width (m)</th><th></th></tr></thead>
          <tbody>
            @for (c of rows(); track c.id) {
              <tr data-testid="corridor-row" [attr.data-id]="c.id">
                <td data-testid="corridor-name">{{ c.name }}</td><td>{{ c.origin }}</td><td>{{ c.destination }}</td>
                <td>{{ c.pointCount }}</td><td>{{ c.widthMetres || '—' }}</td>
                <td><button type="button" class="link" data-testid="corridor-clear" [attr.aria-label]="'Clear corridor ' + c.name"
                            (click)="askClear(c)">Clear corridor</button></td>
              </tr>
            }
          </tbody>
        </table></div>
      }
      <form ngNativeValidate data-testid="corridor-form" (submit)="submit($event)">
        <app-entity-picker [kind]="'terminal'" placeholder="Origin terminal" ariaLabel="Corridor origin terminal id"
                           testId="corridor-origin" [required]="true" [value]="draft().originTerminalId"
                           (valueChange)="set('originTerminalId', $event)" />
        <app-entity-picker [kind]="'terminal'" placeholder="Destination terminal" ariaLabel="Corridor destination terminal id"
                           testId="corridor-destination" [required]="true" [value]="draft().destinationTerminalId"
                           (valueChange)="set('destinationTerminalId', $event)" />
        <input placeholder="Corridor name" aria-label="Corridor name" data-testid="corridor-name-input" required
               [value]="draft().name" (input)="set('name', $any($event.target).value)" />
        <input inputmode="decimal" placeholder="Width in metres (optional)" aria-label="Corridor width in metres"
               data-testid="corridor-width" [value]="draft().widthMetres" (input)="set('widthMetres', $any($event.target).value)" />
        <textarea rows="5" placeholder="One point per line: longitude, latitude" aria-label="Corridor points"
                  data-testid="corridor-points" required [value]="draft().points"
                  (input)="set('points', $any($event.target).value)"></textarea>
        <button type="submit" data-testid="corridor-import">Import corridor</button>
      </form>
      @if (done()) { <p class="ok" role="status" data-testid="corridor-done">{{ done() }}</p> }
      @if (error().message) { <app-error-banner [message]="error().message" [code]="error().code" testId="corridor-error" codeTestId="corridor-refusal" /> }
    </section>
  `,
})
export class CorridorDesk implements OnInit {
  /** The destructive action waiting for its confirmation, shown by the confirm host in the template. */
  protected readonly pending = signal<PendingConfirm | null>(null);
  private readonly gateway = inject(LmsGateway);

  readonly rows = signal<CorridorRow[]>([]);
  readonly read = signal(false);
  readonly draft = signal<CorridorDraft>(BLANK);
  readonly done = signal('');
  readonly error = signal({ message: '', code: '' });

  ngOnInit(): Promise<void> {
    return this.run(() => this.reload());
  }

  set<K extends keyof CorridorDraft>(key: K, value: CorridorDraft[K]): void {
    this.draft.update((d) => ({ ...d, [key]: value }));
  }

  submit(event: Event): Promise<void> {
    event.preventDefault();
    return this.run(async () => {
      const name = this.draft().name.trim();
      await this.gateway.importCorridor(this.draft());
      this.draft.set(BLANK);
      await this.reload();
      this.done.set(`Corridor ${name} imported.`);
    });
  }

  askClear(c: CorridorRow): void {
    this.pending.set({
      wording: confirmWording('Clear corridor', c.name, `Trips on ${c.origin} to ${c.destination} are measured against the straight line again.`),
      run: () => this.clear(c),
    });
  }

  private clear(c: CorridorRow): Promise<void> {
    return this.run(async () => {
      await this.gateway.clearCorridor(c.id);
      await this.reload();
      this.done.set(`Corridor ${c.name} cleared.`);
    });
  }

  private async reload(): Promise<void> {
    this.rows.set(await this.gateway.corridors());
    this.read.set(true);
  }

  private async run(action: () => Promise<void>): Promise<void> {
    this.done.set('');
    this.error.set({ message: '', code: '' });
    try {
      await action();
    } catch (e) {
      this.error.set({ message: (e as Error).message, code: e instanceof ApiProblem ? e.code : '' });
    }
  }
}
