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
package com.lms.clientbilling.command;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.lms.approvals.api.ApprovalRequests;
import com.lms.approvals.api.ApprovalSubject;
import com.lms.approvals.api.ApprovalSubjectTypes;
import com.lms.approvals.api.ApprovalTicket;
import com.lms.clientbilling.command.domain.ClientContract;
import com.lms.clientbilling.command.domain.ContractLane;
import com.lms.clientbilling.command.domain.ContractServiceRate;
import com.lms.clientbilling.query.ClientDirectory;
import com.lms.identity.numbering.DocumentNumbers;
import com.lms.identity.numbering.DocumentType;
import com.lms.masterdata.api.PartnerStandingPort;
import com.lms.masterdata.api.TerminalCategoryPort;
import com.lms.settings.api.SettingContext;
import com.lms.settings.api.Settings;
import com.lms.shared.audit.AuditTrail;
import com.lms.shared.config.Json;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.tenant.TenantContext;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The life of a client contract version: draft, verification, end date,
 * extension and added charges.
 *
 * <p>A version is drafted, edited and deleted by its maker; once it passes the
 * tenant's completeness checklist, verification is asked for through the
 * approvals module ({@code CONTRACT_ACTIVATION}), freezing it. Approval puts
 * it in force by the old publish semantics -- the incumbent is closed the day
 * before, and from then on the database refuses any edit -- and a rejection
 * sends it back to DRAFT with the reason. Two more requests concern a version
 * in force: moving its end date later ({@code CONTRACT_EXTENSION}, the one
 * change permitted to it) and adding a value-added charge from a date
 * ({@code CONTRACT_CHARGE_ADD}, which publishes a successor and so never
 * reprices a raised bill). When a tenant's policy does not ask for approval the
 * request acts at once, as a flow without approvals always did.
 *
 * <p>Every transition is audited in its own transaction with before, after and
 * the reason. The {@code apply...} methods are what an approved request does:
 * unguarded and {@code MANDATORY}, because the decider's right was the policy's
 * permission, and each re-validates the state the request was raised in.
 */
@Service
public class ClientContractLifecycleService {

    /** The action a verification request is raised as. */
    public static final String VERIFY_ACTION = "VERIFY";
    /** The action an extension request is raised as. */
    public static final String EXTEND_ACTION = "EXTEND";
    /** The action a charge-add request is raised as. */
    public static final String ADD_CHARGE_ACTION = "ADD_CHARGE";

    private static final String AUDIT_TYPE = "CLIENT_CONTRACT";
    private static final String MANAGE = "hasAuthority('CLIENT_CONTRACT_MANAGE')"
            + " and @abac.permits('CLIENT_CONTRACT_MANAGE', 'CLIENT_CONTRACT', #id)";

    /**
     * What asking did.
     *
     * @param done     true when the change was made now
     * @param approval the request raised instead, when the tenant's policy asks for approval
     */
    public record Outcome(boolean done, ApprovalTicket approval) {
        static final Outcome DONE = new Outcome(true, null);
    }

    /** One rule of the checklist, and whether the draft passes it. */
    public record ChecklistItem(String code, boolean required, boolean passed, String message) {
    }

    private final ClientContractRepository contracts;
    private final ContractServiceRateRepository serviceRates;
    private final ClientDirectory directory;
    private final JdbcClient jdbc;
    private final AuditTrail audit;
    private final ApprovalRequests approvals;
    private final Settings settings;
    private final DocumentNumbers numbers;
    private final ContractLaneStore laneStore;
    private final TerminalCategoryPort terminals;
    private final PartnerStandingPort partners;

    public ClientContractLifecycleService(ClientContractRepository contracts,
                                          ContractServiceRateRepository serviceRates, ClientDirectory directory,
                                          JdbcClient jdbc, AuditTrail audit, ApprovalRequests approvals,
                                          Settings settings, DocumentNumbers numbers, ContractLaneStore laneStore,
                                          TerminalCategoryPort terminals, PartnerStandingPort partners) {
        this.contracts = contracts;
        this.serviceRates = serviceRates;
        this.directory = directory;
        this.jdbc = jdbc;
        this.audit = audit;
        this.approvals = approvals;
        this.settings = settings;
        this.numbers = numbers;
        this.laneStore = laneStore;
        this.terminals = terminals;
        this.partners = partners;
    }

