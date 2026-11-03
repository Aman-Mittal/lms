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

import { useState, type FormEvent } from 'react';
import {
  ACCESS_FREIGHT_MODES, ApiProblem, DOCUMENT_OWNER_TYPES, type AccessFreightMode, type AccessPolicyView,
  type AdminUser, type DocumentOwnerType, type PickKind, type PolicyEffect, type Row,
} from '../gateway/lmsGateway';
import { EntityPicker } from '../EntityPicker';
import { useSession } from '../session';
import { useLoad } from '../useLoad';
import { RowTable } from './RowTable';

/** The picker each kind of document owner is chosen with. */
const OWNER_PICKS: Record<DocumentOwnerType, PickKind> = { PARTNER: 'partner', VEHICLE: 'vehicle', DRIVER: 'driver' };

export type AdminSection = 'users' | 'policies' | 'compliance';

const TITLES: Record<AdminSection, string> = {
  users: 'Users', policies: 'Access policies', compliance: 'Compliance',
};

/** A refusal as the desk shows it: the problem's stable code, when it has one, and its message. */
interface Refusal {
  code: string;
  message: string;
}

function refusalOf(e: unknown): Refusal {
  return { code: e instanceof ApiProblem ? e.code : '', message: (e as Error).message };
}

/** A day `days` from today, as a date input holds it. */
function inDays(days: number): string {
  return new Date(Date.now() + days * 86_400_000).toISOString().slice(0, 10);
}

/**
 * Runs one desk action: clears the last outcome, then shows what the action said or why it
 * was refused. Returned as a hook so each section keeps its own outcome.
 */
function useOutcome() {
  const [refusal, setRefusal] = useState<Refusal | null>(null);
  const [message, setMessage] = useState('');

  async function act(action: () => Promise<string | void>) {
    setRefusal(null);
    setMessage('');
    try {
      const said = await action();
      if (said) {
        setMessage(said);
      }
    } catch (e) {
      setRefusal(refusalOf(e));
    }
  }

  return { refusal, message, act };
}

function Outcome({ refusal, message }: { refusal: Refusal | null; message: string }) {
  return (
    <>
      {refusal && (
        <p className="error" role="alert" data-testid="admin-error">
          {refusal.code && <><code data-testid="admin-refusal">{refusal.code}</code>{' '}</>}
          {refusal.message}
        </p>
      )}
      {message && <p data-testid="admin-message">{message}</p>}
    </>
  );
}

/**
 * Who may do what, and which certificates are lapsing: users and their access attributes,
 * attribute-based access policies, and compliance documents. One page, three routes; the
 * route says which section it is.
 */
export function AdminDesk({ section }: { section: AdminSection }) {
  return (
    <>
      <h1>{TITLES[section]}</h1>
      {/* A key per section: moving between them starts the next one fresh. */}
      {section === 'users' && <UsersSection key="users" />}
      {section === 'policies' && <PoliciesSection key="policies" />}
      {section === 'compliance' && <ComplianceSection key="compliance" />}
    </>
  );
}

// ----------------------------------------------------------------------- users

function UsersSection() {
  const { gateway } = useSession();
  const list = useLoad(() => gateway.adminUsers(), 0);
  const [picked, setPicked] = useState('');
  const current = list.data?.find((u) => u.id === picked) ?? null;

  return (
    <>
      <p className="muted">Everyone in your organisational unit and below. Access attributes take effect at the user's next sign-in.</p>
      {list.loading && <p className="muted">Loading…</p>}
      {list.error && <p className="error" role="alert" data-testid="admin-error">{list.error}</p>}
      {list.data && list.data.length > 0 && (
        <RowTable testId="users" picked={picked} onPick={(r) => setPicked(r.id)}
          rows={list.data.map((u): Row => ({
            id: u.id, fullName: u.fullName, email: u.email, status: u.status, orgName: u.orgName,
            lastLoginAt: u.lastLoginAt,
          }))}
          columns={[
            { header: 'Name', field: 'fullName' }, { header: 'Email', field: 'email' },
            { header: 'Status', field: 'status' }, { header: 'Unit', field: 'orgName' },
            { header: 'Last sign-in', field: 'lastLoginAt' },
          ]} />
      )}
      {current && <UserDetail key={current.id} user={current} />}
    </>
  );
}

