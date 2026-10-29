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

import { ResourceKey } from '../gateway/lms-gateway';

export interface Column {
  header: string;
  /** Property of the row to show. */
  field: string;
}

export interface Resource {
  key: ResourceKey;
  title: string;
  columns: Column[];
}

/** What each list screen shows. Rows come from the gateway, already formatted. */
export const RESOURCES: Resource[] = [
  { key: 'partners', title: 'Partners', columns: [
    { header: 'Code', field: 'code' }, { header: 'Name', field: 'legalName' },
    { header: 'Type', field: 'partnerType' }, { header: 'Status', field: 'status' } ] },
  { key: 'vehicles', title: 'Vehicles', columns: [
    { header: 'Registration', field: 'registrationNo' }, { header: 'Type', field: 'vehicleType' },
    { header: 'Status', field: 'status' }, { header: 'Payload kg', field: 'payloadCapacityKg' } ] },
  { key: 'orders', title: 'Orders', columns: [
    { header: 'Order', field: 'orderNo' }, { header: 'Status', field: 'status' },
    { header: 'Customer', field: 'customerName' }, { header: 'Origin', field: 'originTerminalCode' } ] },
  { key: 'loads', title: 'Loads', columns: [
    { header: 'Load', field: 'loadNo' }, { header: 'Status', field: 'status' },
    { header: 'Vehicle', field: 'vehicleRegistrationNo' }, { header: 'Weight use %', field: 'weightUtilisationPct' } ] },
  { key: 'trips', title: 'Trips', columns: [
    { header: 'Trip', field: 'tripNo' }, { header: 'Status', field: 'status' },
    { header: 'Vendor', field: 'vendorName' }, { header: 'Driver', field: 'driverName' } ] },
  { key: 'freight-bills', title: 'Freight bills', columns: [
    { header: 'Bill', field: 'billNo' }, { header: 'Status', field: 'status' },
    { header: 'Vendor', field: 'vendorName' }, { header: 'Claimed', field: 'claimedAmount' },
    { header: 'Variance', field: 'varianceAmount' } ] },
];
