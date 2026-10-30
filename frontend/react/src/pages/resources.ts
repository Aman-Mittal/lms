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

import type { CustomFieldKind, ResourceKey } from '../gateway/lmsGateway';

export interface Column {
  header: string;
  field: string;
}

export interface Resource {
  key: ResourceKey;
  title: string;
  /** The sidebar section it sits under. */
  group: string;
  /** The kind of custom field it carries, where it carries any. */
  customKind?: CustomFieldKind;
  /** The permission that shows it in the sidebar; absent means everyone. */
  permission?: string;
  columns: Column[];
}

/** What each list screen shows. Rows come from the gateway, already formatted. */
export const RESOURCES: Resource[] = [
  { key: 'partners', title: 'Partners', group: 'Master data', customKind: 'PARTNER', columns: [
    { header: 'Code', field: 'code' }, { header: 'Name', field: 'legalName' },
    { header: 'Type', field: 'partnerType' }, { header: 'Status', field: 'status' } ] },
  { key: 'vehicles', title: 'Vehicles', group: 'Master data', customKind: 'VEHICLE', columns: [
    { header: 'Registration', field: 'registrationNo' }, { header: 'Type', field: 'vehicleType' },
    { header: 'Status', field: 'status' }, { header: 'Payload kg', field: 'payloadCapacityKg' } ] },
  { key: 'drivers', title: 'Drivers', group: 'Master data', customKind: 'DRIVER', columns: [
    { header: 'Name', field: 'fullName' }, { header: 'Licence', field: 'licenceNo' },
    { header: 'Licence expires', field: 'licenceExpiresOn' }, { header: 'Status', field: 'status' } ] },
  { key: 'terminals', title: 'Terminals', group: 'Master data', customKind: 'TERMINAL', columns: [
    { header: 'Code', field: 'code' }, { header: 'Name', field: 'name' },
    { header: 'Category', field: 'functionalCategory' }, { header: 'Docks', field: 'dockCount' } ] },
  { key: 'orders', title: 'Orders', group: 'Operations', customKind: 'ORDER', columns: [
    { header: 'Order', field: 'orderNo' }, { header: 'Status', field: 'status' },
    { header: 'Customer', field: 'customerName' }, { header: 'Origin', field: 'originTerminalCode' } ] },
  { key: 'loads', title: 'Loads', group: 'Operations', customKind: 'LOAD', columns: [
    { header: 'Load', field: 'loadNo' }, { header: 'Status', field: 'status' },
    { header: 'Vehicle', field: 'vehicleRegistrationNo' }, { header: 'Weight use %', field: 'weightUtilisationPct' } ] },
  { key: 'trips', title: 'Trips', group: 'Operations', customKind: 'TRIP', columns: [
    { header: 'Trip', field: 'tripNo' }, { header: 'Status', field: 'status' },
    { header: 'Vendor', field: 'vendorName' }, { header: 'Driver', field: 'driverName' } ] },
  { key: 'freight-bills', title: 'Freight bills', group: 'Finance', columns: [
    { header: 'Bill', field: 'billNo' }, { header: 'Status', field: 'status' },
    { header: 'Vendor', field: 'vendorName' }, { header: 'Claimed', field: 'claimedAmount' },
    { header: 'Variance', field: 'varianceAmount' } ] },
  { key: 'exceptions', title: 'Exceptions', group: 'Control tower', columns: [
    { header: 'Ticket', field: 'ticketNo' }, { header: 'Severity', field: 'severity' },
    { header: 'Status', field: 'status' }, { header: 'Category', field: 'category' },
    { header: 'Description', field: 'description' } ] },
  { key: 'claims', title: 'Claims', group: 'Delivery', permission: 'CLAIM_READ', columns: [
    { header: 'Claim', field: 'claimNo' }, { header: 'Type', field: 'claimType' },
    { header: 'Amount', field: 'amount' }, { header: 'Status', field: 'status' },
    { header: 'Consignment', field: 'consignmentId' } ] },
];

/** The sidebar's sections, in order. */
export const GROUPS = ['Master data', 'Operations', 'Finance', 'Control tower', 'Delivery'];