    // ------------------------------------------------------------------ draft

    /**
     * Starts a draft version. The code is the one given, or -- when none is --
     * the next number of the tenant's CONTRACT series.
     */
    @Transactional
    @PreAuthorize("hasAuthority('CLIENT_CONTRACT_MANAGE')"
            + " and @abac.permitsIn('CLIENT_CONTRACT_MANAGE', #orgUnitId)")
    public UUID createDraft(UUID orgUnitId, String code, UUID clientPartnerId, String currency,
                            LocalDate effectiveFrom, LocalDate validTill, ClientContract.Rates rates,
                            List<ClientContractService.ServiceRateTerm> valueAdded, boolean billOnDispatch) {
        return createDraft(orgUnitId, code, clientPartnerId, currency, effectiveFrom, validTill, rates, valueAdded,
                billOnDispatch, ClientContract.Allowances.DEFAULT, List.of());
    }

    /**
     * As the other {@code createDraft}, with the version's allowances and its
     * lanes. A draft whose code an earlier version of the same client already
     * carries joins that contract's family; any other starts one.
     */
    @Transactional
    @PreAuthorize("hasAuthority('CLIENT_CONTRACT_MANAGE')"
            + " and @abac.permitsIn('CLIENT_CONTRACT_MANAGE', #orgUnitId)")
    public UUID createDraft(UUID orgUnitId, String code, UUID clientPartnerId, String currency,
                            LocalDate effectiveFrom, LocalDate validTill, ClientContract.Rates rates,
                            List<ClientContractService.ServiceRateTerm> valueAdded, boolean billOnDispatch,
                            ClientContract.Allowances allowances, List<LaneTerm> laneTerms) {
        UUID tenantId = TenantContext.requireTenantId();
        if (!directory.isCustomer(clientPartnerId)) {
            throw new BusinessRuleViolationException("client-not-a-customer",
                    "Partner " + clientPartnerId + " is not a customer of this tenant, so it cannot be a 3PL client");
        }
        List<ClientContractService.ServiceRateTerm> terms = valueAdded == null ? List.of() : valueAdded;
        ClientContractService.requireDistinctServices(terms);

        String number = code == null || code.isBlank()
                ? numbers.next(DocumentType.CONTRACT, orgUnitId).orElseThrow(() -> new IllegalArgumentException(
                "A contract needs a code: give one, or have the tenant configure a CONTRACT number series"))
                : code;
        ClientContract draft = contracts.save(ClientContract.draft(UUID.randomUUID(), tenantId, orgUnitId, number,
                clientPartnerId, currency, effectiveFrom, validTill, rates, billOnDispatch,
                familyOf(clientPartnerId, number), allowances == null ? ClientContract.Allowances.DEFAULT : allowances));
        List<ContractServiceRate> saved = saveRates(tenantId, draft.id(), terms);
        List<ContractLane> lanes = saveLanes(draft.id(), laneTerms);
        audit.recordAtomically("CLIENT_CONTRACT_DRAFTED", AUDIT_TYPE, draft.id(), null,
                snapshot(draft, saved, lanes));
        return draft.id();
    }

    /**
     * Replaces the content of a draft, with the value-added services. The
     * client does not change: that is a different contract. Refused with
     * {@code client-contract-not-draft} once the draft has been submitted.
     */
    @Transactional
    @PreAuthorize(MANAGE + " and @abac.permitsIn('CLIENT_CONTRACT_MANAGE', #orgUnitId)")
    public void reviseDraft(UUID id, long expectedVersion, UUID orgUnitId, String code, String currency,
                            LocalDate effectiveFrom, LocalDate validTill, ClientContract.Rates rates,
                            List<ClientContractService.ServiceRateTerm> valueAdded, boolean billOnDispatch) {
        reviseDraft(id, expectedVersion, orgUnitId, code, currency, effectiveFrom, validTill, rates, valueAdded,
                billOnDispatch, ClientContract.Allowances.DEFAULT, null);
    }