function UserDetail({ user }: { user: AdminUser }) {
  const { gateway } = useSession();
  const permissions = useLoad(() => gateway.userPermissions(user.id), user.id);
  const attributes = useLoad(() => gateway.accessAttributes(user.id), user.id);

  return (
    <section data-testid="user-detail">
      <h2>{user.fullName}</h2>
      <p className="muted">{user.email} · {user.orgName}</p>
      <details data-testid="user-permissions">
        <summary>{permissions.data?.length ?? 0} permissions</summary>
        <ul>{(permissions.data ?? []).map((p) => <li key={p}>{p}</li>)}</ul>
      </details>
      {attributes.error && <p className="error" role="alert" data-testid="admin-error">{attributes.error}</p>}
      {/* Mounted once the attributes are read, so the form starts from what the user carries now. */}
      {attributes.data && <AttributesForm userId={user.id} initial={attributes.data} />}
    </section>
  );
}

function AttributesForm({ userId, initial }: {
  userId: string;
  initial: { region: string; freightModes: AccessFreightMode[]; partnerId: string; approvalLimit: string };
}) {
  const { gateway } = useSession();
  const { refusal, message, act } = useOutcome();
  const [region, setRegion] = useState(initial.region);
  const [modes, setModes] = useState<AccessFreightMode[]>(initial.freightModes);
  const [partnerId, setPartnerId] = useState(initial.partnerId);
  const [approvalLimit, setApprovalLimit] = useState(initial.approvalLimit);

  function save(event: FormEvent) {
    event.preventDefault();
    void act(async () => {
      await gateway.setAccessAttributes(userId, {
        region: region.trim(), freightModes: modes, partnerId: partnerId.trim(), approvalLimit: approvalLimit.trim(),
      });
      return 'Saved. The user’s next sign-in carries these attributes.';
    });
  }

  return (
    <form onSubmit={save} data-testid="access-attributes">
      <h3>Access attributes</h3>
      <p className="muted">Leave a field blank for no restriction along it.</p>
      <input placeholder="Region" aria-label="Region" data-testid="attr-region" value={region}
        onChange={(e) => setRegion(e.target.value)} />
      <fieldset data-testid="attr-modes">
        <legend>Freight modes</legend>
        {ACCESS_FREIGHT_MODES.map((m) => (
          <label key={m}>
            <input type="checkbox" data-testid={`attr-mode-${m}`} checked={modes.includes(m)}
              onChange={(e) => setModes(e.target.checked ? [...modes, m] : modes.filter((x) => x !== m))} />
            {m}
          </label>
        ))}
      </fieldset>
      <EntityPicker kind="partner" placeholder="Partner" ariaLabel="Partner id" testId="attr-partner"
        value={partnerId} onChange={setPartnerId} />
      <input placeholder="Approval limit" aria-label="Approval limit" data-testid="attr-limit" inputMode="decimal"
        value={approvalLimit} onChange={(e) => setApprovalLimit(e.target.value)} />
      <button type="submit" data-testid="save-attributes">Save attributes</button>
      <Outcome refusal={refusal} message={message} />
    </form>
  );
}

// -------------------------------------------------------------------- policies

