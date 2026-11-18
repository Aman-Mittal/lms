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

/*
 * The sidebar, defined once for both consoles. No framework code lives here: the Angular and React shells import
 * this module and only draw what it returns, so the groups, labels, order, test ids and permission gating cannot
 * drift apart between them.
 */

export interface NavItem {
  /** The test id is `nav-${key}`. */
  key: string;
  label: string;
  /** The route path, with a leading slash. */
  route: string;
  /** Shown when the person holds any one of these; absent means everyone. */
  any?: readonly string[];
}

/** A run of links inside a group; a section may carry its own heading and test id. */
export interface NavSection {
  label?: string;
  testid?: string;
  items: readonly NavItem[];
}

export interface NavGroup {
  id: string;
  label: string;
  sections: readonly NavSection[];
}

export const NAV_GROUPS: readonly NavGroup[] = [
  { id: 'overview', label: 'Overview', sections: [{ items: [
    { key: 'overview', label: 'Control tower', route: '/' },
    { key: 'exceptions', label: 'Exceptions', route: '/exceptions' },
    { key: 'exception-desk', label: 'Exception desk', route: '/exception-desk', any: ['EXCEPTION_READ'] },
    { key: 'parked-events', label: 'Parked events', route: '/parked-events', any: ['EVENT_DEAD_LETTER_READ'] },
  ] }] },
  { id: 'orders', label: 'Orders & sales', sections: [{ items: [
    { key: 'orders', label: 'Orders', route: '/orders' },
    { key: 'order-desk', label: 'Order desk', route: '/order-desk', any: ['ORDER_READ'] },
    { key: 'enquiries', label: 'Enquiry desk', route: '/enquiries', any: ['ORDER_READ'] },
    { key: 'order-upload', label: 'Bulk order upload', route: '/order-upload', any: ['ORDER_CREATE'] },
    { key: 'approvals', label: 'Approvals', route: '/approvals', any: ['APPROVAL_READ'] },
  ] }] },
  { id: 'planning', label: 'Planning', sections: [{ items: [
    { key: 'loads', label: 'Loads', route: '/loads' },
    { key: 'load-building', label: 'Load building', route: '/load-building', any: ['LOAD_READ'] },
    { key: 'allocation-rules', label: 'Allocation rules', route: '/allocation-rules', any: ['ALLOCATION_RULE_READ'] },
    { key: 'map', label: 'Network map', route: '/map', any: ['TERMINAL_READ'] },
  ] }] },
  { id: 'execution', label: 'Execution', sections: [{ items: [
    { key: 'trips', label: 'Trips', route: '/trips' },
    { key: 'trip-desk', label: 'Trip desk', route: '/trip-desk', any: ['TRIP_READ'] },
    { key: 'trip-route', label: 'Trip route', route: '/trip-route', any: ['TRIP_READ'] },
    { key: 'consignments', label: 'Consignments', route: '/consignments', any: ['LOAD_READ'] },
    { key: 'gate', label: 'Gate', route: '/gate', any: ['GATE_REGISTER', 'GATE_PASS_MANAGE'] },
    { key: 'eway-bills', label: 'E-way bills', route: '/eway-bills', any: ['ORDER_READ'] },
    { key: 'delivery', label: 'Delivery desk', route: '/delivery', any: ['POD_READ'] },
    { key: 'pod', label: 'Proof of delivery', route: '/pod', any: ['POD_READ'] },
    { key: 'tracking', label: 'Tracking links', route: '/tracking', any: ['TRACKING_LINK_MANAGE'] },
    { key: 'claims', label: 'Claims', route: '/claims', any: ['CLAIM_READ'] },
  ] }] },
  { id: 'warehouse', label: 'Warehouse', sections: [{ items: [
    { key: 'warehouse', label: 'Warehouse operations', route: '/warehouse', any: ['WAREHOUSE_READ'] },
  ] }] },
  { id: 'multimodal', label: 'Multimodal', sections: [{ items: [
    { key: 'itineraries', label: 'Itineraries', route: '/itineraries', any: ['MULTIMODAL_READ'] },
    { key: 'milk-runs', label: 'Milk runs', route: '/milk-runs', any: ['MILKRUN_READ'] },
  ] }] },
  { id: 'finance', label: 'Finance', sections: [{ items: [
    { key: 'freight-bills', label: 'Freight bills', route: '/freight-bills' },
    { key: 'client-billing', label: 'Client billing', route: '/client-billing', any: ['CLIENT_BILLING_READ'] },
    { key: 'manual-charges', label: 'Manual charges', route: '/manual-charges', any: ['MANUAL_CHARGE_RECORD'] },
    { key: 'charge-catalogue', label: 'Charge catalogue', route: '/charge-catalogue', any: ['CHARGE_CATALOGUE_MANAGE'] },
    { key: 'payables', label: 'Vendor payables', route: '/payables', any: ['PAYABLES_MANAGE'] },
    { key: 'ltl', label: 'LTL rating', route: '/ltl', any: ['INVOICE_READ', 'TARIFF_MANAGE'] },
    { key: 'commercial', label: 'Commercial', route: '/commercial',
      any: ['INVOICE_READ', 'TARIFF_MANAGE', 'ROUTING_GUIDE_MANAGE', 'LOAD_ALLOCATE'] },
    { key: 'admin-credit', label: 'Credit limits', route: '/admin/credit-limits',
      any: ['CREDIT_LIMIT_REQUEST', 'CREDIT_LIMIT_APPROVE'] },
  ] }] },
  { id: 'master-data', label: 'Master data', sections: [{ items: [
    { key: 'partners', label: 'Partners', route: '/partners' },
    { key: 'vehicles', label: 'Vehicles', route: '/vehicles' },
    { key: 'drivers', label: 'Drivers', route: '/drivers' },
    { key: 'terminals', label: 'Terminals', route: '/terminals' },
  ] }] },
  { id: 'admin', label: 'Admin & settings', sections: [
    // The first section keeps the `nav-admin` test id: it is present only for someone who holds an administration right.
    { testid: 'nav-admin', label: 'Administration', items: [
      { key: 'admin-users', label: 'Users', route: '/admin/users', any: ['ACCESS_POLICY_MANAGE'] },
      { key: 'admin-policies', label: 'Access policies', route: '/admin/access-policies', any: ['ACCESS_POLICY_MANAGE'] },
      { key: 'admin-compliance', label: 'Compliance', route: '/admin/compliance', any: ['VEHICLE_READ'] },
      { key: 'admin-number-series', label: 'Number series', route: '/admin/number-series', any: ['NUMBER_SERIES_MANAGE'] },
      { key: 'admin-org-units', label: 'Organisation units', route: '/admin/org-units', any: ['ORG_UNIT_MANAGE'] },
      { key: 'admin-settings', label: 'Profile settings', route: '/admin/settings', any: ['SETTINGS_READ', 'SETTINGS_MANAGE'] },
    ] },
    { label: 'Settings', items: [
      { key: 'custom-fields', label: 'Custom fields', route: '/settings/custom-fields', any: ['CUSTOM_FIELD_READ'] },
      { key: 'demo', label: 'Demo data', route: '/settings/demo', any: ['DEMO_RESET'] },
      { key: 'webhooks', label: 'Webhooks', route: '/settings/webhooks', any: ['WEBHOOK_READ'] },
    ] },
  ] },
];

