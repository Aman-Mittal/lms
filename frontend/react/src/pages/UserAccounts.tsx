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
import { useEffect, useState, type FormEvent } from 'react';
import { ApiProblem, type OrgUnitView } from '../gateway/lmsGateway';
import type { AccountDraft, AccountRow, RoleCard } from '../gateway/userAdminTypes';
import { useSession } from '../session';
import { ErrorBanner } from './ErrorBanner';
import { PageHeader } from './PageHeader';
import { StatusBadge } from './StatusBadge';
import { useConfirm } from './ConfirmDialog';
import { confirmWording } from '../../../shared/confirm';

interface Refusal {
  code: string;
  message: string;
}

const BLANK_DRAFT: AccountDraft = { email: '', password: '', fullName: '', roles: [] };

function withCode(codes: string[], code: string, on: boolean): string[] {
  return on ? [...codes.filter((c) => c !== code), code] : codes.filter((c) => c !== code);
}

function problem(e: unknown): Refusal {
  return { code: e instanceof ApiProblem ? e.code : '', message: (e as Error).message };
}

/**
 * User accounts: list and search, create a user by typing the login id and the password, edit the name and roles,
 * switch a user off and on, and set a new password. There is no invitation and no email. The server decides every
 * rule (password policy, the last administrator, switching yourself off); each refusal is shown by its code.
 */
