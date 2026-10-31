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

import { expect, test } from '@playwright/test';
import { signIn, stubTiles } from './support';

const SCREENS: Array<[string, string]> = [
  ['partners', 'Partners'], ['vehicles', 'Vehicles'], ['drivers', 'Drivers'],
  ['terminals', 'Terminals'], ['orders', 'Orders'], ['loads', 'Loads'], ['trips', 'Trips'],
  ['freight-bills', 'Freight bills'], ['exceptions', 'Exceptions'], ['claims', 'Claims'],
];

test.describe('moving around', () => {
  test('every screen in the sidebar loads from the real API without an error', async ({ page }) => {
    await signIn(page);

    for (const [key, title] of SCREENS) {
      await page.getByTestId(`nav-${key}`).click();
      await expect(page.getByRole('heading', { name: title, exact: true, level: 1 })).toBeVisible();
      await expect(page.getByText('Loading…')).toHaveCount(0);
      await expect(page.getByRole('alert'), `${title} showed an error`).toHaveCount(0);
    }
  });

  test('the settings screen is offered to someone who may read custom fields', async ({ page }) => {
    await signIn(page);

    await page.getByTestId('nav-custom-fields').click();
    await expect(page.getByRole('heading', { name: 'Custom fields', level: 1 })).toBeVisible();
    await expect(page.getByTestId('kind')).toBeVisible();
  });

  test('the delivery desk is offered to someone who may read proofs of delivery', async ({ page }) => {
    await signIn(page);

    await page.getByTestId('nav-delivery').click();
    await expect(page.getByRole('heading', { name: 'Delivery desk', level: 1 })).toBeVisible();
    await expect(page.getByTestId('consignment-id')).toBeVisible();
  });

  test('tracking links are offered to someone who may manage them', async ({ page }) => {
    await signIn(page);

    await page.getByTestId('nav-tracking').click();
    await expect(page.getByRole('heading', { name: 'Tracking links', level: 1 })).toBeVisible();
    await expect(page.getByTestId('consignment-id')).toBeVisible();
  });

  test('allocation rules are offered to someone who may read them', async ({ page }) => {
    await signIn(page);

    await page.getByTestId('nav-allocation-rules').click();
    await expect(page.getByRole('heading', { name: 'Allocation rules', level: 1 })).toBeVisible();
    await expect(page.getByText('Loading…')).toHaveCount(0);
    await expect(page.getByRole('alert')).toHaveCount(0);
    await expect(page.getByTestId('dry-run')).toBeVisible();
  });

  test('the map and multimodal screens load from the real API without an error', async ({ page }) => {
    await stubTiles(page);
    await signIn(page);

    for (const [key, title] of [['map', 'Network map'], ['trip-route', 'Trip route'], ['itineraries', 'Itineraries'],
      ['milk-runs', 'Milk runs']]) {
      await page.getByTestId(`nav-${key}`).click();
      await expect(page.getByRole('heading', { name: title, exact: true, level: 1 })).toBeVisible();
      await expect(page.getByText('Loading…')).toHaveCount(0);
      await expect(page.getByRole('alert'), `${title} showed an error`).toHaveCount(0);
    }
  });

  test('the layout fits a phone: nothing makes the page scroll sideways', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 800 });
    await signIn(page);
    await page.getByTestId('nav-partners').click();
    await expect(page.getByRole('heading', { name: 'Partners', level: 1 })).toBeVisible();

    const overflow = await page.evaluate(() => {
      const root = document.scrollingElement!;
      return root.scrollWidth - root.clientWidth;
    });
    expect(overflow).toBeLessThanOrEqual(1);
  });
});
