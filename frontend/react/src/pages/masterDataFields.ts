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

import type { MasterKind, MasterValues } from '../gateway/lmsGateway';

/** One input of a master-data form. `testId` is written out so both consoles' ids can be searched for. */
export interface FieldDef {
  name: string;
  testId: string;
  label: string;
  type: 'text' | 'number' | 'date' | 'time' | 'select' | 'checkbox' | 'textarea' | 'partner';
  required?: boolean;
  /** Fixed once the record exists: shown, not editable. */
  createOnly?: boolean;
  options?: readonly string[];
  hint?: string;
  /** Whether the field applies, given what has been entered and whether the record is new. */
  when?: (values: MasterValues, isNew: boolean) => boolean;
}

export const KIND_LABEL: Record<MasterKind, string> = {
  partners: 'partner', vehicles: 'vehicle', drivers: 'driver', terminals: 'terminal',
};

/** The permission that edits (and switches) each kind, and the one that creates it. */
export const EDIT_PERMISSION: Record<MasterKind, string> = {
  partners: 'PARTNER_UPDATE', vehicles: 'VEHICLE_UPDATE', drivers: 'DRIVER_UPDATE', terminals: 'TERMINAL_UPDATE',
};
export const CREATE_PERMISSION: Record<MasterKind, string> = {
  partners: 'PARTNER_CREATE', vehicles: 'VEHICLE_CREATE', drivers: 'DRIVER_CREATE', terminals: 'TERMINAL_CREATE',
};

export const PARTNER_TYPE_OPTIONS = ['CUSTOMER', 'VENDOR', 'BROKER', 'CONSIGNEE'] as const;
export const CATEGORY_OPTIONS = ['PLANT', 'WAREHOUSE', 'PORT', 'HUB', 'TOLL_PLAZA', 'CUSTOMER_SITE', 'RAILHEAD'] as const;

/** The statuses a list can be narrowed to, after the default Active and All. */
export const STATUS_OPTIONS: Record<MasterKind, readonly string[]> = {
  partners: ['DRAFT', 'PENDING_VERIFICATION', 'ACTIVE', 'SUSPENDED', 'BLACKLISTED'],
  vehicles: ['AVAILABLE', 'ON_TRIP', 'MAINTENANCE', 'RETIRED', 'INACTIVE'],
  drivers: ['AVAILABLE', 'ON_TRIP', 'REST', 'INACTIVE'],
  terminals: ['ACTIVE', 'INACTIVE'],
};

const point = (v: MasterValues) => v.geofenceType !== 'POLYGON';
const POLYGON_NEW = (v: MasterValues, isNew: boolean) => v.geofenceType === 'POLYGON' && isNew;