export function UserAccounts() {
  const { gateway, can, auth } = useSession();
  const ownUnit = auth?.orgUnitId ?? '';
  const [q, setQ] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [list, setList] = useState<AccountRow[]>([]);
  const [listError, setListError] = useState('');
  const [roles, setRoles] = useState<RoleCard[]>([]);
  const [units, setUnits] = useState<OrgUnitView[]>([]);
  const [unitChoice, setUnitChoice] = useState(ownUnit);
  const [draft, setDraft] = useState<AccountDraft>(BLANK_DRAFT);
  const [createError, setCreateError] = useState<Refusal | null>(null);
  const [selectedId, setSelectedId] = useState('');
  const [editName, setEditName] = useState('');
  const [editRoles, setEditRoles] = useState<string[]>([]);
  const confirm = useConfirm();
  const [newPassword, setNewPassword] = useState('');
  const [done, setDone] = useState('');
  const [refusal, setRefusal] = useState<Refusal | null>(null);

  const activeUnits = units.filter((u) => u.active).map((u) => ({ id: u.id, name: u.name, path: u.path }));
  // The caller's own unit is always offered, even if the list of units could not be read.
  const unitOptions = !ownUnit || activeUnits.some((u) => u.id === ownUnit)
    ? activeUnits : [{ id: ownUnit, name: 'My organisational unit', path: '' }, ...activeUnits];

  const selected = list.find((a) => a.id === selectedId) ?? null;

  async function search(): Promise<AccountRow[]> {
    setListError('');
    try {
      const found = await gateway.accounts(q, statusFilter);
      setList(found);
      return found;
    } catch (e) {
      setListError((e as Error).message);
      return list;
    }
  }

  useEffect(() => {
    gateway.roleCards().then(setRoles, () => setRoles([]));
    gateway.orgUnits().then(setUnits, () => setUnits([]));
    void search();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  function select(id: string, from: AccountRow[]) {
    const found = from.find((a) => a.id === id);
    if (id !== selectedId) {
      setDone('');
      setRefusal(null);
      setNewPassword('');
    }
    setSelectedId(found ? id : '');
    if (found) {
      setEditName(found.fullName);
      setEditRoles([...found.roles]);
    }
  }

  async function create(event: FormEvent) {
    event.preventDefault();
    setCreateError(null);
    try {
      const id = await gateway.createAccount({ ...draft, orgUnitId: unitChoice || undefined });
      setDraft(BLANK_DRAFT);
      setUnitChoice(ownUnit);
      const found = await search();
      select(id, found);
      setDone('The user is created. They can sign in with the login id and password you typed.');
    } catch (e) {
      setCreateError(problem(e));
    }
  }

  /** Runs one change on the user, then reloads the list so the next change carries the fresh version. */
  async function act(a: AccountRow, change: () => Promise<void>, ok: string) {
    setDone('');
    setRefusal(null);
    let message = '';
    let failure: Refusal | null = null;
    try {
      await change();
      message = ok;
      setNewPassword('');
    } catch (e) {
      failure = problem(e);
    }
    const found = await search();
    select(a.id, found);
    setDone(message);
    setRefusal(failure);
  }

  return (
    <>
      <PageHeader title="User accounts" />
      <p className="muted">Everyone in your organisational unit and below. You choose each user's login id and password here.</p>

      {can('USER_MANAGE') && (
        <>
          <form data-testid="acct-create-form" onSubmit={(ev) => void create(ev)}>
            <h2>Create a user</h2>
            <div className="inline">
              <input name="email" type="email" placeholder="Login id (email)" aria-label="Login id" data-testid="acct-email" required
                autoComplete="off" value={draft.email} onChange={(ev) => setDraft((d) => ({ ...d, email: ev.target.value }))} />
              <input name="password" type="password" placeholder="Initial password" aria-label="Initial password"
                data-testid="acct-password" required autoComplete="new-password" value={draft.password}
                onChange={(ev) => setDraft((d) => ({ ...d, password: ev.target.value }))} />
              <input name="fullName" placeholder="Full name" aria-label="Full name" data-testid="acct-name" required
                value={draft.fullName} onChange={(ev) => setDraft((d) => ({ ...d, fullName: ev.target.value }))} />
            </div>
            <label className="field">Organisational unit
              <select name="orgUnit" aria-label="Organisational unit" data-testid="acct-org-unit" value={unitChoice}
                onChange={(ev) => setUnitChoice(ev.target.value)}>
                {unitOptions.map((u) => (
                  <option key={u.id} value={u.id}>{u.path ? `${u.name} (${u.path})` : u.name}</option>
                ))}
              </select>
            </label>
            <fieldset data-testid="acct-roles">
              <legend>Roles</legend>
              {roles.map((r) => (
                <label key={r.code}>
                  <input type="checkbox" data-testid={`acct-role-${r.code}`} checked={draft.roles.includes(r.code)}
                    onChange={(ev) => setDraft((d) => ({ ...d, roles: withCode(d.roles, r.code, ev.target.checked) }))} />
                  {' '}{r.name}
                </label>
              ))}
            </fieldset>
            <p className="actions"><button type="submit" data-testid="acct-create">Create user</button></p>
          </form>
          {createError && (
            <ErrorBanner message={createError.message} code={createError.code} testId="acct-create-error"
              codeTestId="acct-create-refusal" />
          )}
        </>
      )}

      <form className="inline" data-testid="acct-search-form" onSubmit={(ev) => { ev.preventDefault(); void search(); }}>
        <input name="q" placeholder="Search users" aria-label="Search accounts" data-testid="acct-search" value={q}
          onChange={(ev) => setQ(ev.target.value)} />
        <select aria-label="Account status" data-testid="acct-status-filter" value={statusFilter}
          onChange={(ev) => setStatusFilter(ev.target.value)}>
          <option value="">Any status</option>
          <option value="ACTIVE">ACTIVE</option>
          <option value="SUSPENDED">SUSPENDED</option>
          <option value="LOCKED">LOCKED</option>
        </select>
        <button type="submit" data-testid="acct-search-go">Find users</button>
      </form>

      {listError && <ErrorBanner message={listError} testId="acct-list-error" />}
      {list.length === 0 ? (
        <p className="muted" data-testid="acct-list-empty">No users match.</p>
      ) : (
        <div className="table-wrap"><table data-testid="acct-list">
          <thead><tr><th>Login id</th><th>Name</th><th>Status</th><th>Roles</th><th></th></tr></thead>
          <tbody>
            {list.map((a) => (
              <tr key={a.id} data-testid="acct-item" data-id={a.id}>
                <td>{a.email}</td><td>{a.fullName}</td>
                <td><StatusBadge value={a.status} /></td><td>{a.roles.join(', ') || '—'}</td>
                <td><button type="button" className="link" data-testid="acct-select" aria-label={`Select account ${a.email}`}
                  onClick={() => select(a.id, list)}>Select</button></td>
              </tr>
            ))}
          </tbody>
        </table></div>
      )}

      {selected && (
        <section data-testid="acct-panel" data-id={selected.id}>
          <h2>{selected.fullName}</h2>
          <dl className="facts">
            <div><dt>Login id</dt><dd data-testid="acct-shown-email">{selected.email}</dd></div>
            <div><dt>Status</dt><dd><StatusBadge data-testid="acct-shown-status" value={selected.status} /></dd></div>
            <div><dt>Unit</dt><dd data-testid="acct-shown-org">{selected.orgName}</dd></div>
          </dl>
          {done && <p className="ok" role="status" data-testid="acct-done">{done}</p>}
          {refusal && <ErrorBanner message={refusal.message} code={refusal.code} testId="acct-error" codeTestId="acct-refusal" />}
          {can('USER_MANAGE') && (
            <>
              <form data-testid="acct-edit-form" onSubmit={(ev) => {
                ev.preventDefault();
                void act(selected, () => gateway.updateAccount(selected.id, selected.version, editName, editRoles),
                  'The changes are saved.');
              }}>
                <h3>Name and roles</h3>
                <input name="editName" aria-label="Edit full name" data-testid="acct-edit-name" required value={editName}
                  onChange={(ev) => setEditName(ev.target.value)} />
                <fieldset data-testid="acct-edit-roles">
                  <legend>Roles</legend>
                  {roles.map((r) => (
                    <label key={r.code}>
                      <input type="checkbox" data-testid={`acct-edit-role-${r.code}`} checked={editRoles.includes(r.code)}
                        onChange={(ev) => setEditRoles((c) => withCode(c, r.code, ev.target.checked))} />
                      {' '}{r.name}
                    </label>
                  ))}
                </fieldset>
                <p className="actions"><button type="submit" data-testid="acct-save">Save changes</button></p>
              </form>

              <div data-testid="acct-switch-form">
                <h3>{selected.status === 'ACTIVE' ? 'Switch off' : 'Switch on'}</h3>
                <p className="actions">
                  <button type="button" data-testid={selected.status === 'ACTIVE' ? 'acct-deactivate' : 'acct-reactivate'}
                    onClick={() => {
                      const off = selected.status === 'ACTIVE';
                      confirm.ask({
                        wording: confirmWording(off ? 'Deactivate user' : 'Reactivate user', selected.email,
                          off ? 'They can no longer sign in until they are switched on again.' : 'They can sign in again.'),
                        reason: { label: 'Reason', testId: 'acct-reason', placeholder: 'Reason', required: true },
                        tone: off ? 'danger' : 'neutral',
                        run: (why) => act(selected, () => (off
                          ? gateway.deactivateAccount(selected.id, selected.version, why)
                          : gateway.reactivateAccount(selected.id, selected.version, why)),
                        off ? 'The user is switched off.' : 'The user is switched on.'),
                      });
                    }}>
                    {selected.status === 'ACTIVE' ? 'Deactivate user' : 'Reactivate user'}
                  </button>
                </p>
              </div>

              <form data-testid="acct-password-form" onSubmit={(ev) => {
                ev.preventDefault();
                void act(selected, () => gateway.setAccountPassword(selected.id, newPassword), 'The new password is set.');
              }}>
                <h3>Set a new password</h3>
                <input name="newPassword" type="password" placeholder="New password" aria-label="New password"
                  data-testid="acct-new-password" required autoComplete="new-password" value={newPassword}
                  onChange={(ev) => setNewPassword(ev.target.value)} />
                <p className="actions"><button type="submit" data-testid="acct-set-password">Set password</button></p>
              </form>
            </>
          )}
        </section>
      )}
      {confirm.dialog}
    </>
  );
}
