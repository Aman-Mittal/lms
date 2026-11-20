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
import { openScreen, signIn } from './support';
import { stubUsers, stubUsersSession, type UsersStub } from './support-users';

/*
 * Users and roles, in both consoles: create a user by typing the login id and password, search, edit the name and roles,
 * switch a user off and on, set a new password, and the administrator guards; create a custom role from the permission
 * list, edit its permissions, delete an unused one, and the refusals. The API is stubbed so the same assertions run
 * whatever the demo tenant holds; the stub keeps the server's rules and the spec checks what each console sends. Set
 * E2E_STUB_SESSION=1 to stub the sign-in too, for a run with no backend at all.
 */

test.beforeEach(async ({ page }) => {
  if (process.env.E2E_STUB_SESSION) {
    await stubUsersSession(page);
  }
});

/** A password built at run time, long enough and with letters and digits. */
const secret = () => `Zq7-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;

/** The last request the stub saw whose path ends as given. */
function sent(stub: UsersStub, ending: string) {
  return stub.changes.filter((c) => c.path.endsWith(ending)).at(-1);
}

async function openAccounts(page: Page) {
  await openScreen(page, 'nav-admin-accounts', 'User accounts');
}

async function openRoles(page: Page) {
  await openScreen(page, 'nav-admin-roles', 'Roles');
}

async function createUser(page: Page, email: string, password: string, name: string, roles: string[]) {
  await page.getByTestId('acct-email').fill(email);
  await page.getByTestId('acct-password').fill(password);
  await page.getByTestId('acct-name').fill(name);
  for (const code of roles) {
    await page.getByTestId(`acct-role-${code}`).check();
  }
  await page.getByTestId('acct-create').click();
}

test.describe('user accounts', () => {
  test('a user is created with a typed login id and password, then edited, switched off and on, and given a new password', async ({ page }) => {
    const stub = await stubUsers(page);
    await signIn(page);
    await openAccounts(page);
    const email = `new.person.${Date.now()}@example.test`;
    const password = secret();

    await test.step('a weak password is refused by its code', async () => {
      await createUser(page, email, 'short1', 'New Person', ['PLANNER']);
      await expect(page.getByTestId('acct-create-refusal')).toHaveText(/password-policy/);
    });

    await test.step('a user is created', async () => {
      await createUser(page, email, password, 'New Person', ['PLANNER']);
      await expect(page.getByTestId('acct-done')).toContainText('The user is created');
      expect(sent(stub, '/users')?.body).toMatchObject({ email, fullName: 'New Person', roles: ['PLANNER'] });
      await expect(page.getByTestId('acct-panel')).toBeVisible();
      await expect(page.getByTestId('acct-shown-email')).toHaveText(email);
    });

    await test.step('the login id cannot be used twice', async () => {
      await createUser(page, email, secret(), 'Again', []);
      await expect(page.getByTestId('acct-create-refusal')).toHaveText(/login-id-taken/);
    });

    await test.step('users are searched by name', async () => {
      await page.getByTestId('acct-search').fill('new person');
      await page.getByTestId('acct-search-go').click();
      await expect(page.getByTestId('acct-item')).toHaveCount(1);
    });

    await test.step('the name and roles are edited under the version read', async () => {
      await page.getByTestId('acct-select').click();
      await page.getByTestId('acct-edit-name').fill('Renamed Person');
      await page.getByTestId('acct-edit-role-VIEWER').check();
      await page.getByTestId('acct-save').click();
      await expect(page.getByTestId('acct-done')).toHaveText('The changes are saved.');
      const change = sent(stub, `/users/${stub.users.find((u) => u.email === email)!.id}`);
      expect(change?.ifMatch).toBe('"0"');
      expect(change?.body).toMatchObject({ fullName: 'Renamed Person', roles: ['PLANNER', 'VIEWER'] });
    });

    await test.step('the user is switched off and on, each with a reason', async () => {
      await page.getByTestId('acct-reason').fill('left the company');
      await page.getByTestId('acct-deactivate').click();
      await expect(page.getByTestId('acct-done')).toHaveText('The user is switched off.');
      await expect(page.getByTestId('acct-shown-status')).toContainText('SUSPENDED');
      await page.getByTestId('acct-reason').fill('came back');
      await page.getByTestId('acct-reactivate').click();
      await expect(page.getByTestId('acct-done')).toHaveText('The user is switched on.');
      await expect(page.getByTestId('acct-shown-status')).toContainText('ACTIVE');
    });

    await test.step('a new password is set, or refused by its code', async () => {
      await page.getByTestId('acct-new-password').fill('short1');
      await page.getByTestId('acct-set-password').click();
      await expect(page.getByTestId('acct-refusal')).toHaveText(/password-policy/);
      await page.getByTestId('acct-new-password').fill(secret());
      await page.getByTestId('acct-set-password').click();
      await expect(page.getByTestId('acct-done')).toHaveText('The new password is set.');
    });
  });

  test('an administrator cannot deactivate themselves, and the last administrator keeps the role', async ({ page }) => {
    await stubUsers(page);
    await signIn(page);
    await openAccounts(page);
    await page.getByTestId('acct-select').click();
    await page.getByTestId('acct-reason').fill('testing');
    await page.getByTestId('acct-deactivate').click();
    await expect(page.getByTestId('acct-refusal')).toHaveText(/self-deactivation/);

    await page.getByTestId('acct-edit-role-ADMIN').uncheck();
    await page.getByTestId('acct-save').click();
    await expect(page.getByTestId('acct-refusal')).toHaveText(/last-administrator/);
  });

  test('without the right to manage users no create or edit form is offered', async ({ page }) => {
    test.skip(!process.env.E2E_STUB_SESSION, 'dropping rights is done on the stubbed sign-in');
    await stubUsers(page);
    await stubUsersSession(page, ['USER_MANAGE']);
    await signIn(page);
    await expect(page.getByTestId('nav-admin-accounts')).toHaveCount(0);
  });
});

test.describe('roles', () => {
  test('a custom role is created from the permission list, edited under If-Match and deleted while unused', async ({ page }) => {
    const stub = await stubUsers(page);
    await signIn(page);
    await openRoles(page);

    await test.step('a role is created', async () => {
      await page.getByTestId('role-code').fill('yard_lead');
      await page.getByTestId('role-name').fill('Yard lead');
      await page.getByTestId('role-perm-TRIP_READ').check();
      await page.getByTestId('role-create').click();
      await expect(page.getByTestId('role-done')).toHaveText('The role is created.');
      expect(sent(stub, '/roles')?.body).toMatchObject({ code: 'yard_lead', name: 'Yard lead', permissions: ['TRIP_READ'] });
      await expect(page.getByTestId('role-item').filter({ hasText: 'YARD_LEAD' })).toHaveCount(1);
    });

    await test.step('a taken code is refused by its code', async () => {
      await page.getByTestId('role-code').fill('YARD_LEAD');
      await page.getByTestId('role-name').fill('Again');
      await page.getByTestId('role-create').click();
      await expect(page.getByTestId('role-create-refusal')).toHaveText(/role-code-taken/);
    });

    await test.step('its permissions are edited', async () => {
      await page.getByTestId('role-edit-perm-TRIP_EXECUTE').check();
      await page.getByTestId('role-save').click();
      await expect(page.getByTestId('role-done')).toHaveText('The role is saved.');
      const role = stub.roles.find((r) => r.code === 'YARD_LEAD')!;
      const change = sent(stub, `/roles/${role.id}`);
      expect(change?.ifMatch).toBe('"0"');
      expect(change?.body).toMatchObject({ permissions: ['TRIP_READ', 'TRIP_EXECUTE'] });
    });

    await test.step('an unused role is deleted', async () => {
      await page.getByTestId('role-delete').click();
      await expect(page.getByTestId('role-done')).toHaveText('The role is deleted.');
      await expect(page.getByTestId('role-item').filter({ hasText: 'YARD_LEAD' })).toHaveCount(0);
    });
  });

  test('a role somebody holds is not deleted, and the Administrator is fixed', async ({ page }) => {
    const stub = await stubUsers(page);
    stub.users[0].roles.push('PLANNER');
    await signIn(page);
    await openRoles(page);

    await page.getByTestId('role-item').filter({ hasText: 'PLANNER' }).getByTestId('role-select').click();
    await page.getByTestId('role-delete').click();
    await expect(page.getByTestId('role-refusal')).toHaveText(/role-in-use/);

    await page.getByTestId('role-item').filter({ hasText: 'ADMIN' }).getByTestId('role-select').click();
    await expect(page.getByTestId('role-fixed')).toBeVisible();
    await expect(page.getByTestId('role-save')).toHaveCount(0);
  });
});
