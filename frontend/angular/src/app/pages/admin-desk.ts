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

import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import {
  ACCESS_FREIGHT_MODES, AccessFreightMode, AccessPolicyView, AdminUser, ApiProblem, DOCUMENT_OWNER_TYPES,
  DocumentOwnerType, LmsGateway, PolicyEffect, Row,
} from '../gateway/lms-gateway';
import { Session } from '../session';
import { RowTable } from './row-table';

export type AdminSection = 'users' | 'policies' | 'compliance';

const TITLES: Record<AdminSection, string> = {
  users: 'Users', policies: 'Access policies', compliance: 'Compliance',
};

/** What an `(input)` or `(change)` event carries, read without a cast in the template. */
function valueOf(event: Event): string {
  return (event.target as HTMLInputElement).value;
}

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
 * Who may do what, and which certificates are lapsing: users and their access attributes,
 * attribute-based access policies, and compliance documents. One component, three routes;
 * the route says which section it is.
 *
 * The text inputs bind through signals rather than `ngModel`: without zone.js `ngModel`
 * writes its first value on a later pass and can wipe what was typed before it.
 */
@Component({
  selector: 'app-admin-desk',
  imports: [RowTable],
  template: `
    <h1>{{ title }}</h1>
    @if (refusal(); as r) {
      <p class="error" role="alert" data-testid="admin-error">
        @if (r.code) { <code data-testid="admin-refusal">{{ r.code }}</code> }
        {{ r.message }}
      </p>
    }
    @if (message()) { <p data-testid="admin-message">{{ message() }}</p> }

    @if (section === 'users') {
      <p class="muted">Everyone in your organisational unit and below. Access attributes take effect at the user's next sign-in.</p>
      @if (loading()) { <p class="muted">Loading…</p> }
      @if (users().length > 0) {
        <app-row-table testId="users" [rows]="userRows()" [columns]="userColumns" [pickable]="true"
                       [picked]="pickedUser()" (pick)="openUser($event['id'])" />
      }
      @if (currentUser(); as u) {
        <section data-testid="user-detail">
          <h2>{{ u.fullName }}</h2>
          <p class="muted">{{ u.email }} · {{ u.orgName }}</p>
          <details data-testid="user-permissions">
            <summary>{{ permissions().length }} permissions</summary>
            <ul>@for (p of permissions(); track p) { <li>{{ p }}</li> }</ul>
          </details>
          <form (submit)="saveAttributes($event, u.id)" data-testid="access-attributes">
            <h3>Access attributes</h3>
            <p class="muted">Leave a field blank for no restriction along it.</p>
            <input placeholder="Region" aria-label="Region" data-testid="attr-region"
                   [value]="region()" (input)="region.set(valueOf($event))" />
            <fieldset data-testid="attr-modes">
              <legend>Freight modes</legend>
              @for (m of freightModes; track m) {
                <label>
                  <input type="checkbox" [attr.data-testid]="'attr-mode-' + m" [checked]="modes().includes(m)"
                         (change)="toggleMode(m, $any($event.target).checked)" />
                  {{ m }}
                </label>
              }
            </fieldset>
            <input placeholder="Partner id" aria-label="Partner id" data-testid="attr-partner"
                   [value]="partnerId()" (input)="partnerId.set(valueOf($event))" />
            <input placeholder="Approval limit" aria-label="Approval limit" data-testid="attr-limit" inputmode="decimal"
                   [value]="approvalLimit()" (input)="approvalLimit.set(valueOf($event))" />
            <button type="submit" data-testid="save-attributes">Save attributes</button>
          </form>
        </section>
      }
    }

    @if (section === 'policies') {
      <p class="muted">Rules over a permission: a DENY vetoes when its condition holds; when an action has PERMIT
        policies, one of them must hold as well.</p>
      @if (loading()) { <p class="muted">Loading…</p> }
      @if (!loading() && policies().length === 0) { <p class="muted" data-testid="no-policies">No access policies.</p> }
      @if (policies().length > 0) {
        <app-row-table testId="policies" [rows]="policyRows()" [columns]="policyColumns" [pickable]="true"
                       [picked]="pickedPolicy()" (pick)="openPolicy($event['id'])" />
      }
      @if (policyCursor()) {
        <button type="button" data-testid="more-policies" (click)="morePolicies()">Load more</button>
      }
      @if (currentPolicy(); as p) {
        <section data-testid="policy-detail">
          <h2>{{ p.code }}</h2>
          <button type="button" class="link" data-testid="close-policy" (click)="closePolicy()">Back to creating</button>
          <dl class="facts"><div><dt>Action</dt><dd data-testid="policy-action-shown">{{ p.action }}</dd></div></dl>
          <form (submit)="savePolicy($event, p.id)" data-testid="policy-revise">
            <select aria-label="Effect" data-testid="policy-effect" [value]="effect()"
                    (change)="effect.set($any(valueOf($event)))">
              <option value="DENY">DENY</option>
              <option value="PERMIT">PERMIT</option>
            </select>
            <input placeholder="Description" aria-label="Description" data-testid="policy-description" required
                   [value]="description()" (input)="description.set(valueOf($event))" />
            <textarea aria-label="Condition" data-testid="policy-condition" rows="6" required
                      [value]="condition()" (input)="condition.set(valueOf($event))"></textarea>
            <label>
              <input type="checkbox" data-testid="policy-enabled" [checked]="enabled()"
                     (change)="enabled.set($any($event.target).checked)" />
              Enabled
            </label>
            <button type="submit" data-testid="save-policy">Save policy</button>
            <button type="button" data-testid="delete-policy" (click)="deletePolicy(p.id)">Delete</button>
          </form>
        </section>
      } @else {
        <form (submit)="createPolicy($event)" data-testid="policy-create">
          <h2>New access policy</h2>
          <input placeholder="Code" aria-label="Code" data-testid="policy-code" required
                 [value]="code()" (input)="code.set(valueOf($event))" />
          <input placeholder="Permission, such as INVOICE_APPROVE" aria-label="Action" data-testid="policy-action" required
                 [value]="action()" (input)="action.set(valueOf($event))" />
          <select aria-label="Effect" data-testid="policy-effect" [value]="effect()"
                  (change)="effect.set($any(valueOf($event)))">
            <option value="DENY">DENY</option>
            <option value="PERMIT">PERMIT</option>
          </select>
          <input placeholder="Description" aria-label="Description" data-testid="policy-description" required
                 [value]="description()" (input)="description.set(valueOf($event))" />
          <textarea aria-label="Condition" data-testid="policy-condition" rows="6" required
                    placeholder='{"attr": "resource.amount", "op": "gt", "ref": "subject.approvalLimit"}'
                    [value]="condition()" (input)="condition.set(valueOf($event))"></textarea>
          <label>
            <input type="checkbox" data-testid="policy-enabled" [checked]="enabled()"
                   (change)="enabled.set($any($event.target).checked)" />
            Enabled
          </label>
          <button type="submit" data-testid="create-policy">Create policy</button>
        </form>
      }
    }

    @if (section === 'compliance') {
      <form (submit)="showExpiring($event)" data-testid="expiring-filter">
        <label>Expiring within
          <input type="number" min="0" max="3650" aria-label="Days" data-testid="expiring-days"
                 [value]="withinDays()" (input)="withinDays.set(valueOf($event))" /> days
        </label>
        <button type="submit" data-testid="show-expiring">Show</button>
      </form>
      @if (loading()) { <p class="muted">Loading…</p> }
      @if (!loading() && expiring().length === 0) {
        <p class="muted" data-testid="nothing-expiring">Nothing lapses in that time.</p>
      }
      @if (expiring().length > 0) {
        <app-row-table testId="expiring" [rows]="expiring()" [columns]="documentColumns" />
      }

      <section data-testid="owner-documents-panel">
        <h2>Documents of one record</h2>
        <form (submit)="openOwner($event)" data-testid="owner-form">
          <select aria-label="Kind" data-testid="owner-type" [value]="ownerType()"
                  (change)="ownerType.set($any(valueOf($event)))">
            @for (t of ownerTypes; track t) { <option [value]="t">{{ t }}</option> }
          </select>
          <input placeholder="Id" aria-label="Owner id" data-testid="owner-id" required
                 [value]="ownerId()" (input)="ownerId.set(valueOf($event))" />
          <button type="submit" data-testid="open-owner">Open</button>
        </form>
        @if (ownerOpened()) {
          @if (ownerDocuments().length === 0) { <p class="muted" data-testid="no-documents">No documents on file.</p> }
          @else { <app-row-table testId="owner-documents" [rows]="ownerDocuments()" [columns]="documentColumns" /> }
          @if (canAttach()) {
            <form (submit)="attach($event)" data-testid="attach-document">
              <h3>Attach a document</h3>
              <input placeholder="Type, such as INSURANCE" aria-label="Document type" data-testid="doc-type" required
                     [value]="docType()" (input)="docType.set(valueOf($event))" />
              <input placeholder="Number" aria-label="Document number" data-testid="doc-no" required
                     [value]="docNo()" (input)="docNo.set(valueOf($event))" />
              <input placeholder="Issued by" aria-label="Issuing authority" data-testid="doc-authority"
                     [value]="docAuthority()" (input)="docAuthority.set(valueOf($event))" />
              <label>Issued <input type="date" aria-label="Issued on" data-testid="doc-issued"
                     [value]="docIssued()" (input)="docIssued.set(valueOf($event))" /></label>
              <label>Expires <input type="date" aria-label="Expires on" data-testid="doc-expires"
                     [value]="docExpires()" (input)="docExpires.set(valueOf($event))" /></label>
              <button type="submit" data-testid="attach-document-submit">Attach</button>
            </form>
          }
        }
      </section>
    }
  `,
})
export class AdminDesk implements OnInit {
  private readonly gateway = inject(LmsGateway);
  private readonly session = inject(Session);
  readonly section: AdminSection = inject(ActivatedRoute).snapshot.data['section'] ?? 'users';
  readonly title = TITLES[this.section];