function PoliciesSection() {
  const { gateway } = useSession();
  const [reload, setReload] = useState(0);
  const [extra, setExtra] = useState<{ policies: AccessPolicyView[]; cursor: string | null } | null>(null);
  const [picked, setPicked] = useState('');
  const { refusal, message, act } = useOutcome();
  const first = useLoad(() => gateway.accessPolicies(), reload);

  const policies = [...(first.data?.policies ?? []), ...(extra?.policies ?? [])];
  const cursor = extra ? extra.cursor : first.data?.nextCursor ?? null;
  const current = policies.find((p) => p.id === picked) ?? null;

  /** Reads the list again from the first page; a policy just made or changed may sort anywhere. */
  function refresh() {
    setExtra(null);
    setReload((n) => n + 1);
  }

  function more() {
    if (!cursor) {
      return;
    }
    void act(async () => {
      const page = await gateway.accessPolicies(cursor);
      setExtra({ policies: [...(extra?.policies ?? []), ...page.policies], cursor: page.nextCursor });
    });
  }

  return (
    <>
      <p className="muted">Rules over a permission: a DENY vetoes when its condition holds; when an action has PERMIT
        policies, one of them must hold as well.</p>
      <Outcome refusal={refusal} message={message} />
      {first.loading && <p className="muted">Loading…</p>}
      {first.error && <p className="error" role="alert" data-testid="admin-error">{first.error}</p>}
      {first.data && policies.length === 0 && <p className="muted" data-testid="no-policies">No access policies.</p>}
      {policies.length > 0 && (
        <RowTable testId="policies" picked={picked} onPick={(r) => setPicked(r.id)}
          rows={policies.map((p): Row => ({
            id: p.id, code: p.code, action: p.action, effect: p.effect, enabled: p.enabled ? 'Yes' : 'No',
            description: p.description,
          }))}
          columns={[
            { header: 'Code', field: 'code' }, { header: 'Action', field: 'action' },
            { header: 'Effect', field: 'effect' }, { header: 'Enabled', field: 'enabled' },
            { header: 'Description', field: 'description' },
          ]} />
      )}
      {cursor && <button type="button" data-testid="more-policies" onClick={more}>Load more</button>}
      {current
        ? <PolicyDetail key={current.id} policy={current} onClose={() => setPicked('')}
            onChanged={refresh} onDeleted={() => { setPicked(''); refresh(); }} />
        : <CreatePolicy onCreated={(id) => { setPicked(id); refresh(); }} />}
    </>
  );
}

/** Effect, description, condition and the enabled flag: what both the create and the revise forms edit. */
function PolicyFields({ effect, setEffect, description, setDescription, condition, setCondition, enabled, setEnabled }: {
  effect: PolicyEffect; setEffect: (v: PolicyEffect) => void;
  description: string; setDescription: (v: string) => void;
  condition: string; setCondition: (v: string) => void;
  enabled: boolean; setEnabled: (v: boolean) => void;
}) {
  return (
    <>
      <select aria-label="Effect" data-testid="policy-effect" value={effect}
        onChange={(e) => setEffect(e.target.value as PolicyEffect)}>
        <option value="DENY">DENY</option>
        <option value="PERMIT">PERMIT</option>
      </select>
      <input placeholder="Description" aria-label="Description" data-testid="policy-description" required
        value={description} onChange={(e) => setDescription(e.target.value)} />
      <textarea aria-label="Condition" data-testid="policy-condition" rows={6} required
        placeholder='{"attr": "resource.amount", "op": "gt", "ref": "subject.approvalLimit"}'
        value={condition} onChange={(e) => setCondition(e.target.value)} />
      <label>
        <input type="checkbox" data-testid="policy-enabled" checked={enabled}
          onChange={(e) => setEnabled(e.target.checked)} />
        Enabled
      </label>
    </>
  );
}

