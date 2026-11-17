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

import type { MasterKind } from '../gateway/master-data-types';

/** One field of a register's form. `id` is its name in the values and in its test ids. */
export interface MasterField {
  id: string;
  label: string;
  /** The data-testid of the control, written out so it can be found in the source. */
  testId: string;
  /** The data-testid of its inline error. */
  errorId: string;
  type: 'text' | 'number' | 'date' | 'time' | 'select' | 'checkbox' | 'textarea' | 'partner';
  required?: boolean;
  /** Fixed once the record exists. */
  createOnly?: boolean;
  options?: readonly string[];
  hint?: string;
  /** Shown only for these geofence types (terminals). */
  shape?: 'POINT_RADIUS' | 'POLYGON';
}

const f = (id: string, testId: string, label: string, type: MasterField['type'], extra: Partial<MasterField> = {}): MasterField =>
  ({ id, testId, label, type, errorId: `error-${id}`, ...extra });

const CATEGORIES = ['PLANT', 'WAREHOUSE', 'PORT', 'HUB', 'TOLL_PLAZA', 'CUSTOMER_SITE', 'RAILHEAD'] as const;

export const MASTER_FIELDS: Record<MasterKind, MasterField[]> = {
  partners: [
    f('code', 'field-code', 'Code', 'text', { required: true, createOnly: true }),
    f('legalName', 'field-legalName', 'Legal name', 'text', { required: true }),
    f('partnerType', 'field-partnerType', 'Partner type', 'select', { createOnly: true, options: ['CUSTOMER', 'VENDOR', 'BROKER', 'CONSIGNEE'] }),
    f('taxId', 'field-taxId', 'Tax id', 'text'),
    f('paymentTerms', 'field-paymentTerms', 'Payment terms', 'text'),
    f('billingAddress', 'field-billingAddress', 'Billing address', 'textarea'),
  ],
  vehicles: [
    f('registrationNo', 'field-registrationNo', 'Registration number', 'text', { required: true, createOnly: true }),
    f('category', 'field-category', 'Category', 'text', { required: true }),
    f('vehicleType', 'field-vehicleType', 'Vehicle type', 'text', { required: true }),
    f('axleConfig', 'field-axleConfig', 'Axle configuration', 'text'),
    f('grossWeightKg', 'field-grossWeightKg', 'Gross weight (kg)', 'number', { required: true }),
    f('tareWeightKg', 'field-tareWeightKg', 'Tare weight (kg)', 'number', { required: true, hint: 'Must be less than the gross weight.' }),
    f('maxVolumeM3', 'field-maxVolumeM3', 'Maximum volume (m3)', 'number', { required: true }),
    f('ownerPartnerId', 'field-ownerPartnerId', 'Owner', 'partner'),
    f('hazmatCertified', 'field-hazmatCertified', 'Certified for dangerous goods', 'checkbox'),
    f('reeferCapable', 'field-reeferCapable', 'Refrigerated', 'checkbox'),
  ],
  drivers: [
    f('fullName', 'field-fullName', 'Full name', 'text', { required: true }),
    f('phone', 'field-phone', 'Phone', 'text', { required: true }),
    f('licenceNo', 'field-licenceNo', 'Licence number', 'text', { required: true, createOnly: true }),
    f('licenceClass', 'field-licenceClass', 'Licence class', 'text', { required: true }),
    f('licenceAuthority', 'field-licenceAuthority', 'Licence authority', 'text'),
    f('licenceExpiresOn', 'field-licenceExpiresOn', 'Licence expires on', 'date', { required: true }),
    f('employerPartnerId', 'field-employerPartnerId', 'Employer', 'partner'),
    f('hazmatEndorsed', 'field-hazmatEndorsed', 'Endorsed for dangerous goods', 'checkbox'),
  ],
  terminals: [
    f('code', 'field-code', 'Code', 'text', { required: true, createOnly: true }),
    f('name', 'field-name', 'Name', 'text', { required: true }),
    f('functionalCategory', 'field-functionalCategory', 'Category', 'select', { createOnly: true, options: CATEGORIES }),
    f('geofenceType', 'field-geofenceType', 'Geofence', 'select', { createOnly: true, options: ['POINT_RADIUS', 'POLYGON'] }),
    f('centreLat', 'field-centreLat', 'Centre latitude', 'number', { required: true, shape: 'POINT_RADIUS' }),
    f('centreLon', 'field-centreLon', 'Centre longitude', 'number', { required: true, shape: 'POINT_RADIUS' }),
    f('radiusMetres', 'field-radiusMetres', 'Radius (metres)', 'number', { required: true, shape: 'POINT_RADIUS' }),
    f('ring', 'field-ring', 'Corners', 'textarea', { required: true, shape: 'POLYGON', hint: 'One corner per line as latitude, longitude. At least three.' }),
    f('dockCount', 'field-dockCount', 'Docks', 'number'),
    f('opensAt', 'field-opensAt', 'Opens at', 'time'),
    f('closesAt', 'field-closesAt', 'Closes at', 'time'),
    f('avgDwellMinutes', 'field-avgDwellMinutes', 'Average dwell (minutes)', 'number'),
  ],
};

export const KIND_WORD: Record<MasterKind, string> = {
  partners: 'partner', vehicles: 'vehicle', drivers: 'driver', terminals: 'terminal',
};

/** The values a new record's form starts with. */
export function blankValues(kind: MasterKind): Record<string, string> {
  const v: Record<string, string> = {};
  for (const field of MASTER_FIELDS[kind]) {
    v[field.id] = field.type === 'checkbox' ? 'false' : field.options ? field.options[0] : '';
  }
  return v;
}

const NUMBER = /^-?\d+(\.\d+)?$/;

/** What is wrong with each field of the form, by field id; empty when it can be sent. */
export function validate(kind: MasterKind, values: Record<string, string>, creating: boolean): Record<string, string> {
  const problems: Record<string, string> = {};
  const shape = values['geofenceType'];
  for (const field of MASTER_FIELDS[kind]) {
    if ((field.createOnly && !creating) || (field.shape && field.shape !== shape)
      || (field.id === 'ring' && !creating && !(values['ring'] ?? '').trim())) {
      continue;
    }
    const value = (values[field.id] ?? '').trim();
    if (field.required && !value) {
      problems[field.id] = `${field.label} is required.`;
    } else if (value && field.type === 'number' && !NUMBER.test(value)) {
      problems[field.id] = `${field.label} must be a number.`;
    } else if (value && field.type === 'date' && !/^\d{4}-\d{2}-\d{2}$/.test(value)) {
      problems[field.id] = `${field.label} must be a date.`;
    }
  }
  const number = (id: string) => Number(values[id]);
  if (kind === 'vehicles' && !problems['tareWeightKg'] && !problems['grossWeightKg']
    && number('tareWeightKg') >= number('grossWeightKg')) {
    problems['tareWeightKg'] = 'Tare weight must be less than the gross weight.';
  }
  for (const id of ['grossWeightKg', 'maxVolumeM3', 'radiusMetres']) {
    if (!problems[id] && values[id]?.trim() && number(id) <= 0) {
      problems[id] = 'Must be more than zero.';
    }
  }
  if (kind === 'terminals' && shape === 'POLYGON' && !problems['ring']) {
    const lines = (values['ring'] ?? '').split('\n').map((l) => l.trim()).filter(Boolean);
    if (lines.length < 3 || lines.some((l) => !/^-?\d+(\.\d+)?[,\s]+-?\d+(\.\d+)?$/.test(l))) {
      problems['ring'] = 'Give at least three corners, one per line as latitude, longitude.';
    }
  }
  return problems;
}