    /**
     * As the other {@code reviseDraft}, restating the allowances and replacing
     * the lanes whole. What is left out (null) is kept as it is: a flag or list
     * of load types the caller does not mention, and the lanes.
     */
    @Transactional
    @PreAuthorize(MANAGE + " and @abac.permitsIn('CLIENT_CONTRACT_MANAGE', #orgUnitId)")
    public void reviseDraft(UUID id, long expectedVersion, UUID orgUnitId, String code, String currency,
                            LocalDate effectiveFrom, LocalDate validTill, ClientContract.Rates rates,
                            List<ClientContractService.ServiceRateTerm> valueAdded, boolean billOnDispatch,
                            ClientContract.Allowances allowances, List<LaneTerm> laneTerms) {
        UUID tenantId = TenantContext.requireTenantId();
        ClientContract before = requireVersion(require(id), expectedVersion);
        before.requireDraft();
        List<ClientContractService.ServiceRateTerm> terms = valueAdded == null ? List.of() : valueAdded;
        ClientContractService.requireDistinctServices(terms);
        List<ContractServiceRate> beforeRates = serviceRates.findForContract(tenantId, id);

        List<ContractLane> beforeLanes = laneStore.lanesOf(id);

        ClientContract.Allowances stated = allowances == null ? ClientContract.Allowances.DEFAULT : allowances;
        ClientContract.Allowances merged = new ClientContract.Allowances(
                stated.directOrdersAllowed() == null ? before.directOrdersAllowed() : stated.directOrdersAllowed(),
                stated.allowedLoadTypes() == null ? (before.allowedLoadTypes() == null ? null
                        : List.of(before.allowedLoadTypes().split(","))) : stated.allowedLoadTypes());
        ClientContract revised = before.revised(orgUnitId, code == null || code.isBlank() ? before.code() : code,
                currency, effectiveFrom, validTill, rates, billOnDispatch, merged);
        ClientContract saved = contracts.save(revised);
        serviceRates.deleteAll(beforeRates);
        List<ContractServiceRate> afterRates = saveRates(tenantId, id, terms);
        List<ContractLane> afterLanes = beforeLanes;
        if (laneTerms != null) {
            laneStore.deleteAllOf(id);
            afterLanes = saveLanes(id, laneTerms);
        }
        audit.recordAtomically("CLIENT_CONTRACT_DRAFT_REVISED", AUDIT_TYPE, id,
                snapshot(before, beforeRates, beforeLanes), snapshot(saved, afterRates, afterLanes));
    }

    /** Deletes a draft with its service rates, and gives back its number when a series issued it. */
    @Transactional
    @PreAuthorize(MANAGE)
    public void deleteDraft(UUID id, long expectedVersion) {
        UUID tenantId = TenantContext.requireTenantId();
        ClientContract draft = requireVersion(require(id), expectedVersion);
        draft.requireDraft();
        List<ContractServiceRate> rates = serviceRates.findForContract(tenantId, id);
        List<ContractLane> lanes = laneStore.lanesOf(id);
        serviceRates.deleteAll(rates);
        laneStore.deleteAllOf(id);
        contracts.delete(draft);
        numbers.release(DocumentType.CONTRACT, draft.code(), "Contract draft deleted");
        audit.recordAtomically("CLIENT_CONTRACT_DRAFT_DELETED", AUDIT_TYPE, id, snapshot(draft, rates, lanes), null);
    }

    // ---------------------------------------------------------- verification

    /** Every rule of the checklist, with whether a draft passes it and why not. */
    @Transactional(readOnly = true)
    @PreAuthorize(MANAGE)
    public List<ChecklistItem> checklistOf(UUID id) {
        ClientContract contract = require(id);
        List<String> required = requiredItems(contract);
        Map<String, String> failures = ContractChecklist.failures(contract, facts(contract), ContractChecklist.ITEMS);
        List<ChecklistItem> items = new ArrayList<>();
        for (String item : ContractChecklist.ITEMS) {
            items.add(new ChecklistItem(item, required.contains(item), !failures.containsKey(item),
                    failures.get(item)));
        }
        return items;
    }