  readonly valueOf = valueOf;
  readonly freightModes = ACCESS_FREIGHT_MODES;
  readonly ownerTypes = DOCUMENT_OWNER_TYPES;
  readonly loading = signal(true);
  readonly refusal = signal<Refusal | null>(null);
  readonly message = signal('');

  // users
  readonly users = signal<AdminUser[]>([]);
  readonly userRows = computed<Row[]>(() => this.users().map((u) => ({
    id: u.id, fullName: u.fullName, email: u.email, status: u.status, orgName: u.orgName, lastLoginAt: u.lastLoginAt,
  })));
  readonly pickedUser = signal('');
  readonly currentUser = computed(() => this.users().find((u) => u.id === this.pickedUser()) ?? null);
  readonly permissions = signal<string[]>([]);
  readonly region = signal('');
  readonly modes = signal<AccessFreightMode[]>([]);
  readonly partnerId = signal('');
  readonly approvalLimit = signal('');
  readonly userColumns = [
    { header: 'Name', field: 'fullName' }, { header: 'Email', field: 'email' }, { header: 'Status', field: 'status' },
    { header: 'Unit', field: 'orgName' }, { header: 'Last sign-in', field: 'lastLoginAt' },
  ];

  // policies
  readonly policies = signal<AccessPolicyView[]>([]);
  readonly policyRows = computed<Row[]>(() => this.policies().map((p) => ({
    id: p.id, code: p.code, action: p.action, effect: p.effect, enabled: p.enabled ? 'Yes' : 'No',
    description: p.description,
  })));
  readonly policyCursor = signal<string | null>(null);
  readonly pickedPolicy = signal('');
  readonly currentPolicy = computed(() => this.policies().find((p) => p.id === this.pickedPolicy()) ?? null);
  readonly code = signal('');
  readonly action = signal('');
  readonly effect = signal<PolicyEffect>('DENY');
  readonly description = signal('');
  readonly condition = signal('');
  readonly enabled = signal(true);
  readonly policyColumns = [
    { header: 'Code', field: 'code' }, { header: 'Action', field: 'action' }, { header: 'Effect', field: 'effect' },
    { header: 'Enabled', field: 'enabled' }, { header: 'Description', field: 'description' },
  ];

