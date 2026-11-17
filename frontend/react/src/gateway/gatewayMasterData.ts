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

import type { LmsGateway, MasterKind, MasterRecord, MasterValues } from './lmsGateway';
import { GatewayDeps, guard, text } from './gatewayShared';
import {
  PartnerTransitionRequestTargetEnum, type RegisterPartnerRequestPartnerTypeEnum,
  type RegisterPointRadiusTerminalRequestCategoryEnum, type RegisterPolygonTerminalRequestCategoryEnum,
} from '../api';

/** A field as the form holds it: a string, or a boolean for a checkbox. */
const str = (v: unknown): string => (typeof v === 'string' ? v.trim() : '');
/** An optional string: absent when blank, so the server keeps or clears it rather than storing ''. */
const opt = (v: unknown): string | undefined => str(v) || undefined;
const num = (v: unknown): number => Number(str(v));
const optNum = (v: unknown): number | undefined => (str(v) ? Number(str(v)) : undefined);
const flag = (v: unknown): boolean => v === true;
/** Time inputs give HH:mm; the API takes HH:mm:ss. */
const time = (v: unknown): string | undefined => { const t = str(v); return t ? (t.length === 5 ? `${t}:00` : t) : undefined; };
/** A calendar day as a form's date input holds it. The generated client reads a date as local midnight. */
const dayValue = (d: Date | undefined): string => (d && !Number.isNaN(d.getTime())
  ? `${String(d.getFullYear()).padStart(4, '0')}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
  : '');

/** The Date the generated client serialises as that calendar day (it writes the local year, month and day). */
const localDay = (value: string): Date => {
  const [y, m, d] = value.split('-').map(Number);
  return new Date(y, (m || 1) - 1, d || 1);
};

const hhmm = (v: unknown): string => text(v).slice(0, 5);

/** The lines of a polygon textarea, each "lat, lon". */
function ringOf(value: unknown): Array<{ lat: number; lon: number }> {
  return str(value).split('\n').map((l) => l.trim()).filter(Boolean).map((l) => {
    const [lat, lon] = l.split(/[\s,;]+/).map(Number);
    return { lat, lon };
  });
}

const SWITCH_TARGET = {
  deactivate: PartnerTransitionRequestTargetEnum.Suspended,
  reactivate: PartnerTransitionRequestTargetEnum.Active,
  blacklist: PartnerTransitionRequestTargetEnum.Blacklisted,
} as const;

/** Reading, saving and switching off partners, vehicles, drivers and terminals, for the master-data forms. */
export function masterDataVerbs(d: GatewayDeps): Pick<LmsGateway, 'masterRecord' | 'saveMasterRecord' | 'switchMasterRecord'> {
  const { partners, vehicles, drivers, terminals } = d;

  return {
    masterRecord: (kind: MasterKind, id: string) => guard(async (): Promise<MasterRecord> => {
      if (kind === 'partners') {
        const p = await partners.getPartner({ id });
        return { id, version: p.version ?? 0, values: {
          status: text(p.status), code: text(p.code), legalName: text(p.legalName), partnerType: text(p.partnerType),
          taxId: text(p.taxId), paymentTerms: text(p.paymentTerms), billingAddress: text(p.billingAddress) } };
      }
      if (kind === 'vehicles') {
        const v = await vehicles.getVehicle({ id });
        return { id, version: v.version ?? 0, values: {
          status: text(v.status), registrationNo: text(v.registrationNo), category: text(v.category),
          vehicleType: text(v.vehicleType), axleConfig: text(v.axleConfig), grossWeightKg: text(v.grossWeightKg),
          tareWeightKg: text(v.tareWeightKg), maxVolumeM3: text(v.maxVolumeM3), ownerPartnerId: text(v.ownerPartnerId),
          hazmatCertified: v.hazmatCertified === true, reeferCapable: v.reeferCapable === true } };
      }
      if (kind === 'drivers') {
        const r = await drivers.getDriver({ id });
        return { id, version: r.version ?? 0, values: {
          status: text(r.status), fullName: text(r.fullName), phone: text(r.phone), licenceNo: text(r.licenceNo),
          licenceClass: text(r.licenceClass), licenceAuthority: text(r.licenceAuthority),
          licenceExpiresOn: dayValue(r.licenceExpiresOn), employerPartnerId: text(r.employerPartnerId),
          hazmatEndorsed: r.hazmatEndorsed === true } };
      }
      const t = await terminals.getTerminal({ id });
      return { id, version: t.version ?? 0, values: {
        status: text(t.status), code: text(t.code), name: text(t.name), functionalCategory: text(t.functionalCategory),
        geofenceType: text(t.geofenceType), centreLat: text(t.centreLat), centreLon: text(t.centreLon),
        radiusMetres: text(t.radiusM), dockCount: text(t.dockCount), opensAt: hhmm(t.opensAt),
        closesAt: hhmm(t.closesAt), avgDwellMinutes: text(t.avgDwellMinutes), ring: '' } };
    }),

    saveMasterRecord: (kind: MasterKind, orgUnitId: string, id: string | null, v: MasterValues, version = 0) =>
      guard(async () => {
        const ifMatch = String(version);
        if (kind === 'partners') {
          if (id) {
            await partners.updatePartner({ id, ifMatch, updatePartnerRequest: {
              legalName: str(v.legalName), taxId: opt(v.taxId), paymentTerms: opt(v.paymentTerms),
              billingAddress: opt(v.billingAddress) } });
            return id;
          }
          return text((await partners.registerPartner({ registerPartnerRequest: {
            orgUnitId, code: str(v.code), legalName: str(v.legalName),
            partnerType: str(v.partnerType) as RegisterPartnerRequestPartnerTypeEnum, taxId: opt(v.taxId) } })).id);
        }
        if (kind === 'vehicles') {
          const body = {
            ownerPartnerId: opt(v.ownerPartnerId), category: str(v.category), vehicleType: str(v.vehicleType),
            axleConfig: opt(v.axleConfig), grossWeightKg: num(v.grossWeightKg), tareWeightKg: num(v.tareWeightKg),
            maxVolumeM3: num(v.maxVolumeM3), hazmatCertified: flag(v.hazmatCertified), reeferCapable: flag(v.reeferCapable),
          };
          if (id) {
            await vehicles.updateVehicle({ id, ifMatch, updateVehicleRequest: body });
            return id;
          }
          return text((await vehicles.registerVehicle({ registerVehicleRequest: {
            orgUnitId, registrationNo: str(v.registrationNo), ...body } })).id);
        }
        if (kind === 'drivers') {
          const body = {
            employerPartnerId: opt(v.employerPartnerId), fullName: str(v.fullName), phone: str(v.phone),
            licenceClass: str(v.licenceClass), licenceAuthority: opt(v.licenceAuthority),
            licenceExpiresOn: localDay(str(v.licenceExpiresOn)), hazmatEndorsed: flag(v.hazmatEndorsed),
          };
          if (id) {
            await drivers.updateDriver({ id, ifMatch, updateDriverRequest: body });
            return id;
          }
          return text((await drivers.registerDriver({ registerDriverRequest: {
            orgUnitId, licenceNo: str(v.licenceNo), ...body } })).id);
        }
        const common = {
          dockCount: optNum(v.dockCount), opensAt: time(v.opensAt), closesAt: time(v.closesAt),
          avgDwellMinutes: optNum(v.avgDwellMinutes),
        };
        const polygon = str(v.geofenceType) === 'POLYGON';
        const centre = { lat: num(v.centreLat), lon: num(v.centreLon) };
        if (id) {
          const geofence = polygon ? (str(v.ring) ? { ring: ringOf(v.ring) } : {})
            : { centre, radiusMetres: num(v.radiusMetres) };
          await terminals.updateTerminal({ id, updateTerminalRequest: {
            name: str(v.name), version, ...geofence, ...common } }, { headers: { 'If-Match': ifMatch } });
          return id;
        }
        if (polygon) {
          return text((await terminals.registerPolygonTerminal({ registerPolygonTerminalRequest: {
            orgUnitId, code: str(v.code), name: str(v.name),
            category: str(v.functionalCategory) as RegisterPolygonTerminalRequestCategoryEnum,
            ring: ringOf(v.ring), ...common } })).id);
        }
        return text((await terminals.registerPointRadiusTerminal({ registerPointRadiusTerminalRequest: {
          orgUnitId, code: str(v.code), name: str(v.name),
          category: str(v.functionalCategory) as RegisterPointRadiusTerminalRequestCategoryEnum,
          centre, radiusMetres: num(v.radiusMetres), ...common } })).id);
      }),

    switchMasterRecord: (kind: MasterKind, id: string, action, reason: string, version: number) =>
      guard(async () => {
        const ifMatch = String(version);
        const reasonRequest = { reason };
        if (kind === 'partners') {
          await partners.transitionPartner({ id, partnerTransitionRequest: { target: SWITCH_TARGET[action], reason } },
            { headers: { 'If-Match': ifMatch } });
        } else if (kind === 'vehicles') {
          await (action === 'reactivate' ? vehicles.reactivateVehicle : vehicles.deactivateVehicle)
            .call(vehicles, { id, ifMatch, reasonRequest });
        } else if (kind === 'drivers') {
          await (action === 'reactivate' ? drivers.reactivateDriver : drivers.deactivateDriver)
            .call(drivers, { id, ifMatch, reasonRequest });
        } else if (action === 'reactivate') {
          await terminals.reactivateTerminal({ id, reasonRequest }, { headers: { 'If-Match': ifMatch } });
        } else {
          await terminals.deactivateTerminal({ id, reasonRequest }, { headers: { 'If-Match': ifMatch } });
        }
      }),
  };
}
