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

/** The registers, besides the four master-data ones, that are switched off and on from their own screens. */
export type ConfigKind = 'allocationRules' | 'orgUnits' | 'chargeCatalogue' | 'webhooks' | 'customFields' | 'bins'
  | 'skus' | 'guideVendors' | 'vendorInvoices';

/** Void is the vendor invoice's own way of switching off: only before anything is paid. */
export type ConfigAction = 'deactivate' | 'reactivate' | 'void';