  // compliance
  readonly withinDays = signal('30');
  readonly expiring = signal<Row[]>([]);
  readonly ownerType = signal<DocumentOwnerType>('VEHICLE');
  readonly ownerId = signal('');
  readonly ownerOpened = signal(false);
  readonly ownerDocuments = signal<Row[]>([]);
  readonly canAttach = computed(() => this.session.can('VEHICLE_UPDATE'));
  readonly docType = signal('');
  readonly docNo = signal('');
  readonly docAuthority = signal('');
  readonly docIssued = signal(inDays(0));
  readonly docExpires = signal(inDays(365));
  readonly documentColumns = [
    { header: 'Owner', field: 'ownerLabel' }, { header: 'Kind', field: 'ownerType' },
    { header: 'Document', field: 'documentType' }, { header: 'Number', field: 'documentNo' },
    { header: 'Expires', field: 'expiresOn' }, { header: 'Days left', field: 'daysToExpiry' },
    { header: 'State', field: 'state' },
  ];

  ngOnInit(): Promise<void> {
    if (this.section === 'policies') {
      return this.loadPolicies();
    }
    if (this.section === 'compliance') {
      return this.loadExpiring();
    }
    return this.loadUsers();
  }

  // ------------------------------------------------------------------ users

  async loadUsers(): Promise<void> {
    await this.read(async () => this.users.set(await this.gateway.adminUsers()));
  }