    /**
     * Asks for a draft to be verified: DRAFT to PENDING_VERIFICATION, frozen,
     * with a request in the approvals inbox. Refused with
     * {@code contract-checklist-incomplete}, listing every failing rule, until
     * the draft is complete. When the tenant's policy does not ask for
     * verification the draft goes into force at once.
     */
    @Transactional
    @PreAuthorize(MANAGE)
    public Outcome requestVerification(UUID id, long expectedVersion) {
        ClientContract draft = requireVersion(require(id), expectedVersion);
        draft.requireDraft();
        requireComplete(draft);
        if (!approvals.isRequired(ApprovalSubjectTypes.CONTRACT_ACTIVATION)) {
            activate(draft, "Verification is not required by the tenant's policy");
            return Outcome.DONE;
        }
        ClientContract pending = contracts.save(draft.submitted());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", draft.code());
        payload.put("clientPartnerId", draft.clientPartnerId().toString());
        payload.put("effectiveFrom", draft.effectiveFrom().toString());
        payload.put("validTill", draft.validTill() == null ? null : draft.validTill().toString());
        payload.put("transportRatePerKg", draft.transportRatePerKg().toPlainString());
        ApprovalTicket ticket = approvals.request(
                new ApprovalSubject(ApprovalSubjectTypes.CONTRACT_ACTIVATION, id, draft.orgUnitId()),
                VERIFY_ACTION, payload, "Verify client contract " + abbreviate(draft.code())
                        + " from " + draft.effectiveFrom());
        audit.recordAtomically("CLIENT_CONTRACT_VERIFICATION_REQUESTED", AUDIT_TYPE, id,
                Json.object("status", draft.status().name()),
                Json.object("status", pending.status().name(), "approvalId", ticket.id().toString()));
        return new Outcome(false, ticket);
    }

