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
import { ApiProblem } from '../gateway/lmsGateway';
import type { PermissionChoice, RoleCard, RoleDraft } from '../gateway/userAdminTypes';
import { useSession } from '../session';
import { ErrorBanner } from './ErrorBanner';
import { PageHeader } from './PageHeader';
import { useConfirm } from './ConfirmDialog';
import { confirmWording } from '../../../shared/confirm';

interface Refusal {
  code: string;
  message: string;
}

/** The Administrator always grants everything; the server refuses to change or delete it. */
const ADMINISTRATOR = 'ADMIN';

const BLANK_DRAFT: RoleDraft = { code: '', name: '', permissions: [] };

function withCode(codes: string[], code: string, on: boolean): string[] {
  return on ? [...codes.filter((c) => c !== code), code] : codes.filter((c) => c !== code);
}

function problem(e: unknown): Refusal {
  return { code: e instanceof ApiProblem ? e.code : '', message: (e as Error).message };
}

/** The vocabulary grouped by what it is about, in the order it arrives. */
function grouped(choices: PermissionChoice[]): { resource: string; items: PermissionChoice[] }[] {
  const groups = new Map<string, PermissionChoice[]>();
  for (const c of choices) {
    groups.set(c.resource, [...(groups.get(c.resource) ?? []), c]);
  }
  return [...groups.entries()].map(([resource, items]) => ({ resource, items }));
}

/**
 * Roles: the tenant's roles and what each grants, custom roles built from the permission list, a role's permissions
 * edited under the version read, and an unused role deleted. The server refuses to delete a role somebody holds and to
 * change the Administrator; each refusal is shown by its code.
 */