function CreatePolicy({ onCreated }: { onCreated: (id: string) => void }) {
  const { gateway } = useSession();
  const { refusal, message, act } = useOutcome();
  const [code, setCode] = useState('');
  const [action, setAction] = useState('');
  const [effect, setEffect] = useState<PolicyEffect>('DENY');
  const [description, setDescription] = useState('');
  const [condition, setCondition] = useState('');
  const [enabled, setEnabled] = useState(true);

  function submit(event: FormEvent) {
    event.preventDefault();
    void act(async () => {
      onCreated(await gateway.createAccessPolicy({ code, action, effect, description, condition, enabled }));
    });
  }

  return (
    <form onSubmit={submit} data-testid="policy-create">
      <h2>New access policy</h2>
      <input placeholder="Code" aria-label="Code" data-testid="policy-code" required value={code}
        onChange={(e) => setCode(e.target.value)} />
      <input placeholder="Permission, such as INVOICE_APPROVE" aria-label="Action" data-testid="policy-action" required
        value={action} onChange={(e) => setAction(e.target.value)} />
      <PolicyFields effect={effect} setEffect={setEffect} description={description} setDescription={setDescription}
        condition={condition} setCondition={setCondition} enabled={enabled} setEnabled={setEnabled} />
      <button type="submit" data-testid="create-policy">Create policy</button>
      <Outcome refusal={refusal} message={message} />
    </form>
  );
}

function PolicyDetail({ policy, onClose, onChanged, onDeleted }: {
  policy: AccessPolicyView;
  onClose: () => void;
  onChanged: () => void;
  onDeleted: () => void;
}) {
  const { gateway } = useSession();
  const { refusal, message, act } = useOutcome();
  const [effect, setEffect] = useState<PolicyEffect>(policy.effect);
  const [description, setDescription] = useState(policy.description);
  const [condition, setCondition] = useState(policy.condition);
  const [enabled, setEnabled] = useState(policy.enabled);

  function save(event: FormEvent) {
    event.preventDefault();
    void act(async () => {
      await gateway.reviseAccessPolicy(policy.id, { effect, description, condition, enabled });
      onChanged();
      return 'Policy saved.';
    });
  }

  function remove() {
    void act(async () => {
      await gateway.deleteAccessPolicy(policy.id);
      onDeleted();
    });
  }

  return (
    <section data-testid="policy-detail">
      <h2>{policy.code}</h2>
      <button type="button" className="link" data-testid="close-policy" onClick={onClose}>Back to creating</button>
      <dl className="facts"><div><dt>Action</dt><dd data-testid="policy-action-shown">{policy.action}</dd></div></dl>
      <form onSubmit={save} data-testid="policy-revise">
        <PolicyFields effect={effect} setEffect={setEffect} description={description} setDescription={setDescription}
          condition={condition} setCondition={setCondition} enabled={enabled} setEnabled={setEnabled} />
        <button type="submit" data-testid="save-policy">Save policy</button>
        <button type="button" data-testid="delete-policy" onClick={remove}>Delete</button>
      </form>
      <Outcome refusal={refusal} message={message} />
    </section>
  );
}

// ------------------------------------------------------------------ compliance

