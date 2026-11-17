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

import { inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { API_URL } from '../config';
import {
  PartnerTransitionRequestTargetEnum, RegisterPartnerRequestPartnerTypeEnum,
  RegisterPointRadiusTerminalRequestCategoryEnum, RegisterPolygonTerminalRequestCategoryEnum,
} from '../api';
import type { UpdateTerminalRequest } from '../api';
import { GeneratedGatewayEway } from './generated-gateway-eway';
import { text } from './generated-gateway-helpers';
import type { MasterAction, MasterKind, MasterRecord } from './master-data-types';

const flag = (v: boolean | undefined) => (v ? 'true' : 'false');
const num = (s: string) => Number(s.trim());
const optional = (s: string | undefined) => (s && s.trim() ? s.trim() : undefined);
const optionalNumber = (s: string | undefined) => (s && s.trim() ? Number(s) : undefined);
const clock = (s: string | undefined) => (s ?? '').slice(0, 5);

/** Reads each ring line "lat, lon" into a point. */
function ringOf(source: string): Array<{ lat: number; lon: number }> {
  return source.split('\n').map((l) => l.trim()).filter(Boolean).map((l) => {
    const [lat, lon] = l.split(/[,\s]+/).map(Number);
    return { lat, lon };
  });
}

/** Creating, editing and switching off partners, vehicles, drivers and terminals. */
export abstract class GeneratedGatewayMaster extends GeneratedGatewayEway {
  private readonly masterHttp = inject(HttpClient);

  masterRecord(kind: MasterKind, id: string): Promise<MasterRecord> {
    switch (kind) {
      case 'partners':
        return this.call(this.partners.getPartner({ id }), (p) => ({
          id: text(p.id), version: p.version ?? 0, status: text(p.status), values: {
            code: text(p.code), legalName: text(p.legalName), partnerType: text(p.partnerType), taxId: text(p.taxId),
            paymentTerms: text(p.paymentTerms), billingAddress: text(p.billingAddress),
          },
        }));
      case 'vehicles':
        return this.call(this.vehicles.getVehicle({ id }), (v) => ({
          id: text(v.id), version: v.version ?? 0, status: text(v.status), values: {
            registrationNo: text(v.registrationNo), category: text(v.category), vehicleType: text(v.vehicleType),
            axleConfig: text(v.axleConfig), grossWeightKg: text(v.grossWeightKg), tareWeightKg: text(v.tareWeightKg),
            maxVolumeM3: text(v.maxVolumeM3), ownerPartnerId: text(v.ownerPartnerId),
            hazmatCertified: flag(v.hazmatCertified), reeferCapable: flag(v.reeferCapable),
          },
        }));
      case 'drivers':
        return this.call(this.drivers.getDriver({ id }), (d) => ({
          id: text(d.id), version: d.version ?? 0, status: text(d.status), values: {
            fullName: text(d.fullName), phone: text(d.phone), licenceNo: text(d.licenceNo),
            licenceClass: text(d.licenceClass), licenceAuthority: text(d.licenceAuthority),
            licenceExpiresOn: text(d.licenceExpiresOn), employerPartnerId: text(d.employerPartnerId),
            hazmatEndorsed: flag(d.hazmatEndorsed),
          },
        }));
      case 'terminals':
        return this.call(this.terminals.getTerminal({ id }), (t) => ({
          id: text(t.id), version: t.version ?? 0, status: text(t.status), values: {
            code: text(t.code), name: text(t.name), functionalCategory: text(t.functionalCategory),
            geofenceType: text(t.geofenceType), centreLat: text(t.centreLat), centreLon: text(t.centreLon),
            radiusMetres: text(t.radiusM), dockCount: text(t.dockCount), opensAt: clock(t.opensAt),
            closesAt: clock(t.closesAt), avgDwellMinutes: text(t.avgDwellMinutes),
          },
        }));
    }
  }

  /** Creates the record (no `id`), or edits it under If-Match with the version it was read at. */
  async saveMasterRecord(kind: MasterKind, orgUnitId: string, id: string | null, v: Record<string, string>,
    version = 0): Promise<string> {
    const ifMatch = `"${version}"`;
    switch (kind) {
      case 'partners':
        if (id) {
          await this.call(this.partners.updatePartner({
            id, ifMatch, updatePartnerRequest: {
              legalName: v['legalName'].trim(), taxId: optional(v['taxId']),
              paymentTerms: optional(v['paymentTerms']), billingAddress: optional(v['billingAddress']),
            },
          }), () => undefined);
          return id;
        }
        return this.call(this.partners.registerPartner({
          registerPartnerRequest: {
            orgUnitId, code: v['code'].trim(), legalName: v['legalName'].trim(),
            partnerType: v['partnerType'] as RegisterPartnerRequestPartnerTypeEnum, taxId: optional(v['taxId']),
          },
        }), (r) => text(r.id));
      case 'vehicles': {
        const shared = {
          category: v['category'].trim(), vehicleType: v['vehicleType'].trim(), axleConfig: optional(v['axleConfig']),
          grossWeightKg: num(v['grossWeightKg']), tareWeightKg: num(v['tareWeightKg']),
          maxVolumeM3: num(v['maxVolumeM3']), ownerPartnerId: optional(v['ownerPartnerId']),
          hazmatCertified: v['hazmatCertified'] === 'true', reeferCapable: v['reeferCapable'] === 'true',
        };
        if (id) {
          await this.call(this.vehicles.updateVehicle({ id, ifMatch, updateVehicleRequest: shared }), () => undefined);
          return id;
        }
        return this.call(this.vehicles.registerVehicle({
          registerVehicleRequest: { orgUnitId, registrationNo: v['registrationNo'].trim(), ...shared },
        }), (r) => text(r.id));
      }
      case 'drivers': {
        const shared = {
          employerPartnerId: optional(v['employerPartnerId']), fullName: v['fullName'].trim(), phone: v['phone'].trim(),
          licenceClass: v['licenceClass'].trim(), licenceAuthority: optional(v['licenceAuthority']),
          licenceExpiresOn: v['licenceExpiresOn'], hazmatEndorsed: v['hazmatEndorsed'] === 'true',
        };
        if (id) {
          await this.call(this.drivers.updateDriver({ id, ifMatch, updateDriverRequest: shared }), () => undefined);
          return id;
        }
        return this.call(this.drivers.registerDriver({
          registerDriverRequest: { orgUnitId, licenceNo: v['licenceNo'].trim(), ...shared },
        }), (r) => text(r.id));
      }
      case 'terminals': {
        const hours = {
          dockCount: optionalNumber(v['dockCount']), opensAt: optional(v['opensAt']), closesAt: optional(v['closesAt']),
          avgDwellMinutes: optionalNumber(v['avgDwellMinutes']),
        };
        if (id) {
          const body: UpdateTerminalRequest = { name: v['name'].trim(), version, ...hours };
          if (v['geofenceType'] === 'POLYGON') {
            if (optional(v['ring'])) {
              body.ring = ringOf(v['ring']);
            }
          } else {
            body.centre = { lat: num(v['centreLat']), lon: num(v['centreLon']) };
            body.radiusMetres = num(v['radiusMetres']);
          }
          await this.call(this.masterHttp.put(`${API_URL}/api/v1/terminals/${id}`, body,
            { headers: { 'If-Match': `"${version}"` } }), () => undefined);
          return id;
        }
        const named = { orgUnitId, code: v['code'].trim(), name: v['name'].trim(), ...hours };
        if (v['geofenceType'] === 'POLYGON') {
          return this.call(this.terminals.registerPolygonTerminal({
            registerPolygonTerminalRequest: {
              ...named, category: v['functionalCategory'] as RegisterPolygonTerminalRequestCategoryEnum,
              ring: ringOf(v['ring']),
            },
          }), (r) => text(r.id));
        }
        return this.call(this.terminals.registerPointRadiusTerminal({
          registerPointRadiusTerminalRequest: {
            ...named, category: v['functionalCategory'] as RegisterPointRadiusTerminalRequestCategoryEnum,
            centre: { lat: num(v['centreLat']), lon: num(v['centreLon']) }, radiusMetres: num(v['radiusMetres']),
          },
        }), (r) => text(r.id));
      }
    }
  }

  switchMasterRecord(kind: MasterKind, id: string, action: MasterAction, reason: string, version: number): Promise<void> {
    const ifMatch = `"${version}"`;
    const reasonRequest = { reason: reason.trim() };
    const done = () => undefined;
    switch (kind) {
      case 'partners': {
        const target = action === 'deactivate' ? PartnerTransitionRequestTargetEnum.Suspended
          : action === 'reactivate' ? PartnerTransitionRequestTargetEnum.Active : PartnerTransitionRequestTargetEnum.Blacklisted;
        // The generated call has no If-Match parameter for this transition; the header is optional on the server.
        return this.call(this.masterHttp.post(`${API_URL}/api/v1/partners/${id}/transitions`,
          { target, reason: reason.trim() }, { headers: { "If-Match": ifMatch } }), done);
      }
      case 'vehicles':
        return action === 'reactivate'
          ? this.call(this.vehicles.reactivateVehicle({ id, ifMatch, reasonRequest }), done)
          : this.call(this.vehicles.deactivateVehicle({ id, ifMatch, reasonRequest }), done);
      case 'drivers':
        return action === 'reactivate'
          ? this.call(this.drivers.reactivateDriver({ id, ifMatch, reasonRequest }), done)
          : this.call(this.drivers.deactivateDriver({ id, ifMatch, reasonRequest }), done);
      case 'terminals':
        return action === 'reactivate'
          ? this.call(this.terminals.reactivateTerminal({ id, reasonRequest }), done)
          : this.call(this.terminals.deactivateTerminal({ id, reasonRequest }), done);
    }
  }
}