export const FIELDS: Record<MasterKind, FieldDef[]> = {
  partners: [
    { name: 'code', testId: 'field-code', label: 'Code', type: 'text', required: true, createOnly: true },
    { name: 'legalName', testId: 'field-legalName', label: 'Legal name', type: 'text', required: true },
    { name: 'partnerType', testId: 'field-partnerType', label: 'Partner type', type: 'select', required: true,
      createOnly: true, options: PARTNER_TYPE_OPTIONS },
    { name: 'taxId', testId: 'field-taxId', label: 'Tax id', type: 'text' },
    { name: 'paymentTerms', testId: 'field-paymentTerms', label: 'Payment terms', type: 'text',
      when: (_v, isNew) => !isNew },
    { name: 'billingAddress', testId: 'field-billingAddress', label: 'Billing address', type: 'textarea',
      when: (_v, isNew) => !isNew },
  ],
  vehicles: [
    { name: 'registrationNo', testId: 'field-registrationNo', label: 'Registration number', type: 'text', required: true, createOnly: true },
    { name: 'category', testId: 'field-category', label: 'Category', type: 'text', required: true },
    { name: 'vehicleType', testId: 'field-vehicleType', label: 'Vehicle type', type: 'text', required: true },
    { name: 'axleConfig', testId: 'field-axleConfig', label: 'Axle configuration', type: 'text' },
    { name: 'grossWeightKg', testId: 'field-grossWeightKg', label: 'Gross weight (kg)', type: 'number', required: true },
    { name: 'tareWeightKg', testId: 'field-tareWeightKg', label: 'Tare weight (kg)', type: 'number', required: true },
    { name: 'maxVolumeM3', testId: 'field-maxVolumeM3', label: 'Maximum volume (m3)', type: 'number', required: true },
    { name: 'ownerPartnerId', testId: 'field-ownerPartnerId', label: 'Owner', type: 'partner' },
    { name: 'hazmatCertified', testId: 'field-hazmatCertified', label: 'Certified for dangerous goods', type: 'checkbox' },
    { name: 'reeferCapable', testId: 'field-reeferCapable', label: 'Refrigerated', type: 'checkbox' },
  ],
  drivers: [
    { name: 'fullName', testId: 'field-fullName', label: 'Full name', type: 'text', required: true },
    { name: 'phone', testId: 'field-phone', label: 'Phone', type: 'text', required: true },
    { name: 'licenceNo', testId: 'field-licenceNo', label: 'Licence number', type: 'text', required: true, createOnly: true },
    { name: 'licenceClass', testId: 'field-licenceClass', label: 'Licence class', type: 'text', required: true },
    { name: 'licenceAuthority', testId: 'field-licenceAuthority', label: 'Licence authority', type: 'text' },
    { name: 'licenceExpiresOn', testId: 'field-licenceExpiresOn', label: 'Licence expires on', type: 'date', required: true },
    { name: 'employerPartnerId', testId: 'field-employerPartnerId', label: 'Employer', type: 'partner' },
    { name: 'hazmatEndorsed', testId: 'field-hazmatEndorsed', label: 'Endorsed for dangerous goods', type: 'checkbox' },
  ],
  terminals: [
    { name: 'code', testId: 'field-code', label: 'Code', type: 'text', required: true, createOnly: true },
    { name: 'name', testId: 'field-name', label: 'Name', type: 'text', required: true },
    { name: 'functionalCategory', testId: 'field-functionalCategory', label: 'Category', type: 'select', required: true,
      createOnly: true, options: CATEGORY_OPTIONS },
    { name: 'geofenceType', testId: 'field-geofenceType', label: 'Geofence', type: 'select', createOnly: true,
      options: ['POINT_RADIUS', 'POLYGON'] },
    { name: 'centreLat', testId: 'field-centreLat', label: 'Centre latitude', type: 'number', required: true, when: point },
    { name: 'centreLon', testId: 'field-centreLon', label: 'Centre longitude', type: 'number', required: true, when: point },
    { name: 'radiusMetres', testId: 'field-radiusMetres', label: 'Radius (m)', type: 'number', required: true, when: point },
    { name: 'ring', testId: 'field-ring', label: 'Polygon corners', type: 'textarea', required: true,
      hint: 'One corner per line as latitude, longitude; at least three.', when: POLYGON_NEW },
    { name: 'dockCount', testId: 'field-dockCount', label: 'Docks', type: 'number' },
    { name: 'opensAt', testId: 'field-opensAt', label: 'Opens at', type: 'time' },
    { name: 'closesAt', testId: 'field-closesAt', label: 'Closes at', type: 'time' },
    { name: 'avgDwellMinutes', testId: 'field-avgDwellMinutes', label: 'Average dwell (minutes)', type: 'number' },
  ],
};

/** What a new record starts with. */
export function blankValues(kind: MasterKind): MasterValues {
  const v: MasterValues = {};
  for (const f of FIELDS[kind]) {
    v[f.name] = f.type === 'checkbox' ? false : '';
  }
  if (kind === 'partners') { v.partnerType = 'CUSTOMER'; }
  if (kind === 'terminals') { v.functionalCategory = 'WAREHOUSE'; v.geofenceType = 'POINT_RADIUS'; }
  return v;
}

const isNumber = (s: string) => s.trim() !== '' && Number.isFinite(Number(s));

/** What is wrong with the values, by field name; empty when they can be sent. */
export function validate(kind: MasterKind, values: MasterValues, isNew: boolean): Record<string, string> {
  const errors: Record<string, string> = {};
  for (const f of FIELDS[kind]) {
    if (f.when && !f.when(values, isNew)) { continue; }
    if (f.createOnly && !isNew) { continue; }
    const raw = values[f.name];
    const text = typeof raw === 'string' ? raw.trim() : '';
    if (f.required && f.type !== 'checkbox' && !text) {
      errors[f.name] = `${f.label} is required.`;
    } else if (f.type === 'number' && text && !isNumber(text)) {
      errors[f.name] = `${f.label} must be a number.`;
    }
  }
  if (kind === 'vehicles' && !errors.grossWeightKg && !errors.tareWeightKg
    && Number(values.tareWeightKg) >= Number(values.grossWeightKg)) {
    errors.tareWeightKg = 'Tare weight must be less than gross weight.';
  }
  if (kind === 'terminals' && values.geofenceType !== 'POLYGON' && !errors.radiusMetres && Number(values.radiusMetres) <= 0) {
    errors.radiusMetres = 'Radius must be positive.';
  }
  if (kind === 'terminals' && POLYGON_NEW(values, isNew) && !errors.ring) {
    const lines = String(values.ring).split('\n').map((l) => l.trim()).filter(Boolean);
    if (lines.length < 3 || lines.some((l) => l.split(/[\s,;]+/).filter(Boolean).length !== 2
      || l.split(/[\s,;]+/).some((n) => !isNumber(n)))) {
      errors.ring = 'Enter at least three corners, one per line as latitude, longitude.';
    }
  }
  return errors;
}
