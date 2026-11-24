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
import { useState } from 'react';
import { ApiProblem, type ConfigAction, type ConfigKind } from '../gateway/lmsGateway';
import { useSession } from '../session';
import { CONFLICT_TEXT } from './MasterDataForm';
import { ErrorBanner } from './ErrorBanner';
import { ConfirmDialog } from './ConfirmDialog';
import { confirmWording } from '../../../shared/confirm';

const MANAGE: Record<ConfigKind, string> = {
  allocationRules: 'ALLOCATION_RULE_MANAGE', orgUnits: 'ORG_UNIT_MANAGE', chargeCatalogue: 'CHARGE_CATALOGUE_MANAGE',
  webhooks: 'WEBHOOK_MANAGE', customFields: 'CUSTOM_FIELD_MANAGE', bins: 'WAREHOUSE_SETUP', skus: 'WAREHOUSE_SETUP',
  guideVendors: 'ROUTING_GUIDE_MANAGE', vendorInvoices: 'PAYABLES_MANAGE',
};

const WORDS: Record<ConfigAction, { verb: string; line: string }> = {
  deactivate: { verb: 'Deactivate', line: 'It is kept, but can no longer be used until it is reactivated.' },
  reactivate: { verb: 'Reactivate', line: 'It will be available again.' },
  void: { verb: 'Void', line: 'The invoice stays on record as void; its bills and advances are released.' },
};

interface Props {
  kind: ConfigKind;
  id: string;
  /** The warehouse of a bin, the guide of a vendor. */
  parentId?: string;
  /** Whether the record is on now; for a vendor invoice, whether it can still be voided. */
  active: boolean;
  /** What the record is called in the question. */
  label: string;
  version: number | string;
  /** Called after a switch went through, and when a stale one is dismissed, so the list is read again. */
  onChanged: () => void;
}

/**
 * The Deactivate and Reactivate buttons of a row (Void, for a vendor invoice) and the confirmation each asks for: the
 * same shape as the master-data panel's. A reason is always required; a refusal from the server is shown in place and
 * the confirmation stays open. The version is the one the list read, so a row somebody else changed says so.
 */
export function ConfigActions({ kind, id, parentId, active, label, version, onChanged }: Props) {
  const { can, gateway } = useSession();
  const [pending, setPending] = useState<ConfigAction | null>(null);
  const [refusal, setRefusal] = useState('');
  const [busy, setBusy] = useState(false);
  // A stale refusal is shown in place; the list is read again when the confirmation is left.
  const [stale, setStale] = useState(false);

  function ask(action: ConfigAction) {
    setPending(action);
    setRefusal('');
    setStale(false);
  }

  function cancel() {
    setPending(null);
    if (stale) { onChanged(); }
  }

  async function confirm(reason: string) {
    if (!pending) { return; }
    setRefusal('');
    setBusy(true);
    try {
      await gateway.switchConfigRecord(kind, id, pending, reason, Number(version), parentId);
      setPending(null);
      onChanged();
    } catch (e) {
      const conflict = e instanceof ApiProblem && e.code === 'concurrent-modification';
      setRefusal(conflict ? CONFLICT_TEXT : (e as Error).message);
      setStale(conflict);
    } finally {
      setBusy(false);
    }
  }

  if (!can(MANAGE[kind])) { return null; }
  return (
    <>
      {kind === 'vendorInvoices' ? (
        active ? <span className="actions"><button type="button" data-testid="void-record" onClick={() => ask('void')}>Void</button></span> : null
      ) : (
        <span className="actions">
          {active
            ? <button type="button" className="link" data-testid="deactivate-record" onClick={() => ask('deactivate')}>Deactivate</button>
            : <button type="button" className="link" data-testid="reactivate-record" onClick={() => ask('reactivate')}>Reactivate</button>}
        </span>
      )}
      {pending && (
        <ConfirmDialog wording={confirmWording(WORDS[pending].verb, label, `${WORDS[pending].line} The reason is kept in the audit trail.`)}
          reason={{ label: 'Reason', testId: 'reason-input', required: true, multiline: true }}
          tone={pending === 'reactivate' ? 'neutral' : 'danger'} busy={busy} onConfirm={(why) => void confirm(why)} onDismiss={cancel}>
          {refusal && <ErrorBanner message={refusal} testId="action-error" />}
        </ConfirmDialog>
      )}
    </>
  );
}
