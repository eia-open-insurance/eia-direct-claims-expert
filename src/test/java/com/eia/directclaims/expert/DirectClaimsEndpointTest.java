package com.eia.directclaims.expert;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static com.eia.directclaims.expert.Fixtures.CLAIM_ID;
import static com.eia.directclaims.expert.Fixtures.EXPOSURE_ID;
import static com.eia.directclaims.expert.Fixtures.INJURY_ID;
import static com.eia.directclaims.expert.Fixtures.LIEN_ID;
import static com.eia.directclaims.expert.Fixtures.LITIGATION_ID;
import static com.eia.directclaims.expert.Fixtures.NOW;
import static com.eia.directclaims.expert.Fixtures.PAYMENT_ID;
import static com.eia.directclaims.expert.Fixtures.RECOVERY_ID;
import static com.eia.directclaims.expert.Fixtures.SETTLEMENT_ID;
import static com.eia.directclaims.expert.Fixtures.TENANT;
import static com.eia.directclaims.expert.Fixtures.coverage;
import static com.eia.directclaims.expert.Fixtures.exposure;
import static com.eia.directclaims.expert.Fixtures.injury;
import static com.eia.directclaims.expert.Fixtures.lien;
import static com.eia.directclaims.expert.Fixtures.litigation;
import static com.eia.directclaims.expert.Fixtures.recovery;
import static com.eia.directclaims.expert.Fixtures.reserve;
import static com.eia.directclaims.expert.Fixtures.settlement;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {
        InjuryController.class,
        ExposureController.class,
        ReserveController.class,
        CoverageController.class,
        LitigationController.class,
        SettlementController.class,
        RecoveryController.class,
        LienController.class
})
class DirectClaimsEndpointTest {
    private static final String BASE = "/internal/v1/claims/" + CLAIM_ID;
    private static final String PUBLIC_BASE = "/api/v1/claims/" + CLAIM_ID;
    private static final String TENANT_PARAM = TENANT.toString();

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private InjuryService injuryService;
    @MockitoBean
    private ExposureService exposureService;
    @MockitoBean
    private ReserveService reserveService;
    @MockitoBean
    private CoverageService coverageService;
    @MockitoBean
    private LitigationService litigationService;
    @MockitoBean
    private SettlementService settlementService;
    @MockitoBean
    private RecoveryService recoveryService;
    @MockitoBean
    private LienService lienService;

    // ------------------------------------------------------------------ injuries
    @Test
    void listsInjuries() throws Exception {
        when(injuryService.list(TENANT, CLAIM_ID)).thenReturn(List.of(injury()));

        mvc.perform(get(BASE + "/injuries").param("tenantId", TENANT_PARAM).header("X-EIA-Book", "DIRECT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].injuryType").value("MESOTHELIOMA"))
                .andExpect(jsonPath("$[0].diagnosisCodes[0]").value("C45.0"));
    }