  async openUser(id: string): Promise<void> {
    this.pickedUser.set(id);
    this.message.set('');
    await this.act(async () => {
      const [permissions, attributes] = await Promise.all([
        this.gateway.userPermissions(id), this.gateway.accessAttributes(id)]);
      this.permissions.set(permissions);
      this.region.set(attributes.region);
      this.modes.set(attributes.freightModes);
      this.partnerId.set(attributes.partnerId);
      this.approvalLimit.set(attributes.approvalLimit);
    });
  }

  toggleMode(mode: AccessFreightMode, on: boolean): void {
    this.modes.update((m) => (on ? [...m, mode] : m.filter((x) => x !== mode)));
  }

  async saveAttributes(event: Event, userId: string): Promise<void> {
    event.preventDefault();
    await this.act(async () => {
      await this.gateway.setAccessAttributes(userId, {
        region: this.region().trim(), freightModes: this.modes(), partnerId: this.partnerId().trim(),
        approvalLimit: this.approvalLimit().trim(),
      });
      return 'Saved. The user’s next sign-in carries these attributes.';
    });
  }

  // --------------------------------------------------------------- policies

  async loadPolicies(): Promise<void> {
    await this.read(async () => {
      const page = await this.gateway.accessPolicies();
      this.policies.set(page.policies);
      this.policyCursor.set(page.nextCursor);
    });
  }

  async morePolicies(): Promise<void> {
    const cursor = this.policyCursor();
    if (!cursor) {
      return;
    }
    await this.act(async () => {
      const page = await this.gateway.accessPolicies(cursor);
      this.policies.update((p) => [...p, ...page.policies]);
      this.policyCursor.set(page.nextCursor);
    });
  }

