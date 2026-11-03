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

import { expect, test, type Page } from '@playwright/test';
import { signIn, stubTiles } from './support';

/**
 * Types into an input the moment it is added to the page, before the screen
 * around it has finished starting -- what a quick hand or a slow machine does.
 * The value is set through the element's own setter and announced with an
 * input event, as a keystroke would be, so either console sees it as typed.
 */
async function typeOnArrival(page: Page, testId: string, value: string): Promise<void> {
  await page.evaluate(([id, text]) => {
    const observer = new MutationObserver(() => {
      const input = document.querySelector<HTMLInputElement>(`[data-testid="${id}"]`);
      if (input) {
        observer.disconnect();
        Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(input, text);
        input.dispatchEvent(new Event('input', { bubbles: true }));
      }
    });
    observer.observe(document.body, { childList: true, subtree: true });
  }, [testId, value]);
}

const LOOK_UPS: Array<{ nav: string; heading: string; input: string; button: string }> = [
  { nav: 'nav-delivery', heading: 'Delivery desk', input: 'consignment-id', button: 'Look up' },
  { nav: 'nav-tracking', heading: 'Tracking links', input: 'consignment-id', button: 'Look up' },
  { nav: 'nav-trip-route', heading: 'Trip route', input: 'trip-id', button: 'Show route' },
];

/*
 * An id typed as soon as a look-up screen appears is the id looked up. The
 * Angular screens once lost it: ngModel connects to its input only after the
 * screen is shown, then writes its empty starting value over what is there,
 * and the look-up went out with no id (#144).
 */
test.describe('look-ups keep what was typed', () => {
  for (const l of LOOK_UPS) {
    test(`${l.heading}: an id typed the moment the screen opens is the one looked up`, async ({ page }) => {
      await stubTiles(page);
      await signIn(page);
      const id = crypto.randomUUID();

      await typeOnArrival(page, l.input, id);
      await page.getByTestId(l.nav).click();
      await expect(page.getByRole('heading', { name: l.heading, exact: true, level: 1 })).toBeVisible();
      await expect(page.getByTestId(l.input)).toHaveValue(id);

      const asked = page.waitForRequest((r) => r.url().includes(id), { timeout: 10_000 });
      await page.getByRole('button', { name: l.button }).click();
      await asked;
    });
  }
});