export interface VisibleSection { label?: string; testid?: string; items: NavItem[] }
export interface VisibleGroup { id: string; label: string; sections: VisibleSection[]; count: number }

/**
 * What a person is offered: each entry kept only if they hold one of its permissions, and, when `query` is not blank,
 * only entries whose label, or whose group's label, contains it. Groups left with nothing are dropped.
 */
export function visibleNav(can: (permission: string) => boolean, query = ''): VisibleGroup[] {
  const needle = query.trim().toLowerCase();
  const out: VisibleGroup[] = [];
  for (const group of NAV_GROUPS) {
    const groupHit = needle !== '' && group.label.toLowerCase().includes(needle);
    const sections: VisibleSection[] = [];
    let count = 0;
    for (const section of group.sections) {
      const items = section.items.filter((i) => (!i.any || i.any.some(can))
        && (needle === '' || groupHit || i.label.toLowerCase().includes(needle)));
      if (items.length > 0) {
        sections.push({ label: section.label, testid: section.testid, items });
        count += items.length;
      }
    }
    if (count > 0) {
      out.push({ id: group.id, label: group.label, sections, count });
    }
  }
  return out;
}

/** The group holding the route being shown, so it can be opened. */
export function activeGroupId(path: string): string | null {
  const clean = path.split(/[?#]/)[0] || '/';
  let best: { id: string; length: number } | null = null;
  for (const group of NAV_GROUPS) {
    for (const section of group.sections) {
      for (const item of section.items) {
        const hit = item.route === '/' ? clean === '/' : clean === item.route || clean.startsWith(`${item.route}/`);
        if (hit && (!best || item.route.length > best.length)) {
          best = { id: group.id, length: item.route.length };
        }
      }
    }
  }
  return best?.id ?? null;
}

/** Whether a link is the page being shown (React Router and Angular both do this themselves; the drawer uses it). */
export function isActiveRoute(item: NavItem, path: string): boolean {
  const clean = path.split(/[?#]/)[0] || '/';
  return item.route === '/' ? clean === '/' : clean === item.route || clean.startsWith(`${item.route}/`);
}

// ----------------------------------------------------------- remembered open and closed groups

const STORAGE_KEY = 'lms.nav.groups';

/** At or below this width the sidebar is an off-canvas drawer. */
export const DRAWER_QUERY = '(max-width: 899.98px)';

export function isNarrow(): boolean {
  try {
    return typeof matchMedia === 'function' && matchMedia(DRAWER_QUERY).matches;
  } catch {
    return false;
  }
}

/** Group id to open (true) or closed (false), as the person last left it. Storage may be missing or blocked. */
export function loadGroupState(): Record<string, boolean> {
  try {
    const parsed: unknown = JSON.parse(localStorage.getItem(STORAGE_KEY) ?? '{}');
    if (parsed && typeof parsed === 'object') {
      const out: Record<string, boolean> = {};
      for (const [id, open] of Object.entries(parsed)) {
        if (typeof open === 'boolean') {
          out[id] = open;
        }
      }
      return out;
    }
  } catch {
    // unreadable or unavailable: start from the defaults
  }
  return {};
}

export function saveGroupState(state: Record<string, boolean>): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
  } catch {
    // private mode or blocked storage: the choice just is not remembered
  }
}

/**
 * Whether a group is shown open. With no choice made, groups are open on a wide screen (every link is one click away)
 * and closed in the drawer, apart from the group of the page being shown. Searching opens every group that matches.
 */
export function groupOpen(
  state: Record<string, boolean>, id: string, activeId: string | null, narrow: boolean, searching: boolean,
): boolean {
  if (searching) {
    return true;
  }
  return state[id] ?? (narrow ? id === activeId : true);
}