function ComplianceSection() {
  const { gateway, can } = useSession();
  const [withinDays, setWithinDays] = useState('30');
  const [shownDays, setShownDays] = useState(30);
  const [reload, setReload] = useState(0);
  const expiring = useLoad(() => gateway.expiringDocuments(shownDays), `${shownDays}:${reload}`);
  const { refusal, message, act } = useOutcome();

  const [ownerType, setOwnerType] = useState<DocumentOwnerType>('VEHICLE');
  const [ownerId, setOwnerId] = useState('');
  const [documents, setDocuments] = useState<Row[] | null>(null);
  const [docType, setDocType] = useState('');
  const [docNo, setDocNo] = useState('');
  const [docAuthority, setDocAuthority] = useState('');
  const [docIssued, setDocIssued] = useState(inDays(0));
  const [docExpires, setDocExpires] = useState(inDays(365));

  const columns = [
    { header: 'Owner', field: 'ownerLabel' }, { header: 'Kind', field: 'ownerType' },
    { header: 'Document', field: 'documentType' }, { header: 'Number', field: 'documentNo' },
    { header: 'Expires', field: 'expiresOn' }, { header: 'Days left', field: 'daysToExpiry' },
    { header: 'State', field: 'state' },
  ];

  function show(event: FormEvent) {
    event.preventDefault();
    const n = Number(withinDays);
    setShownDays(Number.isFinite(n) && n >= 0 ? Math.min(Math.floor(n), 3650) : 30);
    setReload((r) => r + 1);
  }

  function openOwner(event: FormEvent) {
    event.preventDefault();
    setDocuments(null);
    void act(async () => setDocuments(await gateway.ownerDocuments(ownerType, ownerId)));
  }

  function attach(event: FormEvent) {
    event.preventDefault();
    void act(async () => {
      await gateway.attachDocument(ownerType, ownerId, {
        documentType: docType, documentNo: docNo, issuingAuthority: docAuthority, issuedOn: docIssued,
        expiresOn: docExpires,
      });
      setDocuments(await gateway.ownerDocuments(ownerType, ownerId));
      setReload((r) => r + 1);
      setDocType('');
      setDocNo('');
      setDocAuthority('');
      return 'Document attached.';
    });
  }

  return (
    <>
      <Outcome refusal={refusal} message={message} />
      <form onSubmit={show} data-testid="expiring-filter">
        <label>Expiring within{' '}
          <input type="number" min={0} max={3650} aria-label="Days" data-testid="expiring-days" value={withinDays}
            onChange={(e) => setWithinDays(e.target.value)} /> days
        </label>
        <button type="submit" data-testid="show-expiring">Show</button>
      </form>
      {expiring.loading && <p className="muted">Loading…</p>}
      {expiring.error && <p className="error" role="alert" data-testid="admin-error">{expiring.error}</p>}
      {expiring.data && expiring.data.length === 0 && (
        <p className="muted" data-testid="nothing-expiring">Nothing lapses in that time.</p>
      )}
      {expiring.data && expiring.data.length > 0 && <RowTable testId="expiring" rows={expiring.data} columns={columns} />}

      <section data-testid="owner-documents-panel">
        <h2>Documents of one record</h2>
        <form onSubmit={openOwner} data-testid="owner-form">
          <select aria-label="Kind" data-testid="owner-type" value={ownerType}
            onChange={(e) => setOwnerType(e.target.value as DocumentOwnerType)}>
            {DOCUMENT_OWNER_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
          </select>
          <EntityPicker key={ownerType} kind={OWNER_PICKS[ownerType]} placeholder="Find one" ariaLabel="Owner id"
            testId="owner-id" required value={ownerId} onChange={setOwnerId} />
          <button type="submit" data-testid="open-owner">Open</button>
        </form>
        {documents && (documents.length === 0
          ? <p className="muted" data-testid="no-documents">No documents on file.</p>
          : <RowTable testId="owner-documents" rows={documents} columns={columns} />)}
        {documents && can('VEHICLE_UPDATE') && (
          <form onSubmit={attach} data-testid="attach-document">
            <h3>Attach a document</h3>
            <input placeholder="Type, such as INSURANCE" aria-label="Document type" data-testid="doc-type" required
              value={docType} onChange={(e) => setDocType(e.target.value)} />
            <input placeholder="Number" aria-label="Document number" data-testid="doc-no" required value={docNo}
              onChange={(e) => setDocNo(e.target.value)} />
            <input placeholder="Issued by" aria-label="Issuing authority" data-testid="doc-authority"
              value={docAuthority} onChange={(e) => setDocAuthority(e.target.value)} />
            <label>Issued <input type="date" aria-label="Issued on" data-testid="doc-issued" value={docIssued}
              onChange={(e) => setDocIssued(e.target.value)} /></label>
            <label>Expires <input type="date" aria-label="Expires on" data-testid="doc-expires" value={docExpires}
              onChange={(e) => setDocExpires(e.target.value)} /></label>
            <button type="submit" data-testid="attach-document-submit">Attach</button>
          </form>
        )}
      </section>
    </>
  );
}