    @Test
    void recordsAnInjury() throws Exception {
        when(injuryService.add(eq(TENANT), eq(CLAIM_ID), any(InjuryRequest.class))).thenReturn(injury());

        mvc.perform(post(BASE + "/injuries")
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", "DIRECT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "claimantPartyId": "33333333-3333-7333-8333-333333333333",
                                  "injuryType": "MESOTHELIOMA",
                                  "bodyPart": "LUNG",
                                  "diagnosisCodes": ["C45.0"],
                                  "severity": "SEVERE",
                                  "diagnosisDate": "2026-01-10"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void updatesAnInjury() throws Exception {
        when(injuryService.update(eq(TENANT), eq(CLAIM_ID), eq(INJURY_ID), any(InjuryRequest.class)))
                .thenReturn(injury());

        mvc.perform(patch(BASE + "/injuries/" + INJURY_ID)
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", "DIRECT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"severity\": \"FATAL\", \"expectedVersion\": 1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.injuryType").value("MESOTHELIOMA"));
    }

    @Test
    void injuryRoutesStayInsideTheBookWall() throws Exception {
        mvc.perform(get(BASE + "/injuries").param("tenantId", TENANT_PARAM).header("X-EIA-Book", "ASSUMED"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        verifyNoInteractions(injuryService);
    }

    // ------------------------------------------------------------------ exposures
    @Test
    void listsExposures() throws Exception {
        when(exposureService.list(TENANT, CLAIM_ID)).thenReturn(List.of(exposure()));

        mvc.perform(get(BASE + "/exposures").param("tenantId", TENANT_PARAM).header("X-EIA-Book", "DIRECT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].exposureType").value("SUBSTANCE"))
                .andExpect(jsonPath("$[0].jurisdiction").value("US-OH"));
    }

    @Test
    void recordsAnExposure() throws Exception {
        when(exposureService.add(eq(TENANT), eq(CLAIM_ID), any(ExposureRequest.class))).thenReturn(exposure());

        mvc.perform(post(BASE + "/exposures")
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", "DIRECT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "claimantPartyId": "33333333-3333-7333-8333-333333333333",
                                  "exposureType": "SUBSTANCE",
                                  "substanceName": "Chrysotile asbestos",
                                  "jurisdiction": "US-OH",
                                  "exposureFrom": "2015-01-01",
                                  "exposureTo": "2020-12-31",
                                  "intensity": "HIGH"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.exposureFrom").value("2015-01-01"));
    }

    @Test
    void updatesAnExposure() throws Exception {
        when(exposureService.update(eq(TENANT), eq(CLAIM_ID), eq(EXPOSURE_ID), any(ExposureRequest.class)))
                .thenReturn(exposure());

        mvc.perform(patch(BASE + "/exposures/" + EXPOSURE_ID)
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", "DIRECT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"intensity\": \"LOW\", \"expectedVersion\": 1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exposureType").value("SUBSTANCE"));
    }

    // ------------------------------------------------------------------ reserves
    @Test
    void returnsDerivedReserveHistory() throws Exception {
        ReserveSummary summary = new ReserveSummary(CLAIM_ID, "USD",
                new java.math.BigDecimal("100000.0000"),
                List.of(new ReserveSummary.ReserveComponentBalance("INDEMNITY",
                        new java.math.BigDecimal("100000.0000"), "USD")),
                List.of(reserve()));
        when(reserveService.summary(TENANT, CLAIM_ID)).thenReturn(summary);

        mvc.perform(get(BASE + "/reserves").param("tenantId", TENANT_PARAM).header("X-EIA-Book", "DIRECT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCurrentAmount").isString())
                .andExpect(jsonPath("$.totalCurrentAmount").value("100000.0000"))
                .andExpect(jsonPath("$.history[0].component").value("INDEMNITY"));
    }

    @Test
    void postsAReserveChange() throws Exception {
        when(reserveService.change(eq(TENANT), eq(CLAIM_ID), any(ReserveChangeRequest.class)))
                .thenReturn(reserve());

        mvc.perform(post(BASE + "/reserves")
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", "DIRECT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "component": "INDEMNITY",
                                  "amount": "100000.00",
                                  "currency": "USD",
                                  "effectiveDate": "2026-03-18",
                                  "reasonCode": "INITIAL",
                                  "reasonText": "Initial case reserve",
                                  "authority": "Manager",
                                  "expectedVersion": 0
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").isString())
                .andExpect(jsonPath("$.amount").value("100000.0000"))
                .andExpect(jsonPath("$.current").value(true));
    }

    @Test
    void reserveRoutesStayInsideTheBookWall() throws Exception {
        mvc.perform(post(BASE + "/reserves")
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", "ASSUMED")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(reserveService);
    }

    // ------------------------------------------------------------------ coverage
    @Test
    void returnsTheCoverageDecision() throws Exception {
        when(coverageService.get(TENANT, CLAIM_ID)).thenReturn(coverage("COVERED"));

        mvc.perform(get(BASE + "/coverage").param("tenantId", TENANT_PARAM).header("X-EIA-Book", "DIRECT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.position").value("COVERED"))
                .andExpect(jsonPath("$.allocationPct").isString());
    }

    @Test
    void missingCoverageDecisionIs404() throws Exception {
        when(coverageService.get(TENANT, CLAIM_ID))
                .thenThrow(new CoverageDecisionNotFoundException(CLAIM_ID));

        mvc.perform(get(BASE + "/coverage").param("tenantId", TENANT_PARAM).header("X-EIA-Book", "DIRECT"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void recordsACoverageDecision() throws Exception {
        when(coverageService.decide(eq(TENANT), eq(CLAIM_ID), any(CoverageDecisionRequest.class)))
                .thenReturn(coverage("DEFENSE_ONLY"));

        mvc.perform(post(BASE + "/coverage")
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", "DIRECT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "position": "DEFENSE_ONLY",
                                  "basis": "No indemnity trigger in policy year 2024",
                                  "policyProvisions": "Coverage A",
                                  "allocationMethod": "PRO_RATA_TIME",
                                  "allocationPct": "0.25",
                                  "decisionDate": "2026-03-18",
                                  "reviewerPartyId": "44444444-4444-7444-8444-444444444444"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.position").value("DEFENSE_ONLY"));
    }

    // ------------------------------------------------------------------ litigation
    @Test
    void listsLitigation() throws Exception {
        when(litigationService.list(TENANT, CLAIM_ID)).thenReturn(List.of(litigation()));

        mvc.perform(get(BASE + "/litigation").param("tenantId", TENANT_PARAM).header("X-EIA-Book", "DIRECT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].matterType").value("DEFENSE"))
                .andExpect(jsonPath("$[0].trialDate").value("2026-09-01"));
    }

    @Test
    void recordsALitigationMatter() throws Exception {
        when(litigationService.add(eq(TENANT), eq(CLAIM_ID), any(LitigationRequest.class)))
                .thenReturn(litigation());

        mvc.perform(post(BASE + "/litigation")
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", "DIRECT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "matterType": "DEFENSE",
                                  "jurisdiction": "US-OH",
                                  "counselPartyId": "44444444-4444-7444-8444-444444444444",
                                  "filingDate": "2026-02-01",
                                  "trialDate": "2026-09-01",
                                  "status": "ACTIVE",
                                  "caseNumber": "CV-2026-001"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.jurisdiction").value("US-OH"));
    }

    @Test
    void updatesALitigationMatter() throws Exception {
        when(litigationService.update(eq(TENANT), eq(CLAIM_ID), eq(LITIGATION_ID), any(LitigationRequest.class)))
                .thenReturn(litigation());

        mvc.perform(patch(BASE + "/litigation/" + LITIGATION_ID)
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", "DIRECT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"STAYED\", \"expectedVersion\": 1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    // ------------------------------------------------------------------ settlements
    @Test
    void listsSettlements() throws Exception {
        when(settlementService.list(TENANT, CLAIM_ID)).thenReturn(List.of(settlement("PROPOSED")));

        mvc.perform(get(BASE + "/settlements").param("tenantId", TENANT_PARAM).header("X-EIA-Book", "DIRECT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].amount").isString())
                .andExpect(jsonPath("$[0].status").value("PROPOSED"));
    }

    @Test
    void proposesASettlement() throws Exception {
        when(settlementService.propose(eq(TENANT), eq(CLAIM_ID), any(SettlementRequest.class)))
                .thenReturn(settlement("PROPOSED"));

        mvc.perform(post(BASE + "/settlements")
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", "DIRECT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": "60000.00",
                                  "currency": "USD",
                                  "structure": "LUMP_SUM",
                                  "releaseScope": "FULL",
                                  "paymentInstructions": "Wire to claimant trust account"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value("60000.0000"));
    }

    @Test
    void authorizesASettlement() throws Exception {
        when(settlementService.approve(eq(TENANT), eq(CLAIM_ID), eq(SETTLEMENT_ID),
                any(SettlementApprovalRequest.class))).thenReturn(settlement("AUTHORIZED"));

        mvc.perform(post(BASE + "/settlements/" + SETTLEMENT_ID + "/approve")
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", "DIRECT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "approvalAuthority": "Claims Committee",
                                  "authorizedBy": "44444444-4444-7444-8444-444444444444",
                                  "expectedVersion": 1
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AUTHORIZED"));
    }

    @Test
    void executesASettlement() throws Exception {
        when(settlementService.execute(eq(TENANT), eq(CLAIM_ID), eq(SETTLEMENT_ID),
                any(SettlementExecutionRequest.class))).thenReturn(settlement("EXECUTED"));

        mvc.perform(post(BASE + "/settlements/" + SETTLEMENT_ID + "/execute")
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", "DIRECT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "releaseDocumentId": "44444444-4444-7444-8444-444444444444",
                                  "paymentInstructions": "Wire funds",
                                  "expectedVersion": 2
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"));
    }

    @Test
    void linksASettlementPaymentOnThePublicRoute() throws Exception {
        when(settlementService.updatePayment(eq(TENANT), eq(CLAIM_ID), eq(SETTLEMENT_ID),
                any(SettlementPaymentRequest.class)))
                .thenReturn(settlement("AUTHORIZED", PAYMENT_ID, NOW, 2));

        mvc.perform(patch(PUBLIC_BASE + "/settlements/" + SETTLEMENT_ID + "/payment")
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", "DIRECT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentId\": \"" + PAYMENT_ID + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value(PAYMENT_ID.toString()))
                .andExpect(jsonPath("$.paymentLinkedAt").isNotEmpty());
    }

    @Test
    void unlinksASettlementPayment() throws Exception {
        when(settlementService.updatePayment(eq(TENANT), eq(CLAIM_ID), eq(SETTLEMENT_ID),
                any(SettlementPaymentRequest.class)))
                .thenReturn(settlement("AUTHORIZED", null, null, 3));

        mvc.perform(patch(BASE + "/settlements/" + SETTLEMENT_ID + "/payment")
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", "DIRECT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentId\": null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value(nullValue()))
                .andExpect(jsonPath("$.paymentLinkedAt").value(nullValue()));
    }

    @Test
    void rejectsAPaymentLinkOnAnUnlinkableSettlement() throws Exception {
        when(settlementService.updatePayment(eq(TENANT), eq(CLAIM_ID), eq(SETTLEMENT_ID),
                any(SettlementPaymentRequest.class)))
                .thenThrow(new SettlementNotLinkableException("Settlement is PROPOSED"));

        mvc.perform(patch(BASE + "/settlements/" + SETTLEMENT_ID + "/payment")
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", "DIRECT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentId\": \"" + PAYMENT_ID + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SETTLEMENT_NOT_LINKABLE"));
    }

    // ------------------------------------------------------------------ recoveries
    @Test
    void listsRecoveries() throws Exception {
        when(recoveryService.list(TENANT, CLAIM_ID)).thenReturn(List.of(recovery("RECEIVED")));

        mvc.perform(get(BASE + "/recoveries").param("tenantId", TENANT_PARAM).header("X-EIA-Book", "DIRECT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].recoveryType").value("SUBROGATION"))
                .andExpect(jsonPath("$[0].amount").isString());
    }

    @Test
    void recordsARecovery() throws Exception {
        when(recoveryService.record(eq(TENANT), eq(CLAIM_ID), any(RecoveryRequest.class)))
                .thenReturn(recovery("RECEIVED"));

        mvc.perform(post(BASE + "/recoveries")
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", "DIRECT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "recoveryType": "SUBROGATION",
                                  "amount": "20000.00",
                                  "currency": "USD",
                                  "source": "Co-defendant Acme",
                                  "status": "RECEIVED",
                                  "recoveryDate": "2026-05-01"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("RECEIVED"));
    }

    @Test
    void updatesARecoveryStatus() throws Exception {
        when(recoveryService.changeStatus(eq(TENANT), eq(CLAIM_ID), eq(RECOVERY_ID),
                any(RecoveryRequest.class))).thenReturn(recovery("RECEIVED"));

        mvc.perform(patch(BASE + "/recoveries/" + RECOVERY_ID)
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", "DIRECT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"RECEIVED\", \"recoveryDate\": \"2026-05-01\", \"expectedVersion\": 1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECEIVED"));
    }

    // ------------------------------------------------------------------ liens
    @Test
    void listsLiens() throws Exception {
        when(lienService.list(TENANT, CLAIM_ID)).thenReturn(List.of(lien("IDENTIFIED")));

        mvc.perform(get(BASE + "/liens").param("tenantId", TENANT_PARAM).header("X-EIA-Book", "DIRECT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].lienType").value("MEDICARE"))
                .andExpect(jsonPath("$[0].amount").isString());
    }

    @Test
    void recordsALien() throws Exception {
        when(lienService.record(eq(TENANT), eq(CLAIM_ID), any(LienRequest.class)))
                .thenReturn(lien("IDENTIFIED"));

        mvc.perform(post(BASE + "/liens")
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", "DIRECT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "lienType": "MEDICARE",
                                  "lienholder": "Centers for Medicare & Medicaid Services",
                                  "amount": "15000.00",
                                  "currency": "USD",
                                  "status": "IDENTIFIED"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("IDENTIFIED"));
    }

    @Test
    void updatesALien() throws Exception {
        when(lienService.update(eq(TENANT), eq(CLAIM_ID), eq(LIEN_ID), any(LienRequest.class)))
                .thenReturn(lien("RESOLVED"));

        mvc.perform(patch(BASE + "/liens/" + LIEN_ID)
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", "DIRECT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"RESOLVED\", \"resolutionType\": \"NEGOTIATED\", \"expectedVersion\": 1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"));
    }
}