    /**
     * What an approved verification does, in the deciding transaction: the
     * contract must still be awaiting verification and still pass the
     * checklist, and the version in force must still be one it can succeed.
     * Otherwise the decision is refused and the request stays open.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void applyActivation(UUID id, String note) {
        ClientContract pending = require(id);
        if (pending.status() != ClientContract.Status.PENDING_VERIFICATION) {
            throw new BusinessRuleViolationException("client-contract-not-pending",
                    "Client contract " + pending.code() + " is " + pending.status()
                            + ", not awaiting verification");
        }
        requireComplete(pending);
        activate(pending, note);
    }

    /**
     * Verification came to nothing -- rejected, withdrawn or expired -- so the
     * contract returns to DRAFT carrying why. A contract that has moved on
     * (deleted or otherwise) is left as it is.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void applyVerificationClosed(UUID id, String status, String reason) {
        ClientContract pending = contracts.findById(id).orElse(null);
        if (pending == null || pending.status() != ClientContract.Status.PENDING_VERIFICATION) {
            return;
        }
        String why = reason == null || reason.isBlank() ? "Verification " + status.toLowerCase() : reason.strip();
        ClientContract draft = contracts.save(pending.returnedToDraft(why));
        audit.recordAtomically("CLIENT_CONTRACT_VERIFICATION_" + status, AUDIT_TYPE, id,
                Json.object("status", pending.status().name()),
                Json.object("status", draft.status().name(), "reason", why));
    }

    private void activate(ClientContract contract, String note) {
        UUID tenantId = TenantContext.requireTenantId();
        contracts.findOpenVersion(tenantId, contract.clientPartnerId())
                .filter(incumbent -> !incumbent.id().equals(contract.id()))
                .ifPresent(incumbent -> {
                    if (!contract.effectiveFrom().isAfter(incumbent.effectiveFrom())) {
                        throw new BusinessRuleViolationException("client-contract-backdated",
                                "Client contract " + incumbent.code() + " has been in force since "
                                        + incumbent.effectiveFrom() + "; a successor cannot start on "
                                        + contract.effectiveFrom());
                    }
                    contracts.save(incumbent.closeOn(contract.effectiveFrom().minusDays(1)));
                });
        ClientContract active = contracts.save(contract.activated());
        audit.recordAtomically("CLIENT_CONTRACT_ACTIVATED", AUDIT_TYPE, contract.id(),
                Json.object("status", contract.status().name()),
                Json.object("status", active.status().name(), "effectiveFrom", active.effectiveFrom().toString(),
                        "reason", note));
    }

    private void requireComplete(ClientContract contract) {
        Map<String, String> failures = ContractChecklist.failures(contract, facts(contract), requiredItems(contract));
        if (!failures.isEmpty()) {
            throw new BusinessRuleViolationException("contract-checklist-incomplete",
                    "Client contract " + contract.code() + " is not complete: "
                            + String.join(", ", failures.keySet()), failures);
        }
    }

    private List<String> requiredItems(ClientContract contract) {
        return ClientBillingSettingDefinitions.required(settings.effective(
                ClientBillingSettingDefinitions.REQUIRED_CHECKLIST,
                SettingContext.ofOrgUnit(contract.orgUnitId()).withCustomer(contract.clientPartnerId())));
    }

    private ContractChecklist.Facts facts(ClientContract contract) {
        UUID tenantId = TenantContext.requireTenantId();
        LocalDate incumbentFrom = contracts.findOpenVersion(tenantId, contract.clientPartnerId())
                .filter(incumbent -> !incumbent.id().equals(contract.id()))
                .map(ClientContract::effectiveFrom).orElse(null);
        return new ContractChecklist.Facts(directory.isTradingCustomer(contract.clientPartnerId()),
                LocalDate.now(ZoneOffset.UTC), incumbentFrom, laneStore.lanesOf(contract.id()).size());
    }

    // -------------------------------------------------------------- extension

    /**
     * Asks to move a version's end date later. The one change a version in force
     * accepts, and only through an approval (or at once, when the tenant's
     * policy asks for none).
     */
    @Transactional
    @PreAuthorize(MANAGE)
    public Outcome requestExtension(UUID id, LocalDate newValidTill, String reason) {
        ClientContract contract = require(id);
        contract.requireExtendableTo(newValidTill);
        String why = requireReason(reason);
        if (!approvals.isRequired(ApprovalSubjectTypes.CONTRACT_EXTENSION)) {
            applyExtension(id, newValidTill, why);
            return Outcome.DONE;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", contract.code());
        payload.put("validTill", contract.validTill().toString());
        payload.put("newValidTill", newValidTill.toString());
        payload.put("reason", why);
        ApprovalTicket ticket = approvals.request(
                new ApprovalSubject(ApprovalSubjectTypes.CONTRACT_EXTENSION, id, contract.orgUnitId()),
                EXTEND_ACTION, payload, "Extend client contract " + abbreviate(contract.code()) + " from "
                        + contract.validTill() + " to " + newValidTill);
        audit.recordAtomically("CLIENT_CONTRACT_EXTENSION_REQUESTED", AUDIT_TYPE, id,
                Json.object("validTill", contract.validTill().toString()),
                Json.object("validTill", contract.validTill().toString(),
                        "requestedValidTill", newValidTill.toString(), "approvalId", ticket.id().toString(),
                        "reason", why));
        return new Outcome(false, ticket);
    }

    /**
     * Moves the end date later, re-validated against the contract as it is now.
     *
     * <p>The database permits the change only inside a transaction that says
     * so (the extension setting, switched on for this one statement), and only
     * to a later date; nothing else about a version in force can move.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void applyExtension(UUID id, LocalDate newValidTill, String reason) {
        ClientContract before = require(id);
        before.requireExtendableTo(newValidTill);
        long expected = before.version() == null ? 0 : before.version();
        jdbc.sql("SELECT set_config('app.contract_extension', 'on', true)").query(String.class).single();
        int changed = jdbc.sql("""
                        UPDATE client_contract SET valid_till = :validTill, version = coalesce(version, 0) + 1
                         WHERE id = :id AND coalesce(version, 0) = :version
                        """)
                .param("validTill", newValidTill).param("id", id).param("version", expected).update();
        jdbc.sql("SELECT set_config('app.contract_extension', 'off', true)").query(String.class).single();
        if (changed == 0) {
            throw new OptimisticLockingFailureException("Client contract " + before.code() + " has changed");
        }
        audit.recordAtomically("CLIENT_CONTRACT_EXTENDED", AUDIT_TYPE, id,
                Json.object("validTill", before.validTill().toString()),
                Json.object("validTill", newValidTill.toString(), "reason", reason));
    }

    // ------------------------------------------------------------- charge add

    /**
     * Asks to add a value-added charge to a version in force, from a date. When
     * approved it publishes a successor from that date carrying everything
     * the version carried plus the charge; what was raised before is never
     * repriced. One open request per contract: a second is refused.
     */
    @Transactional
    @PreAuthorize(MANAGE)
    public Outcome requestChargeAddition(UUID id, String serviceCode, String unit, BigDecimal rate,
                                         LocalDate effectiveFrom, String reason) {
        ClientContract contract = require(id);
        ContractServiceRate term = ContractServiceRate.of(UUID.randomUUID(), contract.tenantId(), id,
                serviceCode, unit, rate);
        requireChargeAddable(contract, term, effectiveFrom);
        String why = requireReason(reason);
        if (!approvals.isRequired(ApprovalSubjectTypes.CONTRACT_CHARGE_ADD)) {
            applyChargeAddition(id, term.serviceCode(), term.unit(), term.rate(), effectiveFrom, why);
            return Outcome.DONE;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", contract.code());
        payload.put("serviceCode", term.serviceCode());
        payload.put("unit", term.unit());
        payload.put("rate", term.rate().toPlainString());
        payload.put("effectiveFrom", effectiveFrom.toString());
        payload.put("reason", why);
        ApprovalTicket ticket = approvals.request(
                new ApprovalSubject(ApprovalSubjectTypes.CONTRACT_CHARGE_ADD, id, contract.orgUnitId()),
                ADD_CHARGE_ACTION, payload, "Add charge " + term.serviceCode() + " to client contract "
                        + abbreviate(contract.code()) + " from " + effectiveFrom);
        audit.recordAtomically("CLIENT_CONTRACT_CHARGE_ADD_REQUESTED", AUDIT_TYPE, id, null,
                Json.object("serviceCode", term.serviceCode(), "rate", term.rate().toPlainString(),
                        "effectiveFrom", effectiveFrom.toString(), "approvalId", ticket.id().toString(),
                        "reason", why));
        return new Outcome(false, ticket);
    }

    /**
     * Publishes the successor carrying the added charge, closing the version it
     * follows the day before, re-validated against the contract as it is now
     * (a version already closed, a date gone by or a charge since priced
     * refuses the decision).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID applyChargeAddition(UUID id, String serviceCode, String unit, BigDecimal rate,
                                    LocalDate effectiveFrom, String reason) {
        UUID tenantId = TenantContext.requireTenantId();
        ClientContract incumbent = require(id);
        ContractServiceRate term = ContractServiceRate.of(UUID.randomUUID(), tenantId, id, serviceCode, unit, rate);
        requireChargeAddable(incumbent, term, effectiveFrom);

        List<ContractServiceRate> carried = serviceRates.findForContract(tenantId, id);
        ClientContract closed = contracts.save(incumbent.closeOn(effectiveFrom.minusDays(1)));
        ClientContract successor = contracts.save(incumbent.successor(UUID.randomUUID(), effectiveFrom));
        for (ContractServiceRate existing : carried) {
            serviceRates.save(ContractServiceRate.of(UUID.randomUUID(), tenantId, successor.id(),
                    existing.serviceCode(), existing.unit(), existing.rate()));
        }
        serviceRates.save(ContractServiceRate.of(UUID.randomUUID(), tenantId, successor.id(), term.serviceCode(),
                term.unit(), term.rate()));
        // The lanes are part of the version: the successor carries them, so an
        // order resolved against it finds the same corridors and prices.
        laneStore.copyAll(id, successor.id());
        audit.recordAtomically("CLIENT_CONTRACT_CHARGE_ADDED", AUDIT_TYPE, id,
                Json.object("effectiveTo", null),
                Json.object("effectiveTo", closed.effectiveTo().toString(), "successor", successor.id().toString(),
                        "serviceCode", term.serviceCode(), "rate", term.rate().toPlainString(),
                        "effectiveFrom", effectiveFrom.toString(), "reason", reason));
        return successor.id();
    }

    private void requireChargeAddable(ClientContract contract, ContractServiceRate term, LocalDate effectiveFrom) {
        if (contract.status() != ClientContract.Status.ACTIVE || contract.effectiveTo() != null) {
            throw new BusinessRuleViolationException("client-contract-not-open",
                    "Client contract " + contract.code() + " is not the version in force; add the charge to the"
                            + " version that is");
        }
        if (effectiveFrom == null) {
            throw new IllegalArgumentException("Say the day the charge applies from");
        }
        if (!effectiveFrom.isAfter(contract.effectiveFrom())) {
            throw new BusinessRuleViolationException("client-contract-backdated",
                    "Client contract " + contract.code() + " has been in force since " + contract.effectiveFrom()
                            + "; a successor cannot start on " + effectiveFrom);
        }
        if (effectiveFrom.isBefore(LocalDate.now(ZoneOffset.UTC))) {
            throw new BusinessRuleViolationException("client-contract-charge-add-backdated",
                    "A charge cannot apply from " + effectiveFrom + ", which has passed: it would reprice work"
                            + " already done");
        }
        ClientContract.requireValidity(effectiveFrom, contract.validTill());
        boolean priced = serviceRates.findForContract(contract.tenantId(), contract.id()).stream()
                .anyMatch(existing -> existing.serviceCode().equals(term.serviceCode()));
        if (priced) {
            throw new BusinessRuleViolationException("client-service-rate-duplicated",
                    "Value-added service " + term.serviceCode() + " is already priced in contract " + contract.code());
        }
    }

    // ---------------------------------------------------------------- helpers

    private ClientContract require(UUID id) {
        return contracts.findById(id).orElseThrow(() -> new ResourceNotFoundException("ClientContract", id));
    }

    /** Refuses a version other than the stored one before any work is done. */
    private static ClientContract requireVersion(ClientContract contract, long expectedVersion) {
        if (contract.version() == null || contract.version() != expectedVersion) {
            throw new OptimisticLockingFailureException(
                    "Client contract " + contract.code() + " has changed since version " + expectedVersion);
        }
        return contract;
    }

    private List<ContractServiceRate> saveRates(UUID tenantId, UUID contractId,
                                                List<ClientContractService.ServiceRateTerm> terms) {
        List<ContractServiceRate> saved = new ArrayList<>();
        for (ClientContractService.ServiceRateTerm term : terms) {
            saved.add(serviceRates.save(ContractServiceRate.of(UUID.randomUUID(), tenantId, contractId,
                    term.serviceCode(), term.unit(), term.rate())));
        }
        return saved;
    }

    /**
     * The family a new draft joins: that of the client's latest version carrying
     * the same code (the contract continued or replaced), else a new one.
     */
    private UUID familyOf(UUID clientPartnerId, String code) {
        return jdbc.sql("""
                        SELECT coalesce(family_id, id) FROM client_contract
                         WHERE tenant_id = :tenantId AND client_partner_id = :client AND code = :code
                         ORDER BY effective_from DESC, created_at DESC LIMIT 1
                        """)
                .param("tenantId", TenantContext.requireTenantId()).param("client", clientPartnerId)
                .param("code", code).query(UUID.class).optional().orElse(null);
    }

    /**
     * Validates and stores a draft's lanes: codes distinct, every terminal and
     * partner one this tenant can see (looked up under row-level security: a
     * foreign key would accept another tenant's), then the lanes themselves.
     */
    private List<ContractLane> saveLanes(UUID contractId, List<LaneTerm> terms) {
        if (terms == null || terms.isEmpty()) {
            return List.of();
        }
        List<ContractLane> lanes = terms.stream().map(term -> term.toLane(contractId)).toList();
        if (lanes.stream().map(ContractLane::laneCode).map(String::toUpperCase).distinct().count() != lanes.size()) {
            throw new BusinessRuleViolationException("client-contract-lane-duplicated",
                    "Lane codes must be distinct within a contract");
        }
        for (ContractLane lane : lanes) {
            requireTerminal(lane.origin().terminalId());
            requireTerminal(lane.destination().terminalId());
            lane.consignors().forEach(this::requirePartner);
            lane.consignees().forEach(this::requirePartner);
        }
        laneStore.insertAll(contractId, lanes);
        return lanes;
    }

    private void requireTerminal(UUID terminalId) {
        if (terminalId != null && terminals.functionalCategoryOf(terminalId).isEmpty()) {
            throw new ResourceNotFoundException("Terminal", terminalId);
        }
    }

    private void requirePartner(UUID partnerId) {
        if (!partners.exists(partnerId)) {
            throw new ResourceNotFoundException("BusinessPartner", partnerId);
        }
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A reason is required");
        }
        return reason.strip();
    }

    private static String abbreviate(String text) {
        return text.length() <= 80 ? text : text.substring(0, 79) + "...";
    }

    private static Json snapshot(ClientContract c, List<ContractServiceRate> rates, List<ContractLane> lanes) {
        String laneDigest = lanes.stream().sorted(java.util.Comparator.comparing(ContractLane::laneCode))
                .map(l -> l.laneCode() + ":" + place(l.origin()) + ">" + place(l.destination())
                        + (l.freightMode() == null ? "" : ":" + l.freightMode())
                        + (l.loadType() == null ? "" : ":" + l.loadType()) + ":" + l.rateBasis()
                        + (l.rate() == null ? "" : "=" + l.rate().toPlainString())
                        + (l.slabs().isEmpty() ? "" : ":" + l.slabs().size() + " slabs")
                        + (l.transitDays() == null ? "" : ":" + l.transitDays() + "d"))
                .collect(java.util.stream.Collectors.joining("; "));
        StringBuilder services = new StringBuilder();
        for (ContractServiceRate r : rates.stream().sorted(java.util.Comparator.comparing(
                ContractServiceRate::serviceCode)).toList()) {
            if (services.length() > 0) {
                services.append("; ");
            }
            services.append(r.serviceCode()).append('=').append(r.rate().toPlainString()).append('/').append(r.unit());
        }
        return Json.object("code", c.code(), "status", c.status().name(), "client", c.clientPartnerId().toString(),
                "orgUnitId", c.orgUnitId() == null ? null : c.orgUnitId().toString(), "currency", c.currency(),
                "effectiveFrom", c.effectiveFrom().toString(),
                "validTill", c.validTill() == null ? null : c.validTill().toString(),
                "transportRatePerKg", c.transportRatePerKg().toPlainString(),
                "transportMinimumCharge", c.transportMinimumCharge().toPlainString(),
                "storageRatePerPalletDay", c.storageRatePerPalletDay().toPlainString(),
                "handlingInRatePerPallet", c.handlingInRatePerPallet().toPlainString(),
                "handlingOutRatePerPallet", c.handlingOutRatePerPallet().toPlainString(),
                "transportTaxRatePct", c.transportTaxRatePct().toPlainString(),
                "warehouseTaxRatePct", c.warehouseTaxRatePct().toPlainString(),
                "storageUnitsPerPallet", String.valueOf(c.storageUnitsPerPallet()),
                "billOnDispatch", String.valueOf(c.billOnDispatch()), "valueAdded", services.toString(),
                "directOrdersAllowed", String.valueOf(c.directOrdersAllowed()),
                "allowedLoadTypes", c.allowedLoadTypes(), "lanes", laneDigest);
    }

    private static String place(ContractLane.Place place) {
        return place.terminalId() != null ? place.terminalId().toString() : "zone " + place.zone();
    }
}