  openPolicy(id: string): void {
    this.pickedPolicy.set(id);
    const p = this.currentPolicy();
    this.effect.set(p?.effect ?? 'DENY');
    this.description.set(p?.description ?? '');
    this.condition.set(p?.condition ?? '');
    this.enabled.set(p?.enabled ?? true);
    this.refusal.set(null);
    this.message.set('');
  }

  closePolicy(): void {
    this.pickedPolicy.set('');
    this.code.set('');
    this.action.set('');
    this.effect.set('DENY');
    this.description.set('');
    this.condition.set('');
    this.enabled.set(true);
  }

  async createPolicy(event: Event): Promise<void> {
    event.preventDefault();
    await this.act(async () => {
      const id = await this.gateway.createAccessPolicy({
        code: this.code(), action: this.action(), effect: this.effect(), description: this.description(),
        condition: this.condition(), enabled: this.enabled(),
      });
      await this.reloadPolicies();
      this.openPolicy(id);
      return 'Policy created.';
    });
  }

  async savePolicy(event: Event, id: string): Promise<void> {
    event.preventDefault();
    await this.act(async () => {
      await this.gateway.reviseAccessPolicy(id, {
        effect: this.effect(), description: this.description(), condition: this.condition(), enabled: this.enabled(),
      });
      await this.reloadPolicies();
      return 'Policy saved.';
    });
  }

  async deletePolicy(id: string): Promise<void> {
    await this.act(async () => {
      await this.gateway.deleteAccessPolicy(id);
      this.closePolicy();
      await this.reloadPolicies();
      return 'Policy deleted.';
    });
  }

  /** Reads the list again from the first page; a policy just made or changed may sort anywhere. */
  private async reloadPolicies(): Promise<void> {
    const page = await this.gateway.accessPolicies();
    this.policies.set(page.policies);
    this.policyCursor.set(page.nextCursor);
  }

  // ------------------------------------------------------------- compliance

  async loadExpiring(): Promise<void> {
    await this.read(async () => this.expiring.set(await this.gateway.expiringDocuments(this.days())));
  }

  async showExpiring(event: Event): Promise<void> {
    event.preventDefault();
    await this.loadExpiring();
  }

  async openOwner(event: Event): Promise<void> {
    event.preventDefault();
    this.ownerOpened.set(false);
    await this.act(async () => {
      this.ownerDocuments.set(await this.gateway.ownerDocuments(this.ownerType(), this.ownerId()));
      this.ownerOpened.set(true);
    });
  }

  async attach(event: Event): Promise<void> {
    event.preventDefault();
    await this.act(async () => {
      await this.gateway.attachDocument(this.ownerType(), this.ownerId(), {
        documentType: this.docType(), documentNo: this.docNo(), issuingAuthority: this.docAuthority(),
        issuedOn: this.docIssued(), expiresOn: this.docExpires(),
      });
      this.ownerDocuments.set(await this.gateway.ownerDocuments(this.ownerType(), this.ownerId()));
      this.expiring.set(await this.gateway.expiringDocuments(this.days()));
      this.docType.set('');
      this.docNo.set('');
      this.docAuthority.set('');
      return 'Document attached.';
    });
  }

  private days(): number {
    const n = Number(this.withinDays());
    return Number.isFinite(n) && n >= 0 ? Math.min(Math.floor(n), 3650) : 30;
  }

  // ---------------------------------------------------------------- helpers

  /** A first read of the section: shows Loading… until it settles. */
  private async read(load: () => Promise<void>): Promise<void> {
    this.loading.set(true);
    this.refusal.set(null);
    try {
      await load();
    } catch (e) {
      this.refusal.set(refusalOf(e));
    } finally {
      this.loading.set(false);
    }
  }

  private async act(action: () => Promise<string | void>): Promise<void> {
    this.refusal.set(null);
    this.message.set('');
    try {
      const said = await action();
      if (said) {
        this.message.set(said);
      }
    } catch (e) {
      this.refusal.set(refusalOf(e));
    }
  }
}