export function RoleDesk() {
  const { gateway, can } = useSession();
  const confirm = useConfirm();
  const [roles, setRoles] = useState<RoleCard[]>([]);
  const [groups, setGroups] = useState<{ resource: string; items: PermissionChoice[] }[]>([]);
  const [listError, setListError] = useState('');
  const [draft, setDraft] = useState<RoleDraft>(BLANK_DRAFT);
  const [createError, setCreateError] = useState<Refusal | null>(null);
  const [selectedId, setSelectedId] = useState('');
  const [editName, setEditName] = useState('');
  const [editPermissions, setEditPermissions] = useState<string[]>([]);
  const [done, setDone] = useState('');
  const [refusal, setRefusal] = useState<Refusal | null>(null);

  const selected = roles.find((r) => r.id === selectedId) ?? null;

  async function load(): Promise<RoleCard[]> {
    setListError('');
    try {
      const found = await gateway.roleCards();
      setRoles(found);
      return found;
    } catch (e) {
      setListError((e as Error).message);
      return roles;
    }
  }

  useEffect(() => {
    gateway.permissionChoices().then((c) => setGroups(grouped(c)), (e: Error) => setListError(e.message));
    void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  function select(id: string, from: RoleCard[]) {
    const found = from.find((r) => r.id === id);
    if (id !== selectedId) {
      setDone('');
      setRefusal(null);
    }
    setSelectedId(found ? id : '');
    if (found) {
      setEditName(found.name);
      setEditPermissions([...found.permissions]);
    }
  }

  async function create(event: FormEvent) {
    event.preventDefault();
    setCreateError(null);
    try {
      const id = await gateway.createRole(draft);
      setDraft(BLANK_DRAFT);
      const found = await load();
      select(id, found);
      setDone('The role is created.');
    } catch (e) {
      setCreateError(problem(e));
    }
  }

  async function save(r: RoleCard) {
    setDone('');
    setRefusal(null);
    let message = '';
    let failure: Refusal | null = null;
    try {
      await gateway.updateRole(r.id, r.version, editName, editPermissions);
      message = 'The role is saved.';
    } catch (e) {
      failure = problem(e);
    }
    const found = await load();
    select(r.id, found);
    setDone(message);
    setRefusal(failure);
  }

  async function remove(r: RoleCard) {
    setDone('');
    setRefusal(null);
    try {
      await gateway.deleteRole(r.id);
      await load();
      setSelectedId('');
      setDone('The role is deleted.');
    } catch (e) {
      const failure = problem(e);
      const found = await load();
      select(r.id, found);
      setRefusal(failure);
    }
  }

  return (
    <>
      <PageHeader title="Roles" />
      <p className="muted">A role is a named set of permissions. Give users roles on the user accounts screen.</p>

      {can('ROLE_MANAGE') && (
        <>
          <form data-testid="role-create-form" onSubmit={(ev) => void create(ev)}>
            <h2>Create a custom role</h2>
            <div className="inline">
              <input name="code" placeholder="Role code" aria-label="Role code" data-testid="role-code" required
                value={draft.code} onChange={(ev) => setDraft((d) => ({ ...d, code: ev.target.value }))} />
              <input name="name" placeholder="Role name" aria-label="Role name" data-testid="role-name" required
                value={draft.name} onChange={(ev) => setDraft((d) => ({ ...d, name: ev.target.value }))} />
            </div>
            {groups.map((g) => (
              <fieldset key={g.resource}>
                <legend>{g.resource}</legend>
                {g.items.map((p) => (
                  <label key={p.code}>
                    <input type="checkbox" data-testid={`role-perm-${p.code}`} checked={draft.permissions.includes(p.code)}
                      onChange={(ev) => setDraft((d) => ({ ...d, permissions: withCode(d.permissions, p.code, ev.target.checked) }))} />
                    {' '}{p.code}
                  </label>
                ))}
              </fieldset>
            ))}
            <p className="actions"><button type="submit" data-testid="role-create">Create role</button></p>
          </form>
          {createError && (
            <ErrorBanner message={createError.message} code={createError.code} testId="role-create-error"
              codeTestId="role-create-refusal" />
          )}
        </>
      )}

      {done && <p className="ok" role="status" data-testid="role-done">{done}</p>}
      {refusal && <ErrorBanner message={refusal.message} code={refusal.code} testId="role-error" codeTestId="role-refusal" />}

      {listError && <ErrorBanner message={listError} testId="role-list-error" />}
      {roles.length === 0 ? (
        <p className="muted" data-testid="role-list-empty">No roles.</p>
      ) : (
        <div className="table-wrap"><table data-testid="role-list">
          <thead><tr><th>Code</th><th>Name</th><th>Permissions</th><th>Users</th><th></th></tr></thead>
          <tbody>
            {roles.map((r) => (
              <tr key={r.id} data-testid="role-item" data-id={r.id} data-code={r.code}>
                <td>{r.code}</td><td>{r.name}</td><td>{r.permissions.length}</td><td>{r.userCount}</td>
                <td><button type="button" className="link" data-testid="role-select" aria-label={`Select role ${r.code}`}
                  onClick={() => select(r.id, roles)}>Select</button></td>
              </tr>
            ))}
          </tbody>
        </table></div>
      )}

      {selected && (
        <section data-testid="role-panel" data-id={selected.id}>
          <h2>{selected.name}</h2>
          {selected.code === ADMINISTRATOR ? (
            <p className="muted" data-testid="role-fixed">
              The Administrator role always grants everything and cannot be changed or deleted.
            </p>
          ) : can('ROLE_MANAGE') ? (
            <form data-testid="role-edit-form" onSubmit={(ev) => { ev.preventDefault(); void save(selected); }}>
              <input name="editName" aria-label="Edit role name" data-testid="role-edit-name" required value={editName}
                onChange={(ev) => setEditName(ev.target.value)} />
              {groups.map((g) => (
                <fieldset key={g.resource}>
                  <legend>{g.resource}</legend>
                  {g.items.map((p) => (
                    <label key={p.code}>
                      <input type="checkbox" data-testid={`role-edit-perm-${p.code}`} checked={editPermissions.includes(p.code)}
                        onChange={(ev) => setEditPermissions((c) => withCode(c, p.code, ev.target.checked))} />
                      {' '}{p.code}
                    </label>
                  ))}
                </fieldset>
              ))}
              <p className="actions">
                <button type="submit" data-testid="role-save">Save role</button>
                <button type="button" data-testid="role-delete" onClick={() => confirm.ask({
                  wording: confirmWording('Delete role', selected.name, 'This cannot be undone.'),
                  run: () => remove(selected),
                })}>Delete role</button>
              </p>
            </form>
          ) : (
            <ul data-testid="role-permissions">{selected.permissions.map((p) => <li key={p}>{p}</li>)}</ul>
          )}
        </section>
      )}
      {confirm.dialog}
    </>
  );
}
